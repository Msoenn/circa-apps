package org.circa.watchlink

/**
 * Smart Wake (docs/watch-ui/circa/bedtime.md): inside the half hour before a Clock alarm, decide minute by minute
 * whether the wearer is stirring - light sleep or awake - so the alarm may ring early. Pure Kotlin, host-tested; the
 * Android glue is in [SleepTracking].
 *
 * The sleep tracker's 10-minute labels arrive up to an hour late, so this uses the raw movement measure instead: the
 * same 5-second arm-angle epochs as [MotionEpochs], counted per minute. Deep sleep and REM keep the arm still;
 * movement comes with light sleep and brief arousals. A minute with [RESTLESS_MINUTE] or more restless epochs, or
 * [CALM_RUN] minutes in a row with at least one each, is a trigger - once [MIN_DATA_MIN] minutes have been measured
 * since the watch began, and only while the watch is worn.
 *
 * Feed it the accelerometer samples ([add]) and call [evaluate] after each flush. Times are elapsedRealtime ms.
 */
class SmartWakeDetector(private val startMs: Long) {

    private val motion = MotionEpochs()
    private var nextMinuteEnd = (Math.floorDiv(startMs, MotionEpochs.MINUTE_MS) + 1) * MotionEpochs.MINUTE_MS

    /** Restless-epoch counts of the measured minutes so far, oldest first. */
    val minutes = ArrayList<Int>()

    fun add(tMs: Long, x: Float, y: Float, z: Float) = motion.add(tMs, x, y, z)

    /**
     * Takes every whole minute that ended at or before [nowMs] and says whether the alarm may ring now.
     * [worn] = on the wrist and not charging.
     */
    fun evaluate(nowMs: Long, worn: Boolean): Boolean {
        while (nextMinuteEnd <= nowMs) {
            val t = motion.take(nextMinuteEnd)
            if (t.minutes > 0) minutes.add(t.restless)
            nextMinuteEnd += MotionEpochs.MINUTE_MS
        }
        return worn && SmartWakeRule.stirring(minutes)
    }
}

object SmartWakeRule {
    /** Restless epochs (of 12) in one minute that count as stirring on their own. */
    const val RESTLESS_MINUTE = 2

    /** Consecutive minutes with at least one restless epoch that count as stirring. */
    const val CALM_RUN = 2

    /** Measured minutes needed before anything counts (the first comparisons need a settled arm angle). */
    const val MIN_DATA_MIN = 3

    /** Whether the latest minutes of [minutes] (restless epochs per minute, oldest first) show the wearer stirring. */
    @JvmStatic
    fun stirring(minutes: List<Int>): Boolean {
        if (minutes.size < MIN_DATA_MIN) return false
        if (minutes.last() >= RESTLESS_MINUTE) return true
        return minutes.takeLast(CALM_RUN).let { run -> run.size == CALM_RUN && run.all { it >= 1 } }
    }
}
