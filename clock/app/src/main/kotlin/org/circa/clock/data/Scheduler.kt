package org.circa.clock.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import org.circa.clock.MainActivity
import org.circa.clock.model.AlarmLogic
import org.circa.clock.model.SmartWake
import org.circa.clock.model.TimerPhase
import org.circa.clock.model.TimerState
import java.time.ZoneId

/**
 * Programs AlarmManager: one alarm-clock entry (the earliest alarm, shown as the next alarm) + the timer, and
 * for Smart Wake one exact alarm where the earliest Smart Wake window opens. The alarm-clock entry is never
 * changed by Smart Wake: if WatchLink never calls back, the alarm rings at its set time.
 */
object Scheduler {
    private fun fireIntent(ctx: Context, action: String, req: Int, id: Int = -1, at: Long = 0): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, req,
            Intent(ctx, ClockReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id).putExtra(EXTRA_AT, at),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Re-evaluates every alarm and (re)arms the earliest one with setAlarmClock, or cancels. Idempotent. */
    fun rescheduleAlarms(ctx: Context, now: Long = System.currentTimeMillis()) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        scheduleSmartWake(ctx, am, now)
        val e = AlarmLogic.earliest(Store(ctx).alarms, now, ZoneId.systemDefault())
        if (e == null) {
            am.cancel(fireIntent(ctx, ACTION_ALARM, REQ_ALARM))
            return
        }
        val (alarm, at) = e
        val show = PendingIntent.getActivity(
            ctx, REQ_SHOW,
            Intent(ctx, MainActivity::class.java).setAction("android.intent.action.SHOW_ALARMS"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), fireIntent(ctx, ACTION_ALARM, REQ_ALARM, alarm.id, at))
    }

    /**
     * Arms the exact alarm that opens the next Smart Wake window ([ACTION_SMART_WINDOW]), or cancels it; and
     * tells WatchLink to stop watching when the occurrence it watches for is no longer the next one.
     */
    private fun scheduleSmartWake(ctx: Context, am: AlarmManager, now: Long) {
        val store = Store(ctx)
        val next = AlarmLogic.nextSmartWake(store.alarms, now, ZoneId.systemDefault())
        val requested = store.smartRequestedAt
        if (requested != 0L && requested != next?.second) {
            cancelSmartWake(ctx)
            store.smartRequestedAt = 0L
        }
        val pi = fireIntent(ctx, ACTION_SMART_WINDOW, REQ_SMART)
        if (next == null) {
            am.cancel(pi)
            return
        }
        val (alarm, at) = next
        if (requested == at) return // WatchLink is already watching for it
        val open = maxOf(at - SmartWake.WINDOW_MS, now + 1_000L)
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, open,
            fireIntent(ctx, ACTION_SMART_WINDOW, REQ_SMART, alarm.id, at))
    }

    /** The Smart Wake window of occurrence [at] of alarm [id] opened: ask WatchLink to watch until [at]. */
    fun requestSmartWake(ctx: Context, id: Int, at: Long) {
        val callback = fireIntent(ctx, ACTION_SMART_WAKE, REQ_SMART_CALLBACK, id, at)
        val i = android.content.Intent(SmartWake.ACTION_REQUEST).setPackage(SmartWake.WATCHLINK_PACKAGE)
            .putExtra(SmartWake.EXTRA_DEADLINE, at).putExtra(SmartWake.EXTRA_CALLBACK, callback)
        ctx.sendBroadcast(i)
        Store(ctx).smartRequestedAt = at
        Log.i(TAG, "smart wake: asked WatchLink to watch alarm $id until $at")
    }

    private fun cancelSmartWake(ctx: Context) {
        ctx.sendBroadcast(android.content.Intent(SmartWake.ACTION_CANCEL).setPackage(SmartWake.WATCHLINK_PACKAGE))
        Log.i(TAG, "smart wake: cancelled the watch")
    }

    /** Arms (or cancels) the timer's exact wake-up for [t] (elapsed-realtime based). */
    fun scheduleTimer(ctx: Context, t: TimerState) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = fireIntent(ctx, ACTION_TIMER, REQ_TIMER)
        if (t.phase == TimerPhase.RUNNING) {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, t.endAt, pi)
        } else am.cancel(pi)
    }

    const val ACTION_ALARM = "org.circa.clock.ALARM"
    const val ACTION_TIMER = "org.circa.clock.TIMER"
    const val ACTION_SMART_WINDOW = "org.circa.clock.SMART_WINDOW"
    const val ACTION_SMART_WAKE = "org.circa.clock.SMART_WAKE"
    const val EXTRA_ID = "id"
    const val EXTRA_AT = "at"
    private const val REQ_ALARM = 1
    private const val REQ_TIMER = 2
    private const val REQ_SHOW = 3
    private const val REQ_SMART = 4
    private const val REQ_SMART_CALLBACK = 5
    private const val TAG = "CircaClock"

    fun elapsedNow() = SystemClock.elapsedRealtime()
}
