package org.circa.watchlink

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log

/**
 * Process-wide live health values: [WatchLinkService] (producer) feeds them from the sensor callbacks it already
 * has, [HealthProvider] (consumer) reads them for the launcher. Both run in the same process; this object is the
 * bridge, so the provider needs no live reference to the (possibly not running) service.
 *
 * The service feeds values from the sensor callbacks it already has — the HR sink that feeds the 5-minute samples
 * and the 10-minute buckets, and the always-registered step counter. Live HR while the screen is on adds no
 * wakelock; the 5-minute sample window keeps using the existing token.
 *
 * Observer notifications are throttled by [NotifyThrottle]: one pair of `notifyChange` calls per
 * [NotifyThrottle.INTERACTIVE_INTERVAL_MS] while the screen is on (the launcher shows live values) and per
 * [NotifyThrottle.BACKGROUND_INTERVAL_MS] when it is off. Both the `latest` and `history` URIs are notified; a
 * subscriber registered on the authority is notified for either.
 *
 * The step baseline (local midnight + cumulative counter at that midnight) is persisted so a service restart
 * within the same day does not lose the count; the pure class re-snaps it on a day change or a counter reset.
 */
internal object HealthStore {
    private const val TAG = "WatchLink"
    const val PREFS = "health"
    const val KEY_DAY_START = "steps_day_start_ms"
    const val KEY_BASE_TOTAL = "steps_base_total"

    /** Notification spacing: 3 s while the screen is interactive, 30 s when it is off ([NotifyThrottle]). */
    private val throttle = NotifyThrottle()

    private val snapshot = HealthSnapshot()

    @Volatile private var ctx: Context? = null
    @Volatile private var prefs: SharedPreferences? = null

    /** WatchLink's clock (phone-synced), installed by the service; system time until then. */
    @Volatile private var clock: () -> Long = { System.currentTimeMillis() }

    /** Screen interactivity, pushed by the service on every SCREEN_ON/OFF transition. */
    @Volatile private var interactive = false

    private var lastNotifyElapsed = -NotifyThrottle.BACKGROUND_INTERVAL_MS
    private var savedDayStart = Long.MIN_VALUE
    private var savedBaseTotal = -1L

    /**
     * Idempotent: the provider calls it to get a Context, the service also passes its clock. The persisted step
     * baseline is loaded once.
     */
    @Synchronized
    fun attach(c: Context, now: (() -> Long)? = null) {
        ctx = c.applicationContext
        if (now != null) clock = now
        val p = prefs
        if (p == null) {
            val np = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs = np
            if (np.contains(KEY_BASE_TOTAL) && np.contains(KEY_DAY_START)) {
                val day = np.getLong(KEY_DAY_START, Long.MIN_VALUE)
                val base = np.getLong(KEY_BASE_TOTAL, -1L)
                snapshot.restoreSteps(day, base)
                savedDayStart = day
                savedBaseTotal = base
                // Debug, not INFO: the baseline is step-counter numbers (health numbers stay out of the default log).
                Log.d(TAG, "health: step baseline restored (day=$day base=$base)")
            }
        }
    }

    /** Current time in the snapshot's clock domain. */
    fun nowMs(): Long = clock()

    /** Screen interactivity, set by the service on start and on every SCREEN_ON/OFF transition. */
    fun setInteractive(on: Boolean) {
        interactive = on
    }

    fun snapshot(): HealthSnapshot = snapshot

    /** One valid HR reading; caller passes WatchLink time. */
    fun onHr(nowMs: Long, bpm: Int) {
        if (bpm <= 0) return
        snapshot.addHr(nowMs, bpm)
        maybeNotify()
    }

    /** One cumulative step-counter value; [tzOffsetMs] is the local UTC offset at [nowMs]. */
    fun onSteps(nowMs: Long, total: Long, tzOffsetMs: Int) {
        if (total < 0) return
        snapshot.updateSteps(nowMs, total, tzOffsetMs)
        persistStepBaseline()
        maybeNotify()
    }

    private fun persistStepBaseline() {
        val p = prefs ?: return
        val day = snapshot.dayStartMs()
        val base = snapshot.baseTotal()
        if (day == savedDayStart && base == savedBaseTotal) return   // only on a day change / counter reset
        savedDayStart = day
        savedBaseTotal = base
        p.edit().putLong(KEY_DAY_START, day).putLong(KEY_BASE_TOTAL, base).apply()
    }

    private fun maybeNotify() {
        val c = ctx ?: return
        val now = SystemClock.elapsedRealtime()
        if (!throttle.shouldNotify(lastNotifyElapsed, now, interactive)) return
        lastNotifyElapsed = now
        c.contentResolver.notifyChange(HealthProvider.URI_LATEST, null)
        c.contentResolver.notifyChange(HealthProvider.URI_HISTORY, null)
    }
}
