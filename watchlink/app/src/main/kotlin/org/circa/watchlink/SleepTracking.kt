package org.circa.watchlink

import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.os.Handler
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.TimeZone
import java.util.TreeMap

/**
 * Android glue for sleep tracking (docs/watch-ui/circa/sleep.md); the decisions live in [SleepModel] /
 * [SleepTracker], the movement measures in [MotionEpochs] / [WornClock]. Runs on the service's handler thread.
 *
 * - Sensors: the accelerometer, non-wakeup and batched at its lowest rate, so the sensor hub buffers it while the
 *   CPU sleeps and the existing 5-minute wake collects it; the low-latency off-body sensor; charging from the service.
 * - Before a bucket closes, [beforeClose] flushes the accelerometer batch and closes the bucket when the flush
 *   completes, under a wakelock with a hard [FLUSH_TIMEOUT_MS] timeout (a flush that never completes cannot keep
 *   the CPU awake; the bucket then closes with whatever arrived).
 * - Off (Settings.Secure [SETTING_KEY] = 0), or no accelerometer: nothing is measured or held, and buckets go out
 *   unlabelled exactly as before.
 * - State: the tracker's mode and last offered ts in prefs; labels in `sleep-labels.csv` (`ts,kind`, 30 days) so a
 *   backfill (actfetch) carries them; buckets still held are rebuilt from history.csv after a restart.
 */
