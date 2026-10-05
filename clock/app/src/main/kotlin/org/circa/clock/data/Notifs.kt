package org.circa.clock.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.SystemClock
import org.circa.clock.MainActivity
import org.circa.clock.R
import org.circa.clock.RingActivity
import org.circa.clock.model.StopwatchState
import org.circa.clock.model.TimerPhase
import org.circa.clock.model.TimerState

/** Notification channels and the three notifications (ringing, running timer, running stopwatch). */
object Notifs {
    const val CH_RING = "ring"
    const val CH_TIMER = "timer"
    const val CH_STOPWATCH = "stopwatch"
    const val CH_MISSED = "missed"
    const val ID_RING = 100
    const val ID_TIMER = 101
    const val ID_STOPWATCH = 102
    const val ID_MISSED = 103

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        fun ch(id: String, name: Int, imp: Int) = NotificationChannel(id, ctx.getString(name), imp).apply {
            setSound(null, null); enableVibration(false); setShowBadge(false)
        }
        // The service plays sound and vibration itself (it has to follow the ringer mode, escalate, and loop).
        nm.createNotificationChannel(ch(CH_RING, R.string.channel_ring, NotificationManager.IMPORTANCE_HIGH).apply {
            setBypassDnd(true); lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(ch(CH_TIMER, R.string.channel_timer, NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(ch(CH_STOPWATCH, R.string.channel_stopwatch, NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(ch(CH_MISSED, R.string.channel_missed, NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun icon(ctx: Context) = Icon.createWithResource(ctx, R.drawable.ic_clock)

    private fun open(ctx: Context, page: Int) = PendingIntent.getActivity(
        ctx, 10 + page,
        Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_PAGE, page)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun ringAction(ctx: Context, action: String, req: Int, label: String): Notification.Action =
        Notification.Action.Builder(
            icon(ctx), label,
            PendingIntent.getService(ctx, req, Intent(ctx, RingService::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
        ).build()

    fun ringing(ctx: Context, title: String, text: String, snoozable: Boolean): Notification {
        val full = PendingIntent.getActivity(
            ctx, 20, Intent(ctx, RingActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(ctx, CH_RING)
            .setSmallIcon(icon(ctx)).setContentTitle(title).setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM).setOngoing(true).setAutoCancel(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setFullScreenIntent(full, true).setContentIntent(full)
            .apply { if (snoozable) addAction(ringAction(ctx, RingService.ACTION_SNOOZE, 21, "Snooze")) }
            .addAction(ringAction(ctx, RingService.ACTION_DISMISS, 22, "Dismiss"))
            .build()
    }

    fun missed(ctx: Context, text: String) {
        val n = Notification.Builder(ctx, CH_MISSED).setSmallIcon(icon(ctx)).setContentTitle("Missed alarm")
            .setContentText(text).setAutoCancel(true).setContentIntent(open(ctx, 0)).build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_MISSED, n)
    }

    /** Ongoing countdown while the timer runs (system chronometer, no per-second updates by us). */
    fun showTimer(ctx: Context, t: TimerState) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (t.phase == TimerPhase.IDLE) { nm.cancel(ID_TIMER); return }
        val b = Notification.Builder(ctx, CH_TIMER).setSmallIcon(icon(ctx)).setOngoing(true)
            .setCategory(Notification.CATEGORY_PROGRESS).setContentTitle("Timer").setContentIntent(open(ctx, 1))
        if (t.phase == TimerPhase.RUNNING) {
            val endWall = System.currentTimeMillis() + (t.endAt - SystemClock.elapsedRealtime())
            b.setShowWhen(true).setWhen(endWall).setUsesChronometer(true).setChronometerCountDown(true)
        } else b.setContentText("Paused " + org.circa.clock.model.Fmt.duration(t.pausedRemainingMs))
        nm.notify(ID_TIMER, b.build())
    }

    fun cancelTimer(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).cancel(ID_TIMER)

    fun showStopwatch(ctx: Context, s: StopwatchState) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (s.isIdle) { nm.cancel(ID_STOPWATCH); return }
        val b = Notification.Builder(ctx, CH_STOPWATCH).setSmallIcon(icon(ctx)).setOngoing(true)
            .setCategory(Notification.CATEGORY_STOPWATCH).setContentTitle("Stopwatch").setContentIntent(open(ctx, 2))
        val now = SystemClock.elapsedRealtime()
        if (s.running) b.setShowWhen(true).setWhen(System.currentTimeMillis() - s.elapsed(now)).setUsesChronometer(true)
        else b.setContentText("Paused " + org.circa.clock.model.Fmt.stopwatch(s.accumulatedMs))
        nm.notify(ID_STOPWATCH, b.build())
    }
}
