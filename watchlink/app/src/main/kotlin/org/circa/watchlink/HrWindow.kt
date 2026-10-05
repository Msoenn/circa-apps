package org.circa.watchlink

/**
 * One scheduled heart-rate acquisition: a sensor window that ends at the first usable reading, at a NO_CONTACT
 * timeout, or at a hard cap. Pure Kotlin, no Android imports, host-tested.
 *
 * WatchLink opens the HR sensor on every 5-minute grid point and holds the `"hr-window"` partial wakelock while it
 * waits for a reading. The window must end as early as the sensor allows: [Sensors] forwards every reading it
 * accepts to the bucket mean (spec §4.3: `hrm` = "mean bpm in the interval") and to the health provider, so a
 * second reading only costs battery. [WatchLinkService] therefore ends the window on the first usable reading;
 * [maxMs] bounds the case where the sensor has nothing to give, after which the interval is recorded as not
 * measured (`hrm` = 0) and the wakelock is released.
 *
 * Off the wrist the PPG reports `SENSOR_STATUS_NO_CONTACT` (accuracy -1, bpm 0) instead of a reading, and the real
 * watch logs exactly one such event per window. [onNoContact] starts a [NO_CONTACT_GRACE_MS] grace from the first
 * such event; if no usable reading arrives inside it, [noContactTimedOut] lets the window end early with reason
 * "no-contact" instead of holding the wakelock for the full cap. The grace exists because the PPG can report
 * no-contact briefly at power-up even on the wrist.
 *
 * "Usable" = `bpm > 0` with accuracy >= [ACCURACY_MEDIUM]. [Sensors] already forwards LOW readings to the bucket mean,
 * but the PPG's first readings after power-up are often LOW and off, so they don't end the window.
 */
class HrWindow(private val startMs: Long, private val maxMs: Long) {
    private var valid = 0
    private var lastBpmValue = 0
    private var firstNoContactMs = -1L

    fun startMs(): Long = startMs

    /** Hard cap of one window: the worst-case hold of the wakelock that backs it. */
    fun maxHoldMs(): Long = maxMs

    /** Feed one sensor reading; returns true once the window may end (a usable reading arrived). */
    fun onSample(bpm: Int, accuracy: Int): Boolean {
        if (bpm > 0 && accuracy >= ACCURACY_MEDIUM) {
            valid++
            lastBpmValue = bpm
        }
        return readingReady()
    }

    /** Feed one NO_CONTACT event; returns true once the window may end (its grace has expired). */
    fun onNoContact(atMs: Long): Boolean {
        if (firstNoContactMs < 0) firstNoContactMs = atMs
        return shouldEnd(atMs)
    }

    /** True once one usable reading has arrived. */
    fun readingReady(): Boolean = valid > 0

    /** True when a NO_CONTACT event has been seen (its grace is running). */
    fun noContactSeen(): Boolean = firstNoContactMs >= 0

    /** Deadline [NO_CONTACT_GRACE_MS] after the first NO_CONTACT event, or [NO_DEADLINE] when none was seen. */
    fun noContactDeadlineMs(): Long =
        if (firstNoContactMs < 0) NO_DEADLINE else firstNoContactMs + NO_CONTACT_GRACE_MS

    /** True when the grace after the first NO_CONTACT event has expired without a usable reading. */
    fun noContactTimedOut(atMs: Long): Boolean =
        !readingReady() && firstNoContactMs >= 0 && atMs >= noContactDeadlineMs()

    /** True when the window must end regardless of what was measured: reading, NO_CONTACT timeout, or the cap. */
    fun shouldEnd(atMs: Long): Boolean =
        readingReady() || capReached(atMs) || noContactTimedOut(atMs)

    /** True when the window must end regardless of what was measured. */
    fun capReached(atMs: Long): Boolean = atMs - startMs >= maxMs

    /** Usable readings seen so far (for the per-acquisition log). */
    fun validSamples(): Int = valid

    /** Last usable bpm, or 0 when the window has none (GB's "no reading" value). */
    fun lastBpm(): Int = lastBpmValue

    /** How long the window has been (or was) open at [atMs]. */
    fun holdMs(atMs: Long): Long = atMs - startMs

    companion object {
        /** SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM (kept here so this class stays Android-free). */
        const val ACCURACY_MEDIUM = 2

        /** How long a NO_CONTACT reading may last before the window ends, as it can flicker at PPG power-up. */
        const val NO_CONTACT_GRACE_MS = 3_000L

        /** [noContactDeadlineMs] value while no NO_CONTACT event has been seen. */
        const val NO_DEADLINE = Long.MAX_VALUE
    }
}
