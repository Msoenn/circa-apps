package org.circa.watchlink

/**
 * Sleep detection on the 10-minute activity buckets (docs/watch-ui/circa/sleep.md). Pure Kotlin, host-tested; the
 * Android glue is [SleepTracking].
 *
 * Gadgetbridge's Bangle.js support stores the kind a sample first arrives with and never changes it (its upsert only
 * merges heart rate, steps and intensity), and its sleep views look only at the kinds. So every bucket is labelled
 * once, before it is sent: buckets that may turn out to be sleep are held back (at most [MAX_HOLD_MIN]) until the
 * label is final. Only [LIGHT] and [AWAKE] are sent: wrist movement plus a heart rate every 5 minutes cannot tell
 * light from deep sleep, so no deep or REM sleep is ever reported.
 */
object SleepModel {
    /** Gadgetbridge ActivityKind names (the `act` field, upper-cased by GB before ActivityKind.valueOf). */
    const val LIGHT = "LIGHT_SLEEP"
    const val AWAKE = "AWAKE_SLEEP"

    /** Sleep starts after this many minutes of candidate buckets at night ... */
    const val ONSET_NIGHT_MIN = 30

    /** ... and after this many when the run starts during the day ([DAY_START_MIN] until [DAY_END_MIN] local). */
    const val ONSET_DAY_MIN = 45
    const val DAY_START_MIN = 11 * 60
    const val DAY_END_MIN = 20 * 60

    /** A wake period this long ends the night; so does [WAKE_END_STEPS] steps inside it. */
    const val WAKE_END_MIN = 60
    const val WAKE_END_STEPS = 200

    /** Upper bound on how long any bucket is held back (checked by the host tests). */
    const val MAX_HOLD_MIN = 60

    /** A candidate bucket: worn and still for at least these minutes of 10, and at most [MAX_STEPS] steps. */
    const val MIN_WORN = 8
    const val MIN_STILL = 8
    const val MAX_STEPS = 10

    /** A bucket heart rate more than this above the resting estimate is not sleep. */
    const val HR_MARGIN = 10

    /** Resting estimate: this percentile of the last days' bucket heart rates, once there are [MIN_HR_BUCKETS]. */
    const val RESTING_PERCENTILE = 10
    const val MIN_HR_BUCKETS = 36

    /** A gap in the bucket series longer than this ends any night (watch off, service down). */
    const val GAP_MS = 30 * 60_000L

    private const val BUCKET_MIN = (Buckets.BUCKET_MS / 60_000L).toInt()

    @JvmStatic
    fun onsetMinutes(localMinuteOfDay: Int): Int =
        if (localMinuteOfDay in DAY_START_MIN until DAY_END_MIN) ONSET_DAY_MIN else ONSET_NIGHT_MIN

    /** Could this bucket be sleep? [restingHr] <= 0 = no estimate yet (no heart-rate check). */
    @JvmStatic
    fun candidate(r: Buckets.Record, restingHr: Int): Boolean =
        r.measured() && r.worn >= MIN_WORN && r.still >= MIN_STILL && r.steps <= MAX_STEPS &&
                (restingHr <= 0 || r.hr <= 0 || r.hr <= restingHr + HR_MARGIN)

    /** True when the bucket shows the watch was taken off (a measured worn time under [MIN_WORN]). */
    @JvmStatic
    fun offWrist(r: Buckets.Record): Boolean = r.worn in 0 until MIN_WORN

    /** [RESTING_PERCENTILE]th percentile of the positive values, or 0 with fewer than [MIN_HR_BUCKETS] of them. */
    @JvmStatic
    fun restingHr(hrs: IntArray): Int {
        val v = hrs.filter { it > 0 }.sorted()
        if (v.size < MIN_HR_BUCKETS) return 0
        return v[(v.size - 1) * RESTING_PERCENTILE / 100]
    }

    @JvmStatic
    fun isSleep(kind: String?): Boolean = kind == LIGHT || kind == AWAKE

