package org.circa.launcher.model

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgendaModelTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    private val now = ms(2026, 10, 4, 10, 0)
    private val hour = 3_600_000L

    private fun timed(title: String, start: Long, durH: Long = 1, color: Int = 0) =
        CalendarEvent(title, start, start + durH * hour, false, color)

    private fun allDay(title: String, y: Int, mo: Int, d: Int, color: Int = 0) =
        CalendarEvent(title, ms(y, mo, d, 0, 0), ms(y, mo, d, 0, 0) + 24 * hour, true, color)

    @Test fun todayAllDayFirstThenTimedByStartWithOverflowCounted() {
        val view = AgendaModel.view(
            listOf(
                timed("Late", ms(2026, 10, 4, 15, 0)),
                allDay("Holiday", 2026, 10, 4, 0xFF00FF00.toInt()),
                timed("Soon", ms(2026, 10, 4, 11, 0)),
            ),
            now, zone, is24Hour = true,
        )!!
        assertEquals("Today", view.header)
        assertEquals(listOf("Holiday", "Soon"), view.items.map { it.title })
        assertEquals(listOf(true, false), view.items.map { it.allDay })
        assertEquals("All day", view.items[0].timeText)
        assertEquals(0xFF00FF00.toInt(), view.items[0].color)
        assertEquals(1, view.moreCount)
    }

    @Test fun cappedAtTwoAndCountsTheRest() {
        val events = (11..15).map { timed("E$it", ms(2026, 10, 4, it, 0)) }
        val view = AgendaModel.view(events, now, zone, is24Hour = true)!!
        assertEquals(2, view.items.size)
        assertEquals(listOf("E11", "E12"), view.items.map { it.title })
        assertEquals(3, view.moreCount)
    }

    @Test fun twoEventsLeaveNoMoreLine() {
        val events = (11..12).map { timed("E$it", ms(2026, 10, 4, it, 0)) }
        val view = AgendaModel.view(events, now, zone, is24Hour = true)!!
        assertEquals(2, view.items.size)
        assertEquals(0, view.moreCount)
    }

    @Test fun anOngoingTimedEventShowsNow() {
        // 09:00-11:00, started before `now` (10:00) but not ended.
        val view = AgendaModel.view(listOf(timed("Standup", ms(2026, 10, 4, 9, 0), durH = 2)), now, zone, true)!!
        assertEquals("Today", view.header)
        assertEquals(listOf("Standup"), view.items.map { it.title })
        assertEquals("Now", view.items[0].timeText)
    }

    @Test fun nothingLeftTodayFallsBackToTomorrow() {
        val view = AgendaModel.view(
            listOf(
                timed("Over", ms(2026, 10, 4, 9, 0)),       // ended at 10:00
                timed("Tomorrow", ms(2026, 10, 5, 9, 0)),
            ),
            now, zone, is24Hour = true,
        )!!
        assertEquals("Tomorrow", view.header)
        assertEquals(listOf("Tomorrow"), view.items.map { it.title })
        assertEquals("09:00", view.items[0].timeText)
        assertEquals(0, view.moreCount)
    }

    @Test fun tomorrowOverflowIsCounted() {
        val view = AgendaModel.view(
            listOf(
                timed("E1", ms(2026, 10, 5, 9, 0)),
                timed("E2", ms(2026, 10, 5, 10, 0)),
                timed("E3", ms(2026, 10, 5, 11, 0)),
            ),
            now, zone, is24Hour = true,
        )!!
        assertEquals("Tomorrow", view.header)
        assertEquals(listOf("E1", "E2"), view.items.map { it.title })
        assertEquals(1, view.moreCount)
    }

    @Test fun beyondTomorrowIsIgnored() {
        assertNull(AgendaModel.view(listOf(timed("Next week", ms(2026, 10, 8, 9, 0))), now, zone, true))
    }

    @Test fun emptyIsNull() {
        assertNull(AgendaModel.view(emptyList(), now, zone, true))
    }

    @Test fun twelveHourFormatting() {
        val view = AgendaModel.view(listOf(timed("Call", ms(2026, 10, 4, 14, 30))), now, zone, is24Hour = false)!!
        assertEquals("2:30 PM", view.items[0].timeText)
        assertTrue(view.items[0].description.contains("Call"))
        assertTrue(view.items[0].description.contains("2:30 PM"))
    }

    @Test fun overnightEventBelongsToToday() {
        // Started yesterday 23:00, runs until today 12:00 -> still upcoming, listed under Today.
        val view = AgendaModel.view(listOf(timed("Night shift", ms(2026, 10, 3, 23, 0), durH = 13)), now, zone, true)!!
        assertEquals("Today", view.header)
        assertEquals(listOf("Night shift"), view.items.map { it.title })
        assertEquals("Now", view.items[0].timeText)
    }

    @Test fun blankTitleFallsBack() {
        val view = AgendaModel.view(listOf(timed("   ", ms(2026, 10, 4, 12, 0))), now, zone, true)!!
        assertEquals("(No title)", view.items[0].title)
    }
}
