package org.circa.launcher

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import org.circa.launcher.model.GlanceEnd
import org.circa.launcher.model.GlancePolicy
import org.circa.launcher.model.Sample3
import org.circa.launcher.model.TiltFeatures
import org.circa.launcher.model.TiltGate
import org.circa.launcher.model.TiltLog
import org.circa.launcher.model.TiltSensitivity
import org.circa.launcher.model.TiltThresholds
import org.circa.launcher.model.TiltVerdict
import org.circa.launcher.model.WristDownDetector

/**
 * What happens between a hub wrist-tilt event and the screen going back to ambient
 * (launcher/README.md):
 *
 * 1. **Gate.** The hub event has woken the AP. The *wake-up* accelerometer and gyroscope are
 *    registered for one short window ([TiltThresholds.windowMs], 300-450 ms at 50 Hz) - their events
 *    are what keep the AP up for that window, so no wakelock of our own is taken - and unregistered as
 *    soon as the window is complete. [TiltGate] decides; only an accepted raise calls [wake].
 * 2. **Glance.** After an accepted wake nobody touches, the screen goes back to ambient after
 *    [GlancePolicy.QUICK_TIMEOUT_MS] instead of the 30 s system timeout, or at once on wrist-down
 *    ([WristDownDetector], *non*-wake-up accelerometer at UI rate: the screen is on, the AP is awake
 *    anyway). Any touch / crown / button in the launcher ([onUserInteraction]), or the launcher losing
 *    focus to another window, cancels that: the normal timeout applies. "Back to ambient" is
 *    `PowerManager.goToSleep(…, GO_TO_SLEEP_REASON_TIMEOUT, 0)` - exactly what the system timeout
 *    does, so the doze dream ([AmbientDreamService]) and its doze-suspend take over as usual and the
 *    keyguard's "Lock after" grace is honoured.
 * 3. **Telemetry.** Every event (accepted, rejected or skipped) becomes one [TiltLog] line in
 *    [TiltTelemetry]; an accepted one is written when its glance ends, with the outcome.
 *
 * Main-thread only (sensor callbacks are delivered on [handler]).
 */
