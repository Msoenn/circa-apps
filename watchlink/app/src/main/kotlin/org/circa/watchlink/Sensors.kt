package org.circa.watchlink

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.SystemClock
import android.util.Log

/** Heart rate (type 21, on demand, reference-counted by reason) and the step counter (always on). */
internal class Sensors(
    private val sm: SensorManager,
    private val h: Handler,
    private val sink: HrSink,
    /** Cumulative step-counter value on every event (the step sensor is registered anyway). */
    private val stepSink: (Long) -> Unit = {},
    /** elapsedRealtime of every HR event whose accuracy is SENSOR_STATUS_NO_CONTACT (off the wrist). */
    private val noContactSink: (Long) -> Unit = {},
) : SensorEventListener {

    fun interface HrSink {
        fun onHr(bpm: Int, accuracy: Int)
    }

    private val hrSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_HEART_RATE)
    private val stepSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val hrReasons = HashSet<String>()
    private var hrOn = false
    private var stepOn = false
    private var lastBpm = 0
    private var lastAcc = Int.MIN_VALUE
    private var lastHrAt = 0L            // elapsedRealtime of the last valid reading
    private var hrEvents = 0L
    private var lastHrLogAt = 0L
    private var stepTotal = -1L

    init {
        Log.i(TAG, "sensors: heartRate=" + (hrSensor?.name ?: "none")
                + " stepCounter=" + (stepSensor?.name ?: "none"))
    }

    fun startSteps() {
        if (stepOn || stepSensor == null) return
        stepOn = sm.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL, h)
        Log.i(TAG, "step counter register -> $stepOn")
    }

    fun wantHr(reason: String, on: Boolean) {
        val changed = if (on) hrReasons.add(reason) else hrReasons.remove(reason)
        if (!changed) return
        val want = hrReasons.isNotEmpty()
        if (want && !hrOn && hrSensor != null) {
            hrOn = sm.registerListener(this, hrSensor, SensorManager.SENSOR_DELAY_NORMAL, h)
            hrEvents = 0
            Log.i(TAG, "HR sensor register ($hrReasons) -> $hrOn")
        } else if (!want && hrOn) {
            sm.unregisterListener(this, hrSensor)
            hrOn = false
            Log.i(TAG, "HR sensor released (events=$hrEvents)")
        } else {
            Log.i(TAG, "HR reasons now $hrReasons (sensor ${if (hrOn) "on" else "off"})")
        }
    }

    fun hrOn(): Boolean = hrOn

    fun stopAll() {
        sm.unregisterListener(this)
        if (hrOn) Log.i(TAG, "HR sensor released (stopAll)")
        hrOn = false
        stepOn = false
        hrReasons.clear()
    }

    /** Latest valid bpm if younger than maxAgeMs, else 0 (GB: 0 = no reading). */
    fun latestHr(maxAgeMs: Long): Int {
        if (lastHrAt == 0L || SystemClock.elapsedRealtime() - lastHrAt > maxAgeMs) return 0
        return lastBpm
    }

    fun lastAccuracy(): Int = lastAcc

    /** Cumulative steps since boot, or -1 if unknown. */
    fun stepTotal(): Long = stepTotal

    override fun onSensorChanged(e: SensorEvent) {
        val type = e.sensor.type
        if (type == Sensor.TYPE_HEART_RATE) {
            hrEvents++
            val bpm = Math.round(e.values[0])
            lastAcc = e.accuracy
            val now = SystemClock.elapsedRealtime()
            val valid = bpm > 0 && e.accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_LOW
            if (valid) {
                lastBpm = bpm
                lastHrAt = now
                sink.onHr(bpm, e.accuracy)
            } else if (e.accuracy == SensorManager.SENSOR_STATUS_NO_CONTACT) {
                // Off the wrist the PPG emits only `bpm=0 acc=-1 valid=false`; the window needs this to end early.
                noContactSink(now)
            }
            if (hrEvents == 1L || now - lastHrLogAt > 30_000) {
                lastHrLogAt = now
                Log.d(TAG, "HR event bpm=$bpm acc=${e.accuracy} valid=$valid n=$hrEvents")
            }
        } else if (type == Sensor.TYPE_STEP_COUNTER) {
            val v = e.values[0].toLong()
            if (stepTotal < 0) Log.d(TAG, "step counter first value $v")
            stepTotal = v
            stepSink(v)
        }
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}

    companion object {
        const val TAG = "WatchLink"
    }
}
