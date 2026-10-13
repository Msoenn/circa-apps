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

    @Test fun uvScale() {
        assertNull(Wx.uvIndex(null))
        assertEquals(3, Wx.uvIndex(3.4f))
        assertEquals(4, Wx.uvIndex(3.5f))     // half rounds up, the safer band
        assertEquals(0, Wx.uvIndex(0.4f))
        assertEquals("Low", Wx.uvBand(0)); assertEquals("Low", Wx.uvBand(2))
        assertEquals("Moderate", Wx.uvBand(3)); assertEquals("Moderate", Wx.uvBand(5))
        assertEquals("High", Wx.uvBand(6)); assertEquals("High", Wx.uvBand(7))
        assertEquals("Very high", Wx.uvBand(8)); assertEquals("Very high", Wx.uvBand(10))
        assertEquals("Extreme", Wx.uvBand(11)); assertEquals("Extreme", Wx.uvBand(20))
        assertEquals(0xFF4CAF50.toInt(), Wx.uvColorArgb(0))
        assertEquals(0xFF4CAF50.toInt(), Wx.uvColorArgb(2))
        assertEquals(0xFFFDD835.toInt(), Wx.uvColorArgb(3))
        assertEquals(0xFFFB8C00.toInt(), Wx.uvColorArgb(6))
        assertEquals(0xFFE53935.toInt(), Wx.uvColorArgb(8))
        assertEquals(0xFFAB47BC.toInt(), Wx.uvColorArgb(11))
        assertNull(Wx.uvBadge(null))
        assertEquals("UV 3" to 0xFFFDD835.toInt(), Wx.uvBadge(3.4f))
        assertEquals("UV 0" to 0xFF4CAF50.toInt(), Wx.uvBadge(0f))
        assertEquals("3 · Moderate", Wx.uvDetail(3))
        assertEquals("11 · Extreme", Wx.uvDetail(11))
    }

    @Test fun compassAndWind() {
        assertNull(Wx.compass(null))
        assertEquals("N", Wx.compass(0)); assertEquals("N", Wx.compass(22)); assertEquals("NE", Wx.compass(23))
        assertEquals("NE", Wx.compass(45)); assertEquals("E", Wx.compass(90)); assertEquals("SE", Wx.compass(135))
        assertEquals("S", Wx.compass(180)); assertEquals("SW", Wx.compass(225)); assertEquals("W", Wx.compass(270))
        assertEquals("NW", Wx.compass(315)); assertEquals("N", Wx.compass(359)); assertEquals("N", Wx.compass(360))
        assertEquals("N", Wx.compass(-1))
        assertNull(Wx.wind(null, 270, false))
        assertEquals("19 km/h NW", Wx.wind(19.3f, 315, false))
        assertEquals("12 mph NW", Wx.wind(19.3f, 315, true))
        assertEquals("19 km/h", Wx.wind(19.3f, null, false))
    }

    @Test fun detailUnits() {
        assertNull(Wx.percent(null))
        assertEquals("85%", Wx.percent(85))
        assertEquals("0%", Wx.percent(0))
        assertNull(Wx.pressure(null, false))
        assertEquals("1012 hPa", Wx.pressure(1012f, false))
        assertEquals("29.88 inHg", Wx.pressure(1012f, true))
        assertNull(Wx.visibility(null, false))
        assertEquals("16 km", Wx.visibility(16000f, false))
        assertEquals("0.5 km", Wx.visibility(500f, false))
        assertEquals("10 mi", Wx.visibility(16093.5f, true))
        assertEquals("9.9 mi", Wx.visibility(15900f, true))
        assertEquals("0.5 mi", Wx.visibility(800f, true))
    }

    @Test fun detailRows() {
        val sun = ms(2026, 10, 5, 7, 12)
        val set = ms(2026, 10, 5, 18, 40)
        val all = DetailValues(
            feelsC = 15, windKmh = 19.3f, windDir = 315, humidity = 72, rainPct = 80, uv = 3.4f,
            sunriseMs = sun, sunsetMs = set, pressureHpa = 1012f, visibilityM = 16000f, dewC = 9, cloudPct = 90,
        )
        val rows = Details.rows(all, fahrenheit = false, zone = berlin, is24h = false, locale = us)
        assertEquals(11, rows.size)
        assertEquals(
            listOf("Feels like", "Wind", "Humidity", "Chance of rain", "UV index", "Sunrise", "Sunset", "Pressure",
                "Visibility", "Dew point", "Cloud cover"),
            rows.map { it.label },
        )
        assertEquals(
            listOf(DetailIcon.THERMOSTAT, DetailIcon.WIND, DetailIcon.HUMIDITY, DetailIcon.RAIN, DetailIcon.UV,
                DetailIcon.SUNRISE, DetailIcon.SUNSET, DetailIcon.PRESSURE, DetailIcon.VISIBILITY, DetailIcon.DEW_POINT,
                DetailIcon.CLOUD),
            rows.map { it.icon },
        )
        assertEquals("15°", rows[0].value)
        assertEquals("19 km/h NW", rows[1].value)
        assertEquals("72%", rows[2].value)
        assertEquals("80%", rows[3].value)
        assertEquals("3 · Moderate", rows[4].value)
        assertEquals(0xFFFDD835.toInt(), rows[4].valueArgb)
        assertEquals("7:12 AM", rows[5].value)
        assertEquals("6:40 PM", rows[6].value)
        assertEquals("1012 hPa", rows[7].value)
        assertEquals("16 km", rows[8].value)
        assertEquals("9°", rows[9].value)
        assertEquals("90%", rows[10].value)
        assertTrue(rows.all { it.valueArgb == null || it.icon == DetailIcon.UV })
        // the same values in °F with a 24 h clock
        val imperial = Details.rows(all, fahrenheit = true, zone = berlin, is24h = true, locale = us)
        assertEquals("59°", imperial[0].value)
        assertEquals("12 mph NW", imperial[1].value)
        assertEquals("07:12", imperial[5].value)
        assertEquals("18:40", imperial[6].value)
        assertEquals("29.88 inHg", imperial[7].value)
        assertEquals("9.9 mi", imperial[8].value)
        // unknown values have no row
        val only = Details.rows(
            DetailValues(null, null, null, 55, null, null, null, null, null, null, null, null),
            fahrenheit = false, zone = berlin, is24h = true, locale = us,
        )
        assertEquals(listOf("Humidity"), only.map { it.label })
        assertEquals(DetailIcon.HUMIDITY, only[0].icon)
        assertTrue(
            Details.rows(
                DetailValues(null, null, null, null, null, null, null, null, null, null, null, null),
                fahrenheit = false, zone = berlin, is24h = true, locale = us,
            ).isEmpty(),
        )
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
        assertEquals("just now", Fmt.age(now - 30_000, now))
        assertEquals("just now", Fmt.age(now + 5_000, now))
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
