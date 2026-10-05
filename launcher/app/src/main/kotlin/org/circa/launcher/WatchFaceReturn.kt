package org.circa.launcher

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log

/**
 * Stock's "return to the watch face": after the screen has been off for a while, the next wake
 * lands on the watch face instead of the app that was on screen before.
 *
 * **Mechanism.** `ACTION_SCREEN_OFF`/`ACTION_SCREEN_ON` are sent with
 * `FLAG_RECEIVER_REGISTERED_ONLY` (`Notifier.sendGoToSleepBroadcast` / `sendWakeUpBroadcast`), so a
 * manifest receiver can never see them; they have to be caught by a receiver registered in a live
 * process. This receiver is registered from [AuroraApp] (the launcher's `Application`), i.e. at
 * process scope, so it survives the activity being stopped or recreated while the screen is off -
 * a `MainActivity`-registered receiver would not. It is not a service: there is nothing to do
 * between the two broadcasts but remember a timestamp.
 *
 * On `ACTION_SCREEN_ON` the elapsed time since the screen went off decides: at least
 * [SCREEN_OFF_RETURN_THRESHOLD_MS] and the launcher brings itself to the front on the face (page 0,
 * tray closed); less than that and whatever was on screen stays there, which is stock's "glance at
 * the watch and put it back" behaviour.
 *
 * Bringing the activity forward from the background is a background activity launch, so the
 * launcher declares `START_ACTIVITIES_FROM_BACKGROUND` (`signature|privileged`, granted by the
 * platform signature - see launcher/README.md).
 */
object WatchFaceReturn {

    /** Screen-off time after which the next wake returns to the watch face. */
    const val SCREEN_OFF_RETURN_THRESHOLD_MS = 15_000L

    private const val TAG = "WatchFaceReturn"

    /**
     * When the screen last went off, in `SystemClock.elapsedRealtime()` terms (null = the launcher
     * has not seen a screen-off since it started). Only ever touched on the main thread: broadcasts
     * are delivered there, and [register] is called from `Application.onCreate`.
     */
    private var screenOffElapsedRealtime: Long? = null

    /** True when the wake at [nowElapsedRealtime] should land on the watch face. */
    fun shouldReturnToFace(screenOffElapsedRealtime: Long?, nowElapsedRealtime: Long): Boolean =
        screenOffElapsedRealtime != null &&
            nowElapsedRealtime - screenOffElapsedRealtime >= SCREEN_OFF_RETURN_THRESHOLD_MS

    /** The HOME intent that lands the launcher on the face whatever screen it was showing. */
    fun homeIntent(context: Context): Intent = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_HOME)
        .setComponent(ComponentName(context, MainActivity::class.java))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Register the process-wide screen-off/on receiver. Idempotent per process. */
    fun register(context: Context) {
        val appContext = context.applicationContext
        appContext.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        Intent.ACTION_SCREEN_OFF ->
                            screenOffElapsedRealtime = SystemClock.elapsedRealtime()

                        Intent.ACTION_SCREEN_ON -> {
                            val offAt = screenOffElapsedRealtime
                            screenOffElapsedRealtime = null
                            if (!shouldReturnToFace(offAt, SystemClock.elapsedRealtime())) return
                            try {
                                appContext.startActivity(homeIntent(appContext))
                            } catch (e: RuntimeException) {
                                // A refused background activity launch (no
                                // START_ACTIVITIES_FROM_BACKGROUND) must not kill the launcher.
                                Log.w(TAG, "could not return to the watch face", e)
                            }
                        }
                    }
                }
            },
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
    }
}
