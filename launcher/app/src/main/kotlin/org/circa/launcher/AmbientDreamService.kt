package org.circa.launcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.dreams.DreamService
import android.text.format.DateFormat
import android.util.Log
import android.view.Display
import org.circa.launcher.model.AmbientDozeStateMachine
import org.circa.launcher.model.AodBrightness
import org.circa.launcher.model.AmbientDozeStateMachine.Screen

/**
 * The always-on (ambient/doze) face, as the platform's **doze component**:
 * `config_dozeComponent` is pointed at this service by the `aurora-doze` RRO (see
 * launcher/README.md and launcher/README.md). `DreamManagerService` starts it
 * whenever the power manager is dozing - i.e. on screen-off once always-on display is available and
 * enabled - and `config_dozeAlwaysOnDisplayAvailable` + `Settings.Secure.DOZE_ALWAYS_ON` are what
 * make the panel actually stay in the low-power doze state instead of turning off.
 *
 * It draws the "time only" ambient face (black, a small grey time, nothing else) through
 * [AmbientFaceView] and re-renders once a minute, and it keeps the panel in
 * `Display.STATE_DOZE_SUSPEND` between those redraws: that is the state in which the panel holds the
 * last frame itself and the application processor is allowed to suspend. See
 * [AmbientDozeStateMachine] for the state machine (a redraw is given a short `STATE_DOZE` hold first)
 * and for the AOSP `DozeService`/`DozeScreenState`/`DozeUi` behaviour it copies.
 *
 * The minute update is an exact **wakeup** alarm for the next whole minute, as AOSP's `DozeUi`
 * schedules it: the platform's own `ACTION_TIME_TICK` is an `ELAPSED_REALTIME` (non-wakeup) alarm
 * and cannot be delivered while the AP is suspended - which, with this fix, is most of the time. That
 * broadcast remains the tick source for a non-doze run of the same service (a screensaver preview,
 * where the display stays on and it does arrive).
 *
 * Everything it needs from the platform is `startDozing()`/`stopDozing()`/`setDozeScreenState(int)`/
 * `setDozeScreenBrightness(float)` (and `canDoze()`), which are `@hide` ("for use by system UI components only") and therefore
 * reached by reflection; see [callHidden].
 */
class AmbientDreamService : DreamService() {

    private lateinit var face: AmbientFaceView

    private val doze = AmbientDozeStateMachine()
    private val handler = Handler(Looper.getMainLooper())

    /** True while the platform runs this service as the *doze* dream (not as a plain screensaver). */
    private var dozing = false

    /** Whether this doze session's first frame has been drawn (it gets the longer entry hold). */
    private var firstFrameDrawn = false

    /**
     * Ends the current `STATE_DOZE` hold: the frame the hold was for is on the panel, so the panel
     * may go back to `STATE_DOZE_SUSPEND` and the application processor may suspend again.
     */
    private val endHold = Runnable {
        doze.onHoldElapsed()
        applyPanelState()
        Log.i(TAG, "redraw hold over, panel ${doze.screen}")
    }

