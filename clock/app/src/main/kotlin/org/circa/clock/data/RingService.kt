package org.circa.clock.data

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.text.format.DateFormat
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.circa.clock.RingActivity
import org.circa.clock.model.Alarm
import org.circa.clock.model.Alert
import org.circa.clock.model.AlarmLogic
import org.circa.clock.model.Fmt

/** What the ringing screen shows; observed by [RingActivity]. */
object RingState {
    var active by mutableStateOf(false)
    var title by mutableStateOf("")
    var timeText by mutableStateOf("")
    var ampm by mutableStateOf("")
    var canSnooze by mutableStateOf(true)
    var snoozeMinutes by mutableStateOf(10)
}

/**
 * Plays the alarm / timer: foreground service with the ringing notification (full-screen intent), the
 * ringtone and the escalating vibration according to the ringer mode. Snooze, Dismiss, the crown, and the
 * side button (screen off) all end up in [snooze] / [dismiss].
 */
class RingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var alarmId = Store.RING_NONE
    private var screenOffReceiver: BroadcastReceiver? = null
    private val timeout = Runnable { missed() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SNOOZE -> { snooze(); return START_NOT_STICKY }
            ACTION_DISMISS -> { dismiss(); return START_NOT_STICKY }
        }
        val id = intent?.getIntExtra(EXTRA_ID, Store.RING_NONE) ?: Store.RING_NONE
        val timerMs = intent?.getLongExtra(EXTRA_TIMER_MS, 0) ?: 0
        startRinging(id, timerMs)
        return START_NOT_STICKY
    }

    private fun startRinging(id: Int, timerMs: Long) {
        Notifs.ensureChannels(this)
        val store = Store(this)
        stopEffects()
        alarmId = id
        store.ringing = id
        val is24 = DateFormat.is24HourFormat(this)
        val alarm = store.alarms.firstOrNull { it.id == id }
        val title: String; val text: String
        if (id == Store.RING_TIMER) {
            title = "Timer " + Fmt.duration(timerMs); text = "Time's up"
            RingState.timeText = "Time's up"; RingState.ampm = ""
            RingState.canSnooze = false
        } else {
            val a = alarm ?: run { stopSelf(); return }
            title = a.label.ifBlank { "Alarm" }; text = Fmt.time(a.hour, a.minute, is24)
            RingState.timeText = text.removeSuffix(""); RingState.ampm = if (is24) "" else Fmt.ampm(a.hour)
            RingState.canSnooze = true; RingState.snoozeMinutes = a.snoozeMinutes
        }
        RingState.title = title
        RingState.active = true
        startForeground(Notifs.ID_RING, Notifs.ringing(this, title, text, RingState.canSnooze),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        // Direct start of the full-screen activity (the notification's full-screen intent is the backup).
        runCatching {
            startActivity(Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION))
        }.onFailure { Log.w(TAG, "direct ring activity start refused: $it") }

        val am = getSystemService(AudioManager::class.java)
        val nm = getSystemService(android.app.NotificationManager::class.java)
        val total = nm.currentInterruptionFilter == android.app.NotificationManager.INTERRUPTION_FILTER_NONE
        val plan = Alert.plan(am.ringerMode, total)
        Log.i(TAG, "ringing id=$id ringer=${am.ringerMode} totalSilence=$total plan=$plan")
        if (plan.sound) playSound()
        if (plan.vibrate) vibrate()
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, RING_TIMEOUT_MS)
        // Side button: with the keyguard up it turns the screen off; treat that as Snooze (Dismiss for a timer).
        screenOffReceiver?.let { runCatching { unregisterReceiver(it) } }
        val armedAt = System.currentTimeMillis()
        screenOffReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (System.currentTimeMillis() - armedAt < 1500) return // ignore the screen cycling at start-up
                if (RingState.canSnooze) snooze() else dismiss()
            }
        }.also { registerReceiver(it, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED) }
    }

    private fun playSound() {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val r = RingtoneManager.getRingtone(this, uri) ?: run { Log.w(TAG, "no ringtone available"); return }
        r.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        r.isLooping = true
        r.play()
        ringtone = r
    }

    private fun vibrate() {
        val v = getSystemService(VibratorManager::class.java).defaultVibrator
        if (!v.hasVibrator()) return
        val w = Alert.escalatingWave()
        v.vibrate(VibrationEffect.createWaveform(w.timings, w.amplitudes, w.repeatIndex),
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
    }

    private fun stopEffects() {
        handler.removeCallbacks(timeout)
        ringtone?.stop(); ringtone = null
        getSystemService(VibratorManager::class.java).defaultVibrator.cancel()
    }

    private fun finishRinging() {
        stopEffects()
        screenOffReceiver?.let { runCatching { unregisterReceiver(it) } }; screenOffReceiver = null
        RingState.active = false
        Store(this).ringing = Store.RING_NONE
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun snooze() {
        if (alarmId >= 0) {
            val store = Store(this)
            store.alarms = store.alarms.map { if (it.id == alarmId) AlarmLogic.snooze(it, System.currentTimeMillis()) else it }
            Scheduler.rescheduleAlarms(this)
            Log.i(TAG, "snoozed alarm $alarmId")
        }
        finishRinging()
    }

    fun dismiss() { Log.i(TAG, "dismissed $alarmId"); finishRinging() }

    private fun missed() {
        Notifs.missed(this, RingState.title + " " + RingState.timeText)
        finishRinging()
    }

    override fun onDestroy() { stopEffects(); instance = null; super.onDestroy() }
    override fun onCreate() { super.onCreate(); instance = this }

    companion object {
        const val TAG = "CircaClock"
        const val ACTION_SNOOZE = "org.circa.clock.SNOOZE"
        const val ACTION_DISMISS = "org.circa.clock.DISMISS"
        const val EXTRA_ID = "id"
        const val EXTRA_TIMER_MS = "timerMs"
        const val RING_TIMEOUT_MS = 10 * 60_000L
        @Volatile var instance: RingService? = null

        fun start(ctx: Context, id: Int, label: Long = 0) {
            ctx.startForegroundService(Intent(ctx, RingService::class.java)
                .putExtra(EXTRA_ID, id).putExtra(EXTRA_TIMER_MS, label))
        }

        fun send(ctx: Context, action: String) {
            ctx.startService(Intent(ctx, RingService::class.java).setAction(action))
        }
    }
}
