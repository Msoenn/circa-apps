package org.circa.launcher.model

import java.time.LocalDateTime
import java.time.ZoneId
import org.circa.symbols.WxIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneComplicationsTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    private val now = ms(2026, 10, 4, 10, 0)
    private val hour = 3_600_000L

    private fun timed(title: String, start: Long, durH: Long = 1) = CalendarEvent(title, start, start + durH * hour, false)

    @Test fun iconGroups() {
        assertEquals(WxIcon.CLEAR, PhoneComplications.icon(800))
        assertEquals(WxIcon.PARTLY, PhoneComplications.icon(801))
        assertEquals(WxIcon.CLOUDS, PhoneComplications.icon(803))
        assertEquals(WxIcon.RAIN, PhoneComplications.icon(500))
        assertEquals(WxIcon.RAIN, PhoneComplications.icon(301))
        assertEquals(WxIcon.SNOW, PhoneComplications.icon(511))
        assertEquals(WxIcon.SNOW, PhoneComplications.icon(601))
        assertEquals(WxIcon.STORM, PhoneComplications.icon(212))
        assertEquals(WxIcon.FOG, PhoneComplications.icon(741))
        assertEquals(WxIcon.CLOUDS, PhoneComplications.icon(null))
    }

    @Test fun temperatureUnits() {
        assertEquals("20°", PhoneComplications.temp(20, false))
        assertEquals("68°", PhoneComplications.temp(20, true))
        assertEquals("-4°", PhoneComplications.temp(-4, false))
        assertEquals("25°", PhoneComplications.temp(-4, true))
        assertTrue(PhoneComplications.usesFahrenheit("us"))
        assertFalse(PhoneComplications.usesFahrenheit("DE"))
        assertFalse(PhoneComplications.usesFahrenheit(""))
        assertFalse(PhoneComplications.usesFahrenheit(null))
    }

    @Test fun weatherFreshStaleHidden() {
        val w = WeatherReading(updatedMs = now - hour, tempC = 20, code = 800)
        val fresh = PhoneComplications.weather(w, now, false)!!
        assertEquals("20°", fresh.text); assertFalse(fresh.stale); assertEquals(WxIcon.CLEAR, fresh.icon)
        // exactly 3 h is still fresh, one ms more is stale
        assertFalse(PhoneComplications.weather(w.copy(updatedMs = now - 3 * hour), now, false)!!.stale)
        val stale = PhoneComplications.weather(w.copy(updatedMs = now - 3 * hour - 1), now, true)!!
        assertTrue(stale.stale); assertEquals("68°", stale.text)
        assertTrue(stale.description.endsWith("out of date"))
        assertNull(PhoneComplications.weather(w.copy(updatedMs = now - 24 * hour - 1), now, false))
        assertNull(PhoneComplications.weather(null, now, false))
    }

    @Test fun nextEventTodayFutureAndNow() {
        val evs = listOf(
            timed("Past", now - 3 * hour), // ended
            timed("Later", ms(2026, 10, 4, 16, 30)),
            timed("Standup", ms(2026, 10, 4, 14, 30)),
        )
        val v = PhoneComplications.nextEvent(evs, now, zone, true)!!
        assertEquals("Standup", v.title); assertEquals("14:30", v.whenText)
        assertEquals("2:30 PM", PhoneComplications.nextEvent(evs, now, zone, false)!!.whenText)
        val ongoing = PhoneComplications.nextEvent(listOf(timed("Workshop", now - hour, 3)) + evs, now, zone, true)!!
        assertEquals("Workshop", ongoing.title); assertEquals("Now", ongoing.whenText)
    }

    @Test fun nextEventTomorrowOnlyWhenNothingToday() {
        val tomorrow = timed("Dentist", ms(2026, 10, 5, 9))
        assertEquals("Tmrw 09:00", PhoneComplications.nextEvent(listOf(tomorrow), now, zone, true)!!.whenText)
        val v12 = PhoneComplications.nextEvent(listOf(tomorrow), now, zone, false)!!
        assertEquals("Tmrw 9 AM", v12.whenText)
        assertEquals("Next event, Dentist, Tomorrow 9:00 AM", v12.description)
        assertEquals("Tmrw 9:30 AM", PhoneComplications.nextEvent(listOf(timed("X", ms(2026, 10, 5, 9, 30))), now, zone, false)!!.whenText)
        assertEquals("Tmrw 12 PM", PhoneComplications.nextEvent(listOf(timed("X", ms(2026, 10, 5, 12))), now, zone, false)!!.whenText)
        val today = timed("Lunch", ms(2026, 10, 4, 12))
        assertEquals("Lunch", PhoneComplications.nextEvent(listOf(tomorrow, today), now, zone, true)!!.title)
    }

    @Test fun nothingBeyondTomorrowOrEnded() {
        assertNull(PhoneComplications.nextEvent(listOf(timed("Far", ms(2026, 10, 6, 9))), now, zone, true))
        assertNull(PhoneComplications.nextEvent(listOf(timed("Old", now - 2 * hour)), now, zone, true))
        assertNull(PhoneComplications.nextEvent(emptyList(), now, zone, true))
    }

    @Test fun allDayHandling() {
        val utcDay = java.time.LocalDate.of(2026, 10, 4).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val holiday = CalendarEvent("Holiday", utcDay, utcDay + 24 * hour, true)
        val v = PhoneComplications.nextEvent(listOf(holiday), now, zone, true)!!
        assertEquals("All day", v.whenText)
        // a timed event later today wins over the all-day one
        assertEquals("Standup", PhoneComplications.nextEvent(listOf(holiday, timed("Standup", ms(2026, 10, 4, 15))), now, zone, true)!!.title)
        // all-day tomorrow is not shown
        val tmrw = CalendarEvent("Trip", utcDay + 24 * hour, utcDay + 48 * hour, true)
        assertNull(PhoneComplications.nextEvent(listOf(tmrw), now, zone, true))
        // all-day today beats a timed event tomorrow
        assertEquals("Holiday", PhoneComplications.nextEvent(listOf(timed("Dentist", ms(2026, 10, 5, 9)), holiday), now, zone, true)!!.title)
        // multi-day all-day covering today
        val multi = CalendarEvent("Conf", utcDay - 24 * hour, utcDay + 48 * hour, true)
        assertEquals("Conf", PhoneComplications.nextEvent(listOf(multi), now, zone, true)!!.title)
    }

    @Test fun titles() {
        assertEquals("Standup", PhoneComplications.shortTitle("  Standup \n"))
        val long = PhoneComplications.shortTitle("Quarterly planning review with everybody")
        assertEquals(PhoneComplications.TITLE_MAX_CHARS, long.length)
        assertTrue(long.endsWith("…"))
        assertEquals("a b", PhoneComplications.shortTitle("a   b"))
    }
}
