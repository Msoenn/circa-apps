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
    const val ID_SAVED = 2
    /** High importance: the "Walk detected" ask (heads-up). Silent; the buzz is [Haptics.detected]. */
    const val CH_DETECTED = "detected"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_WORKOUT, ctx.getString(R.string.channel_workout), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null); enableVibration(false); setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DETECTED, ctx.getString(R.string.channel_detected), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null); enableVibration(false); setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 1,
        Intent(ctx, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun action(ctx: Context, code: Int, label: String, act: String): Notification.Action =
        Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.ic_stat_exercise), label,
            PendingIntent.getService(
                ctx, code, Intent(ctx, ExerciseService::class.java).setAction(act),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build()

    /**
     * "Walk detected" / "Run detected" with the backdated start and Keep / Discard. It is the foreground notification
     * of the auto workout while it is undecided; on Keep (or after [AutoParams.DECIDE_MS]) the service replaces it with
     * [workout] under the same id, so it turns into the normal ongoing card.
     */
    fun detected(ctx: Context, w: Workout): Notification {
        val since = java.time.Instant.ofEpochMilli(w.startMs).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US))
            .replace(' ', '\u00a0')   // "3:42 PM" never breaks inside the time
        return Notification.Builder(ctx, CH_DETECTED)
            .setSmallIcon(R.drawable.ic_stat_exercise)
            .setContentTitle(NotifText.detectedTitle(w.type))
            .setContentText(NotifText.detectedText(since))
            .setCategory(Notification.CATEGORY_WORKOUT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(ctx, 11, "Keep", ExerciseService.ACTION_AUTO_KEEP))
            .addAction(action(ctx, 12, "Discard", ExerciseService.ACTION_AUTO_DISCARD))
            .build()
    }

    /** An auto workout ended by itself and was saved: a quiet card that opens the app. */
    fun saved(ctx: Context, s: org.circa.exercise.model.Summary) {
        val n = Notification.Builder(ctx, CH_WORKOUT)
            .setSmallIcon(R.drawable.ic_stat_exercise)
            .setContentTitle(NotifText.savedTitle(s.type))
            .setContentText(NotifText.savedText(s.activeMs, s.distanceM, s.type.gps))
            .setCategory(Notification.CATEGORY_WORKOUT)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_SAVED, n)
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
