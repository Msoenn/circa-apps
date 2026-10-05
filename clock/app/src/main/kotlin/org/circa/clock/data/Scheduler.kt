package org.circa.clock.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import org.circa.clock.MainActivity
import org.circa.clock.model.AlarmLogic
import org.circa.clock.model.TimerPhase
import org.circa.clock.model.TimerState
import java.time.ZoneId

/** Programs AlarmManager: one alarm-clock entry (the earliest alarm, shown as the next alarm) + the timer. */
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
    const val EXTRA_ID = "id"
    const val EXTRA_AT = "at"
    private const val REQ_ALARM = 1
    private const val REQ_TIMER = 2
    private const val REQ_SHOW = 3

    fun elapsedNow() = SystemClock.elapsedRealtime()
}
