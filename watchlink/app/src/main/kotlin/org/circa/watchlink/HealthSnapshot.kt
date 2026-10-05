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
 *   `total - totalAtLocalMidnight`. The baseline is re-snapped when the local date changes or when the counter
 *   jumps backwards (reboot: the HAL restarts at 0), so a day boundary always starts at 0.
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
    private var baseTotal = -1L                     // cumulative step counter at that midnight (or at the reset)
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
     * unknown (-1). [tzOffsetMs] is the local UTC offset at [nowMs].
     */
    @Synchronized
    fun updateSteps(nowMs: Long, total: Long, tzOffsetMs: Int): Long? {
        if (total < 0) return stepsToday
        val midnight = localMidnight(nowMs, tzOffsetMs)
        if (baseTotal < 0 || dayStartMs != midnight || total < baseTotal) {
            // First observation, a new local day, or the counter restarted (reboot): rebase at the current total.
            dayStartMs = midnight
            baseTotal = total
        }
        val v = total - baseTotal
        stepsToday = if (v < 0) 0 else v
        stepsAtMs = nowMs
        return stepsToday
    }

    /** Steps since local midnight, or null if the step counter has not been observed. */
    @Synchronized
    fun stepsToday(): Long? = stepsToday

    /** Time of the last step observation, or null. */
    @Synchronized
    fun stepsTimeMs(): Long? = stepsAtMs

    /** Local-midnight epoch ms of the tracked day (for persistence); [Long.MIN_VALUE] before the first update. */
    @Synchronized
    fun dayStartMs(): Long = dayStartMs

    /** Cumulative step counter at that midnight (for persistence); -1 before the first update. */
    @Synchronized
    fun baseTotal(): Long = baseTotal

    /** Restore a persisted baseline (process restart). An invalid pair is ignored; the next update re-snaps. */
    @Synchronized
    fun restoreSteps(dayStartMs: Long, baseTotal: Long) {
        if (baseTotal < 0 || dayStartMs == Long.MIN_VALUE) return
        this.dayStartMs = dayStartMs
        this.baseTotal = baseTotal
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