    /** One night (or nap) as the watch shows it: [asleepMin] counts sleep only, like Gadgetbridge's total. */
    class Session(@JvmField val startMs: Long, @JvmField val endMs: Long, @JvmField val asleepMin: Int)

    /**
     * The latest session in [labels] (bucket end ts -> kind, any order): the last run of consecutive labelled
     * buckets that contains sleep. It starts at the start of its first bucket and ends at its last [LIGHT] bucket.
     */
    @JvmStatic
    fun lastSession(labels: Map<Long, String>): Session? {
        val ts = labels.keys.sorted()
        var i = ts.size - 1
        while (i >= 0 && labels[ts[i]] != LIGHT) i--
        if (i < 0) return null
        val end = ts[i]
        var first = i
        var asleep = 0
        var j = i
        while (j >= 0) {
            if (labels[ts[j]] == LIGHT) asleep += BUCKET_MIN
            first = j
            if (j == 0 || ts[j] - ts[j - 1] != Buckets.BUCKET_MS) break
            j--
        }
        return Session(ts[first] - Buckets.BUCKET_MS, end, asleep)
    }
}

/** A bucket ready to send, with its Gadgetbridge kind (null = unlabelled, GB stores it as ACTIVITY). */
class Labelled(@JvmField val record: Buckets.Record, @JvmField val kind: String?)

/**
 * The hold-back state machine (pure). [offer] takes each closed bucket in order and returns the buckets whose
 * label is now final, oldest first; every offered bucket comes out exactly once.
 *
 * - Awake: a candidate bucket is held. Once the held run reaches the onset ([SleepModel.onsetMinutes] of the run's
 *   local start time) the night has begun and the run goes out as sleep, backdated to its first bucket. A
 *   non-candidate bucket releases the run unlabelled.
 * - Asleep: a candidate bucket is sleep. Anything else is held as a possible wake period: back to sleep releases it
 *   as [SleepModel.AWAKE] (a bathroom trip stays inside the night, since GB ends a session at any non-sleep sample
 *   with steps), while [SleepModel.WAKE_END_MIN] minutes or [SleepModel.WAKE_END_STEPS] steps release it unlabelled
 *   and end the night. Taking the watch off ends the night at once.
 *
 * [localMinuteOfDay] maps an epoch ms to the local minute of the day (0..1439).
 */
class SleepTracker(private val localMinuteOfDay: (Long) -> Int) {
    var asleep = false
        private set

    /** ts of the last bucket offered (0 = none). */
    var lastTs = 0L
        private set

    private val held = ArrayList<Buckets.Record>()

    /** ts of the oldest held bucket, or 0 when nothing is held. */
    fun heldFrom(): Long = if (held.isEmpty()) 0L else held[0].ts

    fun heldCount(): Int = held.size

    /**
     * The ts to persist as "last offered": just before the oldest held bucket while something is held, so a restart
     * that offers every stored bucket after it again rebuilds the same held run (see [restore]).
     */
    fun resumeTs(): Long = if (held.isEmpty()) lastTs else held[0].ts - 1

    /** Restore the persisted state; held buckets are rebuilt by offering them again. */
    fun restore(asleep: Boolean, lastTs: Long) {
        held.clear()
        this.asleep = asleep
        this.lastTs = lastTs
    }

    fun offer(r: Buckets.Record, restingHr: Int): List<Labelled> {
        val out = ArrayList<Labelled>()
        if (r.ts <= lastTs) {          // a bucket at or before one already offered: pass it through untouched
            out.add(Labelled(r, null))
            return out
        }
        if (lastTs != 0L && r.ts - lastTs > SleepModel.GAP_MS) out.addAll(flush())
        lastTs = r.ts
        val cand = SleepModel.candidate(r, restingHr)
        if (!asleep) {
            if (cand) {
                held.add(r)
                val startMs = held[0].ts - Buckets.BUCKET_MS
                if (held.size * BUCKET_MIN >= SleepModel.onsetMinutes(localMinuteOfDay(startMs))) {
                    asleep = true
                    release(out, SleepModel.LIGHT)
                }
            } else {
                release(out, null)
                out.add(Labelled(r, null))
            }
        } else if (cand) {
            release(out, SleepModel.AWAKE)
            out.add(Labelled(r, SleepModel.LIGHT))
        } else if (SleepModel.offWrist(r)) {
            release(out, null)
            out.add(Labelled(r, null))
            asleep = false
        } else {
            held.add(r)
            val steps = held.sumOf { Math.max(0, it.steps) }
            if (held.size * BUCKET_MIN >= SleepModel.WAKE_END_MIN || steps >= SleepModel.WAKE_END_STEPS) {
                release(out, null)
                asleep = false
            }
        }
        return out
    }

