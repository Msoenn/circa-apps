package org.circa.exercise.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.circa.exercise.MainActivity
import org.circa.exercise.R
import org.circa.exercise.model.Phase
import org.circa.exercise.model.Workout

/** The ongoing "recording" notification (opens the live screen). */
object Notifs {
    const val CH_WORKOUT = "workout"
    const val ID_WORKOUT = 1

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_WORKOUT, ctx.getString(R.string.channel_workout), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null); enableVibration(false); setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    fun workout(ctx: Context, w: Workout): Notification {
        val open = PendingIntent.getActivity(
            ctx, 1,
            Intent(ctx, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val now = System.currentTimeMillis()
        val paused = w.phase == Phase.PAUSED
        return Notification.Builder(ctx, CH_WORKOUT)
            .setSmallIcon(R.drawable.ic_stat_exercise)
            .setContentTitle(w.type.label)
            .setContentText(if (paused) "Paused" else "Recording")
            .setCategory(Notification.CATEGORY_WORKOUT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .apply {
                if (!paused) {
                    // The chronometer shows the active time: base = now - active.
                    setWhen(now - w.activeMs(now)); setUsesChronometer(true); setShowWhen(true)
                } else setShowWhen(false)
            }
            .build()
    }
}
