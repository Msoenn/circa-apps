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
                val at = ZonedDateTime.of(LocalDateTime.of(date, time), zone)
                if (at.toInstant().toEpochMilli() > nowMillis) return at.toInstant().toEpochMilli()
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
            URLEncoder.encode(a.label, "UTF-8")).joinToString("\t")
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
            )
        }.getOrNull()
    }
}
