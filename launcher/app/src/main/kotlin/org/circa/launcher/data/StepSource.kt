package org.circa.launcher.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * `TYPE_STEP_COUNTER` (steps since boot), delivered to [onCounter] while the launcher is in the
 * foreground. It is a low-power on-change sensor: the last value arrives right after registering,
 * so the face has a number as soon as it is shown. Needs the `ACTIVITY_RECOGNITION` runtime
 * permission; without it (or without the sensor) [start] returns false and the face keeps its
 * "no data" state - nothing is faked.
 */
class StepSource(private val context: Context, private val onCounter: (Long) -> Unit) :
    SensorEventListener {

    private val manager = context.getSystemService(SensorManager::class.java)
    private var registered = false

    fun start(): Boolean {
        if (registered) return true
        if (context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return false
        registered = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        return registered
    }

    fun stop() {
        if (!registered) return
        manager?.unregisterListener(this)
        registered = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.values.isNotEmpty()) onCounter(event.values[0].toLong())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
