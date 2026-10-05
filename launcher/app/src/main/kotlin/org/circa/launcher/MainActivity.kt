package org.circa.launcher

import android.app.AlarmManager
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.BatteryManager
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.window.OnBackInvokedDispatcher
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.circa.launcher.data.StepSource
import org.circa.launcher.ui.LauncherRoot

/**
 * HOME activity. Single task, state not needed (state lives in [LauncherController]).
 */
class MainActivity : ComponentActivity() {

    private lateinit var controller: LauncherController

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            controller.batteryPercent.value =
                if (level >= 0 && scale > 0) level * 100 / scale else null
        }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            controller.refreshApps()
        }
    }

    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }

    private fun refreshLocked() {
        controller.setLocked(keyguard.isKeyguardLocked)
    }

    /** Screen on/off and unlock events re-read the keyguard state (the listener below is the main path). */
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshLocked()
        }
    }

    private val keyguardListener = KeyguardManager.KeyguardLockedStateListener { refreshLocked() }

    private val stepSource by lazy { StepSource(this) { controller.onStepCounter(it) } }

    /**
     * True when the platform's `config_shortPressOnStemPrimaryBehavior` carries a Circa value:
     * PhoneWindowManager then owns the side button (`KEYCODE_STEM_PRIMARY`; short press -> the
     * notifications screen, long press -> power menu), so the launcher must not consume the key - an
     * unconsumed key is what lets the framework's deferred action run (launcher/README.md).
     * The crown's press is the POWER key, which no app receives: the framework turns it into an
     * explicit ALL_APPS to this app on the face, home elsewhere. On a build without the Circa values
     * (the stock GSI, value 2) the launcher still answers the raw stem key itself.
     */
    private val stemHandledByPlatform: Boolean by lazy {
        val res = android.content.res.Resources.getSystem()
        val id = res.getIdentifier("config_shortPressOnStemPrimaryBehavior", "integer", "android")
        id != 0 && CircaButtons.stemHandledByPlatform(res.getInteger(id))
    }

    /**
     * Demo/diagnostic values for the health complications, overriding WatchLink's provider while
     * active (the emulator has no heart-rate or step sensor):
     * `adb shell am broadcast -a org.circa.launcher.DEMO_HEALTH --ei hr 72 --ei steps 4213
     * --es history 66,70,74,71,69,75,80,72`; `--ei hr -1 --ei steps -1` clears. Registered with the DUMP
     * permission as the sender requirement, so only the shell / system can send it.
     */
    private val demoHealthReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            val hr = intent.getIntExtra("hr", -1).takeIf { it >= 0 }
            val steps = intent.getIntExtra("steps", -1).takeIf { it >= 0 }
            val history = intent.getStringExtra("history")
                ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
            controller.setDemoHealth(hr, steps, history)
        }
    }

    private var lastBackKeyAt = 0L

    /**
     * True while the launcher itself is the app on screen (between `onResume` and `onPause`).
     * [handleIntent] needs it to tell the two ways the platform can raise the launcher with a
     * no-action component launch (`config_primaryShortPressTargetActivity`, the crown's short press):
     * while it is already in front that is the crown's face <-> app list toggle, otherwise the platform
     * is answering a crown press inside another app and the watch face is what stock shows
     * (launcher/README.md). A new intent that raises a stopped activity is delivered before
     * `onResume`, so this is still false there.
     */
    private var inForeground = false

    private val alarmReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            controller.refreshNextAlarm()
        }
    }

    private companion object {
        /** Circa Settings > Display > Watch face (org.circa.settings starts it by package). */
        const val ACTION_PICK_FACE = "org.circa.intent.action.PICK_FACE"
        const val BACK_DEDUPE_MS = 400L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
        // The face shows over the keyguard (launcher/README.md): a wake with a PIN set lands on our
        // face instead of SystemUI's phone lockscreen. No setTurnScreenOn: the crown and side button
        // wake the screen, and WindowManager lets a turn-screen-on activity launched while the screen
        // was on wake it again at its next keyguard update - a sleep right after a HOME start bounced
        // straight back to the face instead of dozing.
        setShowWhenLocked(true)
        lockImmediately()
        controller = LauncherController(this)
        controller.unlockRequester = ::requestUnlock
        refreshLocked()

        // Apps targeting 33+ (this one targets 36) get predictive back by default, which means
        // KEYCODE_BACK is routed to the OnBackInvokedDispatcher instead of dispatchKeyEvent - and
        // without a callback the default one finishes the activity, i.e. HOME restarts. Registering
        // here is what makes BACK behave like the swipe-right dismiss on every screen: it closes the
        // tray, walks the app lists back one level, and on the face it is swallowed (this is HOME,
        // it must never finish).
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
        ) {
            // One BACK key press reaches us twice on this platform (dispatchKeyEvent, then this
            // callback from the same event); the second must not walk back a second level.
            if (android.os.SystemClock.uptimeMillis() - lastBackKeyAt > BACK_DEDUPE_MS) controller.back()
        }
        controller.refreshApps()
        controller.refreshRecentApps()
        controller.refreshNextAlarm()

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        registerReceiver(
            packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
        )
        registerReceiver(
            lockReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
        )
        runCatching {
            keyguard.addKeyguardLockedStateListener(mainExecutor, keyguardListener)
        }
        registerReceiver(
            alarmReceiver,
            IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED),
        )

        registerReceiver(
            demoHealthReceiver,
            IntentFilter("org.circa.launcher.DEMO_HEALTH"),
            android.Manifest.permission.DUMP,
            null,
            Context.RECEIVER_EXPORTED,
        )

        // First run: enable the launcher's own notification listener (WRITE_SECURE_SETTINGS, held
        // because the launcher is platform-signed). Best effort - the platform's own grant path
        // (`cmd notification allow_listener`) is what the launcher emulator smoke test uses as well.
        LauncherNotificationListener.enableIfPossible(this)

        handleIntent(intent)

        setContent {
            LauncherRoot(controller)
        }
    }

    /**
     * The launcher owns the whole round panel: no status bar and no navigation bar, and the
     * Compose content paints edge to edge (black). Swiping in from an edge only brings the bars
     * back transiently ([BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE]), and they hide again by
     * themselves.
     */
    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * Stock Wear locks the moment the screen goes off; AOSP's default is 5 s after. Set the timeout to 0
     * once - only while nobody has chosen a value yet, so a later choice is never overwritten. Needs
     * WRITE_SECURE_SETTINGS (platform signature); best effort.
     */
    private fun lockImmediately() {
        runCatching {
            val resolver = contentResolver
            if (Settings.Secure.getString(resolver, "lock_screen_lock_after_timeout") == null) {
                Settings.Secure.putInt(resolver, "lock_screen_lock_after_timeout", 0)
            }
        }
    }

    /** Bring up the system PIN bouncer; [then] runs only after a successful unlock. */
    private fun requestUnlock(then: () -> Unit) {
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    controller.setLocked(false)
                    then()
                }
            },
        )
    }

    override fun onResume() {
        super.onResume()
        inForeground = true
        refreshLocked()
        controller.reloadShared()
    }

    override fun onPause() {
        inForeground = false
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        stepSource.start()
        controller.startHealth()
        controller.startExerciseState()
    }

    override fun onStop() {
        stepSource.stop()
        controller.stopHealth()
        controller.stopExerciseState()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The crown's press reaches the launcher as an intent (ALL_APPS): an interaction for the tilt
        // glance. The side button is a raw STEM_PRIMARY key to the focused launcher (dispatchKeyEvent).
        // Not the HOME intent: WatchFaceReturn sends that itself on a wake after >= 15 s off.
        val action = intent.action
        if (action == null || action == Intent.ACTION_ALL_APPS || action == CircaButtons.ACTION_SHOW_RECENTS) {
            WakeGestures.instance?.onUserInteraction()
        }
        handleIntent(intent)
    }

    /** Touch, rotary and key input (Activity calls this before dispatching): ends a tilt glance's quick timeout. */
    override fun onUserInteraction() {
        super.onUserInteraction()
        WakeGestures.instance?.onUserInteraction()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        WakeGestures.instance?.onLauncherFocus(hasFocus)
    }

    private fun handleIntent(intent: Intent?) {
        val action = intent?.action
        when {
            // An explicit android.intent.action.ALL_APPS start (the framework's crown press, the shell,
            // a test, another app wanting the app list) is the crown press gesture: face -> Recents,
            // app list -> face.
            action == Intent.ACTION_ALL_APPS -> controller.toggle() // locked: the PIN, then stays on the face
            // Older Circa mapping (config_circaPowerShortPressOpensRecents): PhoneWindowManager's
            // explicit org.circa.intent.action.SHOW_RECENTS to the HOME package
            // (launcher/README.md): open Recents, or go back to the face from there.
            action == CircaButtons.ACTION_SHOW_RECENTS -> controller.showRecents()
            // Circa Settings > Display > Watch face: the face picker (the face is this app's preference).
            action == ACTION_PICK_FACE -> controller.requireUnlock { controller.openFacePicker() }
            // Stock GSI (no Circa values): the stem key's short press when *another* app was on screen
            // starts the launcher by component (config_primaryShortPressTargetActivity, SHORT_PRESS_
            // PRIMARY_LAUNCH_TARGET_ACTIVITY) with no action and FLAG_ACTIVITY_TASK_ON_HOME, and stock
            // goes back to the watch face from there, not into the app list. See launcher/README.md.
            action == null -> controller.crownPress(inForeground)
            action == Intent.ACTION_MAIN &&
                intent.hasCategory(Intent.CATEGORY_HOME) -> controller.showFace()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The branches below may return before super (which is what calls onUserInteraction).
        if (event.action == KeyEvent.ACTION_DOWN) WakeGestures.instance?.onUserInteraction()
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                // KEYCODE_STEM_PRIMARY (264) is the watch's side button (the crown's press is POWER,
                // which apps never see). With the platform signature the launcher holds
                // OVERRIDE_SYSTEM_KEY_BEHAVIOR_IN_FOCUSED_WINDOW, so the framework delivers it here
                // first. On Circa the framework registered the stem key rule and its own behaviour
                // handles the short press (notifications screen) and the long press (power menu), so
                // the key must stay unconsumed (CircaButtons, launcher/README.md). On the
                // stock GSI (no Circa values) the launcher answers it as its face <-> app list toggle.
                // STEM_1 (265, "generic stem key 1 for Wear") is honoured as the same toggle for
                // builds that only deliver that one. See launcher/README.md.
                KeyEvent.KEYCODE_STEM_PRIMARY -> {
                    if (!stemHandledByPlatform) {
                        controller.toggle()
                        return true
                    }
                }
                KeyEvent.KEYCODE_STEM_1 -> {
                    controller.toggle()
                    return true
                }
                // Back one level, swallowed on the face (this is HOME, so back must not finish it).
                // The same as the swipe-right dismiss every non-home screen implements. Normally
                // unreachable on this target (see the OnBackInvokedDispatcher callback in onCreate);
                // kept for any path that still delivers BACK as a key event.
                KeyEvent.KEYCODE_BACK -> {
                    lastBackKeyAt = android.os.SystemClock.uptimeMillis()
                    controller.back()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(batteryReceiver)
        unregisterReceiver(packageReceiver)
        unregisterReceiver(alarmReceiver)
        unregisterReceiver(lockReceiver)
        runCatching { keyguard.removeKeyguardLockedStateListener(keyguardListener) }
        unregisterReceiver(demoHealthReceiver)
    }
}
