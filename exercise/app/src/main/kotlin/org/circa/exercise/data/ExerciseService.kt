package org.circa.exercise.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import org.circa.exercise.model.ActivityType
import org.circa.exercise.model.AutoInterval
import org.circa.exercise.model.AutoParams
import org.circa.exercise.model.AutoRules
import org.circa.exercise.model.Badges
import org.circa.exercise.model.Phase
import org.circa.exercise.model.Summary
import org.circa.exercise.model.Workout
import java.time.Year
import java.time.ZoneId
import java.util.concurrent.Executors

/**
 * The recording foreground service (types health | location). Owns the sensors (TYPE_HEART_RATE, GPS_PROVIDER at
 * 1 s, TYPE_STEP_COUNTER), a 1 s ticker, a partial wake lock, and the ongoing notification. All [Workout] mutation
 * happens on the main thread; disk writes go to a single background thread.
 *
 * Survives the process dying: START_STICKY, and every start (including the sticky restart with a null intent)
 * restores `active/state.json` when no workout is in memory.
 */
class ExerciseService : Service(), SensorEventListener, LocationListener {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var storage: Storage
    private var workout: Workout? = null
    private val rows = ArrayList<String>()
    private var lastFlush = 0L
    private var wake: PowerManager.WakeLock? = null
    private var sensorsOn = false
    private var gpsOn = false
    private var foreground = false

