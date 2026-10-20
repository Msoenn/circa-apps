package org.circa.clock.model

import java.net.URLDecoder
import java.net.URLEncoder
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Days of the week as a bit mask: bit 0 = Monday ... bit 6 = Sunday. 0 = one-shot alarm. */
object Days {
    const val NONE = 0
    const val WEEKDAYS = 0b0011111
    const val WEEKEND = 0b1100000
    const val ALL = 0b1111111
    private val SHORT = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    fun has(mask: Int, d: DayOfWeek): Boolean = mask and (1 shl (d.value - 1)) != 0
    fun toggle(mask: Int, index: Int): Int = mask xor (1 shl index)
    fun name(index: Int): String = SHORT[index]

    /** "Once", "Every day", "Mon - Fri", "Sat, Sun", "Mon, Wed". */
    fun summary(mask: Int): String = when (mask and ALL) {
        NONE -> "Once"
        ALL -> "Every day"
        WEEKDAYS -> "Mon - Fri"
        WEEKEND -> "Sat, Sun"
        else -> (0..6).filter { mask and (1 shl it) != 0 }.joinToString(", ") { SHORT[it] }
    }
}

/**
 * One alarm. [snoozedUntil] (epoch millis) is independent of [enabled]: a one-shot alarm switches itself
 * off when it fires, but a snooze of it still has to ring.
 *
 * [smartWake]: may ring up to [SmartWake.WINDOW_MS] early when WatchLink sees the wearer stir (light sleep);
 * the alarm at the set time stays armed regardless. [smartRangAt] is the occurrence (epoch millis) that
 * already rang early, so it does not ring again at its set time.
 */
data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val days: Int = Days.NONE,
    val label: String = "",
    val enabled: Boolean = true,
    val snoozeMinutes: Int = DEFAULT_SNOOZE_MIN,
    val snoozedUntil: Long? = null,
    val smartWake: Boolean = false,
    val smartRangAt: Long? = null,
) {
    companion object {
        const val DEFAULT_SNOOZE_MIN = 10
        val SNOOZE_CHOICES = listOf(5, 10, 15, 20)
    }
}

object AlarmLogic {

    /**
     * The next time [alarm] rings strictly after [nowMillis] in [zone], ignoring any snooze, or null if
     * it is off. Works on wall-clock time, so 7:00 stays 7:00 across a DST change; a wall time inside
     * the spring-forward gap rings at the first valid instant after it (java.time's rule).
     */
    fun nextRegular(alarm: Alarm, nowMillis: Long, zone: ZoneId): Long? {
        if (!alarm.enabled) return null
        val now = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMillis), zone)
        val time = LocalTime.of(alarm.hour, alarm.minute)
        var date: LocalDate = now.toLocalDate()
        // 8 days covers "today, but already past, then the next matching weekday".
        repeat(8) {
            val matches = alarm.days and Days.ALL == 0 || Days.has(alarm.days, date.dayOfWeek)
            if (matches) {
                val at = ZonedDateTime.of(LocalDateTime.of(date, time), zone).toInstant().toEpochMilli()
                // An occurrence Smart Wake already rang early is spent.
                if (at > nowMillis && at != alarm.smartRangAt) return at
            }
            date = date.plusDays(1)
        }
        return null
    }

    /** What the scheduler should wake for: the snooze if there is one, else the regular next time. */
    fun nextTrigger(alarm: Alarm, nowMillis: Long, zone: ZoneId): Long? {
        val regular = nextRegular(alarm, nowMillis, zone)
        val snooze = alarm.snoozedUntil?.takeIf { it > nowMillis }
        return listOfNotNull(regular, snooze).minOrNull()
    }

    /** The earliest trigger over all alarms, with its alarm. */
    fun earliest(alarms: List<Alarm>, nowMillis: Long, zone: ZoneId): Pair<Alarm, Long>? =
        alarms.mapNotNull { a -> nextTrigger(a, nowMillis, zone)?.let { a to it } }.minByOrNull { it.second }

    /** State of [alarm] right after it rang: the snooze is spent and a one-shot switches off. */
    fun afterFire(alarm: Alarm): Alarm =
        alarm.copy(snoozedUntil = null, enabled = alarm.enabled && alarm.days and Days.ALL != 0)

    /**
     * The Smart Wake watch to arm: the earliest alarm with [Alarm.smartWake] whose next regular occurrence
     * is not yet [SmartWake.WINDOW_MS] past, as (alarm, occurrence). Its window opens at
     * `occurrence - WINDOW_MS` (or now, when that has passed). Snoozes never ring early.
     */
    fun nextSmartWake(alarms: List<Alarm>, nowMillis: Long, zone: ZoneId): Pair<Alarm, Long>? =
        alarms.filter { it.smartWake }
            .mapNotNull { a -> nextRegular(a, nowMillis, zone)?.let { a to it } }
            .minByOrNull { it.second }

    /**
     * Whether a Smart Wake callback for occurrence [at] of [alarm] may ring now: the alarm still wants Smart
     * Wake, [at] is still its next occurrence, inside the window and at least [SmartWake.MIN_LEAD_MS] early
     * (later than that, the alarm at the set time rings anyway).
     */
    fun smartWakeMayRing(alarm: Alarm, at: Long, nowMillis: Long, zone: ZoneId): Boolean =
        alarm.smartWake && alarm.enabled && nextRegular(alarm, nowMillis, zone) == at &&
            nowMillis >= at - SmartWake.WINDOW_MS && nowMillis <= at - SmartWake.MIN_LEAD_MS

    /** State after Smart Wake rang occurrence [at] early: like [afterFire], and [at] is spent. */
    fun afterSmartWake(alarm: Alarm, at: Long): Alarm = afterFire(alarm.copy(smartRangAt = at))

    /** State after Snooze pressed at [nowMillis]: rings again [Alarm.snoozeMinutes] later. */
    fun snooze(alarm: Alarm, nowMillis: Long): Alarm =
        alarm.copy(snoozedUntil = nowMillis + alarm.snoozeMinutes * 60_000L)

    /** "in 9 h 12 min" style text for the time until [triggerMillis]. */
    fun untilText(nowMillis: Long, triggerMillis: Long): String {
        val mins = Duration.ofMillis((triggerMillis - nowMillis).coerceAtLeast(0)).toMinutes() + 1
        val d = mins / (24 * 60)
        val h = mins % (24 * 60) / 60
        val m = mins % 60
        return buildString {
            if (d > 0) append("${d} d ")
            if (h > 0) append("${h} h ")
            if (m > 0 || (d == 0L && h == 0L)) append("${m} min")
        }.trim()
    }

    /** Next free alarm id. */
    fun newId(alarms: List<Alarm>): Int = (alarms.maxOfOrNull { it.id } ?: 0) + 1

    /** Alarms sorted by time of day, then id. */
    fun sorted(alarms: List<Alarm>): List<Alarm> = alarms.sortedWith(compareBy({ it.hour }, { it.minute }, { it.id }))
}

