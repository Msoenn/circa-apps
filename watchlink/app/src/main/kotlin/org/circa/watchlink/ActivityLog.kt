package org.circa.watchlink

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Phone-synced clock + 10-minute activity buckets + on-watch history (spec §3.6, §4.3, §4.4, §7.6).
 *
 * Time: a normal app can't set the system clock, and this watch's clock is months off without Wi-Fi. GB's setTime
 * line gives the true time; we keep base = gbTime - elapsedRealtime (immune to wall-clock changes), persisted with
 * the boot count so it survives an app restart but not a reboot.
 * Buckets closed before the first sync after boot are held in memory and stamped once the phone syncs.
 */
internal class ActivityLog(private val ctx: Context) {
    private val prefs: SharedPreferences = ctx.getSharedPreferences("time", Context.MODE_PRIVATE)
    private val file = File(ctx.filesDir, "history.csv")
    private var base = Long.MIN_VALUE     // true time = elapsedRealtime + base

    var tzHours = Double.NaN
    var lastSyncElapsed = 0L

    // open bucket
    private var bucketStepBase = -1L
    private var hrSum = 0L
    private var hrCount = 0L
    var lastClosedEnd = 0L

    private class Pending {
        var elapsedEnd = 0L
        var steps = 0
        var hr = 0
        var n = 0
    }

    private val pending = ArrayList<Pending>()

    init {
        val boot = bootCount()
        if (prefs.getInt("boot", -2) == boot && prefs.contains("base")) {
            base = prefs.getLong("base", 0)
            tzHours = java.lang.Double.longBitsToDouble(prefs.getLong("tz", java.lang.Double.doubleToLongBits(Double.NaN)))
            Log.i(TAG, "time: restored phone sync from this boot, offset vs system clock "
                    + (now() - System.currentTimeMillis()) + " ms")
        } else {
            Log.i(TAG, "time: not synced since boot; using system clock until the phone sends setTime")
        }
    }

    private fun bootCount(): Int = try {
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT)
    } catch (e: Exception) {
        -1
    }

    fun synced(): Boolean = base != Long.MIN_VALUE

    fun now(): Long = if (synced()) SystemClock.elapsedRealtime() + base else System.currentTimeMillis()

    /** Returns the correction applied vs the previous notion of now (ms). */
    fun sync(unixSec: Long, tz: Double): Long {
        val before = now()
        val el = SystemClock.elapsedRealtime()
        base = unixSec * 1000L + 500 - el   // setTime has 1 s resolution; assume mid-second
        tzHours = tz
        lastSyncElapsed = el
        prefs.edit().putInt("boot", bootCount()).putLong("base", base)
            .putLong("tz", java.lang.Double.doubleToLongBits(tz)).apply()
        val delta = now() - before
        if (pending.isNotEmpty()) {
            // Buckets closed before the sync ran on the watch's wrong clock, so their ends are off the 10-min grid.
            // Snap each end down to the grid (<= 10 min early) so stored records stay >= 10 min apart; GB merges
            // records closer than 120 s and would overwrite steps (BJP:86-126). Same-slot records are merged.
            var prev: Buckets.Record? = null
            var n = 0
            for (p in pending) {
                val ts = Buckets.lastBoundary(p.elapsedEnd + base)
                if (prev != null && prev.ts == ts) {
                    val cnt = prev.hrCount + p.n
                    val hr = if (cnt == 0) 0
                    else Math.round((prev.hr.toDouble() * prev.hrCount + p.hr.toDouble() * p.n) / cnt).toInt()
                    prev = Buckets.Record(ts, prev.steps + p.steps, hr, cnt)
                } else {
                    if (prev != null) { store(prev); n++ }
                    prev = Buckets.Record(ts, p.steps, p.hr, p.n)
                }
            }
            if (prev != null) { store(prev); n++ }
            Log.i(TAG, "time: stamped " + pending.size + " buckets recorded before sync as " + n + " grid records")
            pending.clear()
        }
        return delta
    }

    // ---- bucket accumulation ----
    fun addHr(bpm: Int) {
        hrSum += bpm
        hrCount++
    }

    fun startBucket(stepTotal: Long) {
        bucketStepBase = stepTotal
        hrSum = 0
        hrCount = 0
    }

    fun openSteps(stepTotal: Long): Int {
        if (bucketStepBase < 0 || stepTotal < 0) return 0
        return Math.max(0L, stepTotal - bucketStepBase).toInt()
    }

    fun openHr(): Int = if (hrCount == 0L) 0 else Math.round(hrSum.toDouble() / hrCount).toInt()

    /** Close the open bucket at grid time `end`; returns the record (stored, or pending if unsynced). */
    fun closeBucket(end: Long, stepTotal: Long): Buckets.Record {
        var steps = openSteps(stepTotal)
        if (bucketStepBase < 0 && stepTotal >= 0) steps = 0
        val hr = openHr()
        val n = hrCount.toInt()
        val r = Buckets.Record(end, steps, hr, n)
        lastClosedEnd = end
        if (synced()) {
            store(r)
        } else {
            val p = Pending()
            p.elapsedEnd = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - end)
            p.steps = steps
            p.hr = hr
            p.n = n
            pending.add(p)
        }
        startBucket(stepTotal)
        return r
    }

    // ---- storage ----
    private fun store(r: Buckets.Record) {
        try {
            FileOutputStream(file, true).use { o ->
                o.write((r.csv() + "\n").toByteArray(StandardCharsets.US_ASCII))
            }
        } catch (e: IOException) {
            Log.i(TAG, "history write failed: $e")
        }
    }

    /** Records whose end ts >= since (all if since <= 0), oldest first. Also prunes > 30 days. */
    fun since(since: Long): List<Buckets.Record> {
        val all = ArrayList<Buckets.Record>()
        val out = ArrayList<Buckets.Record>()
        if (file.exists()) {
            try {
                file.bufferedReader().use { br ->
                    while (true) {
                        val line = br.readLine() ?: break
                        var r = Buckets.Record.fromCsv(line)
                        if (r != null && r.ts % Buckets.BUCKET_MS != 0L) {   // legacy off-grid record: snap like sync()
                            r = Buckets.Record(Buckets.lastBoundary(r.ts), r.steps, r.hr, r.hrCount)
                        }
                        if (r != null) all.add(r)
                    }
                }
            } catch (e: IOException) {
                Log.i(TAG, "history read failed: $e")
            }
        }
        val cutoff = now() - KEEP_MS
        val keep = ArrayList<Buckets.Record>()
        for (r in all) {
            if (r.ts >= cutoff) keep.add(r)
            if (r.ts >= since || since <= 0) out.add(r)
        }
        if (keep.size < all.size) {
            try {
                FileOutputStream(file, false).use { o ->
                    for (r in keep) o.write((r.csv() + "\n").toByteArray(StandardCharsets.US_ASCII))
                }
            } catch (e: IOException) {
                Log.i(TAG, "history prune failed: $e")
            }
        }
        return out
    }

    fun pendingCount(): Int = pending.size

    companion object {
        const val TAG = "WatchLink"
        const val KEEP_MS = 30L * 24 * 3600 * 1000
    }
}
