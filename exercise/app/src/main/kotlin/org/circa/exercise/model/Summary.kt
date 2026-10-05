package org.circa.exercise.model

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class BadgeKind { FIRST, LONGEST_DISTANCE, LONGEST_TIME, FASTEST, ZONE_MINUTES, STREAK }

/** A summary chip. [label] is short on purpose: chips never wrap. */
data class Badge(val kind: BadgeKind, val label: String)

/** The saved summary of one finished workout (`<id>.json` next to `<id>.csv`). */
data class Summary(
    val id: String?,
    val type: ActivityType,
    val startMs: Long,
    val endMs: Long,
    val activeMs: Long,
    val distanceM: Double,
    val kcal: Double,
    val hrAvg: Int,
    val hrMax: Int,
    /** Index 0 = below zone 1 / no HR, 1..5. */
    val zoneMs: List<Long>,
    val splitsMs: List<Long>,
    val route: List<DoubleArray>,
    val steps: Long,
    val badges: List<Badge> = emptyList(),
) {
    /** Minutes in zones 4 and 5. */
    val hardMs: Long get() = zoneMs.getOrElse(4) { 0L } + zoneMs.getOrElse(5) { 0L }
    val paceSecPerKm: Double? get() = if (distanceM < 1000) null else Geo.paceSecPerKm(distanceM, activeMs / 1000.0)
    val speedKmh: Double? get() = Geo.speedKmh(distanceM, activeMs / 1000.0)

    fun toJson(): JSONObject = JSONObject().apply {
        put("v", 1)
        id?.let { put("id", it) }
        put("type", type.id); put("start", startMs); put("end", endMs); put("active", activeMs)
        put("distance", distanceM); put("kcal", kcal); put("hrAvg", hrAvg); put("hrMax", hrMax)
        put("zoneMs", JSONArray(zoneMs)); put("splitsMs", JSONArray(splitsMs))
        put("route", JSONArray().also { a -> route.forEach { p -> a.put(JSONArray().put(p[0]).put(p[1])) } })
        put("steps", steps)
        put("badges", JSONArray().also { a -> badges.forEach { b -> a.put(JSONObject().put("kind", b.kind.name).put("label", b.label)) } })
    }

    companion object {
        fun from(w: Workout, id: String? = null): Summary = Summary(
            id = id, type = w.type, startMs = w.startMs, endMs = w.endMs ?: w.startMs,
            activeMs = w.activeMs(w.endMs ?: w.startMs), distanceM = w.distanceM, kcal = w.kcal,
            hrAvg = w.hrAvg, hrMax = w.hrMax, zoneMs = w.zoneMs.toList(), splitsMs = w.splitDurations(),
            route = w.route.toList(), steps = w.steps,
        )

        fun fromJson(o: JSONObject): Summary {
            fun longs(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getLong(it) } } ?: emptyList()
            return Summary(
                id = if (o.has("id")) o.getString("id") else null,
                type = ActivityType.fromId(o.getString("type")) ?: ActivityType.OTHER,
                startMs = o.getLong("start"), endMs = o.getLong("end"), activeMs = o.getLong("active"),
                distanceM = o.getDouble("distance"), kcal = o.getDouble("kcal"),
                hrAvg = o.optInt("hrAvg"), hrMax = o.optInt("hrMax"),
                zoneMs = longs("zoneMs"), splitsMs = longs("splitsMs"),
                route = o.optJSONArray("route")?.let { a -> (0 until a.length()).map { i -> a.getJSONArray(i).let { p -> doubleArrayOf(p.getDouble(0), p.getDouble(1)) } } } ?: emptyList(),
                steps = o.optLong("steps"),
                badges = o.optJSONArray("badges")?.let { a ->
                    (0 until a.length()).mapNotNull { i ->
                        val b = a.getJSONObject(i)
                        runCatching { Badge(BadgeKind.valueOf(b.getString("kind")), b.getString("label")) }.getOrNull()
                    }
                } ?: emptyList(),
            )
        }
    }
}

/** Personal bests and streaks, computed from the saved history. */
object Badges {
    private const val MIN_DISTANCE_M = 100.0
    private const val MIN_TIME_MS = 60_000L
    private const val PACE_MIN_DISTANCE_M = 1000.0

    fun compute(cur: Summary, history: List<Summary>, zone: ZoneId): List<Badge> {
        val same = history.filter { it.type == cur.type && it.startMs != cur.startMs }
        val out = mutableListOf<Badge>()
        if (same.isEmpty()) {
            out += Badge(BadgeKind.FIRST, "First ${cur.type.noun}")
        } else {
            if (cur.type.gps && cur.distanceM >= MIN_DISTANCE_M && cur.distanceM > same.maxOf { it.distanceM })
                out += Badge(BadgeKind.LONGEST_DISTANCE, "Longest")
            else if (cur.activeMs >= MIN_TIME_MS && cur.activeMs > same.maxOf { it.activeMs })
                out += Badge(BadgeKind.LONGEST_TIME, "Longest time")
            if (cur.type.gps && cur.distanceM >= PACE_MIN_DISTANCE_M) {
                val prior = same.filter { it.distanceM >= PACE_MIN_DISTANCE_M }
                if (prior.isNotEmpty()) {
                    val faster = if (cur.type.speedNotPace) (cur.speedKmh ?: 0.0) > prior.maxOf { it.speedKmh ?: 0.0 }
                    else (cur.paceSecPerKm ?: Double.MAX_VALUE) < prior.minOf { it.paceSecPerKm ?: Double.MAX_VALUE }
                    if (faster) out += Badge(BadgeKind.FASTEST, "Fastest")
                }
            }
            if (cur.hardMs >= MIN_TIME_MS && cur.hardMs > same.maxOf { it.hardMs })
                out += Badge(BadgeKind.ZONE_MINUTES, "Zone best")
        }
        val streak = streakDays(cur, history, zone)
        if (streak >= 2) out += Badge(BadgeKind.STREAK, "$streak-day")
        return out
    }

    /** Consecutive days (any activity) ending on the day of [cur]. */
    fun streakDays(cur: Summary, history: List<Summary>, zone: ZoneId): Int {
        fun day(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val days = history.map { day(it.startMs) }.toHashSet()
        var d = day(cur.startMs)
        days += d
        var n = 0
        while (d in days) { n++; d = d.minusDays(1) }
        return n
    }

    /** The badge the headline announces (the summary leaves its chip out), or null for "Nice run!". */
    fun headlineKind(cur: Summary): BadgeKind? {
        val kinds = cur.badges.map { it.kind }.toSet()
        return listOf(BadgeKind.LONGEST_DISTANCE, BadgeKind.FASTEST, BadgeKind.FIRST).firstOrNull { it in kinds }
    }

    /** "Nice run!", "New longest ride!", "New fastest run!", "First hike!". */
    fun headline(cur: Summary): String {
        val noun = cur.type.noun
        return when (headlineKind(cur)) {
            BadgeKind.LONGEST_DISTANCE -> "New longest $noun!"
            BadgeKind.FASTEST -> "New fastest $noun!"
            BadgeKind.FIRST -> "First $noun!"
            else -> "Nice $noun!"
        }
    }
}
