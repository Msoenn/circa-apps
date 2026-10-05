package org.circa.watchlink

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import java.util.LinkedHashMap

/**
 * Foreground service (connectedDevice|health) that makes the watch look like a Bangle.js to Gadgetbridge.
 * Stages (a) connect + battery, (b) notifications, (c) realtime HR/steps + 10-min push, (d) history fetch
 * of watchlink/README.md ("Protocol") Everything runs on one handler thread.
 */
class WatchLinkService : Service(), BleServer.Listener {

    private lateinit var thread: HandlerThread
    private lateinit var h: Handler
    private lateinit var ble: BleServer
    private lateinit var sensors: Sensors
    private lateinit var log: ActivityLog
    private lateinit var nm: NotificationManager
    private val notifyPolicy = NotifyPolicy()
    private lateinit var power: PowerManager
    private lateinit var wake: PowerManager.WakeLock
    private lateinit var vib: Vibrator
    private val liveHr = LiveHrPolicy()
    private val hrSchedule = HrSampleSchedule()
    private var fgsHealth = false
    private var fgsOk = false
    private var fgsNotif: Notification? = null
    private var name = ""

    private var batLevel = -1
    private var batChg = -1
    private var rtRunning = false
    private var rtHrm = false
    private var rtStp = false
    private var rtInt = 10
    private var rtStepBase = -1L
    private var rtSeq = 0
    private var finding = false
    private var findUntil = 0L
    private var nextBucketEnd = 0L
    private var bucketPi: PendingIntent? = null
    private var nextHrSampleAt = 0L
    private var hrSamplePi: PendingIntent? = null

    // ---- wakelock holders (see the "wakelock: token bookkeeping" section) ----
    private var wakeTokenSeq = 0
    private var wakeHrToken = 0
    private var wakeRtToken = 0
    private var wakeFindToken = 0
    private val wakeHolds = LinkedHashMap<Int, WakeHold>()

    /** Non-null while the bucket's HR window is open. */
    private var hrWindow: HrWindow? = null

    /** True when the open window's wake also covers a bucket close (only for the per-acquisition log). */
    private var hrBatched = false
    private val hrWindowCap = Runnable {
        val w = hrWindow
        if (w != null && w.capReached(SystemClock.elapsedRealtime())) endHrWindow("cap")
    }

    /**
     * Backstop for the NO_CONTACT grace: the sensor reports no-contact once per window, so the first event arms
     * this and the window ends here if no usable reading arrived in the meantime (see [HrWindow.onNoContact]).
     */
    private val hrWindowNoContact = Runnable {
        val w = hrWindow
        if (w != null && w.noContactTimedOut(SystemClock.elapsedRealtime())) endHrWindow("no-contact")
    }

