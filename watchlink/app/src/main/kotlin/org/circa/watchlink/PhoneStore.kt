package org.circa.watchlink

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.TimeZone

/**
 * Process-wide holder of [PhoneData] (weather, music, calendar) plus the link status, shared by the service (feeds it
 * from GB lines), the debug receiver (same entry point, [onLine]) and [PhoneDataProvider] (reads it). Persists to
 * SharedPreferences (debounced, because a calendar sync is one message per event) and calls `notifyChange` per changed
 * path (debounced 300 ms for the same reason).
 */
internal object PhoneStore {
    private const val TAG = "WatchLink"
    private const val PREFS = "phonedata"
    private const val KEY_JSON = "json"
    private const val KEY_LAST_SEEN = "last_seen_ms"
    private const val SAVE_DELAY_MS = 3_000L
    private const val NOTIFY_DELAY_MS = 300L

    val AUTHORITY = "org.circa.watchlink.data"
    fun uri(path: String): Uri = Uri.parse("content://$AUTHORITY/$path")

    private val data = PhoneData()
    private var ctx: Context? = null
    private var loaded = false
    private var connected = false
    private var lastSeenMs = 0L
    private var clock: () -> Long = { System.currentTimeMillis() }
    private val ui = Handler(Looper.getMainLooper())
    private var pendingNotify = 0
    private var saveQueued = false

    private val saveRun = Runnable { save() }
    private val notifyRun = Runnable {
        val c: Context?
        val bits: Int
        synchronized(this) { c = ctx; bits = pendingNotify; pendingNotify = 0 }
        if (c == null) return@Runnable
        if (bits and PhoneData.CH_WEATHER != 0) c.contentResolver.notifyChange(uri("weather"), null)
        if (bits and PhoneData.CH_MUSIC != 0) c.contentResolver.notifyChange(uri("music"), null)
        if (bits and PhoneData.CH_CALENDAR != 0) c.contentResolver.notifyChange(uri("calendar"), null)
        if (bits and BIT_STATUS != 0) c.contentResolver.notifyChange(uri("status"), null)
    }
    private const val BIT_STATUS = 8

    @Synchronized
    fun attach(c: Context, now: (() -> Long)? = null) {
        ctx = c.applicationContext
        if (now != null) clock = now
        if (!loaded) {
            loaded = true
            val p = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            p.getString(KEY_JSON, null)?.let { data.load(it) }
            lastSeenMs = p.getLong(KEY_LAST_SEEN, 0)
        }
    }

    /**
     * One raw line (as the BLE path receives it, or as the DEBUG_GB_LINE receiver injects it). Returns the parsed
     * `t` (null when the line is not a GB message) so callers can log it. Data messages update the state.
     */
    fun onLine(line: String): String? {
        val input = Proto.classify(line)
        if (input.kind != Proto.KIND_GB) return null
        val m = input.msg ?: return null
        onMessage(m)
        return input.type()
    }

    /** The shared parsed-message entry. Returns the change bits (0 = not a data message). */
    fun onMessage(m: Map<String, Any?>): Int {
        val now = clock()
        val bits: Int
        synchronized(this) {
            bits = data.apply(m, now, TimeZone.getDefault().getOffset(now).toLong())
            if (bits != 0) {
                lastSeenMs = now
                pendingNotify = pendingNotify or bits or BIT_STATUS
            }
        }
        if (bits != 0) { scheduleSave(); scheduleNotify() }
        return bits
    }

    fun setConnected(on: Boolean) {
        synchronized(this) {
            if (connected == on) return
            connected = on
            lastSeenMs = clock()
            pendingNotify = pendingNotify or BIT_STATUS
        }
        scheduleSave()
        scheduleNotify()
    }

    /**
     * Test hook only (DebugGbLineReceiver `--ez connected`): sets the /status flag as if a GB link were up or down and
     * always refreshes last_seen_ms, even when the flag does not change. Fakes no command delivery.
     */
    fun debugSetConnected(on: Boolean) {
        synchronized(this) {
            connected = on
            lastSeenMs = clock()
            pendingNotify = pendingNotify or BIT_STATUS
        }
        scheduleSave()
        scheduleNotify()
    }

    @Synchronized fun calendarIds(): List<Long> = data.calendarIds()
    @Synchronized fun status(): Pair<Boolean, Long> = Pair(connected, lastSeenMs)
    @Synchronized fun weather(): PhoneData.Weather? = data.weather
    @Synchronized fun music(): PhoneData.Music? = data.music
    @Synchronized fun calendar(): List<PhoneData.Event> = data.calendar(clock())

    private fun scheduleNotify() {
        ui.removeCallbacks(notifyRun)
        ui.postDelayed(notifyRun, NOTIFY_DELAY_MS)
    }

    private fun scheduleSave() {
        synchronized(this) {
            if (saveQueued) return
            saveQueued = true
        }
        ui.postDelayed(saveRun, SAVE_DELAY_MS)
    }

    private fun save() {
        val json: String
        val c: Context?
        val seen: Long
        synchronized(this) { saveQueued = false; json = data.toJson(); c = ctx; seen = lastSeenMs }
        c?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString(KEY_JSON, json)?.putLong(KEY_LAST_SEEN, seen)?.apply()
        Log.d(TAG, "phone data saved (${json.length} chars)")
    }
}
