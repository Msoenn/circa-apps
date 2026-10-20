package org.circa.clock.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.circa.clock.model.AlarmLogic
import java.time.ZoneId
import org.circa.clock.model.TimerPhase

/**
 * Fires alarms and timers and re-arms them after anything that can move them: reboot (also the locked boot,
 * before the first unlock - this class and the storage are direct-boot aware), time or time-zone change, and
 * an app update.
 */
class ClockReceiver : BroadcastReceiver() {
    private companion object {
        const val TAG = "CircaClock"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext ?: context
        val store = Store(ctx)
        when (intent.action) {
            Scheduler.ACTION_ALARM -> {
                val id = intent.getIntExtra(Scheduler.EXTRA_ID, -1)
                val alarms = store.alarms
                val a = alarms.firstOrNull { it.id == id }
                if (a != null) {
                    store.alarms = alarms.map { if (it.id == id) AlarmLogic.afterFire(it) else it }
                    Scheduler.rescheduleAlarms(ctx)
                    RingService.start(ctx, id)
                } else Scheduler.rescheduleAlarms(ctx)
            }
            Scheduler.ACTION_SMART_WINDOW -> {
                val id = intent.getIntExtra(Scheduler.EXTRA_ID, -1)
                val at = intent.getLongExtra(Scheduler.EXTRA_AT, 0L)
                val a = store.alarms.firstOrNull { it.id == id }
                if (a != null && a.smartWake && AlarmLogic.nextRegular(a, System.currentTimeMillis(), ZoneId.systemDefault()) == at) {
                    Scheduler.requestSmartWake(ctx, id, at)
                } else Scheduler.rescheduleAlarms(ctx)
            }
            Scheduler.ACTION_SMART_WAKE -> {
                // WatchLink saw the wearer stir inside the window (it fires the PendingIntent Clock gave it).
                val id = intent.getIntExtra(Scheduler.EXTRA_ID, -1)
                val at = intent.getLongExtra(Scheduler.EXTRA_AT, 0L)
                val now = System.currentTimeMillis()
                val alarms = store.alarms
                val a = alarms.firstOrNull { it.id == id }
                if (a != null && store.ringing == Store.RING_NONE &&
                    AlarmLogic.smartWakeMayRing(a, at, now, ZoneId.systemDefault())) {
                    Log.i(TAG, "smart wake: alarm $id rings ${(at - now) / 60_000} min early")
                    store.alarms = alarms.map { if (it.id == id) AlarmLogic.afterSmartWake(it, at) else it }
                    store.smartRequestedAt = 0L
                    Scheduler.rescheduleAlarms(ctx)
                    RingService.start(ctx, id)
                } else Log.i(TAG, "smart wake: callback for alarm $id at $at ignored")
            }
            Scheduler.ACTION_TIMER -> {
                val t = store.timer
                if (t.phase == TimerPhase.RUNNING) {
                    store.timer = t.reset()
                    Notifs.cancelTimer(ctx)
                    RingService.start(ctx, Store.RING_TIMER, label = t.totalMs)
                }
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Scheduler.rescheduleAlarms(ctx)
                // elapsedRealtime restarts at boot: a running timer or stopwatch from before cannot be continued
                // (paused ones keep their stored remaining/accumulated time). LOCKED_BOOT_COMPLETED comes first.
                if (intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
                    if (store.timer.phase == TimerPhase.RUNNING) store.timer = store.timer.reset()
                    if (store.stopwatch.running) store.stopwatch = store.stopwatch.reset()
                    store.ringing = Store.RING_NONE
                }
            }
        }
    }
}