internal class SleepTracking(
    private val ctx: Context,
    private val sm: SensorManager,
    private val h: Handler,
    private val log: ActivityLog,
    private val wakeAcquire: (String, Long) -> Int,
    private val wakeRelease: (Int) -> Unit,
) {
    private val prefs: SharedPreferences = ctx.getSharedPreferences("sleep", Context.MODE_PRIVATE)
    private val labelsFile = File(ctx.filesDir, "sleep-labels.csv")
    private val labels = TreeMap<Long, String>()
    private val motion = MotionEpochs()
    private val worn = WornClock()
    private val tracker = SleepTracker { ms -> localMinuteOfDay(ms) }
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, false)
        ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val offBody: Sensor? = sm.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT, false)
        ?: sm.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)
    private var accelOn = false
    private var accelFailed = false
    private var offBodyOn = false
    private var measuringSince = 0L      // elapsedRealtime the accelerometer was registered (0 = not measuring)
    private var restingHr = 0
    private var restingAt = 0L
    private var pendingClose: (() -> Unit)? = null
    private var flushToken = 0
    private var started = false

    private val accelListener = object : SensorEventListener2 {
        override fun onSensorChanged(e: SensorEvent) {
            motion.add(e.timestamp / 1_000_000L, e.values[0], e.values[1], e.values[2])
        }

        override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}

        override fun onFlushCompleted(s: Sensor?) {
            finishClose("flushed")
        }
    }

    private val offBodyListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            worn.setOnBody(e.timestamp / 1_000_000L, e.values[0] >= 0.5f)
        }

        override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
    }

    private val flushTimeout = Runnable { finishClose("flush timeout") }

    fun start() {
        if (started) return
        started = true
        loadLabels()
        tracker.restore(prefs.getBoolean("asleep", false), prefs.getLong("lastTs", 0L))
        updateSensors()
        rebuildHeld()
    }

    fun stop() {
        started = false
        finishClose("stop")
        unregister()
    }

    /** Settings.Secure circa_sleep_tracking: absent or anything but "0" = on. */
    fun enabled(): Boolean = try {
        Settings.Secure.getString(ctx.contentResolver, SETTING_KEY)?.trim() != "0"
    } catch (e: Exception) {
        true
    }

    /** Measuring and allowed to label: on, and the accelerometer is registered. */
    fun active(): Boolean = accelOn && enabled()

    fun setCharging(on: Boolean) = worn.setCharging(SystemClock.elapsedRealtime(), on)

    /** Register or release the sensors to match the setting (checked at every bucket). */
    private fun updateSensors() {
        val want = enabled()
        if (want && !accelOn && !accelFailed) {
            accelOn = accel != null &&
                    sm.registerListener(accelListener, accel, MotionEpochs.SAMPLING_US, MotionEpochs.MAX_LATENCY_US, h)
            if (!accelOn) {
                // Degrade to "no sleep detection": buckets go out unlabelled and nothing is held.
                accelFailed = true
                Log.i(TAG, "sleep: accelerometer " + (if (accel == null) "missing" else "register failed")
                        + "; sleep detection off")
            } else {
                measuringSince = SystemClock.elapsedRealtime()
                worn.begin(measuringSince)
                motion.take(measuringSince)
                if (offBody != null) offBodyOn = sm.registerListener(offBodyListener, offBody,
                    SensorManager.SENSOR_DELAY_NORMAL, h)
                Log.i(TAG, "sleep: measuring (accelerometer " + accel?.name + ", off-body " + offBodyOn + ")")
            }
        } else if (!want && accelOn) {
            unregister()
            Log.i(TAG, "sleep: tracking turned off")
        }
    }

    private fun unregister() {
        if (accelOn) sm.unregisterListener(accelListener)
        if (offBodyOn) sm.unregisterListener(offBodyListener)
        accelOn = false
        offBodyOn = false
        measuringSince = 0L
    }

    /**
     * Run [close] once the accelerometer batch is in: after the flush completes, or after [FLUSH_TIMEOUT_MS] at the
     * latest. Not measuring: [close] runs at once.
     */
    fun beforeClose(close: () -> Unit) {
        finishClose("superseded")
        updateSensors()
        if (!accelOn) {
            close()
            return
        }
        pendingClose = close
        flushToken = wakeAcquire("sleep-flush", FLUSH_TIMEOUT_MS)
        h.postDelayed(flushTimeout, FLUSH_TIMEOUT_MS)
        val ok = try { sm.flush(accelListener) } catch (e: Exception) { false }
        if (!ok) finishClose("flush refused")
    }

    private fun finishClose(why: String) {
        val c = pendingClose ?: return
        pendingClose = null
        h.removeCallbacks(flushTimeout)
        try {
            c()
        } finally {
            wakeRelease(flushToken)
            flushToken = 0
            if (why != "flushed") Log.d(TAG, "sleep: bucket closed after $why")
        }
    }

    /**
     * The measures for the bucket ending at [endElapsed] as (still, restless, worn), or null when not measuring for
     * the whole bucket (tracking off, no accelerometer, or registered part-way through it).
     */
    fun measure(endElapsed: Long): IntArray? {
        if (!active()) return null
        val t = motion.take(endElapsed)
        val w = worn.take(endElapsed)
        if (measuringSince == 0L || endElapsed - Buckets.BUCKET_MS < measuringSince) return null
        val max = (Buckets.BUCKET_MS / 60_000L).toInt()
        return intArrayOf(Math.min(t.still, max), t.restless, w)
    }

    /**
     * The buckets to send now for the bucket [r] that just closed (synced clock), with their labels. A running
     * workout ([exerciseKind]) ends any night and labels the bucket with the workout's kind, as before.
     */
    fun onBucket(r: Buckets.Record, exerciseKind: String?): List<Labelled> {
        val out = ArrayList<Labelled>()
        when {
            exerciseKind != null -> {
                out.addAll(tracker.flush())
                out.add(Labelled(r, exerciseKind))
                markOffered(r.ts)
            }
            !active() -> {
                out.addAll(tracker.flush())
                out.add(Labelled(r, null))
                markOffered(r.ts)
            }
            else -> out.addAll(tracker.offer(r, resting()))
        }
        record(out)
        return out
    }

    /** ts of the oldest bucket still held back (0 = none): a backfill must not send it yet. */
    fun heldFrom(): Long = tracker.heldFrom()

    fun heldCount(): Int = tracker.heldCount()

    fun label(ts: Long): String? = labels[ts]

    /** Oldest bucket released while no GB link was up (0 = none); the next actfetch starts there at the latest. */
    fun undeliveredSince(): Long = prefs.getLong("undelivered", 0L)

    fun markUndelivered(out: List<Labelled>) {
        if (out.isEmpty()) return
        val first = out.minOf { it.record.ts }
        val cur = undeliveredSince()
        if (cur == 0L || first < cur) prefs.edit().putLong("undelivered", first).apply()
    }

    fun clearUndelivered() {
        prefs.edit().remove("undelivered").apply()
    }

    // ---- internals ----

    private fun markOffered(ts: Long) {
        // Keep the tracker's clock moving while it is bypassed, so a later restart does not replay these buckets.
        tracker.restore(false, Math.max(tracker.lastTs, ts))
    }

    private fun record(out: List<Labelled>) {
        prefs.edit().putBoolean("asleep", tracker.asleep).putLong("lastTs", tracker.resumeTs()).apply()
        var changed = false
        for (l in out) {
            val k = l.kind
            if (!SleepModel.isSleep(k) || k == null) continue
            labels[l.record.ts] = k
            appendLabel(l.record.ts, k)
            changed = true
        }
        if (changed) publishSession()
    }

    private fun resting(): Int {
        val now = SystemClock.elapsedRealtime()
        if (restingAt == 0L || now - restingAt > RESTING_REFRESH_MS) {
            val recs = log.since(log.now() - RESTING_WINDOW_MS)
            restingHr = SleepModel.restingHr(recs.map { it.hr }.toIntArray())
            restingAt = now
            Log.i(TAG, "sleep: resting heart-rate estimate $restingHr bpm from ${recs.size} buckets")
        }
        return restingHr
    }

    /** After a restart: offer the buckets closed after the last offered one again, so held ones are held again. */
    private fun rebuildHeld() {
        val last = tracker.lastTs
        if (last <= 0L) return
        val recs = log.since(last + 1).filter { it.ts > last }
        if (recs.isEmpty()) return
        val out = ArrayList<Labelled>()
        for (r in recs) out.addAll(if (active()) tracker.offer(r, resting()) else listOf(Labelled(r, null)))
        if (!active()) recs.lastOrNull()?.let { markOffered(it.ts) }
        record(out)
        markUndelivered(out)
        Log.i(TAG, "sleep: restart replayed ${recs.size} buckets, ${out.size} released, ${tracker.heldCount()} held")
    }

    private fun loadLabels() {
        labels.clear()
        if (!labelsFile.exists()) return
        val cutoff = log.now() - ActivityLog.KEEP_MS
        var dropped = false
        try {
            labelsFile.bufferedReader().use { br ->
                while (true) {
                    val line = br.readLine() ?: break
                    val f = line.trim().split(",")
                    val ts = f.getOrNull(0)?.toLongOrNull() ?: continue
                    val k = f.getOrNull(1) ?: continue
                    if (!SleepModel.isSleep(k)) continue
                    if (ts < cutoff) { dropped = true; continue }
                    labels[ts] = k
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "sleep labels read failed: $e")
        }
        if (dropped) {
            try {
                FileOutputStream(labelsFile, false).use { o ->
                    for ((ts, k) in labels) o.write("$ts,$k\n".toByteArray(StandardCharsets.US_ASCII))
                }
            } catch (e: IOException) {
                Log.i(TAG, "sleep labels prune failed: $e")
            }
        }
        publishSession()
    }

    private fun appendLabel(ts: Long, kind: String) {
        try {
            FileOutputStream(labelsFile, true).use { o -> o.write("$ts,$kind\n".toByteArray(StandardCharsets.US_ASCII)) }
        } catch (e: IOException) {
            Log.i(TAG, "sleep label write failed: $e")
        }
    }

    private fun publishSession() {
        val s = SleepModel.lastSession(labels)
        val e = prefs.edit()
        if (s == null) e.remove("sessionStart").remove("sessionEnd").remove("sessionMin")
        else e.putLong("sessionStart", s.startMs).putLong("sessionEnd", s.endMs).putInt("sessionMin", s.asleepMin)
        e.apply()
        try {
            ctx.contentResolver.notifyChange(HealthProvider.URI_SLEEP, null)
        } catch (ignored: Exception) {
        }
    }

    private fun localMinuteOfDay(ms: Long): Int {
        val local = ms + TimeZone.getDefault().getOffset(ms)
        return (Math.floorMod(local, 86_400_000L) / 60_000L).toInt()
    }

    companion object {
        const val TAG = "WatchLink"

        /** Settings.Secure key, written by Circa Settings; absent or anything but "0" = on. */
        const val SETTING_KEY = "circa_sleep_tracking"

        /** Hard cap on waiting for the accelerometer flush (and on its wakelock). */
        const val FLUSH_TIMEOUT_MS = 2_000L

        /** The launcher shows a session that ended at most this long ago. */
        const val SESSION_FRESH_MS = 18 * 3_600_000L

        private const val RESTING_WINDOW_MS = 7 * 24 * 3_600_000L
        private const val RESTING_REFRESH_MS = 6 * 3_600_000L

        /** The last session for [HealthProvider] (WatchLink's clock), or null. */
        @JvmStatic
        fun lastSession(c: Context): SleepModel.Session? {
            val p = c.getSharedPreferences("sleep", Context.MODE_PRIVATE)
            if (!p.contains("sessionEnd")) return null
            return SleepModel.Session(p.getLong("sessionStart", 0L), p.getLong("sessionEnd", 0L), p.getInt("sessionMin", 0))
        }
    }
}