class TiltGlance(
    private val sensorManager: SensorManager?,
    private val handler: Handler,
    private val telemetry: TiltTelemetry,
    private val accelSensor: Sensor?,
    private val gyroSensor: Sensor?,
    /** The non-wake-up accelerometer for wrist-down while the screen is on. */
    private val uiAccelSensor: Sensor?,
    /** Calls the framework's gesture wake; true if it went through. */
    private val wake: () -> Boolean,
    /** Sends the screen back to ambient like a timeout; true if it went through. */
    private val sleep: () -> Boolean,
    private val screenInteractive: () -> Boolean,
) {
    // ---- gate ----------------------------------------------------------------------------------

    private class Sampling(
        val eventUptime: Long,
        val wallMs: Long,
        val source: String,
        val level: TiltSensitivity,
        val thresholds: TiltThresholds,
        val startNs: Long,
    ) {
        val accel = ArrayList<Sample3>(32)
        val gyro = ArrayList<Sample3>(32)
    }

    private var sampling: Sampling? = null

    val busy: Boolean get() = sampling != null

    private val gateListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val s = sampling ?: return
            val sample = Sample3(event.timestamp, event.values[0], event.values[1], event.values[2])
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> s.accel.add(sample)
                Sensor.TYPE_GYROSCOPE -> s.gyro.add(sample)
            }
            if (event.sensor.type == Sensor.TYPE_ACCELEROMETER &&
                TiltGate.windowComplete(event.timestamp, s.startNs, s.thresholds)
            ) {
                finishSampling()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val samplingTimeout = Runnable { finishSampling() }

    /** A hub tilt event that passed the outer gating (setting, theater mode, off-body, debounce). */
    fun onTilt(source: String, level: TiltSensitivity) {
        val now = SystemClock.uptimeMillis()
        val wall = System.currentTimeMillis()
        val t = TiltGate.thresholds(level)
        if (t == null) {
            logSkip(wall, now, source, level, "off")
            return
        }
        if (sampling != null) return // WakeGestures already drops events while a window is open
        val s = Sampling(now, wall, source, level, t, SystemClock.elapsedRealtimeNanos())
        sampling = s
        val manager = sensorManager
        val ok = manager != null && accelSensor != null &&
            manager.registerListener(gateListener, accelSensor, SAMPLE_PERIOD_US, 0, handler)
        if (ok) gyroSensor?.let { manager?.registerListener(gateListener, it, SAMPLE_PERIOD_US, 0, handler) }
        // Fallback in case samples stop arriving; a wake-up sensor keeps the AP up meanwhile.
        handler.postDelayed(samplingTimeout, t.windowMs + SAMPLING_SLACK_MS)
        if (!ok) Log.w(TAG, "gate: accelerometer unavailable; the window will be empty")
    }

    private fun finishSampling() {
        val s = sampling ?: return
        sampling = null
        handler.removeCallbacks(samplingTimeout)
        sensorManager?.unregisterListener(gateListener)
        val verdict = TiltGate.evaluate(s.accel, s.gyro, s.startNs, s.thresholds)
        val latency = SystemClock.uptimeMillis() - s.eventUptime
        when {
            !verdict.accept -> {
                Log.i(TAG, "gate: reject (${verdict.reason.id}) ${describe(verdict.features)}")
                telemetry.append(
                    TiltLog.line(
                        s.wallMs, s.eventUptime, s.source, s.level, "reject", verdict.reason.id,
                        verdict.features, latency, null, null, null,
                    ),
                )
            }
            screenInteractive() -> {
                // Woken some other way (a button) while we were sampling: nothing to do.
                logSkip(s.wallMs, s.eventUptime, s.source, s.level, "awake", verdict.features, latency)
            }
            wake() -> {
                Log.i(TAG, "gate: accept ${describe(verdict.features)} -> wakeUp (${latency} ms)")
                beginGlance(s, verdict, latency)
            }
            else -> logSkip(s.wallMs, s.eventUptime, s.source, s.level, "wakefail", verdict.features, latency)
        }
    }

    // ---- glance --------------------------------------------------------------------------------

    private class Glance(
        val sampling: Sampling,
        val verdict: TiltVerdict,
        val latencyMs: Long,
        val wakeUptime: Long,
        var hadFocus: Boolean,
    ) {
        var interactAt: Long? = null
        var sawInteractive = false
        var leftLauncher = false
        var end: GlanceEnd? = null
    }

    private var glance: Glance? = null

    /** Whether a launcher activity window has input focus right now (MainActivity reports it). */
    private var launcherFocused = false

    private var wristDown: WristDownDetector? = null

    private val wristListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val d = wristDown ?: return
            if (d.onSample(Sample3(event.timestamp, event.values[0], event.values[1], event.values[2]))) {
                quickSleep(GlanceEnd.WRIST_DOWN)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val quickTimeout = Runnable { quickSleep(GlanceEnd.QUICK) }

    private val noWakeCheck = Runnable {
        val g = glance ?: return@Runnable
        if (!g.sawInteractive) finishGlance(GlanceEnd.NO_WAKE)
    }

    private fun beginGlance(s: Sampling, verdict: TiltVerdict, latency: Long) {
        glance?.let { finishGlance(GlanceEnd.OTHER) }
        glance = Glance(s, verdict, latency, SystemClock.uptimeMillis(), launcherFocused)
        handler.postDelayed(quickTimeout, GlancePolicy.QUICK_TIMEOUT_MS)
        handler.postDelayed(noWakeCheck, GlancePolicy.NO_WAKE_AFTER_MS)
        if (uiAccelSensor != null && sensorManager != null) {
            wristDown = WristDownDetector()
            sensorManager.registerListener(wristListener, uiAccelSensor, SensorManager.SENSOR_DELAY_UI, handler)
        }
    }

    private fun mayQuickSleep(g: Glance): Boolean =
        GlancePolicy.mayQuickSleep(g.interactAt != null, launcherFocused, g.leftLauncher)

    private fun quickSleep(end: GlanceEnd) {
        val g = glance ?: return
        stopWristDown()
        if (!mayQuickSleep(g) || !screenInteractive()) {
            Log.i(
                TAG,
                "glance: ${end.id} ignored (interacted=${g.interactAt != null} focused=$launcherFocused " +
                    "left=${g.leftLauncher} interactive=${screenInteractive()})",
            )
            return
        }
        g.end = end
        if (sleep()) {
            Log.i(TAG, "glance: ${end.id} -> back to ambient after ${SystemClock.uptimeMillis() - g.wakeUptime} ms")
        } else {
            g.end = null
        }
    }

    /** A touch, crown turn/press or button press reached the launcher. */
    fun onUserInteraction() {
        val g = glance ?: return
        if (g.interactAt == null) {
            g.interactAt = SystemClock.uptimeMillis()
            handler.removeCallbacks(quickTimeout)
            stopWristDown()
        }
    }

    /** MainActivity's window gained or lost input focus. */
    fun onLauncherFocus(focused: Boolean) {
        launcherFocused = focused
        val g = glance ?: return
        if (focused) {
            g.hadFocus = true
        } else if (g.hadFocus && g.sawInteractive) {
            // Something else took the screen (the shade, an app, a dialog): touches there are invisible
            // to us, so the normal timeout governs from here.
            g.leftLauncher = true
            Log.i(TAG, "glance: launcher lost focus -> normal timeout")
            handler.removeCallbacks(quickTimeout)
            stopWristDown()
        }
    }

    /** The default display's state changed (WakeGestures' display listener). */
    fun onDisplayChanged(interactive: Boolean) {
        val g = glance ?: return
        if (interactive) {
            g.sawInteractive = true
        } else if (g.sawInteractive) {
            finishGlance(g.end ?: if (g.interactAt != null) GlanceEnd.USER else GlanceEnd.OTHER)
        }
    }

    private fun finishGlance(end: GlanceEnd) {
        val g = glance ?: return
        glance = null
        handler.removeCallbacks(quickTimeout)
        handler.removeCallbacks(noWakeCheck)
        stopWristDown()
        val now = SystemClock.uptimeMillis()
        val s = g.sampling
        telemetry.append(
            TiltLog.line(
                s.wallMs, s.eventUptime, s.source, s.level, "wake", "ok", g.verdict.features, g.latencyMs,
                g.interactAt?.let { it - g.wakeUptime },
                if (end == GlanceEnd.NO_WAKE) null else now - g.wakeUptime,
                end,
            ),
        )
    }

    private fun stopWristDown() {
        if (wristDown != null) {
            wristDown = null
            sensorManager?.unregisterListener(wristListener)
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    fun logSkip(
        wallMs: Long,
        uptimeMs: Long,
        source: String,
        level: TiltSensitivity,
        reason: String,
        f: TiltFeatures? = null,
        latencyMs: Long? = null,
    ) {
        Log.i(TAG, "tilt: skip ($reason)")
        telemetry.append(TiltLog.line(wallMs, uptimeMs, source, level, "skip", reason, f, latencyMs, null, null, null))
    }

    private fun describe(f: TiltFeatures): String =
        "n=${f.n} a=(%.1f, %.1f, %.1f) angle=%.0f accelRms=%.2f gyroRms=%.2f"
            .format(java.util.Locale.ROOT, f.ax, f.ay, f.az, f.angleDeg, f.accelRms, f.gyroRms)

    companion object {
        private const val TAG = "WakeGestures"

        /** 50 Hz: the rate the hub's own tilt algorithm runs its accel/gyro at. */
        private const val SAMPLE_PERIOD_US = 20_000

        private const val SAMPLING_SLACK_MS = 400L
    }
}
