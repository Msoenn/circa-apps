package org.circa.launcher.data

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import org.circa.launcher.model.HealthData
import org.circa.launcher.model.HealthMapping
import org.circa.launcher.model.HealthMapping.LatestRow
import org.circa.launcher.model.HrSample
import org.circa.launcher.model.SleepSummary

/**
 * The contract of WatchLink's read-only health provider; the full specification is
 * watchlink/DATA-CONTRACT.md. The authority is not ours: WatchLink owns it and
 * enforces access by caller package, so only `org.circa.launcher` (and WatchLink itself) may read.
 */
object HealthContract {
    const val AUTHORITY = "org.circa.watchlink.health"
    val URI: Uri = Uri.parse("content://$AUTHORITY")
    val LATEST: Uri = Uri.withAppendedPath(URI, "latest")
    val HISTORY: Uri = Uri.withAppendedPath(URI, "history")
    val SLEEP: Uri = Uri.withAppendedPath(URI, "sleep")

    const val COL_HR_BPM = "hr_bpm"
    const val COL_HR_TIME = "hr_time_ms"
    const val COL_STEPS_TODAY = "steps_today"
    const val COL_STEPS_TIME = "steps_time_ms"
    const val COL_RESTING_HR = "resting_hr"
    const val COL_RESTING_HR_7D = "resting_hr_7d"
    const val COL_TIME_MS = "time_ms"
    const val COL_BPM = "bpm"
    const val COL_SLEEP_START = "sleep_start_ms"
    const val COL_SLEEP_END = "sleep_end_ms"
    const val COL_SLEEP_MINUTES = "sleep_minutes"
}

/**
 * Reads WatchLink's health ContentProvider for the watch face and the health tile: `/latest` for the
 * heart rate and step count, `/history` for the sparkline. Live updates arrive through a
 * [ContentObserver] (WatchLink notifies at most once per 30 s), and the values are re-read on a timer
 * as well - WatchLink only notifies on *changes*, so a heart rate that goes stale (15 minutes without
 * a reading) or a provider that was replaced would otherwise stay on the face forever.
 *
 * Everything degrades to the no-data state instead of crashing: WatchLink not installed, a
 * `SecurityException` (a build not named `org.circa.launcher`), a null or malformed cursor, or
 * any other provider error leaves [state] with no heart rate and no steps rather than throwing.
 *
 * Owned by `LauncherController`; [start] while the launcher is started, [stop] when it stops.
 */
class HealthRepository(private val context: Context) {

    private val resolver: ContentResolver = context.contentResolver
    private val handler = Handler(Looper.getMainLooper())
    private var started = false

    private val _state: MutableState<HealthData> = mutableStateOf(HealthData.EMPTY)

    /** The latest values WatchLink reports, or [HealthData.EMPTY] when it has none/is not installed. */
    val state: State<HealthData> get() = _state

    private val _reachable: MutableState<Boolean> = mutableStateOf(false)

    /**
     * Whether the last `/latest` query reached WatchLink's provider (it returned a cursor, even an empty
     * one). False when WatchLink is missing, not allowed, or the query failed.
     */
    val reachable: State<Boolean> get() = _reachable

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            if (started) refresh()
        }
    }

    private val reread = Runnable { if (started) refresh() }

    /** Register the observer and read once. Idempotent; safe when WatchLink is not installed. */
    fun start() {
        if (started) return
        started = true
        runCatching { resolver.registerContentObserver(HealthContract.URI, true, observer) }
        refresh()
    }

    /** Unregister and stop the timer. Idempotent; the launcher calls this from `onStop`. */
    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacks(reread)
        runCatching { resolver.unregisterContentObserver(observer) }
    }

    /** Read `/latest` and `/history` and publish the model. Never throws. */
    fun refresh() {
        val data = HealthMapping.latest(readLatest())
            .copy(history = HealthMapping.history(readHistory()), lastSleep = readSleep())
        _state.value = data
        if (started) {
            handler.removeCallbacks(reread)
            handler.postDelayed(reread, REREAD_INTERVAL_MS)
        }
    }

    private fun readLatest(): LatestRow? = try {
        val cursor = resolver.query(HealthContract.LATEST, LATEST_PROJECTION, null, null, null)
        _reachable.value = cursor != null
        cursor?.use { c ->
            if (c.moveToFirst()) {
                LatestRow(
                    hrBpm = c.intOrNull(0),
                    hrTimeMs = c.longOrNull(1),
                    stepsToday = c.longOrNull(2),
                    stepsTimeMs = c.longOrNull(3),
                    // An older WatchLink may still answer with the four original columns.
                    restingHr = if (c.columnCount > 4) c.intOrNull(4) else null,
                    restingHr7d = if (c.columnCount > 5) c.intOrNull(5) else null,
                )
            } else {
                null
            }
        }
    } catch (e: Exception) {
        Log.d(TAG, "health: /latest not readable: $e")
        _reachable.value = false
        null
    }

    private fun readHistory(): List<HrSample> = try {
        resolver.query(HealthContract.HISTORY, HISTORY_PROJECTION, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val time = c.longOrNull(0)
                    val bpm = c.intOrNull(1)
                    if (time != null && bpm != null) add(HrSample(time, bpm))
                }
            }
        }.orEmpty()
    } catch (e: Exception) {
        Log.d(TAG, "health: /history not readable: $e")
        emptyList()
    }

    /** `/sleep`: one row while a session ended in the last 18 hours; an older WatchLink rejects the URI (null). */
    private fun readSleep(): SleepSummary? = try {
        resolver.query(HealthContract.SLEEP, SLEEP_PROJECTION, null, null, null)?.use { c ->
            if (c.moveToFirst()) HealthMapping.sleep(c.longOrNull(0), c.longOrNull(1), c.intOrNull(2)) else null
        }
    } catch (e: Exception) {
        Log.d(TAG, "health: /sleep not readable: $e")
        null
    }

    private fun Cursor.intOrNull(column: Int): Int? = if (isNull(column)) null else getInt(column)

    private fun Cursor.longOrNull(column: Int): Long? = if (isNull(column)) null else getLong(column)

    companion object {
        private const val TAG = "AuroraLauncher"
        private val LATEST_PROJECTION = arrayOf(
            HealthContract.COL_HR_BPM,
            HealthContract.COL_HR_TIME,
            HealthContract.COL_STEPS_TODAY,
            HealthContract.COL_STEPS_TIME,
            HealthContract.COL_RESTING_HR,
            HealthContract.COL_RESTING_HR_7D,
        )
        private val HISTORY_PROJECTION = arrayOf(HealthContract.COL_TIME_MS, HealthContract.COL_BPM)
        private val SLEEP_PROJECTION = arrayOf(
            HealthContract.COL_SLEEP_START,
            HealthContract.COL_SLEEP_END,
            HealthContract.COL_SLEEP_MINUTES,
        )
        private const val REREAD_INTERVAL_MS = 60_000L
    }
}
