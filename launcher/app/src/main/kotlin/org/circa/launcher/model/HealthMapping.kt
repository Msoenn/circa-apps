package org.circa.launcher.model

/**
 * Pure mapping from WatchLink's provider rows to [HealthData], plus the launcher's fallback rules.
 * Everything Android (the cursor, the ContentResolver, the observer) lives in `data/HealthRepository`;
 * this is JVM-tested.
 */
object HealthMapping {

    /**
     * One `/latest` row, already read off its cursor: the provider's four columns, null where it
     * reports "not live" (a stale heart rate, or a step counter never observed).
     */
    data class LatestRow(
        val hrBpm: Int?,
        val hrTimeMs: Long?,
        val stepsToday: Long?,
        val stepsTimeMs: Long?,
        val restingHr: Int? = null,
        val restingHr7d: Int? = null,
    )

    /**
     * `/latest` -> [HealthData]. WatchLink already reports a heart rate older than its 15-minute
     * window as absent, so a live `hr_bpm` always carries `hr_time_ms`; a bpm without a timestamp is
     * a partial row and is dropped rather than drawn as if it were live.
     *
     * Steps count as data only once the counter has been observed (`steps_time_ms` non-null):
     * WatchLink reports `steps_today = 0` both for a real zero and for "the counter has not been read
     * yet", and the second case must fall through to the launcher's own step sensor rather than show
     * a hard zero.
     */
    fun latest(row: LatestRow?): HealthData {
        if (row == null) return HealthData.EMPTY
        val hrTime = row.hrTimeMs
        val hr = row.hrBpm?.takeIf { hrTime != null }
        val steps = if (row.stepsTimeMs != null) row.stepsToday?.toStepsInt() else null
        return HealthData(
            hrBpm = hr,
            hrTimeMs = if (hr != null) hrTime else null,
            stepsToday = steps,
            history = emptyList(),
            restingHr = row.restingHr?.takeIf { it > 0 },
            restingHr7d = row.restingHr7d?.takeIf { it > 0 },
        )
    }

    /**
     * The health tile's resting line: "Resting 58 · 7-day 60", only the known parts, or null when neither is known.
     */
    fun restingLine(resting: Int?, sevenDay: Int?): String? {
        val r = resting?.takeIf { it > 0 }
        val w = sevenDay?.takeIf { it > 0 }
        return when {
            r != null && w != null -> "Resting $r · 7-day $w"
            r != null -> "Resting $r"
            w != null -> "7-day $w"
            else -> null
        }
    }

    /** `/history` rows -> the model's sample list (oldest first, as the provider returns them). */
    fun history(samples: List<HrSample>): List<HrSample> = samples.filter { it.bpm > 0 }

    /**
     * The steps the face and the tile draw: WatchLink's count when it has one, else the launcher's own
     * `TYPE_STEP_COUNTER` reading (`data/StepSource`). WatchLink is preferred because it counts from
     * the same sensor but keeps counting while the launcher is not running; the sensor is the
     * fallback for when WatchLink is not installed or has not seen the counter yet.
     */
    fun preferredSteps(watchLink: Int?, sensor: Int?): Int? = watchLink ?: sensor

    /**
     * One `/sleep` row (start, end, minutes asleep), null where a column was missing. A row that does not describe
     * a real session (no minutes, end not after start) is dropped.
     */
    fun sleep(startMs: Long?, endMs: Long?, minutes: Int?): SleepSummary? {
        if (startMs == null || endMs == null || minutes == null) return null
        if (minutes <= 0 || endMs <= startMs) return null
        return SleepSummary(startMs, endMs, minutes)
    }

    /** "7 h 12 min", "8 h", "45 min": the time asleep as the health tile shows it. */
    fun sleepDuration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "$m min"
            m == 0 -> "$h h"
            else -> "$h h $m min"
        }
    }

    private fun Long.toStepsInt(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}