    private class WakeHold(val reason: String, val until: Long)

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "WatchLink service", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationChannel(CH_NOTIFY, "Phone notifications", NotificationManager.IMPORTANCE_DEFAULT)
        n.enableVibration(true)
        n.vibrationPattern = longArrayOf(0, 60)   // short buzz; keeps the notification noticeable without a heads-up
        nm.createNotificationChannel(n)
        nm.deleteNotificationChannel(CH_NOTIFY_LEGACY)
        val c = NotificationChannel(CH_CALL, "Phone calls", NotificationManager.IMPORTANCE_HIGH)
        c.enableVibration(true)
        nm.createNotificationChannel(c)
        vib = getSystemService(VibratorManager::class.java).defaultVibrator
        power = getSystemService(PowerManager::class.java)
        wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "watchlink:work")
        // Non-reference-counted: acquire()/release() must not nest (see "wakelock: token bookkeeping" for why every
        // holder goes through wakeAcquire/wakeRelease instead of touching `wake` directly).
        wake.setReferenceCounted(false)
        goForeground()
        if (!fgsOk) {
            // No foreground at all: don't run as a background service (it would be killed, and START_STICKY would
            // bring it straight back into the same failure). Stop and wait for the next Start / boot.
            Log.i(TAG, "no foreground service type could be started; stopping (no sticky restart)")
            stopSelf()
            return
        }
        thread = HandlerThread("watchlink")
        thread.start()
        h = Handler(thread.looper)
        h.post { startWork() }
    }

    private fun granted(p: String): Boolean = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun healthPermOk(): Boolean =
        granted("android.permission.health.READ_HEART_RATE") || granted("android.permission.BODY_SENSORS")
                || granted("android.permission.ACTIVITY_RECOGNITION")

    /** Local UTC offset used for "steps since local midnight": GB's tz once known, else the watch's default zone. */
    private fun tzOffsetMs(): Int {
        val now = if (::log.isInitialized) log.now() else System.currentTimeMillis()
        val tz = if (::log.isInitialized) log.tzHours else Double.NaN
        return if (tz.isNaN()) java.util.TimeZone.getDefault().getOffset(now)
        else Math.round(tz * 3_600_000.0).toInt()
    }

    /**
     * Follow the phone's UTC offset (GB's `E.setTimeZone`), but only when the user asked for an automatic time
     * zone: this watch has no SIM and no location provider, so time_zone_detector reports NOT_SUPPORTED and the
     * setting alone leaves it in GMT. TzPolicy picks the zone; AlarmManager.setTimeZone needs SET_TIME_ZONE
     * (privileged - declared in the manifest and allowlisted by the device build), so a missing grant is logged
     * and otherwise ignored. Called after every setTime line, i.e. on each connect and periodic time sync.
     */
    private fun applyPhoneTimeZone() {
        val now = log.now()
        val current = java.util.TimeZone.getDefault().id
        val auto = try {
            Settings.Global.getInt(contentResolver, Settings.Global.AUTO_TIME_ZONE, 0) == 1
        } catch (e: Exception) {
            Log.i(TAG, "tz: auto_time_zone unreadable: $e")
            false
        }
        val phone = if (log.tzHours.isNaN()) null else Math.round(log.tzHours * 3_600_000.0).toInt()
        val phoneText = if (phone == null) "?" else "" + phone / 3_600_000.0
        val id = TzPolicy.choose(auto, current, phone, now)
        if (id == null) {
            Log.i(TAG, "tz phone=$phoneText current=$current action=" + (if (!auto) "off" else "unknown"))
        } else if (id == current) {
            Log.i(TAG, "tz phone=$phoneText current=$current action=keep")
        } else {
            try {
                getSystemService(AlarmManager::class.java).setTimeZone(id)
                Log.i(TAG, "tz phone=$phoneText current=$current action=set $id")
            } catch (e: Exception) {
                Log.i(TAG, "tz phone=$phoneText current=$current action=set $id failed: $e")
            }
        }
    }

    /**
     * Foreground with connectedDevice|health if the platform allows it from the current context, else connectedDevice
     * only, so the BLE link always comes up (e.g. from BOOT_COMPLETED). health is added later by ensureHealth().
     */
    private fun goForeground() {
        fgsNotif = Notification.Builder(this, CH_SERVICE)
            .setSmallIcon(R.drawable.circa_ic_watch)
            .setContentTitle("WatchLink")
            .setContentText("Gadgetbridge link (Bangle.js mode)")
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        val n = fgsNotif!!
        val healthOk = healthPermOk()
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                (if (healthOk) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0)
        try {
            startForeground(FGS_ID, n, type)
            fgsHealth = healthOk
            fgsOk = true
            Log.i(TAG, "startForeground ok, types=connectedDevice" + (if (healthOk) "|health" else ""))
        } catch (e: RuntimeException) {
            Log.i(TAG, "FGS_HEALTH_DENIED startForeground(connectedDevice|health) failed: $e; retrying connectedDevice only")
            try {
                startForeground(FGS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                fgsHealth = false
                fgsOk = true
                Log.i(TAG, "startForeground ok, types=connectedDevice (no health yet; will retry at each 10-min bucket"
                        + " and when the app is opened)")
            } catch (e2: RuntimeException) {
                Log.i(TAG, "startForeground failed: $e2")
            }
        }
    }

    /** Add the health FGS type if we don't hold it yet. Called when it may now be permitted; never loops. */
    private fun ensureHealth(why: String) {
        if (fgsHealth || !fgsOk || !healthPermOk()) return
        try {
            startForeground(
                FGS_ID, fgsNotif!!, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                        or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            )
            fgsHealth = true
            Log.i(TAG, "FGS health added ($why), types=connectedDevice|health")
        } catch (e: RuntimeException) {
            Log.i(TAG, "FGS health still not permitted ($why): $e; keeping connectedDevice")
        }
    }

    private val btRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val st = i.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1)
            Log.i(TAG, "bluetooth adapter state $st (ble running=" + (if (::ble.isInitialized) ble.isRunning() else false) + ")")
            if (!::ble.isInitialized) return
            if (st == android.bluetooth.BluetoothAdapter.STATE_ON && !ble.isRunning()) {
                ble.start(name)
            } else if (st == android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF && ble.isRunning()) {
                ble.stop()
            }
        }
    }

    private fun startWork() {
        running = true
        Log.i(TAG, "service start: sdk=" + android.os.Build.VERSION.SDK_INT
                + " perms BT_CONNECT=" + granted("android.permission.BLUETOOTH_CONNECT")
                + " BT_ADVERTISE=" + granted("android.permission.BLUETOOTH_ADVERTISE")
                + " READ_HEART_RATE=" + granted("android.permission.health.READ_HEART_RATE")
                + " HEALTH_BG=" + granted("android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND")
                + " BODY_SENSORS=" + granted("android.permission.BODY_SENSORS")
                + " ACTIVITY_RECOGNITION=" + granted("android.permission.ACTIVITY_RECOGNITION")
                + " POST_NOTIFICATIONS=" + granted("android.permission.POST_NOTIFICATIONS")
                + " exactAlarms=" + getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
                + " fgsHealth=" + fgsHealth)
        log = ActivityLog(this)
        // The launcher reads these through HealthProvider; the clock must be WatchLink's (phone-synced) time.
        HealthStore.attach(this) { log.now() }
        PhoneStore.attach(this) { log.now() }
        instance = this
        sensors = Sensors(
            getSystemService(SensorManager::class.java),
            h,
            { bpm, acc ->
                // The bucket mean (§4.3) is accumulated here as readings arrive, so the value is already in the
                // open bucket when it closes even if the window ends early; GB gets the mean of what was measured.
                log.addHr(bpm)
                HealthStore.onHr(log.now(), bpm)
                val w = hrWindow
                if (w != null && w.onSample(bpm, acc)) endHrWindow("reading")
            },
            { total -> HealthStore.onSteps(log.now(), total, tzOffsetMs()) },
            { atMs ->
                // Off-wrist the PPG reports NO_CONTACT once per window; arm the grace backstop and end the window
                // early (reason "no-contact") if no usable reading arrives inside it (see HrWindow).
                val w = hrWindow
                if (w != null) {
                    val first = !w.noContactSeen()
                    if (w.onNoContact(atMs)) endHrWindow("no-contact")
                    else if (first) h.postDelayed(hrWindowNoContact, w.noContactDeadlineMs() - atMs)
                }
            },
        )
        sensors.startSteps()
        registerReceiver(batteryRx, IntentFilter(Intent.ACTION_BATTERY_CHANGED), null, h)
        val id = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val current = try {
            (getSystemService(BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter?.name
        } catch (e: SecurityException) {
            null
        }
        name = BleName.choose(current, id)
        uiName = name
        ui("starting", null)
        ble = BleServer(this, h, this)
        // At boot the adapter may still be turning on: (re)start the server whenever it reaches STATE_ON.
        registerReceiver(btRx, IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED), null, h)
        ble.start(name)
        // Live HR while the screen is on (and not in theater mode), plus the provider's notify throttle. See
        // updateLiveHr(): no wakelock, the display already keeps the AP awake.
        registerReceiver(
            screenRx,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            null,
            h
        )
        updateLiveHr("service start")
        // activity bucket clock
        log.startBucket(sensors.stepTotal())
        startHrWindow("service start")
        scheduleBucket()
        scheduleHrSample()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!fgsOk) {     // onCreate couldn't go foreground and already called stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null) {
            // START_STICKY restart after the process was killed. Honour a Stop pressed since then.
            val want = wantRunning(this)
            Log.i(TAG, "AUTOSTART sticky restart (process was killed) want_running=$want")
            if (!want) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val a = if (intent == null) ACTION_START else intent.action.toString()
        if (ACTION_STOP == a) {
            Log.i(TAG, "stop requested")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (::h.isInitialized) h.post { handleIntent(a, intent) }
        return START_STICKY
    }

    private fun handleIntent(a: String, intent: Intent?) {
        if (ACTION_ENSURE_HEALTH == a) {
            ensureHealth("app opened")
        } else if (ACTION_START == a && intent != null && "activity" == intent.getStringExtra(EXTRA_ORIGIN)) {
            ensureHealth("Start pressed")
        } else if (ACTION_BUCKET == a) {
            onBucketAlarm()
        } else if (ACTION_HR_SAMPLE == a) {
            onHrSampleAlarm()
        } else if (ACTION_NOTIF_OPEN == a || ACTION_NOTIF_DISMISS == a) {
            val id = intent!!.getLongExtra("id", 0)
            val m = msg("notify")
            m["n"] = if (ACTION_NOTIF_OPEN == a) "OPEN" else "DISMISS"
            m["id"] = id
            Log.i(TAG, "user action on watch notification id=$id -> " + m["n"])
            sendAll(m)
        } else if (ACTION_CALL_ACCEPT == a || ACTION_CALL_REJECT == a) {
            val m = msg("call")
            m["n"] = if (ACTION_CALL_ACCEPT == a) "ACCEPT" else "REJECT"
            nm.cancel(TAG_CALL, 1)
            Log.i(TAG, "user call action -> " + m["n"])
            sendAll(m)
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "service destroy")
        instance = null
        PhoneStore.setConnected(false)
        running = false
        uiState = "stopped"
        if (::h.isInitialized) {
            h.post {
                stopRealtime("service stop")
                endHrWindow("service stop")
                stopFind()
                if (::sensors.isInitialized) sensors.stopAll()
                ble.stop()
                try { unregisterReceiver(batteryRx) } catch (ignored: Exception) {}
                try { unregisterReceiver(btRx) } catch (ignored: Exception) {}
                try { unregisterReceiver(screenRx) } catch (ignored: Exception) {}
                val am = getSystemService(AlarmManager::class.java)
                bucketPi?.let { am.cancel(it) }
                hrSamplePi?.let { am.cancel(it) }
                if (wake.isHeld) wake.release()
                thread.quitSafely()
            }
        }
        super.onDestroy()
    }

    // ---- UI state ----
    private fun ui(state: String?, last: String?) {
        if (state != null) {
            uiState = state
            Log.i(TAG, "STATE $state")
        }
        if (last != null) uiLast = last
        val linkCount = if (::ble.isInitialized) ble.links.size else 0
        uiDetail = "links $linkCount · bat $batLevel%" + (if (batChg == 1) "+" else "") +
                "\nHR " + (if (::sensors.isInitialized) sensors.latestHr(120_000) else 0) +
                (if (::sensors.isInitialized && sensors.hrOn()) " (on)" else "") +
                " · steps " + (if (::sensors.isInitialized) log.openSteps(sensors.stepTotal()) else -1) +
                (if (rtRunning) " · live ${rtInt}s" else "") +
                "\ntime " + (if (::log.isInitialized && log.synced()) "phone" else "watch")
    }

    private fun refreshState() {
        if (!::ble.isInitialized) return
        PhoneStore.setConnected(ble.subscribedLinks().isNotEmpty())
        if (ble.links.isNotEmpty()) {
            val sub = ble.subscribedLinks().size
            ui(if (sub > 0) "connected" else "connecting", null)
        } else if (ble.isAdvertising()) {
            ui("advertising", null)
        }
    }

    // ---- BleServer.Listener ----
    override fun onServerState(state: String) {
        if ("advertising" == state) refreshState()
        else ui(state, null)
    }

    override fun onLinkUp(l: BleServer.Link) {
        refreshState()
    }

    override fun onSubscribed(l: BleServer.Link) {
        refreshState()
        // Like a real Bangle (BA-boot:19-23): ~2 s after connect send ver + status, as ONE buffer padded to ATT_MTU-3
        // so GB's write size grows to the full MTU right away (spec §2.3).
        h.postDelayed({
            if (ble.links[l.addr()] !== l || !l.subscribed) return@postDelayed
            val ver = msg("ver")
            ver["fw"] = FW
            ver["hw"] = HW
            val v = JsonOut.encode(ver)
            val s = JsonOut.encode(statusMsg())
            val b = StringBuilder("\r\n").append(v).append("\r\n").append(s).append("\r\n")
            val target = minOf(l.payload(), 512)
            while (b.length + 2 <= target) b.append("\r\n")
            logOut(l, v)
            logOut(l, s)
            Log.i(TAG, "hello buffer " + b.length + " B (mtu " + l.mtu + ")")
            ble.sendBytes(l, BleServer.ascii(b.toString()))
            requestWeather(l)
        }, HELLO_DELAY_MS)
    }

    override fun onLinkDown(l: BleServer.Link) {
        if (ble.subscribedLinks().isEmpty()) {
            stopRealtime("disconnect")
            stopFind()
        }
        refreshState()
    }

    override fun onLine(l: BleServer.Link, line: String) {
        Log.i(TAG, "IN " + l.addr() + " " + line.length + " chars")   // never log payloads: they carry notification text
        val input = Proto.classify(line)
        when (input.kind) {
            Proto.KIND_EMPTY -> return
            Proto.KIND_TIME -> {
                val delta = log.sync(input.unixSec, input.tzHours)
                Log.i(TAG, "IN.time setTime=" + input.unixSec + " tz=" + input.tzHours + " correction=" + delta + " ms"
                        + " (watch system clock off by " + (log.now() - System.currentTimeMillis()) + " ms)")
                applyPhoneTimeZone()
                if (Math.abs(delta) > 60_000) {
                    log.lastClosedEnd = 0
                    nextHrSampleAt = 0
                    scheduleBucket()
                    scheduleHrSample()
                }
                ui(null, "setTime " + input.unixSec)
                return
            }
            Proto.KIND_RAW -> {
                Log.i(TAG, "IN.raw ignored (no JS interpreter)")
                return
            }
            Proto.KIND_BAD_GB -> {
                Log.i(TAG, "IN.bad GB() line: " + input.error)
                return
            }
            else -> {}
        }
        val m = input.msg ?: return
        val t = input.type()
        Log.i(TAG, "IN.parsed t=$t")
        ui(null, "in: $t")
        if (t == null) return
        when (t) {
            "is_gps_active" -> {
                val r = msg("gps_power")
                r["status"] = false
                send(l, r)
            }
            "notify" -> showNotification(m)
            "notify-" -> {
                val id = Proto.num(m, "id", 0)
                nm.cancel(TAG_GB, id.toInt())
                Log.i(TAG, "NOTIFY removed id=$id")
            }
            "call" -> onCall(m)
            "find" -> if (Proto.bool(m, "n", false)) startFind() else stopFind()
            // Phone data for the Circa apps (PhoneStore -> PhoneDataProvider). Same parser as the DEBUG_GB_LINE receiver.
            "weather", "musicinfo", "musicstate", "calendar", "calendar-" -> PhoneStore.onMessage(m)
            "force_calendar_sync_start" -> {
                // GB asks which event ids the "watch" holds (sent on every setTime); answering with ours makes it
                // delete what we dropped and resend what we lack (BJS handleCalendarSync).
                val r = msg("force_calendar_sync")
                r["ids"] = ArrayList<Any?>(PhoneStore.calendarIds())
                send(l, r)
            }
            "act" -> onAct(m)
            "actfetch" -> onActFetch(l, Proto.num(m, "ts", 0))
            // Finished workouts from the Exercise app, as Bangle.js recorder tracks (circa/exercise-sync.md).
            "listRecs" -> onListRecs(m) { send(l, it) }
            "fetchRec" -> onFetchRec(m, { trackPacketOut(l, it) }) { ble.links[l.addr()] === l && l.subscribed }
            else -> Log.i(TAG, "IN.ignored t=$t")
        }
    }

    // ---- workout tracks (GB recorder-track protocol; TrackSync has the wire format) ----
    private val exercise by lazy { ExerciseSource(this) }

    /** Bumped by every fetchRec (including "stop"): a transfer still in flight sees the change and ends. */
    private var trackSeq = 0
    private var wakeTrackToken = 0

    private fun onListRecs(m: Map<String, Any?>, out: (Map<String, Any?>) -> Unit) {
        val since = TrackSync.idOf(m)
        val ids = exercise.idsAfter(since)
        Log.i(TAG, "LISTRECS since=$since -> " + ids.size + " tracks")
        out(TrackSync.listReply(ids))
    }

    /** Sends one track as actTrk packets, one every [TrackSync.PACKET_INTERVAL_MS], while [alive] holds. */
    private fun onFetchRec(m: Map<String, Any?>, out: (Map<String, Any?>) -> Unit, alive: () -> Boolean) {
        val seq = ++trackSeq
        val id = TrackSync.idOf(m)
        if (id == TrackSync.STOP_ID) {
            Log.i(TAG, "FETCHREC stop")
            wakeRelease(wakeTrackToken)
            wakeTrackToken = 0
            return
        }
        val csv = exercise.csv(id)
        val packets = TrackSync.packets(id, csv ?: "")
        Log.i(TAG, "FETCHREC id=$id last=" + TrackSync.lastOf(m) + " -> " + packets.size + " packets"
                + (if (csv == null) " (no such track)" else ""))
        wakeRelease(wakeTrackToken)
        wakeTrackToken = wakeAcquire("track", packets.size * TrackSync.PACKET_INTERVAL_MS + 2_000L)
        sendTrackPacket(seq, packets, 0, out, alive)
    }

    private fun sendTrackPacket(
        seq: Int, packets: List<Map<String, Any?>>, i: Int,
        out: (Map<String, Any?>) -> Unit, alive: () -> Boolean,
    ) {
        if (seq != trackSeq) return
        if (!alive()) { Log.i(TAG, "FETCHREC link gone at packet $i"); return }
        out(packets[i])
        if (i + 1 < packets.size) {
            h.postDelayed({ sendTrackPacket(seq, packets, i + 1, out, alive) }, TrackSync.PACKET_INTERVAL_MS)
        } else {
            wakeRelease(wakeTrackToken)
            wakeTrackToken = 0
        }
    }

    /** Like [send], but logs a summary: the CSV holds GPS positions and must not land in logcat. */
    private fun trackPacketOut(l: BleServer.Link, m: Map<String, Any?>) {
        Log.i(TAG, "OUT " + l.addr() + " " + trackSummary(m))
        ble.sendBytes(l, BleServer.ascii("\r\n" + JsonOut.encode(m) + "\r\n"))
    }

    /** DEBUG_GB_LINE entry for listRecs / fetchRec (no GB link involved): replies are logged as "OUT.debug ...". */
    private fun debugTrackLine(m: Map<String, Any?>) {
        val out: (Map<String, Any?>) -> Unit = { Log.i(TAG, "OUT.debug " + trackSummary(it)) }
        when (m["t"]) {
            "listRecs" -> onListRecs(m) { Log.i(TAG, "OUT.debug " + JsonOut.encode(it)) }
            "fetchRec" -> onFetchRec(m, out) { true }
        }
    }

    // ---- phone data (weather request, provider commands) ----
    /** Asks GB for the weather with the v2 forecast (what a Bangle's weather app does); repeats hourly while linked. */
    private fun requestWeather(l: BleServer.Link) {
        if (ble.links[l.addr()] !== l || !l.subscribed) return
        val r = msg("weather")
        r["v"] = 2
        r["f"] = true
        send(l, r)
        h.postDelayed({ requestWeather(l) }, WEATHER_REFRESH_MS)
    }

    /** Runs on the handler thread; false when no GB link is subscribed. */
    private fun sendToSubscribed(m: Map<String, Any?>): Boolean {
        if (!::ble.isInitialized) return false
        val ls = ble.subscribedLinks()
        for (l in ls) send(l, m)
        return ls.isNotEmpty()
    }

    // ---- sending ----
    private fun logOut(l: BleServer.Link, json: String) {
        Log.i(TAG, "OUT " + l.addr() + " " + json)
        uiLast = "out: " + json.substring(0, minOf(json.length, 40))
    }

    /** One line, framed like Bangle's gbSend: "\r\n" + json + "\r\n" (spec §2.6: the trailing \r is mandatory). */
    private fun send(l: BleServer.Link, m: Map<String, Any?>) {
        val j = JsonOut.encode(m)
        logOut(l, j)
        ble.sendBytes(l, BleServer.ascii("\r\n" + j + "\r\n"))
    }

    private fun sendAll(m: Map<String, Any?>) {
        val ls = ble.subscribedLinks()
        if (ls.isEmpty()) Log.i(TAG, "OUT dropped (not connected): " + JsonOut.encode(m))
        for (l in ls) send(l, m)
    }

    // ---- (a) battery ----
    private fun statusMsg(): LinkedHashMap<String, Any?> {
        val s = msg("status")
        s["bat"] = Math.max(0, batLevel)
        s["chg"] = if (batChg == 1) 1 else 0
        return s
    }

    private val batteryRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val pct = if (level < 0 || scale <= 0) -1 else Math.round(level * 100f / scale)
            val chg = if (i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0) 1 else 0
            if (pct != batLevel || chg != batChg) {
                val first = batLevel < 0
                batLevel = pct
                batChg = chg
                Log.i(TAG, "battery $pct% chg=$chg")
                ui(null, null)
                if (!first && ::ble.isInitialized) sendAll(statusMsg())
            }
        }
    }

    // ---- (c) live HR while the screen is on ----
    private val screenRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            updateLiveHr(if (i.action == Intent.ACTION_SCREEN_ON) "screen on" else "screen off")
        }
    }

    /**
     * Screen interactivity changed, or the service just started. Hold the HR sensor open for live values while the
     * screen is on and theater mode is off (reason "screen" — no wakelock: an interactive display keeps the AP
     * awake, and the sensor is unregistered on screen-off). The same transition speeds the health provider's
     * observer notifications up to 3 s while interactive, back to 30 s when idle.
     */
    private fun updateLiveHr(why: String) {
        if (!::sensors.isInitialized) return
        val interactive = power.isInteractive
        val theater = theaterModeOn()
        liveHr.setTheaterMode(theater)
        val want = liveHr.setScreenOn(interactive)
        HealthStore.setInteractive(interactive)
        sensors.wantHr(REASON_SCREEN, want)
        Log.i(TAG, "live HR " + (if (want) "on" else "off")
                + " ($why: interactive=$interactive theater=$theater)")
    }

    /** Wear's theater-mode setting; not in the public SDK, so the key is spelled out. Absent/unreadable = off. */
    private fun theaterModeOn(): Boolean = try {
        Settings.Global.getInt(contentResolver, SETTING_THEATER_MODE_ON, 0) == 1
    } catch (e: Exception) {
        false
    }

    // ---- (b) notifications, calls, find ----
    private fun svcPi(action: String, id: Long): PendingIntent {
        val i = Intent(this, WatchLinkService::class.java).setAction(action).putExtra("id", id)
        val kind = if (ACTION_NOTIF_OPEN == action) 0 else if (ACTION_NOTIF_DISMISS == action) 1
        else if (ACTION_CALL_ACCEPT == action) 2 else 3
        val req = ((id and 0x0fffffffL) * 4 + kind).toInt()
        return PendingIntent.getService(this, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * 48 dp large icon: a circle in the app's deterministic [NotifIcon.color] with its first letter in white, so
     * the shade and heads-up card identify the app at a glance. Null `src` still gets the fallback color and '?'.
     */
    private fun avatar(src: String?): Bitmap {
        val size = Math.round(48 * resources.displayMetrics.density)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val circle = Paint(Paint.ANTI_ALIAS_FLAG)
        circle.color = NotifIcon.color(src)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, circle)
        val text = Paint(Paint.ANTI_ALIAS_FLAG)
        text.color = 0xFFFFFFFF.toInt()
        text.textSize = size * 0.6f
        text.textAlign = Paint.Align.CENTER
        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val fm = text.fontMetrics
        canvas.drawText(NotifIcon.letter(src).toString(), size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, text)
        return bmp
    }

    private fun showNotification(m: Map<String, Any?>) {
        val id = Proto.num(m, "id", 0)
        val src = Proto.str(m, "src")
        val title = Proto.str(m, "title")
        val subject = Proto.str(m, "subject")
        val body = Proto.str(m, "body")
        val sender = Proto.str(m, "sender")
        val head = if (title != null && title.isNotEmpty()) title
        else if (sender != null && sender.isNotEmpty()) sender else src
        val text = StringBuilder()
        if (subject != null && subject.isNotEmpty()) text.append(subject).append('\n')
        if (body != null) text.append(body)
        // Alert only when this post is new content outside the rate-limit window; the shade always gets the update.
        val alert = notifyPolicy.decide(id, head, text.toString(), SystemClock.elapsedRealtime())
        val b = Notification.Builder(this, CH_NOTIFY)
            .setSmallIcon(R.drawable.circa_ic_notifications)
            .setLargeIcon(avatar(src))
            .setContentTitle(head ?: "Notification")
            .setContentText(text.toString())
            .setStyle(Notification.BigTextStyle().bigText(text.toString()))
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(!alert)
            .setContentIntent(svcPi(ACTION_NOTIF_OPEN, id))
            .setDeleteIntent(svcPi(ACTION_NOTIF_DISMISS, id))
        // Show the phone app's own name (substName) instead of WatchLink's package label. "SMS Message" is the
        // phone's SMS app under a name users don't recognise; see NotifIcon.appName.
        NotifIcon.appName(src)?.let { app ->
            b.addExtras(Bundle().apply { putString(EXTRA_SUBSTITUTE_APP_NAME, app) })
        }
        if (!alert) b.setGroup(GROUP_KEY_SILENT).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
        nm.notify(TAG_GB, id.toInt(), b.build())
        Log.i(TAG, "NOTIFY posted id=$id alert=$alert")
        ui(null, "notify: " + (head ?: ""))
    }

    private fun onCall(m: Map<String, Any?>) {
        val cmd = Proto.str(m, "cmd")
        if ("incoming" == cmd) {
            val name = Proto.str(m, "name")
            val number = Proto.str(m, "number")
            val who = if (name != null && name.isNotEmpty()) name else number ?: "Unknown"
            val n = Notification.Builder(this, CH_CALL)
                .setSmallIcon(R.drawable.circa_ic_call)
                .setContentTitle("Incoming call")
                .setContentText(who + (if (name != null && number != null) " ($number)" else ""))
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, "Reject", svcPi(ACTION_CALL_REJECT, 1)).build())
                .addAction(Notification.Action.Builder(null, "Accept", svcPi(ACTION_CALL_ACCEPT, 1)).build())
                .build()
            nm.notify(TAG_CALL, 1, n)
            Log.i(TAG, "CALL incoming shown")
        } else {
            nm.cancel(TAG_CALL, 1)
            Log.i(TAG, "CALL $cmd -> call notification cleared")
        }
    }

    private fun startFind() {
        findUntil = SystemClock.elapsedRealtime() + FIND_MAX_MS
        if (finding) return
        finding = true
        Log.i(TAG, "FIND start")
        ui(null, "find: buzzing")
        buzz()
    }

    private fun buzz() {
        if (!finding) return
        if (SystemClock.elapsedRealtime() > findUntil) { stopFind(); return }
        wakeRelease(wakeFindToken)                       // drop the previous buzz's hold; this one covers the next 1 s
        wakeFindToken = wakeAcquire("find", FIND_BUZZ_MS)
        vib.vibrate(
            VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE),
            VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_ALARM).build()
        )
        Log.i(TAG, "FIND buzz")
        h.postDelayed({ buzz() }, 1000)
    }

    private fun stopFind() {
        if (!finding) return
        finding = false
        vib.cancel()
        wakeRelease(wakeFindToken)
        wakeFindToken = 0
        Log.i(TAG, "FIND stop")
    }

    // ---- (c) realtime HR/steps ----
    private fun onAct(m: Map<String, Any?>) {
        val hrm = Proto.bool(m, "hrm", false)
        val stp = Proto.bool(m, "stp", false)
        val iv = Math.max(1L, Math.min(3600L, Proto.num(m, "int", 10))).toInt()
        Log.i(TAG, "REALTIME request hrm=$hrm stp=$stp int=$iv")
        if (!hrm && !stp) {
            stopRealtime("request")
            return
        }
        rtHrm = hrm
        rtStp = stp
        sensors.wantHr("realtime", hrm)
        val restart = !rtRunning || iv != rtInt
        rtInt = iv
        if (!rtRunning) rtStepBase = sensors.stepTotal()
        rtRunning = true
        if (restart) {
            val seq = ++rtSeq
            h.postDelayed({ rtTick(seq) }, rtInt * 1000L)
        }
        ui(null, null)
    }

    private fun rtTick(seq: Int) {
        if (!rtRunning || seq != rtSeq) return
        // The hold covers this tick's work only, not the interval: rtInt can be up to 3600 s and the old code held
        // rtInt + 10 s, i.e. pinned the CPU between ticks. A Handler delay is not a wakeup, so with no hold at all a
        // tick fires whenever the CPU next wakes (the active HR sensor and the BLE link usually keep it ticking, but
        // that is HAL behaviour we don't control); a 2 s hold covers the send and degrades gracefully to best-effort
        // timing instead of pinning the CPU. No per-tick exact alarm: that would wake the AP more often than the
        // samples are worth (spec §4.2 tolerates realtime jitter).
        wakeRelease(wakeRtToken)
        wakeRtToken = wakeAcquire("realtime", RT_TICK_HOLD_MS)
        val total = sensors.stepTotal()
        val steps = if (rtStepBase < 0 || total < 0) 0 else Math.max(0L, total - rtStepBase).toInt()
        if (total >= 0) rtStepBase = total
        val hr = sensors.latestHr(Math.max(15_000L, 3000L * rtInt))
        val m = msg("act")
        m["hrm"] = hr
        m["stp"] = steps
        m["rt"] = 1
        sendAll(m)
        ui(null, null)
        h.postDelayed({ rtTick(seq) }, rtInt * 1000L)
    }

    private fun stopRealtime(why: String) {
        if (!rtRunning && (!::sensors.isInitialized || !sensors.hrOn())) {
            if (::sensors.isInitialized) sensors.wantHr("realtime", false)
            wakeRelease(wakeRtToken)
            wakeRtToken = 0
            return
        }
        rtRunning = false
        rtSeq++
        if (::sensors.isInitialized) sensors.wantHr("realtime", false)
        wakeRelease(wakeRtToken)
        wakeRtToken = 0
        Log.i(TAG, "REALTIME stopped ($why)")
        ui(null, null)
    }

    // ---- wakelock: token bookkeeping ----
    // The lock is created with setReferenceCounted(false): acquire()/release() do not nest, and a timed acquire()
    // replaces the timeout of the previous one. With one shared lock that let a 3 s find buzz cut a 60 s HR window
    // short, and a 60 s window extend a buzz. So every holder gets a token here; the platform lock is held exactly
    // while at least one token is alive, and its timeout is re-armed to the longest remaining holder purely as a
    // backstop in case a release callback never runs (the lock dies with the process in any case).
    private fun wakeAcquire(reason: String, ms: Long): Int {
        val token = ++wakeTokenSeq
        wakeHolds[token] = WakeHold(reason, SystemClock.elapsedRealtime() + ms)
        h.postDelayed({ wakeRelease(token) }, ms)
        syncWake()
        return token
    }

    private fun wakeRelease(token: Int) {
        if (token == 0) return
        if (wakeHolds.remove(token) == null) return
        syncWake()
    }

    /** Hold while any token lives; re-arm the backstop to the longest remaining one. */
    private fun syncWake() {
        val now = SystemClock.elapsedRealtime()
        var left = 0L
        var longest = ""
        for (w in wakeHolds.values) {
            if (w.until - now > left) {
                left = w.until - now
                longest = w.reason
            }
        }
        if (wakeHolds.isEmpty()) {
            if (wake.isHeld) {
                wake.release()
                Log.i(TAG, "wake: released")
            }
            return
        }
        val wasHeld = wake.isHeld
        wake.acquire(left + WAKE_SLACK_MS)   // non-refcounted: refreshes the backstop, never shorter than the longest
        if (!wasHeld) Log.i(TAG, "wake: held " + (left + WAKE_SLACK_MS) + " ms, " + wakeHolds.size
                + " holder(s), longest " + longest)
    }

    // ---- (c) 5-minute HR samples + 10-minute activity push + (d) storage ----
    /**
     * Open one HR acquisition and hold the CPU while the sensor looks for a reading: at most [HR_WINDOW_MAX_MS],
     * ended early by [HrWindow] at the first usable reading or after the NO_CONTACT grace. Called by the 5-minute
     * sample alarm, at service start, and — for the bucket grid points it shares — from [runScheduledWork] too. The
     * bucket mean is accumulated per reading (see the sensor sink), so ending early loses nothing GB stores.
     * [batched] is only for the per-acquisition log: it records that a bucket close rode this window's wakelock.
     */
    private fun startHrWindow(why: String, batched: Boolean = false) {
        endHrWindow("restart")   // invariant: at most one open window / wakelock token
        hrWindow = HrWindow(SystemClock.elapsedRealtime(), HR_WINDOW_MAX_MS)
        hrBatched = batched
        Log.i(TAG, "HR acquisition start ($why, cap " + HR_WINDOW_MAX_MS / 1000 + " s, ends at the first usable"
                + " reading), fgs types=connectedDevice" + (if (fgsHealth) "|health" else ""))
        sensors.wantHr(REASON_HR_WINDOW, true)
        // The platform timeout is only a backstop: the window (and this lock) ends at the reading or at the cap.
        // Nothing here or in the wake path waits on BLE, so the lock is never held across a radio transfer.
        wakeHrToken = wakeAcquire("hr-window", HR_WINDOW_MAX_MS)
        h.postDelayed(hrWindowCap, HR_WINDOW_MAX_MS)
    }

    /** End the HR acquisition (reading, no-contact, or cap): sensor off, wakelock released, at most once per window. */
    private fun endHrWindow(why: String) {
        val w = hrWindow ?: return
        hrWindow = null
        h.removeCallbacks(hrWindowCap)
        h.removeCallbacks(hrWindowNoContact)
        sensors.wantHr(REASON_HR_WINDOW, false)
        wakeRelease(wakeHrToken)
        wakeHrToken = 0
        // One line per acquisition. The held duration is the battery cost that shows up as the "watchlink:work"
        // partial wakelock in batterystats, so it can be checked in logcat.
        Log.d(TAG, "acq end=" + why + " held=" + w.holdMs(SystemClock.elapsedRealtime())
                + "ms samples=" + w.validSamples()
                + (if (w.lastBpm() > 0) " bpm=" + w.lastBpm() else " no-reading")
                + (if (hrBatched) " bucket-batched" else ""))
        hrBatched = false
    }

    private fun scheduleBucket() {
        val am = getSystemService(AlarmManager::class.java)
        val now = log.now()
        // max(): the alarm can fire a few hundred ms before the boundary on the phone-synced clock
        nextBucketEnd = Buckets.nextBoundary(Math.max(now, log.lastClosedEnd))
        val at = SystemClock.elapsedRealtime() + (nextBucketEnd - now) + ALARM_ARM_SLACK_MS
        var pi = bucketPi
        if (pi == null) {
            pi = PendingIntent.getService(
                this, 99, Intent(this, WatchLinkService::class.java).setAction(ACTION_BUCKET),
                PendingIntent.FLAG_IMMUTABLE
            )
            bucketPi = pi
        }
        val exact = am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        Log.i(TAG, "bucket alarm for end=$nextBucketEnd in " + (nextBucketEnd - now) / 1000 + " s (exact=$exact)")
    }

    private fun onBucketAlarm() = runScheduledWork("bucket")

    /**
     * One scheduled wake on the HR-sample / bucket grid. Both alarms land here; whichever fires first does all the
     * work that is due, so at a shared grid point the bucket close and its Gadgetbridge push run under the HR
     * window's single wakelock instead of the bucket alarm's bare handler post (see [SampleWake]). [sendAll] only
     * queues notifications — the BLE pump is asynchronous — so nothing here waits on the radio and the wake is
     * never held across a BLE transfer.
     */
    private fun runScheduledWork(why: String) {
        val now = log.now()
        val plan = SampleWake.plan(now, log.lastClosedEnd, nextBucketEnd, nextHrSampleAt)
        if (!plan.anythingDue()) {
            Log.i(TAG, "wake alarm early/duplicate ($why now=$now bucket=$nextBucketEnd sample=$nextHrSampleAt),"
                    + " rescheduling")
            scheduleBucket()
            scheduleHrSample()
            return
        }
        // The HR window is opened first: its wakelock then covers the bucket close and push of the same wake.
        if (plan.sampleDue) startHrWindow(why, plan.batched())
        if (plan.bucketEndMs > SampleWake.NO_BUCKET) {
            ensureHealth(why + " alarm")
            closeBucket(plan.bucketEndMs)
        }
        scheduleBucket()
        scheduleHrSample()
    }

    /** Close the bucket ending at [end] and push it (spec §4.3) — under the caller's wake when there is one. */
    private fun closeBucket(end: Long) {
        val r = log.closeBucket(end, sensors.stepTotal())
        Log.d(TAG, "BUCKET closed ts=" + r.ts + " steps=" + r.steps + " hr=" + r.hr + " (n=" + r.hrCount + ")"
                + (if (log.synced()) "" else " [pending: time not synced]"))
        if (log.synced() && ble.subscribedLinks().isNotEmpty()) {
            val rec = actRecord(r)
            // During a workout the sample carries GB's activity kind (RUNNING, WALKING, ...; GB >= 0.83 labels the
            // minutes with it). Backfill (actfetch) can't know past workouts and stays unlabelled.
            exercise.activeKind()?.let { rec["act"] = it }
            sendAll(rec)    // spec §4.3 push, like BA-boot:32-36
            sendAll(statusMsg())     // plus status at least every 10 min (spec §4.1)
        }
    }

    /**
     * Arm the next HR sample on the 5-minute grid with the same exact-alarm mechanism as the bucket (falling back
     * to inexact when the platform refuses exact alarms). [HrSampleSchedule.dueAt] keeps a pending sample that is
     * still in the future, so a clock jump that fires this alarm early does not skip the sample.
     */
    private fun scheduleHrSample() {
        val am = getSystemService(AlarmManager::class.java)
        val now = log.now()
        nextHrSampleAt = hrSchedule.dueAt(now, nextHrSampleAt)
        val at = SystemClock.elapsedRealtime() + (nextHrSampleAt - now) + ALARM_ARM_SLACK_MS
        var pi = hrSamplePi
        if (pi == null) {
            pi = PendingIntent.getService(
                this, 98, Intent(this, WatchLinkService::class.java).setAction(ACTION_HR_SAMPLE),
                PendingIntent.FLAG_IMMUTABLE
            )
            hrSamplePi = pi
        }
        val exact = am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        Log.i(TAG, "HR sample alarm for " + nextHrSampleAt + " in " + (nextHrSampleAt - now) / 1000 + " s (exact=$exact)")
    }

    private fun onHrSampleAlarm() = runScheduledWork("sample")

    private fun onActFetch(l: BleServer.Link, since: Long) {
        val recs = log.since(since)
        val st = msg("actfetch")
        st["state"] = "start"
        send(l, st)
        for (r in recs) send(l, actRecord(r))
        val end = msg("actfetch")
        end["state"] = "end"
        end["count"] = recs.size
        send(l, end)
        Log.i(TAG, "ACTFETCH since=$since sent " + recs.size + " records"
                + (if (log.pendingCount() > 0) " (" + log.pendingCount() + " unsynced buckets held back)" else ""))
    }

    companion object {
        const val TAG = "WatchLink"
        const val FW = "watchlink-0.1"
        const val HW = "PixelWatch2"   // never "2": that turns on GB's screenshot feature (spec §4.1)

        const val ACTION_START = "org.circa.watchlink.START"
        const val ACTION_STOP = "org.circa.watchlink.STOP"
        const val ACTION_BUCKET = "org.circa.watchlink.BUCKET"
        const val ACTION_HR_SAMPLE = "org.circa.watchlink.HR_SAMPLE"
        const val ACTION_NOTIF_OPEN = "org.circa.watchlink.NOTIF_OPEN"
        const val ACTION_NOTIF_DISMISS = "org.circa.watchlink.NOTIF_DISMISS"
        const val ACTION_CALL_ACCEPT = "org.circa.watchlink.CALL_ACCEPT"
        const val ACTION_CALL_REJECT = "org.circa.watchlink.CALL_REJECT"

        /** Sent by MainActivity on resume: while it is in the foreground, try to add the health FGS type if missing. */
        const val ACTION_ENSURE_HEALTH = "org.circa.watchlink.ENSURE_HEALTH"
        const val EXTRA_ORIGIN = "origin"

        // ---- "user wants it running" flag: set by the Start/Stop button, read by BootReceiver and sticky restarts ----
        const val PREFS_CONTROL = "control"
        const val KEY_WANT = "want_running"

        @JvmStatic
        fun wantRunning(c: Context): Boolean =
            c.getSharedPreferences(PREFS_CONTROL, Context.MODE_PRIVATE).getBoolean(KEY_WANT, false)

        @JvmStatic
        fun setWantRunning(c: Context, want: Boolean) {
            c.getSharedPreferences(PREFS_CONTROL, Context.MODE_PRIVATE).edit().putBoolean(KEY_WANT, want).commit()
            Log.i(TAG, "want_running=$want")
        }

        // CH_NOTIFY is the v2 channel: the old "phone_notifications" channel was IMPORTANCE_HIGH, which pulses the
        // display on every mirrored phone notification (70 of 84 screen-ons in the battery run). A channel's importance
        // can't be changed after it is created, so v2 is IMPORTANCE_DEFAULT (no heads-up => no screen wake) with a short
        // vibration and the legacy channel is deleted on startup.
        const val CH_SERVICE = "service"
        const val CH_NOTIFY = "phone_notifications_v2"
        const val CH_CALL = "phone_calls"
        const val CH_NOTIFY_LEGACY = "phone_notifications"

        /**
         * Group key that mutes one post. The framework's Notification.Builder has no public setSilent (only
         * NotificationCompat does, and this app has no androidx), but NotificationCompat.setSilent is just this: a child
         * of the `"silent"` group with GROUP_ALERT_SUMMARY, which NotificationManagerService drops in
         * shouldMuteNotificationLocked without hiding the update from the shade.
         */
        const val GROUP_KEY_SILENT = "silent"
        const val FGS_ID = 1
        const val TAG_GB = "gb"
        const val TAG_CALL = "call"
        const val HELLO_DELAY_MS = 2000L
        const val WEATHER_REFRESH_MS = 60L * 60 * 1000

        @Volatile
        private var instance: WatchLinkService? = null

        /** DEBUG_GB_LINE: run a listRecs / fetchRec on the service thread; false when the service is not running. */
        @JvmStatic
        fun debugTrack(m: Map<String, Any?>): Boolean {
            val s = instance ?: return false
            if (!s::h.isInitialized) return false
            s.h.post { s.debugTrackLine(m) }
            return true
        }

        /** "actTrk log=<id> cnt=<n> lines=erase|<chars>|none" (the end packet has no lines key). */
        @JvmStatic
        fun trackSummary(m: Map<String, Any?>): String {
            if (m["t"] != "actTrk") return JsonOut.encode(m)
            val lines = m["lines"]
            val l = when {
                !m.containsKey("lines") -> "none"
                lines == "erase" -> "erase"
                else -> (lines as? String)?.length?.toString() + "ch/" + (lines as? String)?.count { it == '\n' } + "rows"
            }
            return "actTrk log=" + m["log"] + " cnt=" + m["cnt"] + " lines=" + l
        }

        /** For PhoneDataProvider.call (binder thread): send one line to the subscribed GB link; false = not connected. */
        @JvmStatic
        fun sendToPhone(m: Map<String, Any?>): Boolean {
            val s = instance ?: return false
            if (!s::h.isInitialized) return false
            val ok = java.util.concurrent.atomic.AtomicBoolean(false)
            val done = java.util.concurrent.CountDownLatch(1)
            s.h.post { try { ok.set(s.sendToSubscribed(m)) } finally { done.countDown() } }
            done.await(2, java.util.concurrent.TimeUnit.SECONDS)
            return ok.get()
        }

        /**
         * Hard cap of one HR acquisition (was 20 s, held ~19 s per acquisition in the 2026-10-04 battery log).
         * The window normally ends at the first usable reading (see [HrWindow]); this bounds the case where the
         * sensor has nothing to give, which is recorded as "no reading" (GB `hrm` = 0).
         */
        const val HR_WINDOW_MAX_MS = 12_000L

        /** Sensors.wantHr reason: the window opened by the 5-minute sample schedule (feeds the open GB bucket too). */
        const val REASON_HR_WINDOW = "hr-window"

        /** Sensors.wantHr reason: live HR held while the screen is interactive (no wakelock). */
        const val REASON_SCREEN = "screen"

        /** Wear's theater-mode setting (Settings.Global); not in the public SDK, so the key is spelled out. */
        const val SETTING_THEATER_MODE_ON = "theater_mode_on"

        /**
         * Notification.EXTRA_SUBSTITUTE_APP_NAME: the name the shade shows instead of the posting package's label.
         * @SystemApi, gated by SUBSTITUTE_NOTIFICATION_APP_NAME, and absent from the public android.jar, so the
         * key is spelled out (see AndroidManifest.xml for the declaration).
         */
        const val EXTRA_SUBSTITUTE_APP_NAME = "android.substName"

        /** Slack added to every elapsed-time alarm: the phone-synced clock can sit a few hundred ms off the grid. */
        const val ALARM_ARM_SLACK_MS = 200L

        /** Wakelock held around one realtime tick — only this tick's work, never the whole rtInt interval. */
        const val RT_TICK_HOLD_MS = 2_000L

        /** Wakelock held per find buzz (500 ms vibration + one 1 s re-post). */
        const val FIND_BUZZ_MS = 3_000L
        const val FIND_MAX_MS = 60_000L

        /** Slack on the single platform timeout that backs the token bookkeeping. */
        const val WAKE_SLACK_MS = 2_000L

        // ---- state shown by MainActivity (written on the handler thread, read on the UI thread) ----
        @Volatile
        var running = false

        @Volatile
        var uiState = "stopped"

        @Volatile
        var uiName = ""

        @Volatile
        var uiLast = ""

        @Volatile
        var uiDetail = ""

        private fun msg(t: String): LinkedHashMap<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            m["t"] = t
            return m
        }

        private fun actRecord(r: Buckets.Record): LinkedHashMap<String, Any?> {
            val m = msg("act")
            m["ts"] = r.ts
            m["stp"] = r.steps
            m["hrm"] = r.hr
            return m
        }
    }
}
