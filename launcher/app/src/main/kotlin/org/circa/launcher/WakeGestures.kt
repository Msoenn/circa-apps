package org.circa.launcher

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import org.circa.launcher.data.CircaShared
import org.circa.launcher.data.SharedPrefsStore
import org.circa.launcher.model.LauncherSettings
import org.circa.launcher.model.OffBodyAction
import org.circa.launcher.model.TiltAction
import org.circa.launcher.model.TiltSensitivity
import org.circa.launcher.model.WearGestureLogic

/**
 * Tilt-to-wake and lock-when-taken-off (F6; launcher/README.md). Both are *wake-up*
 * sensor listeners, so the decisions run in the sensor hub / MCU and the application processor is
 * only woken by an actual gesture (research §1.2, §5); no AP polling, no wakelocks of our own.
 *
 * * **Tilt-to-wake** — `android.sensor.wrist_tilt_gesture` (hidden Android type 26) has a wake-up
 *   variant and no permission requirement. Its level is `Settings.Secure circa_tilt_wake` (Off / Low /
 *   Normal, Circa Settings > Gestures; [TiltSensitivity.resolve] falls back to the old
 *   `Settings.Global.ambient_tilt_to_wake` toggle) and it is registered only while the display is off
 *   or dozing - and never while theater mode (`Settings.Global.THEATER_MODE_ON`) is on, where only
 *   POWER may wake the screen (launcher/README.md). The hub's gesture alone
 *   fires on arm swings, so a raise is only a *candidate*: [TiltGlance] samples the accelerometer for
 *   a few hundred ms and calls the framework's own gesture wake
 *   `PowerManager.wakeUp(uptime, WAKE_REASON_GESTURE, "aurora:tilt")` (hidden; the platform signature
 *   and DEVICE_POWER, both already held, are what make it legal) only if the face is toward the user
 *   and held; it then returns to ambient quickly when nobody touches the watch, and logs every event.
 * * **Lock when taken off** — `android.sensor.low_latency_offbody_detect` (type 34) has a wake-up
 *   variant too; registered while the preference ([LauncherSettings.lockWhenTakenOff], default on)
 *   and a secure credential are set. Off the wrist it locks immediately through the framework's own
 *   key-gesture path `IWindowManager.lockNow(null)` (hidden; enforces DEVICE_POWER), falling back to
 *   `PowerManager.goToSleep` (screen off ⇒ the keyguard locks at once, `lock_screen_lock_after_timeout
 *   = 0`, launcher/README.md).
 *
 * Hosted in the process-scoped [AuroraApp], like [WatchFaceReturn], so the listeners outlive any
 * activity. It is not a service: a wake-up sensor's registration is held by the sensor service and
 * the process is the resident HOME + doze process anyway (research §3.1).
 */
class WakeGestures(private val context: Context) {

    private val sensorManager: SensorManager? = context.getSystemService(SensorManager::class.java)
    private val powerManager: PowerManager? = context.getSystemService(PowerManager::class.java)
    private val keyguardManager: KeyguardManager? = context.getSystemService(KeyguardManager::class.java)
    private val displayManager: DisplayManager? = context.getSystemService(DisplayManager::class.java)
    private val resolver = context.contentResolver

    /** The launcher's own preferences: the "lock when taken off" fallback before it is set system-wide. */
    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val settings = LauncherSettings(SharedPrefsStore(prefs))

    /**
     * "Lock when taken off": Circa Settings' Security toggle writes `Settings.Secure
     * circa_lock_when_taken_off`; until it has been set, the launcher's old preference (default on).
     */
    private fun lockWhenTakenOff(): Boolean =
        CircaShared.lockWhenTakenOff(resolver) ?: settings.lockWhenTakenOff

    private val logic = WearGestureLogic()

    private var tiltSensor: Sensor? = null
    private var offBodySensor: Sensor? = null
    private var tiltRegistered = false

