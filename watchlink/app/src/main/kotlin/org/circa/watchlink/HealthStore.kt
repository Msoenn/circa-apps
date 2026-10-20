package org.circa.watchlink

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
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
 * The step state (local midnight, cumulative counter at that midnight, last counter seen, boot count) is persisted
 * so a service restart — or a reboot — within the same day does not lose the count; the pure class carries the
 * day's steps over a reboot and re-snaps the baseline on a day change. The last counter is written at most once a
 * minute or every [PERSIST_STEP_INTERVAL] steps, plus immediately on a day change / reboot rebase and on shutdown.
 *
 * Resting heart rate ([RestingHr]) rides the same preferences: today's in-progress samples, the local day they
 * belong to, and the daily values of the last 14 days. [onRestingSample] is fed by the service's scheduled HR
 * windows; the getters do the day rollover check, so the provider shows nothing for a day with no samples even if
 * the service has not run since midnight.
 */
internal object HealthStore {
    private const val TAG = "WatchLink"
    const val PREFS = "health"
    const val KEY_DAY_START = "steps_day_start_ms"
    const val KEY_BASE_TOTAL = "steps_base_total"
    const val KEY_LAST_TOTAL = "steps_last_total"
    const val KEY_BOOT_COUNT = "steps_boot_count"
    const val KEY_RESTING_DAYS = "resting_days"
    const val KEY_RESTING_DAY_START = "resting_day_start_ms"
    const val KEY_RESTING_SAMPLES = "resting_samples"

    /** The last counter is persisted at most this often, or every [PERSIST_STEP_INTERVAL] steps, whichever first. */
    private const val PERSIST_INTERVAL_MS = 60_000L
    private const val PERSIST_STEP_INTERVAL = 50L

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
    private var savedLastTotal = -1L
    private var savedBootCount = -1
    private var lastPersistElapsed = -PERSIST_INTERVAL_MS
    private var persistAtSteps = -1L

    private var restingDays: List<RestingHr.Day> = emptyList()
    private var restingSamples: List<RestingHr.Sample> = emptyList()
    private var restingDayStart = Long.MIN_VALUE
    private var restingToday: Int? = null

    /**
     * Idempotent: the provider calls it to get a Context, the service also passes its clock. The persisted step
     * state is loaded once.
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
                // A preferences file written before the reboot carry-over has no last counter or boot count: treat
                // them as unknown, which is exactly the old re-snap-on-reset behaviour.
                val last = if (np.contains(KEY_LAST_TOTAL)) np.getLong(KEY_LAST_TOTAL, base) else base
                val boot = if (np.contains(KEY_BOOT_COUNT)) np.getLong(KEY_BOOT_COUNT, -1L).toInt() else -1
                snapshot.restoreSteps(day, base, last, boot)
                savedDayStart = day
                savedBaseTotal = base
                savedLastTotal = last
                savedBootCount = boot
                persistAtSteps = last
                // Debug, not INFO: the baseline is step-counter numbers (health numbers stay out of the default log).
                Log.d(TAG, "health: step baseline restored (day=$day base=$base last=$last boot=$boot)")
            }
            restingDays = RestingHr.decode(np.getString(KEY_RESTING_DAYS, null))
            restingDayStart = np.getLong(KEY_RESTING_DAY_START, Long.MIN_VALUE)
            restingSamples = RestingHr.decodeSamples(np.getString(KEY_RESTING_SAMPLES, null))
            restingToday = if (restingDayStart == Long.MIN_VALUE) null
            else RestingHr.daily(restingSamples, restingDayStart)
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
        snapshot.updateSteps(nowMs, total, tzOffsetMs, bootCount())
        persistStepState()
        maybeNotify()
    }

    /**
     * One scheduled HR sample for resting heart rate: [bucketSteps] are the steps of the open 10-minute bucket at
     * that moment. A new local day starts a fresh sample list; the previous day's value is already in [restingDays]
     * because every sample updates it.
     */
    @Synchronized
    fun onRestingSample(nowMs: Long, bpm: Int, bucketSteps: Int) {
        if (bpm <= 0) return
        val day = HealthSnapshot.localMidnight(nowMs, tzOffsetMs(nowMs))
        if (day != restingDayStart) {
            restingDayStart = day
            restingSamples = emptyList()
        }
        restingSamples = restingSamples + RestingHr.Sample(nowMs, bpm, bucketSteps)
        restingToday = RestingHr.daily(restingSamples, day)
        restingDays = RestingHr.withDay(restingDays, day, restingToday)
        val p = prefs ?: return
        p.edit()
            .putString(KEY_RESTING_DAYS, RestingHr.encode(restingDays))
            .putLong(KEY_RESTING_DAY_START, restingDayStart)
            .putString(KEY_RESTING_SAMPLES, RestingHr.encodeSamples(restingSamples))
            .apply()
        maybeNotify()
    }

    /** Today's resting HR (null until 6 inactive samples), or null once the stored day has passed. */
    @Synchronized
    fun restingHr(): Int? {
        val now = clock()
        val day = HealthSnapshot.localMidnight(now, tzOffsetMs(now))
        return if (day == restingDayStart) restingToday else null
    }

    /** Average of the daily resting values in the last 7 days (today included when known), or null. */
    @Synchronized
    fun restingHr7d(): Int? {
        val now = clock()
        val day = HealthSnapshot.localMidnight(now, tzOffsetMs(now))
        return RestingHr.sevenDay(restingDays, day)
    }

    /** Write the step state now (device shutting down: the counter resets on the next boot). */
    @Synchronized
    fun flushStepState() = persistStepState(force = true)

    /** Local UTC offset at [nowMs], from the watch's current zone (the service applies the phone's zone). */
    private fun tzOffsetMs(nowMs: Long): Int = try {
        java.util.TimeZone.getDefault().getOffset(nowMs)
    } catch (e: Exception) {
        0
    }

    /** `Settings.Global.BOOT_COUNT`, or -1 when unknown: the caller side of the model's reboot detection. */
    private fun bootCount(): Int {
        val c = ctx ?: return -1
        return try {
            Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT, -1)
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Persist the step state (day, baseline, last counter, boot count). A day change or reboot rebase writes
     * immediately; otherwise the last counter is written at most once a minute or every [PERSIST_STEP_INTERVAL]
     * steps, since the counter ticks often and a write per event would be needless I/O.
     */
    private fun persistStepState(force: Boolean = false) {
        val p = prefs ?: return
        val day = snapshot.dayStartMs()
        val base = snapshot.baseTotal()
        val last = snapshot.lastTotal()
        if (last < 0) return                                   // nothing observed (or restored) yet
        val boot = snapshot.bootCount()
        val now = SystemClock.elapsedRealtime()
        val rebased = day != savedDayStart || base != savedBaseTotal || boot != savedBootCount
        val due = last - persistAtSteps >= PERSIST_STEP_INTERVAL || now - lastPersistElapsed >= PERSIST_INTERVAL_MS
        if (!force && !rebased && (!due || last == savedLastTotal)) return
        savedDayStart = day
        savedBaseTotal = base
        savedLastTotal = last
        savedBootCount = boot
        persistAtSteps = last
        lastPersistElapsed = now
        val e = p.edit().putLong(KEY_DAY_START, day).putLong(KEY_BASE_TOTAL, base)
            .putLong(KEY_LAST_TOTAL, last).putLong(KEY_BOOT_COUNT, boot.toLong())
        if (force) e.commit() else e.apply()   // at shutdown the async write could be cut off
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
