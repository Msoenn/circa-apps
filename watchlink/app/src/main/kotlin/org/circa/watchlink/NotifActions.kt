package org.circa.watchlink

/**
 * Replies and actions of a mirrored phone notification (Gadgetbridge's Bangle.js protocol, checked against GB master
 * `BangleJSDeviceSupport.onNotification` / `handleNotificationControl`):
 *
 * * phone to watch: `notify` carries `reply:true` when the phone notification has a WearableExtender reply action,
 *   and `actions:[{title}]` with its simple actions (the array position is the index the watch sends back);
 * * watch to phone: `{t:"notify",n:"REPLY",id,msg}` and `{t:"notify",n:"INVOKE_ACTION",id,action}`.
 *
 * Pure Kotlin (no Android), so `HostTest` pins it on the JVM. [WatchLinkService] builds the Notification.Actions.
 */
internal object NotifActions {
    /** GB keeps reply and action handles in two LimitedQueue(16)s keyed by notification id. */
    const val GB_QUEUE_LIMIT = 16

    /** A reply longer than this is cut (a BLE UART line; GB has no limit of its own). */
    const val MAX_REPLY_CHARS = 1000

    /** What one `notify` offers. [sms]: GB has a phone number for it (`tel`), see [shownActions]. */
    class Spec(val id: Long, val reply: Boolean, val actions: List<String>, val sms: Boolean)

    @JvmStatic
    fun parse(m: Map<String, Any?>): Spec {
        val titles = ArrayList<String>()
        (m["actions"] as? List<*>)?.forEach { a ->
            val t = (a as? Map<*, *>)?.get("title")?.toString()?.trim()
            // Keep the position even for an empty title: the index is what GB maps back to the handle.
            titles.add(t ?: "")
        }
        val tel = Proto.str(m, "tel")
        return Spec(Proto.num(m, "id", 0), Proto.bool(m, "reply", false), titles, !tel.isNullOrBlank())
    }

    /**
     * The action titles the watch offers, as (GB index, title). None for an SMS notification: GB turns INVOKE_ACTION
     * into a REPLY event without text, and for a notification it has a sender number for it then calls
     * `SmsManager.sendTextMessage(number, null)` (an invalid, empty SMS) instead of firing the action. Reply stays.
     */
    @JvmStatic
    fun shownActions(spec: Spec): List<Pair<Int, String>> {
        if (spec.sms) return emptyList()
        return spec.actions.withIndex().filter { it.value.isNotEmpty() }.map { it.index to it.value }
    }

    /** The reply as sent: trimmed, cut to [MAX_REPLY_CHARS], null when empty. */
    @JvmStatic
    fun replyText(raw: CharSequence?): String? {
        val t = raw?.toString()?.trim() ?: return null
        if (t.isEmpty()) return null
        return if (t.length <= MAX_REPLY_CHARS) t else t.substring(0, MAX_REPLY_CHARS)
    }

    /** Never with `tel`: with it GB sends an SMS to that number itself. */
    @JvmStatic
    fun replyMsg(id: Long, text: String): LinkedHashMap<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["t"] = "notify"
        m["n"] = "REPLY"
        m["id"] = id
        m["msg"] = text
        return m
    }

    @JvmStatic
    fun invokeMsg(id: Long, index: Int): LinkedHashMap<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["t"] = "notify"
        m["n"] = "INVOKE_ACTION"
        m["id"] = id
        m["action"] = index
        return m
    }

    /** The message as logged: the reply text is replaced by its length (it must not land in logcat). */
    @JvmStatic
    fun summary(m: Map<String, Any?>): String {
        val msg = m["msg"] ?: return JsonOut.encode(m)
        val c = LinkedHashMap(m)
        c["msg"] = "<" + msg.toString().length + " chars>"
        return JsonOut.encode(c)
    }
}

/**
 * A mirror of Gadgetbridge's two handle queues for one link, so the watch stops offering an action GB can no longer
 * resolve (GB drops such a REPLY / INVOKE_ACTION silently, with only a log line on the phone):
 *
 * * `mNotificationReplyAction`: one `add(id, handle)` per `notify` with `reply`, never removed by id (a repost of
 *   the same id takes another slot);
 * * `mNotificationActions`: on a `notify` with actions, `remove(id)` then `add(id, ...)`; `notify-` removes the id.
 *   A `notify` without actions leaves the old entry.
 *
 * Both evict the oldest entry past [limit].
 */
internal class GbHandles(private val limit: Int = NotifActions.GB_QUEUE_LIMIT) {
    private val replies = ArrayDeque<Long>()
    private val actions = ArrayDeque<Long>()

    /** Records one `notify`; returns the other ids that lost their reply or their actions with it. */
    fun onNotify(id: Long, reply: Boolean, hasActions: Boolean): Set<Long> {
        val r0 = replies.toSet()
        val a0 = actions.toSet()
        if (reply) {
            if (replies.size >= limit) replies.removeFirst()
            replies.addLast(id)
        }
        if (hasActions) {
            actions.removeAll { it == id }
            if (actions.size >= limit) actions.removeFirst()
            actions.addLast(id)
        }
        val lost = HashSet<Long>()
        lost.addAll(r0 - replies.toSet())
        lost.addAll(a0 - actions.toSet())
        lost.remove(id)
        return lost
    }

    fun onDelete(id: Long) {
        actions.removeAll { it == id }
    }

    fun hasReply(id: Long): Boolean = replies.contains(id)

    fun hasActions(id: Long): Boolean = actions.contains(id)
}

/**
 * The canned replies GB pushes with `canned_responses_sync` (`d:[{text, disp?}]`; `disp` only exists with "Text as
 * bitmaps" on and is ignored). GB sends them only when the user taps "Send" under the device's canned replies, so
 * they are stored; until then (or after an empty list) [DEFAULTS] are offered.
 */
internal object CannedReplies {
    @JvmField
    val DEFAULTS: List<String> =
        listOf("OK", "Yes", "No", "On my way", "Can't talk now", "Call you later", "Thanks")

    const val MAX = 16

    @JvmStatic
    fun parse(m: Map<String, Any?>): List<String> {
        val out = ArrayList<String>()
        (m["d"] as? List<*>)?.forEach { e ->
            val t = (e as? Map<*, *>)?.get("text")?.toString()?.trim()
            if (!t.isNullOrEmpty() && !out.contains(t) && out.size < MAX) out.add(t)
        }
        return out
    }

    /** Stored form: a JSON array of strings. */
    @JvmStatic
    fun encode(list: List<String>): String = JsonOut.encode(ArrayList<Any?>(list))

    @JvmStatic
    fun decode(s: String?): List<String> {
        if (s.isNullOrEmpty()) return emptyList()
        return try {
            (JsParser.parse(s) as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    @JvmStatic
    fun effective(stored: List<String>): List<String> = stored.ifEmpty { DEFAULTS }
}