    /** Uptime of the last tilt-sensor registration ([WearGestureLogic.inArmingGrace]). */
    private var tiltArmedAt: Long? = null
    private var offBodyRegistered = false

    /** Cached `Settings.Global.THEATER_MODE_ON`, refreshed by [evaluate] (the observer below). */
    private var theaterModeOn = false

    private val telemetry = TiltTelemetry(context)
    private lateinit var glance: TiltGlance

    private var tiltUnavailableLogged = false
    private var offBodyUnavailableLogged = false

    private val mainHandler = Handler(Looper.getMainLooper())

    fun start() {
        // Probe once so a build without the sensors (the emulator; launcher/README.md) reports
        // it even while the screen is on and nothing would be registered yet.
        tiltSensor = wakeUpSensor(TYPE_WRIST_TILT_GESTURE)
        if (tiltSensor == null && !tiltUnavailableLogged) {
            tiltUnavailableLogged = true
            Log.i(TAG, "tilt sensor unavailable")
        }
        glance = TiltGlance(
            sensorManager = sensorManager,
            handler = mainHandler,
            telemetry = telemetry,
            accelSensor = wakeUpSensor(Sensor.TYPE_ACCELEROMETER),
            gyroSensor = wakeUpSensor(Sensor.TYPE_GYROSCOPE),
            uiAccelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
            wake = ::wakeUpFromTilt,
            sleep = { goToSleep(GO_TO_SLEEP_REASON_TIMEOUT) },
            screenInteractive = ::screenInteractive,
        )
        instance = this
        offBodySensor = wakeUpSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)
        if (offBodySensor == null && !offBodyUnavailableLogged) {
            offBodyUnavailableLogged = true
            Log.i(TAG, "off-body sensor unavailable")
        }

