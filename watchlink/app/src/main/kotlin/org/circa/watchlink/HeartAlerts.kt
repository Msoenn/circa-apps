package org.circa.watchlink

/**
 * High / low heart-rate alert decision. Pure Kotlin, host-tested; the notification and vibration live in
 * [WatchLinkService].
 *
 * The input is the scheduled 5-minute HR samples (one per acquisition that produced a usable reading). A high alert
 * needs the last two samples at or above the high threshold while the covering activity buckets hold fewer than
 * [INACTIVE_MAX_STEPS] steps and no workout is recording; a low alert needs the last two samples at or below the low
 * threshold with the watch on the wrist. Readings without skin contact are not samples at all ([Sample.bpm] is
 * positive by the time it reaches this decision).
 *
 * Both thresholds are Settings.Secure keys with a default and "0" = off ([high] / [low]). Each alert type has its
 * own [COOLDOWN_MS] cooldown, passed in as the last-fired time in WatchLink's clock.
 */
object HeartAlerts {
    const val SETTING_HIGH = "circa_hr_high_bpm"
    const val SETTING_LOW = "circa_hr_low_bpm"

    const val HIGH_DEFAULT = 120
    const val LOW_DEFAULT = 40

    /** After an alert of one kind fires, the same kind stays quiet for this long. */
    const val COOLDOWN_MS = 30L * 60_000L

    /** "Inactive" for the high alert: fewer steps than this in the buckets covering the two samples. */
    const val INACTIVE_MAX_STEPS = 30

    /** The newest sample must be this recent, so an old pair never fires after a gap in sampling. */
    const val MAX_SAMPLE_AGE_MS = 7L * 60_000L

    /** The two samples must be this close together to count as consecutive 5-minute samples. */
    const val MAX_GAP_MS = 10L * 60_000L

    /** Three 120 ms pulses (off/on pairs; -1 = play once). */
    @JvmField
    val VIBRATION = longArrayOf(0, 120, 120, 120, 120, 120)

    enum class Kind { HIGH, LOW }

    /** One scheduled HR sample: [timeMs] is WatchLink's clock, [bpm] a usable reading (> 0). */
    class Sample(@JvmField val timeMs: Long, @JvmField val bpm: Int)

    /** One 10-minute activity bucket: [endMs] is the bucket's end, [steps] its step count. */
    class StepBucket(@JvmField val endMs: Long, @JvmField val steps: Int)

    class Alert(@JvmField val kind: Kind, @JvmField val bpm: Int)

    /** `circa_hr_high_bpm`: absent or unreadable = [HIGH_DEFAULT], "0" = off. */
    @JvmStatic
    fun high(raw: String?): Int = threshold(raw, HIGH_DEFAULT)

    /** `circa_hr_low_bpm`: absent or unreadable = [LOW_DEFAULT], "0" = off. */
    @JvmStatic
    fun low(raw: String?): Int = threshold(raw, LOW_DEFAULT)

    private fun threshold(raw: String?, fallback: Int): Int =
        raw?.trim()?.toIntOrNull()?.takeIf { it >= 0 } ?: fallback

    /**
     * Steps in the buckets whose 10-minute interval intersects `[fromMs, toMs]`. Callers pass the closed buckets
     * from the history plus the still-open bucket (with its provisional end), so the total covers both samples.
     */
    @JvmStatic
    fun stepsInCoveringBuckets(buckets: List<StepBucket>, fromMs: Long, toMs: Long): Int {
        var steps = 0
        for (b in buckets) {
            if (b.endMs > fromMs && b.endMs - Buckets.BUCKET_MS <= toMs) steps += Math.max(0, b.steps)
        }
        return steps
    }

    /**
     * The alert the newest samples call for, or null. [samples] are oldest first; [lastHighMs] / [lastLowMs] are the
     * last times each kind fired (0 = never). [workout] is true while a workout is recording.
     */
    @JvmStatic
    fun decide(
        samples: List<Sample>,
        buckets: List<StepBucket>,
        worn: Boolean,
        workout: Boolean,
        high: Int,
        low: Int,
        lastHighMs: Long,
        lastLowMs: Long,
        nowMs: Long,
    ): Alert? {
        val valid = samples.filter { it.bpm > 0 }
        if (valid.size < 2) return null
        val last = valid[valid.size - 1]
        val prev = valid[valid.size - 2]
        if (nowMs - last.timeMs > MAX_SAMPLE_AGE_MS) return null
        if (last.timeMs <= prev.timeMs || last.timeMs - prev.timeMs > MAX_GAP_MS) return null
        if (high > 0 && !workout && last.bpm >= high && prev.bpm >= high &&
            stepsInCoveringBuckets(buckets, prev.timeMs, nowMs) < INACTIVE_MAX_STEPS &&
            cooledDown(lastHighMs, nowMs)
        ) {
            return Alert(Kind.HIGH, last.bpm)
        }
        if (low > 0 && worn && last.bpm <= low && prev.bpm <= low && cooledDown(lastLowMs, nowMs)) {
            return Alert(Kind.LOW, last.bpm)
        }
        return null
    }

    private fun cooledDown(lastFiredMs: Long, nowMs: Long): Boolean =
        lastFiredMs <= 0L || nowMs - lastFiredMs >= COOLDOWN_MS
}
