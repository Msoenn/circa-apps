package org.circa.clock.model

/** What the ringing screen / service should do for a ringer mode and DND state. */
data class AlertPlan(val sound: Boolean, val vibrate: Boolean)

object Alert {
    // AudioManager.RINGER_MODE_* values, inlined so this stays pure JVM.
    const val RINGER_SILENT = 0
    const val RINGER_VIBRATE = 1
    const val RINGER_NORMAL = 2

    /**
     * Follow the system ringer mode: normal = sound and vibration, vibrate = vibration, silent = vibration
     * only (an alarm must not be missed because the watch is on silent). Total-silence DND
     * (INTERRUPTION_FILTER_NONE, which blocks even alarms) removes the sound; vibration stays.
     */
    fun plan(ringerMode: Int, totalSilence: Boolean): AlertPlan = AlertPlan(
        sound = ringerMode == RINGER_NORMAL && !totalSilence,
        vibrate = true,
    )

    /** Escalating vibration: timings (off, on, off, on...) and amplitudes; repeat from [repeatIndex]. */
    data class Wave(val timings: LongArray, val amplitudes: IntArray, val repeatIndex: Int)

    fun escalatingWave(): Wave {
        val steps = 8
        val t = ArrayList<Long>(); val a = ArrayList<Int>()
        t += 0; a += 0
        for (i in 0 until steps) {
            val amp = (50 + (255 - 50) * i / (steps - 1))
            t += 300L + 80L * i; a += amp   // on: longer and stronger each step
            t += 700L - 40L * i; a += 0     // off: shorter each step
        }
        // The last on/off pair repeats until stopped.
        return Wave(t.toLongArray(), a.toIntArray(), repeatIndex = t.size - 2)
    }
}
