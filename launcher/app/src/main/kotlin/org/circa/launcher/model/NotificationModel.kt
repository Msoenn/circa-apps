package org.circa.launcher.model

/**
 * One posted notification, reduced to exactly what the stream draws. Deliberately Android-free (no
 * extras, no content intents, no icons): the tray's ordering and time rules can then be unit-tested
 * on the JVM, and nothing here can leak a notification's payload anywhere but its own card.
 */
data class NotificationItem(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String?,
    val text: String?,
    val postTimeMillis: Long,
    val ongoing: Boolean,
)

/**
 * The stream's list rules, pure: newest first, one card per notification key, capped.
 */
object NotificationStream {

    /** Cards kept at most; older ones fall off the bottom of the tray. */
    const val CAP = 20

    /** Insert (or re-post) [item], keeping the list newest-first and capped. */
    fun upsert(current: List<NotificationItem>, item: NotificationItem): List<NotificationItem> =
        (current.filterNot { it.key == item.key } + item)
            .sortedByDescending { it.postTimeMillis }
            .take(CAP)

    /** Drop the card for [key]; unknown keys leave the list alone. */
    fun remove(current: List<NotificationItem>, key: String): List<NotificationItem> =
        current.filterNot { it.key == key }
}

/**
 * The age label a card shows on the right ("Now", "5m", "2h", "3d"), as stock does.
 */
object NotificationTime {

    private const val MINUTE = 60L
    private const val HOUR = 60L * MINUTE
    private const val DAY = 24L * HOUR

    fun format(nowMillis: Long, postTimeMillis: Long): String {
        val age = ((nowMillis - postTimeMillis) / 1000L).coerceAtLeast(0L)
        return when {
            age < MINUTE -> "Now"
            age < HOUR -> "${age / MINUTE}m"
            age < DAY -> "${age / HOUR}h"
            else -> "${age / DAY}d"
        }
    }
}
