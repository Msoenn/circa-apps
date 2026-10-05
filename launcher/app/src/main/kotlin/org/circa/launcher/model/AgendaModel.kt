package org.circa.launcher.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One row of the agenda tile: a calendar colour dot, the start time and the title. */
data class AgendaTileItem(
    val title: String,
    val timeText: String,
    val color: Int,
    val allDay: Boolean,
    val description: String,
)

/**
 * What the agenda tile draws: a day header, up to [AgendaModel.MAX_ITEMS] events, and how many
 * events of that day did not fit ([moreCount], drawn as the "+N more" line).
 */
data class AgendaTileView(val header: String, val items: List<AgendaTileItem>, val moreCount: Int)

/**
 * Selection and formatting rules for the agenda tile. Pure and JVM-testable: no Android types,
 * java.time only. Same all-day rule as Circa Companion's Agenda (and the face's next-event
 * complication), so all three agree. The data contract is watchlink/DATA-CONTRACT.md.
 */
object AgendaModel {
    /** At most this many events fit the tile under the header. */
    const val MAX_ITEMS = 2

    /** Header when the tile could only find tomorrow's events. */
    const val TOMORROW = "Tomorrow"

    /** Start-time text of a timed event that is running now. */
    const val NOW = "Now"

    /**
     * What the tile shows now: today's upcoming events under "Today", or - when today has none left -
     * tomorrow's under "Tomorrow". Null when neither day has an event (the "No upcoming events" state).
     * All-day events come first, then timed events by start time; only the first [MAX_ITEMS] are drawn,
     * the rest counted in [AgendaTileView.moreCount].
     */
    fun view(events: List<CalendarEvent>, nowMs: Long, zone: ZoneId, is24Hour: Boolean): AgendaTileView? {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        todayItems(events, today, nowMs, zone, is24Hour).takeIf { it.isNotEmpty() }
            ?.let { return tileView("Today", it) }
        val tomorrow = today.plusDays(1)
        todayItems(events, tomorrow, nowMs, zone, is24Hour).takeIf { it.isNotEmpty() }
            ?.let { return tileView(TOMORROW, it) }
        return null
    }

    /** Cap [items] at [MAX_ITEMS] and count the leftover events for the "+N more" line. */
    private fun tileView(header: String, items: List<AgendaTileItem>): AgendaTileView =
        AgendaTileView(header, items.take(MAX_ITEMS), (items.size - MAX_ITEMS).coerceAtLeast(0))

    /** The events the tile lists for [day]: all-day events covering it, then timed events. */
    private fun todayItems(
        events: List<CalendarEvent>,
        day: LocalDate,
        nowMs: Long,
        zone: ZoneId,
        is24Hour: Boolean,
    ): List<AgendaTileItem> {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val allDay = events.filter { it.allDay }
            .filter { day in allDaySpan(it, zone) }
            .sortedBy { it.startMs }
            .map { item(it, "All day", is24Hour, zone) }
        val timed = events.filter { !it.allDay }
            .filter { maxOf(it.endMs, it.startMs) > nowMs }
            .filter { dayOf(it.startMs, zone).coerceAtLeast(today) == day }
            .sortedBy { it.startMs }
            .map { item(it, timeText(it, nowMs, zone, is24Hour), is24Hour, zone) }
        return allDay + timed
    }

    /** "Now" while a timed event runs, otherwise its start time ("6:33 PM" / "18:33"). */
    private fun timeText(ev: CalendarEvent, nowMs: Long, zone: ZoneId, is24Hour: Boolean): String =
        if (ev.startMs <= nowMs) NOW
        else ComplicationFormat.formatTime(Instant.ofEpochMilli(ev.startMs).atZone(zone).toLocalTime(), is24Hour)

    private fun item(ev: CalendarEvent, timeText: String, is24Hour: Boolean, zone: ZoneId): AgendaTileItem {
        val title = ev.title.trim().replace(Regex("\\s+"), " ").ifBlank { "(No title)" }
        return AgendaTileItem(
            title = title,
            timeText = timeText,
            color = ev.color,
            allDay = ev.allDay,
            description = "$title, $timeText",
        )
    }

    /** The first and last (inclusive) day an all-day event covers; same rule as the face complication. */
    private fun allDaySpan(ev: CalendarEvent, zone: ZoneId): ClosedRange<LocalDate> {
        val (first, last) = PhoneComplications.allDaySpan(ev.startMs, ev.endMs, zone)
        return first..last
    }

    private fun dayOf(ms: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
}
