package org.circa.exercise.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.circa.exercise.MainActivity
import org.circa.exercise.R
import org.circa.exercise.model.NotifText
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

    /**
     * The ongoing card. While recording it is a chronometer: the stock shade ticks it from [NotifText.chronoBase],
     * i.e. it counts the accumulated active (unpaused) time. While paused the chronometer is off and the frozen
     * active time is the content text ("Paused · 12:34"), so the card never shows the wall-clock age ("Now").
     *
     * The service rebuilds this on every state change ([ExerciseService.pause]/[ExerciseService.resume]).
     */
    fun workout(ctx: Context, w: Workout): Notification {
        val open = PendingIntent.getActivity(
            ctx, 1,
            Intent(ctx, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val now = System.currentTimeMillis()
        val activeMs = w.activeMs(now)
        val paused = w.phase == Phase.PAUSED
        return Notification.Builder(ctx, CH_WORKOUT)
            .setSmallIcon(R.drawable.ic_stat_exercise)
            .setContentTitle(w.type.label)
            .setContentText(if (paused) NotifText.paused(activeMs) else NotifText.RECORDING)
            .setCategory(Notification.CATEGORY_WORKOUT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .apply {
                if (paused) {
                    setUsesChronometer(false)
                    setShowWhen(false)
                } else {
                    setWhen(NotifText.chronoBase(now, activeMs))
                    setUsesChronometer(true)
                    setShowWhen(true)
                }
            }
            .build()
    }
}