    /** End any night now: held buckets go out unlabelled (tracking off, no accelerometer, a workout, a gap). */
    fun flush(): List<Labelled> {
        val out = ArrayList<Labelled>()
        release(out, null)
        asleep = false
        return out
    }

    private fun release(out: MutableList<Labelled>, kind: String?) {
        for (h in held) out.add(Labelled(h, kind))
        held.clear()
    }

    private companion object {
        val BUCKET_MIN = (Buckets.BUCKET_MS / 60_000L).toInt()
    }
}

/**
 * Synthetic bucket series for the `sleep_replay` debug hook and the host tests. A spec is `HH:MM;item;item;...`:
 * the local start time of the first bucket, then buckets in order. An item is either `steps/hr/still/restless/worn`
 * or a letter with an optional repeat (`S*30`):
 *
 *   S asleep (0/52/10/0/10)  R restless in bed (0/60/5/14/10)  T bathroom trip (40/72/3/30/10)
 *   W awake and moving (350/85/1/60/10)  Q awake but still, high heart rate (0/90/10/0/10)  O off the wrist (0/0/10/0/0)
 */
object SleepReplay {
    /** A normal night: evening, onset, a restless spell, a bathroom trip, more sleep, morning. */
    const val NIGHT = "22:00;W*3;S*36;R*2;S*6;T;S*12;W*4"

    @JvmStatic
    fun letter(c: Char): IntArray? = when (c) {
        'S' -> intArrayOf(0, 52, 10, 0, 10)
        'R' -> intArrayOf(0, 60, 5, 14, 10)
        'T' -> intArrayOf(40, 72, 3, 30, 10)
        'W' -> intArrayOf(350, 85, 1, 60, 10)
        'Q' -> intArrayOf(0, 90, 10, 0, 10)
        'O' -> intArrayOf(0, 0, 10, 0, 0)
        else -> null
    }

    /**
     * Parse [spec] into records. [firstStartMs] maps the spec's `HH:MM` (minutes of the day) to the epoch ms the first
     * bucket starts at. Throws IllegalArgumentException on a malformed spec.
     */
    @JvmStatic
    fun parse(spec: String, firstStartMs: (Int) -> Long): List<Buckets.Record> {
        val parts = spec.trim().split(";").map { it.trim() }.filter { it.isNotEmpty() }
        require(parts.isNotEmpty()) { "empty spec" }
        val hm = parts[0].split(":")
        require(hm.size == 2) { "start must be HH:MM" }
        val minute = hm[0].toInt() * 60 + hm[1].toInt()
        require(minute in 0 until 1440) { "start out of range" }
        var end = Buckets.lastBoundary(firstStartMs(minute)) + Buckets.BUCKET_MS
        val out = ArrayList<Buckets.Record>()
        for (p in parts.drop(1)) {
            val star = p.indexOf('*')
            val body = if (star >= 0) p.substring(0, star) else p
            val times = if (star >= 0) p.substring(star + 1).toInt() else 1
            require(times in 1..1000) { "bad repeat in $p" }
            val v = if (body.length == 1) letter(body[0]) else body.split("/").map { it.trim().toInt() }.toIntArray()
            require(v != null && v.size == 5) { "bad bucket $p" }
            repeat(times) {
                out.add(Buckets.Record(end, v[0], v[1], if (v[1] > 0) 2 else 0, v[2], v[3], v[4]))
                end += Buckets.BUCKET_MS
            }
        }
        return out
    }
}