    /** Minute updates, from our own wakeup alarm while dozing and from the platform's tick otherwise. */
    private val minuteTick = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = redraw()
    }

    /** The wakeup alarm's own action; only ever delivered to us (`setPackage`). */
    private val alarmTickFilter = IntentFilter(ACTION_MINUTE_TICK)

    /** The platform's minute broadcast; an `ELAPSED_REALTIME` alarm, so only usable while awake. */
    private val platformTickFilter = IntentFilter(Intent.ACTION_TIME_TICK)

    /**
     * The alarm that wakes the AP for the minute update. An explicit intent to ourselves, so the
     * runtime-registered [minuteTick] receiver can accept it while the service is alive.
     */
    private val minuteTickAlarm: PendingIntent by lazy {
        PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_MINUTE_TICK).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setInteractive(false)
        setFullscreen(true)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        face = AmbientFaceView(this)
        setContentView(face)
        // The panel state to hold while dozing; applied when startDozing() follows. It is STATE_DOZE
        // (the scanning state) until the first frame has been given its hold: a panel that suspended
        // before the face was drawn would keep an empty frame.
        callHidden("setDozeScreenState", displayState(Screen.DOZE))
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        firstFrameDrawn = false
        doze.onStopped()
        // The panel brightness while dozing: Circa Settings' Always-on brightness (default Normal).
        // Without it the framework's doze default applies (config_screenBrightnessDoze, 0 here).
        val brightness = AodBrightness.resolve(
            Settings.Secure.getString(contentResolver, AodBrightness.SECURE_KEY),
        )
        callHidden("setDozeScreenBrightness", brightness.dozeBrightness)
        // Ask the power manager to keep the panel in the low-power doze state and to let the AP
        // suspend; a doze dream is the only thing that can do this (DreamManagerService acquires its
        // "dream:doze" wake lock for us). canDoze() is what separates this from a preview/screensaver
        // run of the same service, where the panel is not ours to drive.
        dozing = hiddenBoolean("canDoze") && callHidden("startDozing")
        // While dozing the panel suspends and only the wakeup alarm can bring the face up to date, so
        // the receiver takes the alarm's action then and the platform's ACTION_TIME_TICK otherwise (a
        // screensaver preview, where the display stays on and that broadcast does arrive).
        registerReceiver(
            minuteTick,
            if (dozing) alarmTickFilter else platformTickFilter,
            Context.RECEIVER_NOT_EXPORTED,
        )
        redraw()
    }

    override fun onDreamingStopped() {
        handler.removeCallbacks(endHold)
        cancelMinuteTick()
        runCatching { unregisterReceiver(minuteTick) }
        doze.onStopped()
        callHidden("stopDozing")
        super.onDreamingStopped()
    }

    /**
     * Draws the face and keeps the panel awake for as long as the new frame needs to reach it, then
     * hands it back to the [endHold] runnable, which suspends it. Called when the dream starts and at
     * every minute tick.
     */
    private fun redraw() {
        val now = System.currentTimeMillis()
        face.setFace(
            nowMillis = now,
            is24Hour = DateFormat.is24HourFormat(this),
        )
        val holdMillis = if (firstFrameDrawn) {
            doze.onMinuteTick(now)
        } else {
            firstFrameDrawn = true
            doze.onStarted(now)
        }
        // A non-doze run (screensaver preview): draw only - there is no panel state of ours to set.
        if (!dozing) return
        handler.removeCallbacks(endHold)
        applyPanelState()
        handler.postDelayed(endHold, holdMillis)
        scheduleMinuteTick()
        Log.i(TAG, "face at $now: panel ${doze.screen} for ${holdMillis}ms")
    }

    /** Puts the panel into the state the machine asks for. */
    private fun applyPanelState() = callHidden("setDozeScreenState", displayState(doze.screen))

    private fun displayState(screen: Screen): Int = when (screen) {
        Screen.DOZE -> Display.STATE_DOZE
        Screen.DOZE_SUSPEND -> Display.STATE_DOZE_SUSPEND
    }

    /**
     * Schedules the next minute update as an exact wakeup alarm aligned to the next whole minute -
     * AOSP `DozeUi.scheduleTimeTick` (`AlarmTimeout` -> `AlarmManager.setExact(ELAPSED_REALTIME_WAKEUP,
     * ...)`), which is what makes the tick arrive while the AP is suspended. `canScheduleExactAlarms`
     * is checked like WatchLink does (SCHEDULE_EXACT_ALARM is declared in the manifest); without it
     * the frame may be a little late, never wrong.
     */
    private fun scheduleMinuteTick() {
        val alarmManager = getSystemService(AlarmManager::class.java) ?: return
        val delayMillis = AmbientDozeStateMachine.millisUntilNextMinute(System.currentTimeMillis())
        val at = SystemClock.elapsedRealtime() + delayMillis
        try {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, at, minuteTickAlarm,
                )
                Log.i(TAG, "minute tick in ${delayMillis}ms (exact wakeup)")
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, at, minuteTickAlarm,
                )
                Log.w(TAG, "minute tick in ${delayMillis}ms (inexact: exact alarms not allowed)")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "exact wakeup alarm refused; minute updates may be batched", e)
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, at, minuteTickAlarm,
            )
        }
    }

    private fun cancelMinuteTick() {
        getSystemService(AlarmManager::class.java)?.cancel(minuteTickAlarm)
    }

    /**
     * Calls one of `DreamService`'s hidden doze methods. They are public API in the platform but
     * carry no stub in the public SDK the launcher compiles against, so they are resolved at
     * runtime - the launcher is platform-signed, which is what lets a non-system app call them
     * (the same reason the tray's `PowerManager.setPowerSaveModeEnabled` can be used). A platform
     * without them (a phone image, say) leaves the dream as a plain screensaver: the face is still
     * drawn, it just does not drive the panel into its doze state.
     */
    private fun callHidden(name: String, vararg args: Any): Boolean =
        try {
            val types = args.map {
                when (it) {
                    is Int -> Integer.TYPE
                    is Float -> java.lang.Float.TYPE
                    else -> it.javaClass
                }
            }.toTypedArray()
            DreamService::class.java.getMethod(name, *types).invoke(this, *args)
            Log.i(TAG, "$name(${args.joinToString()}) ok")
            true
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "$name() is not callable on this platform", e)
            false
        }

    /** Reads one of those hidden no-argument getters (`canDoze()`); false when it is not there. */
    private fun hiddenBoolean(name: String): Boolean =
        try {
            DreamService::class.java.getMethod(name).invoke(this) as? Boolean ?: false
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "$name() is not callable on this platform", e)
            false
        }

    private companion object {
        const val TAG = "AmbientDreamService"

        /** Only ever delivered to us (`setPackage`), the wakeup alarm's own action. */
        const val ACTION_MINUTE_TICK = "org.circa.launcher.action.MINUTE_TICK"
    }
}
