package org.circa.watchlink

/**
 * Throttle for the health ContentProvider's observer notifications ([HealthStore]). Pure Kotlin, host-tested.
 *
 * The launcher's complications and health tile re-read on every notification. While the screen is on they should
 * follow the live HR sensor closely ([INTERACTIVE_INTERVAL_MS] = 3 s); while the watch is idle there is nothing to
 * look at and the pre-0.7 single rate ([BACKGROUND_INTERVAL_MS] = 30 s) is plenty, keeping background churn down.
 * Only HR and step updates call this, so it caps the notification rate, not the sampling rate.
 */
class NotifyThrottle(
    private val interactiveIntervalMs: Long = INTERACTIVE_INTERVAL_MS,
    private val backgroundIntervalMs: Long = BACKGROUND_INTERVAL_MS,
) {
    /** Minimum spacing between observer notifications for the current interactivity. */
    fun intervalMs(interactive: Boolean): Long =
        if (interactive) interactiveIntervalMs else backgroundIntervalMs

    /** True when a notification may be sent now; [lastNotifyElapsedMs] is the previous send (elapsed realtime). */
    fun shouldNotify(lastNotifyElapsedMs: Long, nowElapsedMs: Long, interactive: Boolean): Boolean =
        nowElapsedMs - lastNotifyElapsedMs >= intervalMs(interactive)

    companion object {
        /** Notification spacing while the screen is on: the launcher shows live values. */
        const val INTERACTIVE_INTERVAL_MS = 3_000L

        /** Notification spacing while the watch is idle (the pre-0.7 rate). */
        const val BACKGROUND_INTERVAL_MS = 30_000L
    }
}
