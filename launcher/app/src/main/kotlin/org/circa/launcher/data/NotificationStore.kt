package org.circa.launcher.data

import android.app.PendingIntent
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import org.circa.launcher.model.NotificationItem
import org.circa.launcher.model.NotificationStream

/**
 * The notification stream, as Compose state, filled by [LauncherNotificationListener] (same app
 * process) and read by the tray. The list rules and the ordering live in the pure
 * [NotificationStream]; this only holds them as observable state plus the per-key pending intent
 * that a card tap fires.
 *
 * Mutations must happen on the main thread (the listener posts them there).
 */
object NotificationStore {

    val items = mutableStateListOf<NotificationItem>()

    private val contentIntents = mutableStateMapOf<String, PendingIntent>()

    /** The intent that opens [key]'s app on the right screen, when it has one. */
    fun contentIntent(key: String): PendingIntent? = contentIntents[key]

    fun upsert(item: NotificationItem, contentIntent: PendingIntent?) {
        val updated = NotificationStream.upsert(items.toList(), item)
        items.clear()
        items.addAll(updated)
        if (contentIntent != null) contentIntents[item.key] = contentIntent
    }

    fun remove(key: String) {
        val updated = NotificationStream.remove(items.toList(), key)
        items.clear()
        items.addAll(updated)
        contentIntents.remove(key)
    }

    /** The listener re-reading every active notification (e.g. right after it connects). */
    fun replaceAll(replacement: List<Pair<NotificationItem, PendingIntent?>>) {
        items.clear()
        contentIntents.clear()
        for ((item, intent) in replacement.sortedByDescending { it.first.postTimeMillis }
            .take(NotificationStream.CAP)) {
            items.add(item)
            if (intent != null) contentIntents[item.key] = intent
        }
    }
}
