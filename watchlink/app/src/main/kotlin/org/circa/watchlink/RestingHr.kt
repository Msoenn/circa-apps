package org.circa.watchlink

/**
 * Resting heart rate from WatchLink's scheduled HR samples. Pure Kotlin, host-tested; [HealthStore] keeps the
 * state and persists the daily values.
 *
 * Today's value is the [PERCENTILE]th percentile of today's usable samples taken while inactive: the 10-minute
 * bucket the sample fell in had fewer than [INACTIVE_MAX_STEPS] steps. Fewer than [MIN_SAMPLES] such samples means
 * unknown (null), so a short or active day has no resting value. The 7-day value is the mean of the daily values
 * known in the last [AVERAGE_DAYS] local days (including today), rounded; it is null until at least one day exists.
 *
 * The daily values are kept for [KEEP_DAYS] days and encoded as `dayStartMs:bpm` pairs, which is what the
 * `health` preferences hold. Day starts come from [HealthSnapshot.localMidnight] and stay in WatchLink's clock.
 */
object RestingHr {
    const val INACTIVE_MAX_STEPS = 30
    const val MIN_SAMPLES = 6
    const val PERCENTILE = 10
    const val AVERAGE_DAYS = 7
    const val KEEP_DAYS = 14
    const val DAY_MS = 24L * 60 * 60 * 1000

    /** One HR sample and the steps of the open bucket it fell in. */
    class Sample(@JvmField val timeMs: Long, @JvmField val bpm: Int, @JvmField val bucketSteps: Int)

    /** One stored daily value: [dayStartMs] is the local midnight of that day. */
    class Day(@JvmField val dayStartMs: Long, @JvmField val bpm: Int)

    /**
     * Today's resting value from [samples] in the day starting at [dayStartMs]. Invalid readings (non-positive
     * bpm) and samples from an active bucket are ignored; fewer than [MIN_SAMPLES] leave it unknown.
     */
    @JvmStatic
    fun daily(samples: List<Sample>, dayStartMs: Long): Int? {
        val values = samples.asSequence()
            .filter { it.timeMs >= dayStartMs && it.timeMs < dayStartMs + DAY_MS }
            .filter { it.bpm > 0 && it.bucketSteps in 0 until INACTIVE_MAX_STEPS }
            .map { it.bpm }
            .sorted()
            .toList()
        if (values.size < MIN_SAMPLES) return null
        return values[(values.size - 1) * PERCENTILE / 100]
    }

    /**
     * Mean of the daily values in the last [AVERAGE_DAYS] days ending at [todayStartMs], rounded; null when no
     * day in that window has a value.
     */
    @JvmStatic
    fun sevenDay(days: List<Day>, todayStartMs: Long): Int? {
        val from = todayStartMs - (AVERAGE_DAYS - 1) * DAY_MS
        val values = days.filter { it.dayStartMs in from..todayStartMs && it.bpm > 0 }
        if (values.isEmpty()) return null
        return Math.round(values.sumOf { it.bpm }.toDouble() / values.size).toInt()
    }

    /** [days] with [dayStartMs] set to [bpm] (null removes the day), pruned to [KEEP_DAYS] and sorted oldest first. */
    @JvmStatic
    fun withDay(days: List<Day>, dayStartMs: Long, bpm: Int?): List<Day> {
        val out = ArrayList<Day>()
        for (d in days) {
            if (d.dayStartMs != dayStartMs && d.bpm > 0) out.add(d)
        }
        if (bpm != null && bpm > 0) out.add(Day(dayStartMs, bpm))
        return prune(out, dayStartMs)
    }

    /** Values from the last [KEEP_DAYS] days ending at [todayStartMs], oldest first. */
    @JvmStatic
    fun prune(days: List<Day>, todayStartMs: Long): List<Day> {
        val from = todayStartMs - (KEEP_DAYS - 1) * DAY_MS
        return days.filter { it.dayStartMs in from..todayStartMs && it.bpm > 0 }.sortedBy { it.dayStartMs }
    }

    /** `dayStartMs:bpm,dayStartMs:bpm` (stable order), for the `health` preferences. */
    @JvmStatic
    fun encode(days: List<Day>): String =
        days.sortedBy { it.dayStartMs }.joinToString(",") { it.dayStartMs.toString() + ":" + it.bpm }

    /** Parse [encode]'s form; invalid entries are dropped and a repeated day keeps the later value. */
    @JvmStatic
    fun decode(raw: String?): List<Day> {
        if (raw.isNullOrBlank()) return emptyList()
        val byDay = LinkedHashMap<Long, Int>()
        for (part in raw.split(",")) {
            val f = part.trim().split(":")
            if (f.size != 2) continue
            val day = f[0].toLongOrNull() ?: continue
            val bpm = f[1].toIntOrNull() ?: continue
            if (bpm > 0) byDay[day] = bpm
        }
        return byDay.entries.sortedBy { it.key }.map { Day(it.key, it.value) }
    }

    /** `timeMs:bpm:bucketSteps,...`, for today's in-progress sample list. */
    @JvmStatic
    fun encodeSamples(samples: List<Sample>): String =
        samples.joinToString(",") { it.timeMs.toString() + ":" + it.bpm + ":" + it.bucketSteps }

    @JvmStatic
    fun decodeSamples(raw: String?): List<Sample> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = ArrayList<Sample>()
        for (part in raw.split(",")) {
            val f = part.trim().split(":")
            if (f.size != 3) continue
            val t = f[0].toLongOrNull() ?: continue
            val bpm = f[1].toIntOrNull() ?: continue
            val steps = f[2].toIntOrNull() ?: continue
            if (bpm > 0) out.add(Sample(t, bpm, steps))
        }
        return out
    }
}
