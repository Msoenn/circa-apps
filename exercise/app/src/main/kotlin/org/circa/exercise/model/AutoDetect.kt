package org.circa.exercise.model

import org.json.JSONArray
import org.json.JSONObject

/** Every tunable of the walk/run auto-detection, in one place (decisions.md "Exercise auto-detection (2026-10-06)"). */
object AutoParams {
    /** Elevated = steps per minute over the sample interval at or above this (a brisk walk). */
    const val MIN_CADENCE_SPM = 80.0

    /** Median cadence at or above this is a run, below a walk. */
    const val RUN_CADENCE_SPM = 150.0

    /** Consecutive elevated samples that start a recording. */
    const val SAMPLES_TO_DETECT = 2

    /** Two samples further apart than this are not "consecutive" (a missed sample or a reboot in between). */
    const val MAX_GAP_MS = 10 * 60_000L

    /** HR threshold when there is no profile at all (no birth year and no max-HR override). */
    const val DEFAULT_THRESHOLD_BPM = 110

    /** The HR threshold is the lower bound of this zone. */
    const val THRESHOLD_ZONE = 2

    /** Auto-end: HR under the threshold and the step rate under [REST_CADENCE_SPM] for this long. */
    const val REST_END_MS = 5 * 60_000L
    const val REST_CADENCE_SPM = 40.0

    /** The step rate for the rest check is measured over this trailing window (and needs at least [REST_MIN_WINDOW_MS]). */
    const val REST_WINDOW_MS = 60_000L
    const val REST_MIN_WINDOW_MS = 20_000L

    /** An auto workout whose active span is shorter than this is dropped when it ends. */
    const val MIN_TOTAL_MS = 10 * 60_000L

    /** No new detection this long after an auto workout ended or was discarded. */
    const val COOLDOWN_MS = 15 * 60_000L

    /** No answer to "Keep / Discard" = keep after this long. */
    const val DECIDE_MS = 2 * 60_000L

    /** Settings.Secure key (Circa Settings writes it); absent or anything but "0" = on. */
    const val SETTING_KEY = "circa_auto_detect"

    fun enabled(raw: String?): Boolean = raw?.trim() != "0"

    /** The "elevated" HR: the zone-2 lower bound (max HR = 220 - age or the override); 110 with no profile at all. */
    fun thresholdBpm(p: Profile, year: Int): Int =
        if (p.birthYear == null && p.maxHrOverride == null) DEFAULT_THRESHOLD_BPM
        else Zones.lowerBpm(THRESHOLD_ZONE, p.maxHr(year))
}

/** One WatchLink sample: HR at [timeMs] and the cumulative step count (since local midnight) at that moment. */
data class AutoSample(val timeMs: Long, val bpm: Int, val steps: Long)

/** An elevated interval (the time between two consecutive samples), as backfilled into the recording. */
data class AutoInterval(val startMs: Long, val endMs: Long, val bpm: Int, val steps: Long) {
    val cadence: Double get() = if (endMs <= startMs) 0.0 else steps * 60_000.0 / (endMs - startMs)

    fun encode(): String = "$startMs,$endMs,$bpm,$steps"

    companion object {
        fun decode(s: String): AutoInterval? = runCatching {
            val f = s.split(",").map { it.trim().toLong() }
            AutoInterval(f[0], f[1], f[2].toInt(), f[3])
        }.getOrNull()

        fun encodeAll(l: List<AutoInterval>): String = l.joinToString(";") { it.encode() }
        fun decodeAll(s: String?): List<AutoInterval> =
            s?.split(";")?.filter { it.isNotBlank() }?.mapNotNull { decode(it) } ?: emptyList()
    }
}

/** What the detector found: when the walk/run began, which, and the elevated intervals that proved it. */
data class Detection(val startMs: Long, val type: ActivityType, val intervals: List<AutoInterval>)

/**
 * The walk/run detector. Pure Kotlin. It sees WatchLink's heart-rate samples (every 5 minutes) with the cumulative
 * step count and finds two consecutive elevated ones (bpm >= the threshold AND step rate over the interval >=
 * [AutoParams.MIN_CADENCE_SPM]). Its whole state (the previous sample, the elevated run, the cooldown) is
 * [toJson]/[fromJson]-able, because the exercise process is killed between samples and the state is restored from a
 * small file for each one.
 */