        // Circa Settings' Tilt-to-wake level (and the legacy toggle) can change from outside.
        for (uri in listOf(Settings.Global.getUriFor(TILT_SETTING), Settings.Secure.getUriFor(TILT_LEVEL_SETTING))) {
            resolver.registerContentObserver(
                uri,
                false,
                object : ContentObserver(mainHandler) {
                    override fun onChange(selfChange: Boolean) {
                        Log.i(TAG, "tilt-to-wake level -> ${tiltLevel().id}")
                        evaluate()
                    }
                },
            )
        }
        // Theater mode (SystemUI's tile or `settings put global theater_mode_on 1`): tilt-to-wake is a
        // *wake* and must stay off while it is on - only POWER may wake the screen then.
        resolver.registerContentObserver(
            Settings.Global.getUriFor(THEATER_SETTING),
            false,
            object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean) = evaluate()
            },
        )
        // Off or dozing vs interactive is the display state: STATE_OFF / STATE_DOZE / STATE_DOZE_SUSPEND
        // are "not interactive" (STATE_ON is), and a doze dream also reports STATE_DOZE.
        displayManager?.registerDisplayListener(
            object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = Unit
                override fun onDisplayRemoved(displayId: Int) = Unit
                override fun onDisplayChanged(displayId: Int) {
                    if (displayId != Display.DEFAULT_DISPLAY) return
                    glance.onDisplayChanged(screenInteractive())
                    evaluate()
                }
            },
            mainHandler,
        )
        // Circa Settings' "Lock when taken off" toggle writes the secure setting: apply it at once.
        resolver.registerContentObserver(
            Settings.Secure.getUriFor(CircaShared.LOCK_WHEN_TAKEN_OFF),
            false,
            object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean) {
                    Log.i(TAG, "lock-when-taken-off preference -> ${lockWhenTakenOff()}")
                    evaluate()
                }
            },
        )
        // Setting or clearing the PIN changes whether off-body auto-lock is possible.
        runCatching {
            keyguardManager?.addKeyguardLockedStateListener(context.mainExecutor) { evaluate() }
        }

        context.registerReceiver(
            debugReceiver,
            IntentFilter().apply {
                addAction(DEBUG_OFFBODY_ACTION)
                addAction(DEBUG_TILT_ACTION)
            },
            android.Manifest.permission.DUMP,
            null,
            Context.RECEIVER_EXPORTED,
        )

        evaluate()
    }

    /** Re-read the settings and (un)register what should be listening right now. */
    fun evaluate() {
        val interactive = screenInteractive()
        val available = sensorManager != null
        theaterModeOn = theaterModeOn()

        val wantTilt = logic.tiltShouldListen(
            tiltLevel() != TiltSensitivity.OFF, interactive, theaterModeOn, available && tiltSensor != null,
        )
        if (wantTilt && !tiltRegistered) {
            tiltRegistered = tiltSensor?.let {
                sensorManager?.registerListener(tiltListener, it, SensorManager.SENSOR_DELAY_NORMAL) == true
            } ?: false
            if (tiltRegistered) tiltArmedAt = SystemClock.uptimeMillis()
        } else if (!wantTilt && tiltRegistered) {
            tiltSensor?.let { sensorManager?.unregisterListener(tiltListener, it) }
            tiltRegistered = false
        }

        val secure = keyguardManager?.isDeviceSecure == true
        val wantOffBody = logic.offBodyShouldListen(
            lockWhenTakenOff(), secure, available && offBodySensor != null,
        )
        if (wantOffBody && !offBodyRegistered) {
            offBodyRegistered = offBodySensor?.let {
                sensorManager?.registerListener(offBodyListener, it, SensorManager.SENSOR_DELAY_NORMAL) == true
            } ?: false
        } else if (!wantOffBody && offBodyRegistered) {
            offBodySensor?.let { sensorManager?.unregisterListener(offBodyListener, it) }
            offBodyRegistered = false
        }
    }

    // ---- tilt-to-wake ---------------------------------------------------------------------------

    private val tiltListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            onTiltEvent(event.values.firstOrNull() ?: 0f, SOURCE_HUB)
            // The sensor is REPORTING_MODE_SPECIAL_TRIGGER and stays active, so re-registering is a
            // no-op safety net rather than a real one-shot re-arm (research §2.5); the debounce in the
            // logic absorbs any duplicate delivery.
            if (tiltRegistered) tiltSensor?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /**
     * One wrist-tilt event (the hub's, or the debug broadcast's): the outer gating here (theater mode,
     * off-body, debounce), then [TiltGlance]'s accelerometer gate decides whether it wakes.
     */
    private fun onTiltEvent(value: Float, source: String) {
        if (value <= 0f) return
        val level = tiltLevel()
        val skip = when {
            source == SOURCE_HUB && logic.inArmingGrace(SystemClock.uptimeMillis(), tiltArmedAt) -> "arming"
            logic.offBody -> "offbody"
            theaterModeOn -> "theater"
            glance.busy -> "busy"
            else -> null
        }
        if (skip != null || logic.tiltAction(SystemClock.uptimeMillis(), value, theaterModeOn) != TiltAction.WAKE) {
            val reason = skip ?: "debounce"
            if (reason in CHATTER) {
                // Repeats inside one gesture (or the activation artefact): logcat only, so a burst
                // cannot push real history out of the rolling file.
                Log.d(TAG, "tilt: skip ($reason)")
            } else {
                glance.logSkip(System.currentTimeMillis(), SystemClock.uptimeMillis(), source, level, reason)
            }
            return
        }
        glance.onTilt(source, level)
    }

    /** A touch / crown / button reached a launcher activity (MainActivity forwards it). */
    fun onUserInteraction() = glance.onUserInteraction()

    /** MainActivity's window focus, for the glance's "is the launcher what is on screen". */
    fun onLauncherFocus(focused: Boolean) = glance.onLauncherFocus(focused)

    private fun wakeUpFromTilt(): Boolean = try {
        PowerManager::class.java
            .getMethod(
                "wakeUp",
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
            )
            .invoke(powerManager, SystemClock.uptimeMillis(), WAKE_REASON_GESTURE, WAKE_DETAILS)
        true
    } catch (e: ReflectiveOperationException) {
        Log.w(TAG, "PowerManager.wakeUp unavailable", e)
        false
    } catch (e: RuntimeException) {
        Log.w(TAG, "PowerManager.wakeUp failed", e)
        false
    }

    // ---- lock when taken off --------------------------------------------------------------------

    private val offBodyListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            onOffBodyValue(event.values.firstOrNull() ?: ON_WRIST)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** Feed one off-body value (0.0 = off the wrist) through the policy and lock if it says so. */
    private fun onOffBodyValue(value: Float) {
        val secure = keyguardManager?.isDeviceSecure == true
        if (logic.offBodyAction(value, secure, lockWhenTakenOff()) != OffBodyAction.LOCK) return
        val path = lockNow()
        if (path != null) Log.i(TAG, "off-body: left the wrist -> locked ($path)")
    }

    /**
     * Lock the keyguard immediately. `IWindowManager.lockNow(null)` is the framework's own key-gesture
     * lock (PhoneWindowManager.lockNow, DEVICE_POWER) and does not touch the display; where the hidden
     * interface is unreachable the fallback turns the screen off, and with `lock_screen_lock_after_timeout
     * = 0` (launcher/README.md) the keyguard locks at once. Returns the path that worked, else null.
     */
    private fun lockNow(): String? =
        if (lockViaWindowManager()) "lockNow" else if (goToSleep(GO_TO_SLEEP_REASON_APPLICATION)) "goToSleep" else null

    private fun lockViaWindowManager(): Boolean = try {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, Context.WINDOW_SERVICE) as? IBinder ?: return false
        val windowManager = Class.forName("android.view.IWindowManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder) ?: return false
        windowManager.javaClass.getMethod("lockNow", Bundle::class.java).invoke(windowManager, null)
        true
    } catch (e: ReflectiveOperationException) {
        Log.w(TAG, "IWindowManager.lockNow unavailable", e)
        false
    } catch (e: RuntimeException) {
        Log.w(TAG, "IWindowManager.lockNow failed", e)
        false
    }

    /** `PowerManager.goToSleep(time, reason, 0)` (hidden, DEVICE_POWER): screen off, doze if enabled. */
    private fun goToSleep(reason: Int): Boolean = try {
        // API 36's signature; the goToSleep(displayId, time, reason, flags) variant of later branches
        // is not what this platform ships.
        PowerManager::class.java
            .getMethod(
                "goToSleep",
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            .invoke(powerManager, SystemClock.uptimeMillis(), reason, 0)
        true
    } catch (e: ReflectiveOperationException) {
        Log.w(TAG, "PowerManager.goToSleep unavailable", e)
        false
    } catch (e: RuntimeException) {
        Log.w(TAG, "PowerManager.goToSleep failed", e)
        false
    }

    // ---- platform reads -------------------------------------------------------------------------

    /**
     * The wake-up variant of [type]. `getDefaultSensor(int, boolean)` is a hidden/system API, so it is
     * reached by reflection (platform signature, like the launcher's other hidden calls); the public
     * one-argument overload returns the same sensor for the types used here, so it is a safe fallback.
     */
    private fun wakeUpSensor(type: Int): Sensor? {
        val manager = sensorManager ?: return null
        val explicit = try {
            SensorManager::class.java
                .getMethod("getDefaultSensor", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .invoke(manager, type, true) as? Sensor
        } catch (e: ReflectiveOperationException) {
            null
        } catch (e: RuntimeException) {
            null
        }
        return explicit ?: runCatching { manager.getDefaultSensor(type) }.getOrNull()
    }

    /** `Settings.Secure circa_tilt_wake`, else the legacy `Settings.Global.ambient_tilt_to_wake` toggle. */
    private fun tiltLevel(): TiltSensitivity = TiltSensitivity.resolve(
        runCatching { Settings.Secure.getString(resolver, TILT_LEVEL_SETTING) }.getOrNull(),
        runCatching { Settings.Global.getString(resolver, TILT_SETTING) }.getOrNull(),
    )

    /** `Settings.Global.THEATER_MODE_ON` (hidden from the public SDK), 1 = on. */
    private fun theaterModeOn(): Boolean =
        runCatching { Settings.Global.getInt(resolver, THEATER_SETTING, 0) == 1 }.getOrDefault(false)

    private fun screenInteractive(): Boolean {
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY) ?: return false
        return display.state == Display.STATE_ON
    }

    /**
     * Diagnostic hook (the emulator has neither sensor): feed an off-body value through the production
     * policy and lock path. `--ei value 0` means off the wrist. Registered with `DUMP` as the sender
     * permission, so only the shell / system can send it - the same shape as `DEMO_HEALTH`.
     */
    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                DEBUG_OFFBODY_ACTION -> onOffBodyValue(intent.getIntExtra(EXTRA_VALUE, ON_WRIST.toInt()).toFloat())
                DEBUG_TILT_ACTION ->
                    if (intent.getBooleanExtra(EXTRA_DUMP, false)) {
                        telemetry.dumpToLogcat()
                    } else {
                        onTiltEvent(intent.getIntExtra(EXTRA_VALUE, 1).toFloat(), SOURCE_DEBUG)
                    }
            }
        }
    }

    companion object {
        private const val TAG = "WakeGestures"

        /** Hidden `Sensor.TYPE_WRIST_TILT_GESTURE` (not in the public SDK). */
        private const val TYPE_WRIST_TILT_GESTURE = 26

        /** `Settings.Global.ambient_tilt_to_wake`; the old Gestures toggle (1 = on), the fallback. */
        private const val TILT_SETTING = "ambient_tilt_to_wake"

        /** `Settings.Secure circa_tilt_wake`: "0" Off, "1" Low, "2" Normal (Circa Settings > Gestures). */
        const val TILT_LEVEL_SETTING = "circa_tilt_wake"

        /** Hidden `Settings.Global.THEATER_MODE_ON`; SystemUI's Theater mode tile writes it. */
        private const val THEATER_SETTING = "theater_mode_on"

        /** Hidden `PowerManager.WAKE_REASON_GESTURE`. */
        private const val WAKE_REASON_GESTURE = 4

        /** Hidden `PowerManager.GO_TO_SLEEP_REASON_APPLICATION`. */
        private const val GO_TO_SLEEP_REASON_APPLICATION = 0

        /** Hidden `PowerManager.GO_TO_SLEEP_REASON_TIMEOUT`: the glance ends like a screen timeout. */
        private const val GO_TO_SLEEP_REASON_TIMEOUT = 2

        /** Skip reasons that are not written to the telemetry file. */
        private val CHATTER = setOf("busy", "debounce", "arming")

        private const val SOURCE_HUB = "hub"
        private const val SOURCE_DEBUG = "debug"

        /** The process's instance, for MainActivity's interaction/focus hooks (set by [start]). */
        @Volatile
        var instance: WakeGestures? = null
            private set

        private const val WAKE_DETAILS = "aurora:tilt"

        private const val ON_WRIST = 1f

        /** `am broadcast -a org.circa.launcher.DEBUG_OFFBODY --ei value 0`. */
        const val DEBUG_OFFBODY_ACTION = "org.circa.launcher.DEBUG_OFFBODY"
        const val EXTRA_VALUE = "value"

        /**
         * `am broadcast -a org.circa.launcher.DEBUG_TILT` feeds one tilt event through the real
         * path (gate, wake, glance, telemetry; the emulator has no tilt sensor); `--ez dump true` logs
         * the telemetry file to logcat instead. Sender needs DUMP (shell/system only).
         */
        const val DEBUG_TILT_ACTION = "org.circa.launcher.DEBUG_TILT"
        const val EXTRA_DUMP = "dump"
    }
}
