package org.circa.watchlink

import java.util.LinkedHashMap

/**
 * Alert decision for one mirrored phone notification. Pure Kotlin, no Android imports, host-tested.
 *
 * Phone notifications can churn hard (71 posts in 20 min in one burst) and each alert used to turn the watch display
 * on, which was most of the screen-ons in a battery measurement. The mirror still updates the notification in the shade, but only alerts (vibrates) when it is worth it:
 *
 * - **Dedupe by id:** a re-send of the same GB notification id with identical title+body changes nothing, so it
 *   is silent — GB re-pushes unchanged notifications on every sync and a phone app can re-post an identical
 *   notification repeatedly.
 * - **Rate limit:** at most one alert per [ALERT_WINDOW_MS]; posts inside the window update silently.
 *
 * Only the displayed content (head + text) is remembered, and only to compare — nothing is logged or persisted.
 */
class NotifyPolicy {
    private class Content(val head: String?, val text: String?) {
        fun same(h: String?, t: String?): Boolean = eq(head, h) && eq(text, t)
    }

    private val lastPosted: LinkedHashMap<Long, Content> =
        object : LinkedHashMap<Long, Content>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Content>): Boolean =
                size > MAX_TRACKED
        }
    private var alerted = false
    private var lastAlertMs = 0L

    /**
     * Record a post and decide whether it may alert (vibrate); false means the notification must be updated silently.
     *
     * @param id    GB notification id (the shade key; the same id is the same notification)
     * @param head  displayed title (title/sender/src fallback)
     * @param text  displayed body (subject + body)
     * @param nowMs monotonic clock (SystemClock.elapsedRealtime on the device)
     */
    fun decide(id: Long, head: String?, text: String?, nowMs: Long): Boolean {
        val prev = lastPosted[id]
        val unchanged = prev != null && prev.same(head, text)
        lastPosted[id] = Content(head, text)
        if (unchanged) return false                                    // dedupe: nothing new to feel
        if (alerted && nowMs - lastAlertMs < ALERT_WINDOW_MS) return false   // rate limit: someone just buzzed
        alerted = true
        lastAlertMs = nowMs
        return true
    }

    companion object {
        /** At most one alert per this window. */
        const val ALERT_WINDOW_MS = 10_000L

        /** Cap on remembered ids: a chatty phone can use many, and this must not grow without bound. */
        const val MAX_TRACKED = 64

        private fun eq(a: String?, b: String?): Boolean = if (a == null) b == null else a == b
    }
}
