package org.circa.launcher

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.circa.launcher.data.NotificationStore
import org.circa.launcher.model.NotificationItem

/**
 * The launcher's own notification listener: the source of the tray's notification stream.
 *
 * It is enabled on first run when the launcher can write secure settings (it is platform-signed and
 * declares WRITE_SECURE_SETTINGS, see [enableIfPossible]); `the launcher emulator smoke test` grants the
 * same access with `cmd notification allow_listener` as a belt-and-braces path.
 *
 * Privacy: nothing here logs a notification's payload. Only the reduced [NotificationItem] reaches
 * the UI, and no title, text or extra is ever written to the log or to a file.
 */
class LauncherNotificationListener : NotificationListenerService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onListenerConnected() {
        instance = this
        // The listener can connect after notifications were posted (it is bound lazily), so seed
        // the stream with whatever is already active. `activeNotifications` is a synchronous call
        // from this callback.
        mainHandler.post { refreshActive() }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val posted = sbn ?: return
        // A group summary (e.g. the platform's auto-grouping of one app's notifications) carries
        // no content of its own; the cards for its children are what the stream shows.
        if (posted.isGroupSummary()) return
        val item = posted.toItem(this)
        mainHandler.post { NotificationStore.upsert(item, posted.notification.contentIntent) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val removed = sbn ?: return
        val key = removed.key
        mainHandler.post { NotificationStore.remove(key) }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun refreshActive() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        NotificationStore.replaceAll(
            active.filterNot { it.isGroupSummary() }
                .map { it.toItem(this) to it.notification.contentIntent },
        )
    }

    companion object {

        /** `Settings.Secure.ENABLED_NOTIFICATION_LISTENERS`, hidden from the public SDK. */
        private const val ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"

        /** The connected service, so the tray can cancel a card on its behalf. */
        @Volatile
        private var instance: LauncherNotificationListener? = null

        /**
         * Add this listener to `Settings.Secure.enabled_notification_listeners`, the grant the
         * listener-access UI writes. Needs WRITE_SECURE_SETTINGS; returns true when the listener is
         * listed afterwards. When the platform's own grant is needed instead, the smoke test uses
         * `cmd notification allow_listener`.
         */
        fun enableIfPossible(context: Context): Boolean {
            val component = ComponentName(context, LauncherNotificationListener::class.java)
            val flat = component.flattenToString()
            val resolver = context.contentResolver
            // The platform's own key for the granted-listener list (Settings.Secure, hidden from
            // the public SDK); NMS reads and writes exactly this string.
            val current = Settings.Secure.getString(
                resolver,
                ENABLED_NOTIFICATION_LISTENERS,
            ).orEmpty()
            if (current.split(':').any { it.equals(flat, ignoreCase = true) }) return true
            val updated = if (current.isBlank()) flat else "$current:$flat"
            val written = runCatching {
                Settings.Secure.putString(
                    resolver,
                    ENABLED_NOTIFICATION_LISTENERS,
                    updated,
                )
            }.getOrDefault(false)
            return written
        }

        /** Cancel a notification by its key, through the listener (the only path that may). */
        fun cancelNotification(key: String): Boolean {
            val service = instance ?: return false
            return runCatching { service.cancelNotification(key) }.isSuccess
        }
    }
}

private fun StatusBarNotification.isGroupSummary(): Boolean =
    notification.flags and Notification.FLAG_GROUP_SUMMARY != 0

/**
 * Reduce a posted notification to the card fields. `ongoing` covers both the ongoing/foreground
 * flags and FLAG_NO_CLEAR: those cards are shown but cannot be swiped away.
 */
private fun StatusBarNotification.toItem(context: Context): NotificationItem {
    val notification = notification
    val extras = notification.extras
    val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
    val text = (
        extras?.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)
        )?.toString()?.takeIf { it.isNotBlank() }
    val stickyFlags = Notification.FLAG_ONGOING_EVENT or
        Notification.FLAG_FOREGROUND_SERVICE or
        Notification.FLAG_NO_CLEAR
    return NotificationItem(
        key = key,
        packageName = packageName,
        appName = appLabel(context, packageName),
        title = title,
        text = text,
        postTimeMillis = postTime,
        ongoing = notification.flags and stickyFlags != 0,
    )
}

/** The posting app's label, for the card's first line; the package name when it cannot be read. */
private fun appLabel(context: Context, packageName: String): String {
    val manager = context.packageManager
    return runCatching {
        manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: packageName
}