/** Line-based text codec (no org.json: pure JVM, unit-testable). One alarm per line, tab separated. */
object AlarmCodec {
    fun encode(alarms: List<Alarm>): String = alarms.joinToString("\n") { a ->
        listOf(a.id, a.hour, a.minute, a.days, if (a.enabled) 1 else 0, a.snoozeMinutes, a.snoozedUntil ?: 0,
            URLEncoder.encode(a.label, "UTF-8"), if (a.smartWake) 1 else 0, a.smartRangAt ?: 0).joinToString("\t")
    }

    fun decode(text: String?): List<Alarm> = text.orEmpty().lines().mapNotNull { line ->
        val f = line.split("\t")
        if (f.size < 8) return@mapNotNull null
        runCatching {
            Alarm(
                id = f[0].toInt(), hour = f[1].toInt().coerceIn(0, 23), minute = f[2].toInt().coerceIn(0, 59),
                days = f[3].toInt() and Days.ALL, enabled = f[4] == "1",
                snoozeMinutes = f[5].toInt().coerceIn(1, 60), snoozedUntil = f[6].toLong().takeIf { it > 0 },
                label = URLDecoder.decode(f[7], "UTF-8"),
                // Fields 9 and 10 came with Smart Wake; older lines have 8.
                smartWake = f.getOrNull(8) == "1",
                smartRangAt = f.getOrNull(9)?.toLongOrNull()?.takeIf { it > 0 },
            )
        }.getOrNull()
    }
}

/** Smart Wake (docs/watch-ui/circa/bedtime.md): the window and the contract with WatchLink. */
object SmartWake {
    /** How early an alarm may ring. */
    const val WINDOW_MS = 30 * 60_000L

    /** A trigger later than this before the set time is ignored: the regular alarm is about to ring. */
    const val MIN_LEAD_MS = 60_000L

    /** To WatchLink: watch for light sleep until [EXTRA_DEADLINE], then fire [EXTRA_CALLBACK]. */
    const val ACTION_REQUEST = "org.circa.watchlink.action.SMART_WAKE"

    /** To WatchLink: stop watching (no extras). */
    const val ACTION_CANCEL = "org.circa.watchlink.action.SMART_WAKE_CANCEL"
    const val EXTRA_DEADLINE = "deadline"
    const val EXTRA_CALLBACK = "callback"
    const val WATCHLINK_PACKAGE = "org.circa.watchlink"
}