    private val ticker = object : Runnable {
        override fun run() {
            onTick()
            if (workout != null) main.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        storage = Storage(this)
        Notifs.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (workout == null) workout = storage.loadActive()?.takeIf { it.isActive }
        when (intent?.action) {
            ACTION_START -> ActivityType.fromId(intent.getStringExtra(EXTRA_TYPE))?.let { start(it) }
            ACTION_START_AUTO -> ActivityType.fromId(intent.getStringExtra(EXTRA_TYPE))?.let {
                startAuto(it, AutoInterval.decodeAll(intent.getStringExtra(EXTRA_INTERVALS)), intent.getIntExtra(EXTRA_THR, AutoParams.DEFAULT_THRESHOLD_BPM))
            }
            ACTION_AUTO_KEEP -> keepAuto()
            ACTION_AUTO_DISCARD -> if (workout?.auto == true) discard()
            ACTION_TOGGLE -> workout?.let { if (it.phase == Phase.RECORDING) pause() else resume() }
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_END -> end()
            ACTION_DISCARD -> discard()
        }
        val w = workout
        if (w == null || !w.isActive) {
            if (!foreground) { stopSelf(); return START_NOT_STICKY }
            return START_NOT_STICKY
        }
        goForeground(w)
        if (workout == null) return START_NOT_STICKY
        startSensors(w)
        main.removeCallbacks(ticker)
        main.post(ticker)
        publish()
        StateProvider.notify(this)
        return START_STICKY
    }

    private fun start(type: ActivityType) {
        if (workout?.isActive == true) return
        // A finished workout still waiting for "Done" is saved first, never thrown away.
        storage.loadPending()?.let { storage.commit(it) ; Live.pending.value = null }
        val p = Live.profile(this)
        val year = Year.now().value
        val w = Workout(type, System.currentTimeMillis(), p.maxHr(year), p.age(year), p.weight, p.effectiveSex)
        storage.begin(w)
        workout = w
        lastFlush = System.currentTimeMillis()
        Haptics.tap(this)
    }

    /**
     * An auto-detected walk/run (see [AutoDetector]): a recording backdated to the first elevated sample, with the
     * intervals that proved it folded in (HR, zones, calories, steps; no GPS before now). It starts undecided: a
     * high-importance notification asks Keep / Discard, and no answer keeps it after [AutoParams.DECIDE_MS].
     */
    private fun startAuto(type: ActivityType, intervals: List<AutoInterval>, thresholdBpm: Int) {
        if (workout?.isActive == true || intervals.isEmpty()) return
        storage.loadPending()?.let { storage.commit(it) ; Live.pending.value = null }
        val p = Live.profile(this)
        val year = Year.now().value
        val now = System.currentTimeMillis()
        val w = Workout(type, intervals.first().startMs, p.maxHr(year), p.age(year), p.weight, p.effectiveSex)
        w.markAuto(now, thresholdBpm)
        val backfilled = w.backfill(intervals, now)
        storage.begin(w)
        workout = w
        rows += backfilled
        flush(force = true)
        Haptics.detected(this)
    }

    private fun keepAuto() {
        val w = workout ?: return
        if (!w.autoPending) return
        w.keepAuto()
        flush(force = true)
        goForeground(w)   // the card becomes the normal ongoing workout notification
    }

    private fun pause() {
        val w = workout ?: return
        if (w.phase != Phase.RECORDING) return
        val now = System.currentTimeMillis()
        w.pause(now)
        Haptics.tap(this)
        flush(force = true)
        workout?.let { goForeground(it) }
    }

    private fun resume() {
        val w = workout ?: return
        if (w.phase != Phase.PAUSED) return
        w.resume(System.currentTimeMillis())
        Haptics.tap(this)
        flush(force = true)
        goForeground(w)
    }

    private fun end(commit: Boolean = false) {
        val w = workout ?: return
        if (!w.isActive) return
        if (w.auto) AutoDetect.noteEnded(this, System.currentTimeMillis())
        w.tick(System.currentTimeMillis(), Live.fakeHr)?.let { rows += it.csvRow }
        w.finish(System.currentTimeMillis())
        flush(force = true)
        val drop = w.gpsDropSpans.toList()
        io.submit { runCatching { storage.scrubGps(drop) }.onFailure { Log.w(TAG, "scrub failed", it) } }
        val history = storage.history()
        val base = Summary.from(w)
        val summary = base.copy(badges = Badges.compute(base, history, ZoneId.systemDefault()))
        if (commit) {
            // An auto workout that ended by itself nobody is watching: saved (and so synced) at once.
            io.submit { runCatching { storage.commit(summary) }.onFailure { Log.w(TAG, "commit failed", it) } }
            Notifs.saved(this, summary)
        } else {
            io.submit { storage.savePending(summary) }
            Live.pending.value = summary
        }
        workout = null
        Live.view.value = null
        // Notify after the flush/scrub/save that precede it on the single-thread executor: the provider then reads the
        // FINISHED snapshot from disk as idle instead of the stale recording one.
        io.submit { StateProvider.notify(this) }
        stopEverything()
    }

    private fun discard() {
        if (workout?.auto == true) AutoDetect.noteEnded(this, System.currentTimeMillis())
        workout = null
        rows.clear()
        Live.view.value = null
        io.submit {
            runCatching { storage.discard() }.onFailure { Log.w(TAG, "discard failed", it) }
            StateProvider.notify(this)
        }
        stopEverything()
    }

    private fun stopEverything() {
        main.removeCallbacks(ticker)
        stopSensors()
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }

    private fun onTick() {
        val w = workout ?: return
        val now = System.currentTimeMillis()
        val r = w.tick(now, Live.fakeHr)
        if (w.auto) {
            if (w.autoPending && AutoRules.decisionDue(w.autoDetectedAt, now)) keepAuto()
            if (w.autoRestExceeded(now)) {
                // 5 minutes at rest: the auto workout is over. Too short = dropped (nothing saved), else saved.
                if (AutoRules.shouldDrop(w.startMs, w.autoLastActive())) discard() else end(commit = true)
                return
            }
        }
        if (r != null) {
            rows += r.csvRow
            if (r.zoneAnnounced) Haptics.zone(this)
        }
        if (now - lastFlush >= FLUSH_MS) flush(force = false)
        publish()
    }

    private fun flush(force: Boolean) {
        val w = workout ?: return
        val out = ArrayList(rows); rows.clear()
        val snap = w.toJson().toString()
        lastFlush = System.currentTimeMillis()
        io.submit { runCatching { storage.flush(out, snap) }.onFailure { Log.w(TAG, "flush failed", it) } }
        if (force) publish()
    }

    private fun publish() {
        val w = workout ?: return
        val now = System.currentTimeMillis()
        val hr = w.currentHr(now) ?: Live.fakeHr
        val gps = when {
            !w.type.gps -> GpsState.NONE
            w.lastFixAt == null || now - (w.lastFixAt ?: 0) > GPS_LOST_MS -> GpsState.SEARCHING
            now - (w.firstFixAt ?: 0) < 3000 -> GpsState.FIXED_NEW
            else -> GpsState.FIXED
        }
        Live.view.value = LiveView(
            type = w.type, phase = w.phase, activeMs = w.activeMs(now), hr = hr,
            zone = org.circa.exercise.model.Zones.zoneOf(hr, w.maxHr), maxHr = w.maxHr,
            distanceM = w.distanceM, paceSecPerKm = w.recentPace(), speedKmh = w.recentSpeedKmh(),
            kcal = w.kcal, steps = w.steps, zoneMs = w.zoneMs.toList(), gps = gps, startMs = w.startMs,
            auto = w.auto, autoPending = w.autoPending,
        )
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun goForeground(w: Workout) {
        val n = if (w.autoPending) Notifs.detected(this, w) else Notifs.workout(this, w)
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        if (w.type.gps && granted(Manifest.permission.ACCESS_FINE_LOCATION)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        try {
            startForeground(Notifs.ID_WORKOUT, n, types)
        } catch (e: Exception) {
            // Location FGS from the background (e.g. a sticky restart) is refused on 14+: keep recording without GPS.
            Log.w(TAG, "startForeground($types) failed, retrying health only", e)
            try { startForeground(Notifs.ID_WORKOUT, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH) }
            catch (e2: Exception) {
                // No health permission at all: recording is impossible. Keep the session on disk, stop.
                Log.e(TAG, "startForeground failed", e2)
                workout = null
                Live.view.value = null
                stopSelf()
                return
            }
        }
        foreground = true
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CircaExercise:recording")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startSensors(w: Workout) {
        if (!sensorsOn) {
            val sm = getSystemService(SensorManager::class.java)
            sm.getDefaultSensor(Sensor.TYPE_HEART_RATE)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, main) }
                ?: Log.i(TAG, "no heart-rate sensor")
            sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, main) }
            sensorsOn = true
        }
        if (!gpsOn && w.type.gps && granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            val lm = getSystemService(LocationManager::class.java)
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
                gpsOn = true
            } catch (e: Exception) { Log.w(TAG, "GPS unavailable", e) }
        }
    }

    private fun stopSensors() {
        if (sensorsOn) getSystemService(SensorManager::class.java).unregisterListener(this)
        sensorsOn = false
        if (gpsOn) runCatching { getSystemService(LocationManager::class.java).removeUpdates(this) }
        gpsOn = false
    }

    override fun onSensorChanged(e: SensorEvent) {
        val w = workout ?: return
        when (e.sensor.type) {
            Sensor.TYPE_HEART_RATE -> {
                val bpm = e.values.firstOrNull()?.toInt() ?: return
                if (e.accuracy != SensorManager.SENSOR_STATUS_NO_CONTACT && bpm > 0) w.onHr(bpm, System.currentTimeMillis())
            }
            Sensor.TYPE_STEP_COUNTER -> w.onStepCounter(e.values.firstOrNull()?.toLong() ?: return)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onLocationChanged(l: Location) {
        val w = workout ?: return
        val accepted = w.onLocation(
            l.latitude, l.longitude, if (l.hasAltitude()) l.altitude else null,
            if (l.hasAccuracy()) l.accuracy else null, System.currentTimeMillis(),
        )
        if (accepted) publish()
    }

    override fun onDestroy() {
        main.removeCallbacks(ticker)
        workout?.let { flush(force = false) }
        stopSensors()
        wake?.let { if (it.isHeld) it.release() }
        io.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CircaExercise"
        const val ACTION_START = "org.circa.exercise.START"
        const val ACTION_TOGGLE = "org.circa.exercise.TOGGLE"
        const val ACTION_PAUSE = "org.circa.exercise.PAUSE"
        const val ACTION_RESUME = "org.circa.exercise.RESUME"
        const val ACTION_END = "org.circa.exercise.END"
        const val ACTION_DISCARD = "org.circa.exercise.DISCARD"
        const val ACTION_RESTORE = "org.circa.exercise.RESTORE"
        const val ACTION_START_AUTO = "org.circa.exercise.START_AUTO"
        const val ACTION_AUTO_KEEP = "org.circa.exercise.AUTO_KEEP"
        const val ACTION_AUTO_DISCARD = "org.circa.exercise.AUTO_DISCARD"
        const val EXTRA_INTERVALS = "intervals"
        const val EXTRA_THR = "thr"
        const val EXTRA_TYPE = "type"
        private const val FLUSH_MS = 5_000L
        private const val GPS_LOST_MS = 15_000L

        /**
         * Start an auto recording from the background (a WatchLink sample arrived). A foreground service started from
         * the background needs an exemption; this app holds START_FOREGROUND_SERVICES_FROM_BACKGROUND (privapp
         * allowlist in device/circa), and the refusal is logged rather than crashing the receiver.
         */
        fun startAuto(ctx: Context, type: ActivityType, intervals: String, thresholdBpm: Int) {
            val i = Intent(ctx, ExerciseService::class.java).setAction(ACTION_START_AUTO)
                .putExtra(EXTRA_TYPE, type.id).putExtra(EXTRA_INTERVALS, intervals).putExtra(EXTRA_THR, thresholdBpm)
            try { ctx.startForegroundService(i) }
            catch (e: Exception) { Log.e(TAG, "cannot start the auto recording from the background", e) }
        }

        fun send(ctx: Context, action: String, type: ActivityType? = null) {
            val i = Intent(ctx, ExerciseService::class.java).setAction(action)
            type?.let { i.putExtra(EXTRA_TYPE, it.id) }
            // Only a start/restore may need to promote the service; the other actions go to a running one (the UI only
            // offers them while it is) and must not trip the startForeground() deadline when it is not.
            if (action == ACTION_START || action == ACTION_RESTORE) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }
}