class AutoDetector(
    private var prev: AutoSample? = null,
    private val run: MutableList<AutoInterval> = mutableListOf(),
    var cooldownUntilMs: Long = 0L,
) {
    val elevatedRun: List<AutoInterval> get() = run.toList()
    val previous: AutoSample? get() = prev

    /**
     * Feed the next sample. [blocked] = a workout is recording or paused (manual or auto): nothing triggers and the
     * run restarts, so two fresh elevated samples are needed once it is over. Returns a [Detection] on the sample
     * that completes the run, else null.
     */
    fun onSample(s: AutoSample, thresholdBpm: Int, blocked: Boolean): Detection? {
        val p = prev
        prev = s
        if (blocked || s.timeMs < cooldownUntilMs) { run.clear(); return null }
        if (p == null || s.timeMs <= p.timeMs || s.timeMs - p.timeMs > AutoParams.MAX_GAP_MS || s.steps < p.steps) {
            run.clear(); return null
        }
        val iv = AutoInterval(p.timeMs, s.timeMs, s.bpm, s.steps - p.steps)
        if (s.bpm < thresholdBpm || iv.cadence < AutoParams.MIN_CADENCE_SPM) { run.clear(); return null }
        run += iv
        if (run.size < AutoParams.SAMPLES_TO_DETECT) return null
        val found = run.toList()
        run.clear()
        return Detection(found.first().startMs, typeFor(found), found)
    }

    /** An auto workout ended or was discarded at [nowMs]: no new detection for [AutoParams.COOLDOWN_MS]. */
    fun noteEnded(nowMs: Long) {
        cooldownUntilMs = nowMs + AutoParams.COOLDOWN_MS
        run.clear()
    }

    fun toJson(): JSONObject = JSONObject().apply {
        prev?.let { put("prev", JSONArray().put(it.timeMs).put(it.bpm).put(it.steps)) }
        put("run", JSONArray(run.map { it.encode() }))
        put("cooldown", cooldownUntilMs)
    }

    companion object {
        /** Walk below the median cadence of the run's intervals of [RUN_CADENCE_SPM], run at or above. */
        fun typeFor(intervals: List<AutoInterval>): ActivityType {
            val c = intervals.map { it.cadence }.sorted()
            val median = if (c.isEmpty()) 0.0 else if (c.size % 2 == 1) c[c.size / 2] else (c[c.size / 2 - 1] + c[c.size / 2]) / 2
            return if (median >= AutoParams.RUN_CADENCE_SPM) ActivityType.RUN else ActivityType.WALK
        }

        fun fromJson(o: JSONObject?): AutoDetector {
            if (o == null) return AutoDetector()
            val prev = o.optJSONArray("prev")?.let { AutoSample(it.getLong(0), it.getInt(1), it.getLong(2)) }
            val run = o.optJSONArray("run")?.let { a ->
                (0 until a.length()).mapNotNull { AutoInterval.decode(a.getString(it)) }.toMutableList()
            } ?: mutableListOf()
            return AutoDetector(prev, run, o.optLong("cooldown", 0L))
        }
    }
}

/** The keep / drop rules for an auto workout. */
object AutoRules {
    /** The active span (start to the last moment of activity) is too short to keep. */
    fun shouldDrop(startMs: Long, lastActiveMs: Long): Boolean = lastActiveMs - startMs < AutoParams.MIN_TOTAL_MS

    /** No answer after [AutoParams.DECIDE_MS] counts as Keep. */
    fun decisionDue(detectedAtMs: Long, nowMs: Long): Boolean = nowMs - detectedAtMs >= AutoParams.DECIDE_MS
}

/**
 * Watches the live seconds of an auto workout for rest: HR under the threshold and the step rate (over a trailing
 * minute) under [AutoParams.REST_CADENCE_SPM]. [lastActiveMs] is the last moment either was exceeded; the workout ends
 * when [restedFor] reaches [AutoParams.REST_END_MS]. Pure; its two numbers survive a process restart via [Workout].
 */
class RestTracker(var lastActiveMs: Long, private val thresholdBpm: Int) {
    private val window = ArrayDeque<Pair<Long, Long>>()   // (time, workout steps)

    fun onTick(nowMs: Long, hr: Int?, steps: Long) {
        window.addLast(nowMs to steps)
        while (window.size > 1 && nowMs - window.first().first > AutoParams.REST_WINDOW_MS) window.removeFirst()
        val spanMs = nowMs - window.first().first
        val rate = if (spanMs >= AutoParams.REST_MIN_WINDOW_MS) (steps - window.first().second) * 60_000.0 / spanMs else null
        // No step rate yet (window too short) counts as moving: a fresh recording never ends in its first seconds.
        val moving = rate == null || rate >= AutoParams.REST_CADENCE_SPM
        if (moving || (hr != null && hr >= thresholdBpm)) lastActiveMs = nowMs
    }

    fun restedFor(nowMs: Long): Long = nowMs - lastActiveMs

    fun shouldEnd(nowMs: Long): Boolean = restedFor(nowMs) >= AutoParams.REST_END_MS
}
