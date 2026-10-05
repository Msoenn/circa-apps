package org.circa.exercise.model

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

enum class Phase { RECORDING, PAUSED, FINISHED }

/** One accepted location fix. */
data class Fix(val lat: Double, val lon: Double, val alt: Double?, val timeMs: Long, val accuracy: Float?)

/** What one [Workout.tick] produced. */
data class TickResult(val csvRow: String, val zone: Int, val zoneAnnounced: Boolean)

/**
 * The recording state machine and all running totals of one workout. Pure Kotlin (only org.json for the snapshot), so
 * the service is a thin shell around it: sensors call [onHr], [onLocation], [onStepCounter]; a 1 s ticker calls
 * [tick], which integrates zone time, calories and the HR average and returns the CSV row for that second.
 *
 * Times are wall-clock milliseconds, so a snapshot ([toJson]) restored after the process died (or after a reboot)
 * continues correctly: the active time is the closed segments plus the open one, and paused time never counts.
 */
class Workout(
    val type: ActivityType,
    val startMs: Long,
    val maxHr: Int,
    val age: Int,
    val weightKg: Double,
    val sex: Sex,
) {
    var phase = Phase.RECORDING; private set
    var activeMsBefore = 0L; private set
    var segmentStart: Long? = startMs; private set
    var endMs: Long? = null; private set
    var distanceM = 0.0; private set
    var kcal = 0.0; private set
    var hrSum = 0L; private set
    var hrSamples = 0L; private set
    var hrMax = 0; private set
    /** Index 0 = below zone 1 / no HR, 1..5 = the zones. */
    val zoneMs = LongArray(Zones.COUNT + 1)
    /** Active time (ms since start, pauses excluded) at which each full km was reached. */
    val splitAt = mutableListOf<Long>()
    val route = mutableListOf<DoubleArray>()
    var steps = 0L; private set

    private var lastStepCounter: Long? = null
    private var lastHr: Int? = null
    private var lastHrAt = 0L
    /** Last accepted fix the distance is measured from; null after a pause (no distance across a pause). */
    private var anchor: Fix? = null
    /**
     * The last fix rejected as a jump. When the next fix agrees with it (plausible speed) but not with the anchor, the
     * anchor was the outlier (a stale first fix, a bad fix right after a resume): re-anchor there without adding the jump.
     */
    private var jumpFix: Fix? = null
    /** [from, to] epoch seconds whose CSV positions turned out to be outliers (see [BangleCsv.dropGps]). */
    val gpsDropSpans = mutableListOf<LongArray>()
    var firstFixAt: Long? = null; private set
    var lastFixAt: Long? = null; private set
    private var rowFix: Fix? = null
    private var rowSteps = 0L
    private var lastTickAt = startMs
    private var announcedZone = 0
    private var candidateZone = 0
    private var candidateSince = 0L
    private val paceWindow = ArrayDeque<Pair<Long, Double>>()

    fun activeMs(now: Long): Long = activeMsBefore + (segmentStart?.let { max(0L, now - it) } ?: 0L)
    val isActive: Boolean get() = phase != Phase.FINISHED

    fun pause(now: Long) {
        if (phase != Phase.RECORDING) return
        tick(now)
        activeMsBefore += max(0L, now - (segmentStart ?: now))
        segmentStart = null
        anchor = null
        jumpFix = null
        paceWindow.clear()
        phase = Phase.PAUSED
    }

    fun resume(now: Long) {
        if (phase != Phase.PAUSED) return
        segmentStart = now
        lastTickAt = now
        anchor = null
        jumpFix = null
        phase = Phase.RECORDING
    }

    fun finish(now: Long) {
        if (phase == Phase.FINISHED) return
        if (phase == Phase.RECORDING) pause(now)
        phase = Phase.FINISHED
        endMs = now
    }

    fun onHr(bpm: Int, now: Long) {
        if (bpm !in 25..250) return
        lastHr = bpm; lastHrAt = now
    }

    /** The heart rate if one arrived in the last 10 s. */
    fun currentHr(now: Long): Int? = lastHr?.takeIf { now - lastHrAt <= HR_FRESH_MS }

    /**
     * A GPS fix. Fixes worse than [MAX_ACCURACY_M] are ignored. Distance only grows while recording, and only by
     * moves larger than the jitter radius (max([MIN_MOVE_M], accuracy / 2)) at a plausible speed. Returns true when
     * the fix was accepted (it then counts as "GPS has a fix").
     */
    fun onLocation(lat: Double, lon: Double, alt: Double?, accuracy: Float?, now: Long): Boolean {
        if (accuracy != null && accuracy > MAX_ACCURACY_M) return false
        if (lat.isNaN() || lon.isNaN() || (lat == 0.0 && lon == 0.0)) return false
        if (firstFixAt == null) firstFixAt = now
        lastFixAt = now
        if (phase != Phase.RECORDING) { anchor = null; return true }
        val fix = Fix(lat, lon, alt, now, accuracy)
        val a = anchor
        if (a == null) { anchor = fix; rowFix = fix; addRoute(fix); return true }
        val d = Geo.haversine(a.lat, a.lon, lat, lon)
        val minMove = max(MIN_MOVE_M, (accuracy ?: 0f) / 2.0)
        if (d < minMove) { jumpFix = null; rowFix = fix; return true }
        val dt = (now - a.timeMs) / 1000.0
        if (dt > 0 && d / dt > maxSpeed()) {
            // a jump: keep the anchor and wait for a consistent fix - unless this fix confirms the previous jump
            val j = jumpFix
            val jdt = j?.let { (now - it.timeMs) / 1000.0 } ?: 0.0
            if (j != null && jdt > 0 && Geo.haversine(j.lat, j.lon, lat, lon) / jdt <= maxSpeed()) {
                if (route.size == 1 && route[0][0] == a.lat && route[0][1] == a.lon) route.clear()
                // the rows written since the outlier anchor carry its position: blanked in the saved CSV
                gpsDropSpans += longArrayOf(a.timeMs / 1000, now / 1000 - 1)
                jumpFix = null
                anchor = fix
                rowFix = fix
                addRoute(fix)
            } else {
                jumpFix = fix
            }
            return true
        }
        jumpFix = null
        rowFix = fix
        distanceM += d
        anchor = fix
        addRoute(fix)
        val active = activeMs(now)
        while (distanceM >= (splitAt.size + 1) * 1000.0) splitAt += active
        return true
    }

    private fun maxSpeed(): Double = if (type == ActivityType.BIKE) 30.0 else 12.0

    private fun addRoute(f: Fix) {
        val last = route.lastOrNull()
        if (last == null || Geo.haversine(last[0], last[1], f.lat, f.lon) >= ROUTE_STEP_M) route += doubleArrayOf(f.lat, f.lon)
    }

    /** TYPE_STEP_COUNTER value (cumulative since boot). The first value is only the baseline. */
    fun onStepCounter(value: Long) {
        val last = lastStepCounter
        lastStepCounter = value
        if (last == null || value < last || phase != Phase.RECORDING) return
        steps += value - last
        rowSteps += value - last
    }

    /**
     * Advance to [now] (call about once a second): zone time, calories, HR average. [fallbackHr] (debug injection on
     * the emulator) is used when no sensor value is fresh. Returns null unless recording.
     */
    fun tick(now: Long, fallbackHr: Int? = null): TickResult? {
        val dtMs = min(max(0L, now - lastTickAt), MAX_TICK_MS)
        lastTickAt = now
        if (phase != Phase.RECORDING) return null
        val hr = currentHr(now) ?: fallbackHr?.takeIf { it in 25..250 }
        val zone = Zones.zoneOf(hr, maxHr)
        zoneMs[zone] += dtMs
        val dt = dtMs / 1000.0
        if (hr != null) {
            hrSum += hr; hrSamples++; hrMax = max(hrMax, hr)
            kcal += Calories.keytelPerMinute(hr, weightKg, age, sex) * dt / 60.0
        } else {
            kcal += Calories.metPerSecond(type.met, weightKg) * dt
        }
        // Announce a zone only once it held for ZONE_HOLD_MS (no buzzing on a boundary).
        if (zone != candidateZone) { candidateZone = zone; candidateSince = now }
        var announced = false
        if (candidateZone != announcedZone && now - candidateSince >= ZONE_HOLD_MS) {
            announced = candidateZone > 0 && announcedZone > 0 || candidateZone > announcedZone
            announcedZone = candidateZone
        }
        val active = activeMs(now)
        paceWindow.addLast(active to distanceM)
        while (paceWindow.size > 1 && active - paceWindow.first().first > PACE_WINDOW_MS) paceWindow.removeFirst()
        val f = rowFix
        val row = BangleCsv.row(
            timeSec = now / 1000,
            lat = f?.lat, lon = f?.lon, alt = f?.alt,
            hr = hr, confidence = if (hr != null) 100 else null, source = if (hr != null) "int" else null,
            steps = rowSteps.toInt(),
        )
        rowFix = null; rowSteps = 0
        return TickResult(row, zone, announced)
    }

    val hrAvg: Int get() = if (hrSamples == 0L) 0 else Math.round(hrSum.toDouble() / hrSamples).toInt()

    /** Pace over the last ~30 s of active time, s/km. */
    fun recentPace(): Double? {
        if (paceWindow.size < 2) return null
        val (t0, d0) = paceWindow.first(); val (t1, d1) = paceWindow.last()
        return Geo.paceSecPerKm(d1 - d0, (t1 - t0) / 1000.0)
    }

    fun recentSpeedKmh(): Double? {
        if (paceWindow.size < 2) return null
        val (t0, d0) = paceWindow.first(); val (t1, d1) = paceWindow.last()
        return Geo.speedKmh(d1 - d0, (t1 - t0) / 1000.0)
    }

    /** Per-km split durations (ms). */
    fun splitDurations(): List<Long> = splitAt.mapIndexed { i, t -> t - (if (i == 0) 0L else splitAt[i - 1]) }

    fun toJson(): JSONObject = JSONObject().apply {
        put("v", 1)
        put("type", type.id); put("start", startMs); put("maxHr", maxHr); put("age", age)
        put("weight", weightKg); put("sex", sex.name)
        put("phase", phase.name); put("activeBefore", activeMsBefore)
        segmentStart?.let { put("segmentStart", it) }
        endMs?.let { put("end", it) }
        put("distance", distanceM); put("kcal", kcal)
        put("hrSum", hrSum); put("hrSamples", hrSamples); put("hrMax", hrMax)
        put("zoneMs", JSONArray(zoneMs.toList()))
        put("splitAt", JSONArray(splitAt))
        put("route", JSONArray().also { a -> route.forEach { p -> a.put(JSONArray().put(p[0]).put(p[1])) } })
        put("steps", steps)
        lastStepCounter?.let { put("lastStepCounter", it) }
        firstFixAt?.let { put("firstFixAt", it) }
        put("lastTickAt", lastTickAt)
        put("announcedZone", announcedZone)
        put("gpsDrop", JSONArray().also { a -> gpsDropSpans.forEach { a.put(JSONArray().put(it[0]).put(it[1])) } })
    }

    companion object {
        const val MAX_ACCURACY_M = 30f
        const val MIN_MOVE_M = 3.0
        const val ROUTE_STEP_M = 15.0
        const val HR_FRESH_MS = 10_000L
        const val MAX_TICK_MS = 5_000L
        const val ZONE_HOLD_MS = 5_000L
        const val PACE_WINDOW_MS = 30_000L

        fun fromJson(o: JSONObject): Workout {
            val w = Workout(
                type = ActivityType.fromId(o.getString("type")) ?: ActivityType.OTHER,
                startMs = o.getLong("start"),
                maxHr = o.getInt("maxHr"), age = o.getInt("age"),
                weightKg = o.getDouble("weight"),
                sex = runCatching { Sex.valueOf(o.getString("sex")) }.getOrDefault(Sex.MALE),
            )
            w.phase = Phase.valueOf(o.getString("phase"))
            w.activeMsBefore = o.getLong("activeBefore")
            w.segmentStart = if (o.has("segmentStart")) o.getLong("segmentStart") else null
            w.endMs = if (o.has("end")) o.getLong("end") else null
            w.distanceM = o.getDouble("distance"); w.kcal = o.getDouble("kcal")
            w.hrSum = o.getLong("hrSum"); w.hrSamples = o.getLong("hrSamples"); w.hrMax = o.getInt("hrMax")
            o.getJSONArray("zoneMs").let { a -> for (i in 0 until min(a.length(), w.zoneMs.size)) w.zoneMs[i] = a.getLong(i) }
            o.getJSONArray("splitAt").let { a -> for (i in 0 until a.length()) w.splitAt += a.getLong(i) }
            o.getJSONArray("route").let { a -> for (i in 0 until a.length()) a.getJSONArray(i).let { p -> w.route += doubleArrayOf(p.getDouble(0), p.getDouble(1)) } }
            w.steps = o.getLong("steps")
            // The step counter restarts at boot; keep the baseline only as a hint (a lower value re-baselines).
            w.lastStepCounter = if (o.has("lastStepCounter")) o.getLong("lastStepCounter") else null
            w.firstFixAt = if (o.has("firstFixAt")) o.getLong("firstFixAt") else null
            w.lastTickAt = o.optLong("lastTickAt", w.startMs)
            w.announcedZone = o.optInt("announcedZone", 0)
            w.candidateZone = w.announcedZone
            o.optJSONArray("gpsDrop")?.let { a -> for (i in 0 until a.length()) a.getJSONArray(i).let { p -> w.gpsDropSpans += longArrayOf(p.getLong(0), p.getLong(1)) } }
            return w
        }
    }
}
