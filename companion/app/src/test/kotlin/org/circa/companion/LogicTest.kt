package org.circa.companion

import org.circa.companion.model.*
import org.circa.symbols.WxIcon
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class LogicTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val us = Locale.US
    private fun ms(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0, z: ZoneId = berlin) =
        LocalDate.of(y, m, d).atTime(h, min).atZone(z).toInstant().toEpochMilli()

    @Test fun codeToIcon() {
        assertEquals(WxIcon.STORM, Wx.icon(211))
        assertEquals(WxIcon.RAIN, Wx.icon(301))
        assertEquals(WxIcon.RAIN, Wx.icon(502))
        assertEquals(WxIcon.SNOW, Wx.icon(511))
        assertEquals(WxIcon.SNOW, Wx.icon(601))
        assertEquals(WxIcon.FOG, Wx.icon(741))
        assertEquals(WxIcon.CLEAR, Wx.icon(800))
        assertEquals(WxIcon.PARTLY, Wx.icon(801))
        assertEquals(WxIcon.PARTLY, Wx.icon(802))
        assertEquals(WxIcon.CLOUDS, Wx.icon(803))
        assertEquals(WxIcon.CLOUDS, Wx.icon(804))
        assertEquals(WxIcon.CLOUDS, Wx.icon(null))
        assertEquals(WxIcon.CLOUDS, Wx.icon(0))
    }

    @Test fun unitConversion() {
        assertEquals(32, Wx.convert(0, true))
        assertEquals(68, Wx.convert(20, true))
        assertEquals(14, Wx.convert(-10, true))
        assertEquals(20, Wx.convert(20, false))
        assertEquals("68°", Wx.temp(20, true))
        assertEquals("-3°", Wx.temp(-3, false))
        assertEquals("--", Wx.temp(null, false))
        assertTrue(Wx.usesFahrenheit("US"))
        assertTrue(Wx.usesFahrenheit("us"))
        assertFalse(Wx.usesFahrenheit("DE"))
        assertFalse(Wx.usesFahrenheit(""))
        assertFalse(Wx.usesFahrenheit(null))
    }

    @Test fun forecastParsing() {
        val json = """[{"day_ms":2000,"hi_c":20,"lo_c":10,"code":800,"rain_pct":5},{"day_ms":1000,"hi_c":22,"lo_c":11,"code":500},{"hi_c":1}]"""
        val f = Forecast.parse(json)
        assertEquals(2, f.size)
        assertEquals(1000L, f[0].dayMs)          // sorted by day
        assertNull(f[0].rainPct)
        assertEquals(5, f[1].rainPct)
        assertTrue(Forecast.parse(null).isEmpty())
        assertTrue(Forecast.parse("").isEmpty())
        assertTrue(Forecast.parse("not json").isEmpty())
        assertTrue(Forecast.parse("[]").isEmpty())
    }

    @Test fun forecastVisibleAndLabels() {
        val now = ms(2026, 10, 4, 15)
        val days = (0..8).map { ForecastDay(ms(2026, 10, 3 + it), 20, 10, 800, null) }  // starts yesterday
        val vis = Forecast.visible(days, now, berlin, max = 7)
        assertEquals(7, vis.size)
        assertEquals("Today", Forecast.label(vis[0].dayMs, now, berlin, us))
        assertEquals("Mon", Forecast.label(vis[1].dayMs, now, berlin, us))
        assertEquals("Tue", Forecast.label(vis[2].dayMs, now, berlin, us))  // 2026-10-06 is a Tuesday
    }

    @Test fun ageText() {
        val now = 10_000_000_000L
        assertEquals("Just now", Fmt.age(now - 30_000, now))
        assertEquals("Just now", Fmt.age(now + 5_000, now))
        assertEquals("1 min ago", Fmt.age(now - 60_000, now))
        assertEquals("59 min ago", Fmt.age(now - 59 * 60_000, now))
        assertEquals("1 h ago", Fmt.age(now - 60 * 60_000, now))
        assertEquals("5 h ago", Fmt.age(now - 5 * 3_600_000L - 1, now))
        assertEquals("2 d ago", Fmt.age(now - 49 * 3_600_000L, now))
    }

    @Test fun musicStaleness() {
        val now = 1_000_000_000L
        assertTrue(Music.isStale(null, "play", now))
        assertTrue(Music.isStale(0, "play", now))
        assertFalse(Music.isStale(now - 60_000, "pause", now))
        assertTrue(Music.isStale(now - 11 * 60_000, "pause", now))
        assertTrue(Music.isStale(now - 11 * 60_000, "stop", now))
        assertFalse(Music.isStale(now - 11 * 60_000, "play", now))   // playing is never stale
        assertFalse(Music.isStale(now - Music.STALE_MS, "pause", now))
    }

    @Test fun positionExtrapolation() {
        assertEquals(12, Music.position(12, 1000, 5000, 300, playing = false))
        assertEquals(16, Music.position(12, 1000, 5000, 300, playing = true))
        assertEquals(290, Music.position(290, 0, 0, 300, playing = true))  // no anchor time: no extrapolation
        assertEquals(300, Music.position(290, 1000, 100_000, 300, playing = true))  // capped at duration
        assertEquals(400, Music.position(400, 0, 0, 0, playing = true))     // unknown duration, no anchor
        assertEquals(0.5f, Music.progress(150, 300)!!, 0.001f)
        assertNull(Music.progress(5, 0))
        assertEquals(1f, Music.progress(400, 300)!!, 0f)
    }

    @Test fun timeFormatting() {
        val s = ms(2026, 10, 4, 14, 0); val e = ms(2026, 10, 4, 15, 30)
        assertEquals("14:00", Fmt.clock(s, berlin, true, us))
        assertEquals("2:00 PM", Fmt.clock(s, berlin, false, us))
        assertEquals("14:00 – 15:30", Fmt.range(s, e, false, berlin, true, us))
        assertEquals("2:00 – 3:30 PM", Fmt.range(s, e, false, berlin, false, us))
        assertEquals("11:00 AM – 1:00 PM", Fmt.range(ms(2026, 10, 4, 11), ms(2026, 10, 4, 13), false, berlin, false, us))
        assertEquals("14:00", Fmt.range(s, s, false, berlin, true, us))
        assertEquals("All day", Fmt.range(s, e, true, berlin, true, us))
        assertEquals("22:00 – Mon 02:00", Fmt.range(ms(2026, 10, 4, 22), ms(2026, 10, 5, 2), false, berlin, true, us))
        assertEquals("3:05", Fmt.duration(185))
        assertEquals("1:01:01", Fmt.duration(3661))
        val today = LocalDate.of(2026, 10, 4)
        assertEquals("Today", Fmt.dayHeader(today, today, us))
        assertEquals("Tomorrow", Fmt.dayHeader(today.plusDays(1), today, us))
        assertEquals("Tue 6 Oct", Fmt.dayHeader(today.plusDays(2), today, us))
    }

    private fun ev(id: Long, title: String, start: Long, end: Long = start + 3_600_000, allDay: Boolean = false) =
        CalEvent(id, title, start, end, allDay, null, "Work", 0)

    @Test fun dayGrouping() {
        val now = ms(2026, 10, 4, 12)  // Sunday noon
        val utcDay = ms(2026, 10, 4, z = ZoneOffset.UTC)   // all-day: UTC midnight
        val events = listOf(
            ev(1, "Late today", ms(2026, 10, 4, 18)),
            ev(2, "Ended", ms(2026, 10, 4, 8), ms(2026, 10, 4, 9)),            // dropped
            ev(3, "Birthday", utcDay, utcDay + Agenda.DAY_MS, allDay = true),   // today, first
            ev(4, "Tomorrow early", ms(2026, 10, 5, 7)),
            ev(5, "Tomorrow early", ms(2026, 10, 5, 7, 0)),
            ev(6, "Day 7", ms(2026, 10, 11, 9)),                                // last day shown
            ev(7, "Day 8", ms(2026, 10, 12, 9)),                                // outside
            ev(8, "Running", ms(2026, 10, 3, 22), ms(2026, 10, 4, 14)),         // started yesterday, still on -> today
            ev(9, "Trip", utcDay + Agenda.DAY_MS, utcDay + 3 * Agenda.DAY_MS, allDay = true),  // 5th and 6th
        )
        val g = Agenda.group(events, now, berlin)
        assertEquals(listOf("2026-10-04", "2026-10-05", "2026-10-06", "2026-10-11"), g.map { it.date.toString() })
        assertEquals(listOf("Birthday", "Running", "Late today"), g[0].events.map { it.title })
        assertEquals(listOf("Trip", "Tomorrow early", "Tomorrow early"), g[1].events.map { it.title })
        assertEquals(listOf("Trip"), g[2].events.map { it.title })
        assertEquals(listOf("Day 7"), g[3].events.map { it.title })
    }

    @Test fun allDayIsReadAsUtcDateOrLocal() {
        // UTC midnight: the date is the UTC date even in a zone ahead of UTC.
        val utc = ms(2026, 10, 4, z = ZoneOffset.UTC)
        assertEquals(LocalDate.of(2026, 10, 4) to LocalDate.of(2026, 10, 4), Agenda.allDaySpan(utc, utc + Agenda.DAY_MS, berlin))
        // Already shifted to local midnight: local date.
        val local = ms(2026, 10, 4)
        assertEquals(LocalDate.of(2026, 10, 4) to LocalDate.of(2026, 10, 4), Agenda.allDaySpan(local, local + Agenda.DAY_MS, berlin))
        // A past all-day event is not shown.
        val past = ms(2026, 10, 2, z = ZoneOffset.UTC)
        assertTrue(Agenda.group(listOf(ev(1, "Old", past, past + Agenda.DAY_MS, true)), ms(2026, 10, 4, 12), berlin).isEmpty())
        assertTrue(Agenda.group(emptyList(), 0, berlin).isEmpty())
    }
}
