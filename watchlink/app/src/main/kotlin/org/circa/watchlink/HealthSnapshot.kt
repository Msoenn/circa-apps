package org.circa.watchlink

/**
 * Latest HR / steps values the launcher reads, plus the recent-HR ring buffer. Pure Kotlin, no Android imports,
 * host-tested. The Android-facing glue (live updates, observer notifications, persistence) is [HealthStore].
 *
 * One clock domain: every `nowMs` passed in must come from the same source as the stored sample times
 * (WatchLink's phone-synced time, [ActivityLog.now]). Staleness and window trimming are differences inside that
 * domain, so they do not care whether the watch clock is right yet.
 *
 * - **HR staleness:** the newest sample older than [HR_MAX_AGE_MS] is reported as absent — a reading from an hour
 *   ago must not look live.
 * - **History:** samples of the last [HISTORY_WINDOW_MS], oldest first, capped at [MAX_SAMPLES] (a realtime HR
 *   session at sensor rate would otherwise grow without bound).
 * - **Steps since local midnight:** the step counter is cumulative since boot, so "today" is
 *   `total - totalAtLocalMidnight`. The baseline is re-snapped when the local date changes. A reboot — a
 *   different boot count, or a counter that jumped backwards when the boot count is unknown — carries the steps
 *   already walked today into the new counter's origin (the baseline goes negative), so the day total survives
 *   the reset; a reboot that lands on a new local day starts from 0.
 */
class HealthSnapshot(
    private val hrMaxAgeMs: Long = HR_MAX_AGE_MS,
    private val historyWindowMs: Long = HISTORY_WINDOW_MS,
    private val maxSamples: Int = MAX_SAMPLES,
) {

    /** One HR reading. `@JvmField` so the host tests (Java) can read it directly, like [Buckets.Record]. */
    class Sample(@JvmField val timeMs: Long, @JvmField val bpm: Int)

    private val samples = ArrayDeque<Sample>()      // oldest first; newest last

    private var dayStartMs = Long.MIN_VALUE         // local-midnight epoch ms of the day the baseline belongs to
    private var baseTotal = -1L                     // cumulative counter at that midnight; negative after a same-day reboot
    private var bootCount = -1                      // platform boot count the baseline belongs to; -1 = unknown
    private var lastTotal = -1L                     // last observed cumulative counter; < 0 = nothing observed yet
    private var stepsToday: Long? = null
    private var stepsAtMs: Long? = null

    // ---- HR ----

    /** Record one valid reading (the sensor sink already filters bpm > 0 / accuracy); trims the ring. */
    @Synchronized
    fun addHr(nowMs: Long, bpm: Int) {
        if (bpm <= 0) return
        samples.addLast(Sample(nowMs, bpm))
        trim(nowMs)
    }

    /** Newest reading if younger than [hrMaxAgeMs], else null. */
    @Synchronized
    fun latest(nowMs: Long): Sample? {
        val s = samples.lastOrNull() ?: return null
        val age = nowMs - s.timeMs
        return if (age > hrMaxAgeMs) null else s
    }

    /** Readings in the last [historyWindowMs], oldest first (a copy; the ring keeps mutating). */
    @Synchronized
    fun history(nowMs: Long): List<Sample> {
        trim(nowMs)
        return ArrayList(samples)
    }

    private fun trim(nowMs: Long) {
        val cutoff = nowMs - historyWindowMs
        while (samples.isNotEmpty() && samples.first().timeMs < cutoff) samples.removeFirst()
        while (samples.size > maxSamples) samples.removeFirst()
    }

    // ---- steps ----

    /**
     * Feed the cumulative step counter. Returns steps since local midnight, or the last value if [total] is
     * unknown (-1). [tzOffsetMs] is the local UTC offset at [nowMs]; [bootCount] is `Settings.Global.BOOT_COUNT`
     * (-1 when unknown), read by the caller.
     */
    @Synchronized
    fun updateSteps(nowMs: Long, total: Long, tzOffsetMs: Int, bootCount: Int): Long? {
        if (total < 0) return stepsToday
        val midnight = localMidnight(nowMs, tzOffsetMs)
        when {
            lastTotal < 0 -> {
                // First observation (nothing restored): today starts here.
                dayStartMs = midnight
                baseTotal = total
            }
            isReboot(total, bootCount) -> {
                // A reboot reset the counter: carry today's steps into the new origin, or start a new day at 0.
                val carried = if (dayStartMs == midnight) (lastTotal - baseTotal).coerceAtLeast(0L) else 0L
                dayStartMs = midnight
                baseTotal = -carried
            }
            dayStartMs != midnight -> {
                // A new local day: everything counted since the last reading is the best estimate of today.
                dayStartMs = midnight
                baseTotal = total
            }
        }
        val v = total - baseTotal
        stepsToday = if (v < 0) 0 else v
        lastTotal = total
        this.bootCount = bootCount
        stepsAtMs = nowMs
        return stepsToday
    }

    /**
     * A different boot count (when both are known) means the device rebooted; a counter below the last reading is
     * the fallback for a reboot the boot count cannot report, and catches a counter reset within the same boot.
     */
    private fun isReboot(total: Long, bootCount: Int): Boolean =
        total < lastTotal || (bootCount >= 0 && this.bootCount >= 0 && bootCount != this.bootCount)

    /** Steps since local midnight, or null if the step counter has not been observed. */
    @Synchronized
    fun stepsToday(): Long? = stepsToday

    /** Time of the last step observation, or null. */
    @Synchronized
    fun stepsTimeMs(): Long? = stepsAtMs

    /** Local-midnight epoch ms of the tracked day (for persistence); [Long.MIN_VALUE] before the first update. */
    @Synchronized
    fun dayStartMs(): Long = dayStartMs

    /** Cumulative step counter at that midnight (for persistence); negative after a same-day reboot, -1 before it. */
    @Synchronized
    fun baseTotal(): Long = baseTotal

    /** Last observed cumulative counter (for persistence); -1 before the first update. */
    @Synchronized
    fun lastTotal(): Long = lastTotal

    /** Boot count the baseline belongs to (for persistence); -1 when unknown. */
    @Synchronized
    fun bootCount(): Int = bootCount

    /**
     * Restore a persisted baseline (process restart): the local-midnight day, the baseline, the last counter seen
     * and the boot count. An invalid record is ignored; the next update re-snaps.
     */
    @Synchronized
    fun restoreSteps(dayStartMs: Long, baseTotal: Long, lastTotal: Long, bootCount: Int) {
        if (dayStartMs == Long.MIN_VALUE || lastTotal < 0) return
        this.dayStartMs = dayStartMs
        this.baseTotal = baseTotal
        this.lastTotal = lastTotal
        this.bootCount = bootCount
        stepsToday = null    // recomputed on the next update
    }

    companion object {
        /** A reading older than this is reported as absent (the provider's `hr_bpm` becomes null). */
        const val HR_MAX_AGE_MS = 15L * 60 * 1000

        /** History window (and the ring's trim horizon). */
        const val HISTORY_WINDOW_MS = 60L * 60 * 1000

        /** Hard cap on buffered readings, so a realtime HR session cannot grow the ring without bound. */
        const val MAX_SAMPLES = 2048

        const val DAY_MS = 24L * 60 * 60 * 1000

        /** Epoch ms of local midnight for [nowMs] at offset [tzOffsetMs] (east positive), floor-based. */
        @JvmStatic
        fun localMidnight(nowMs: Long, tzOffsetMs: Int): Long =
            Math.floorDiv(nowMs + tzOffsetMs, DAY_MS) * DAY_MS - tzOffsetMs
    }
}
