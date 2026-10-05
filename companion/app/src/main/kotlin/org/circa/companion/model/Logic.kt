package org.circa.companion.model

import org.json.JSONArray
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.circa.symbols.WxIcon

/** Pure logic of the Circa Companion apps (no Android types, so it is unit-tested on the JVM). */

// ---- weather ---------------------------------------------------------------------------------------------

object Wx {
    /** OpenWeatherMap condition code -> icon. Groups: 2xx thunderstorm, 3xx drizzle, 5xx rain, 6xx snow, 7xx atmosphere, 800 clear, 80x clouds. */
    fun icon(code: Int?): WxIcon = when (code) {
        null -> WxIcon.CLOUDS
        in 200..299 -> WxIcon.STORM
        in 300..399 -> WxIcon.RAIN
        511 -> WxIcon.SNOW // freezing rain
        in 500..599 -> WxIcon.RAIN
        in 600..699 -> WxIcon.SNOW
        in 700..799 -> WxIcon.FOG
        800 -> WxIcon.CLEAR
        801, 802 -> WxIcon.PARTLY
        in 803..899 -> WxIcon.CLOUDS
        else -> WxIcon.CLOUDS
    }

    fun fallbackText(icon: WxIcon): String = when (icon) {
        WxIcon.CLEAR -> "Clear"
        WxIcon.PARTLY -> "Partly cloudy"
        WxIcon.CLOUDS -> "Cloudy"
        WxIcon.RAIN -> "Rain"
        WxIcon.STORM -> "Thunderstorm"
        WxIcon.SNOW -> "Snow"
        WxIcon.FOG -> "Fog"
    }

    private val FAHRENHEIT_COUNTRIES = setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW", "FM", "MH", "PR", "GU", "VI", "AS")

    /** Countries that use Fahrenheit; everywhere else (and an empty country) uses Celsius. */
    fun usesFahrenheit(country: String?): Boolean = country?.uppercase(Locale.ROOT) in FAHRENHEIT_COUNTRIES

    /** Whole degrees in the display unit, rounded half up. */
    fun convert(celsius: Int, fahrenheit: Boolean): Int =
        if (fahrenheit) Math.round(celsius * 9f / 5f + 32f) else celsius

    /** "21°" (the unit letter is left out like stock Wear); null -> "--". */
    fun temp(celsius: Int?, fahrenheit: Boolean): String =
        if (celsius == null) "--" else "${convert(celsius, fahrenheit)}°"
}

data class ForecastDay(val dayMs: Long, val hiC: Int, val loC: Int, val code: Int, val rainPct: Int?)

object Forecast {
    /** Parses WatchLink's `forecast_json` (`[{"day_ms","hi_c","lo_c","code","rain_pct"}]`); bad input gives an empty list, bad entries are skipped. */
    fun parse(json: String?): List<ForecastDay> {
        if (json.isNullOrBlank()) return emptyList()
        val arr = try { JSONArray(json) } catch (_: Exception) { return emptyList() }
        val out = ArrayList<ForecastDay>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!o.has("day_ms") || !o.has("hi_c") || !o.has("lo_c")) continue
            out += ForecastDay(
                dayMs = o.optLong("day_ms"),
                hiC = o.optInt("hi_c"),
                loC = o.optInt("lo_c"),
                code = o.optInt("code", 0),
                rainPct = if (o.has("rain_pct") && !o.isNull("rain_pct")) o.optInt("rain_pct") else null,
            )
        }
        return out.sortedBy { it.dayMs }
    }

    /**
     * Days to show: today and later, at most [max]. The contract says the list starts tomorrow, but the day is
     * taken from `day_ms` (not the list index), so a producer that sends today's value first is labelled "Today".
     */
    fun visible(days: List<ForecastDay>, nowMs: Long, zone: ZoneId, max: Int = 7): List<ForecastDay> {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return days.filter { dateOf(it.dayMs, zone) >= today }.take(max)
    }

    fun dateOf(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    /** "Today", otherwise the short weekday ("Tue"); "Tomorrow" would not fit the forecast row. */
    fun label(dayMs: Long, nowMs: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): String {
        val today = dateOf(nowMs, zone)
        val d = dateOf(dayMs, zone)
        return when (d) {
            today -> "Today"
            else -> d.format(DateTimeFormatter.ofPattern("EEE", locale))
        }
    }
}

// ---- time / staleness ------------------------------------------------------------------------------------

object Fmt {
    /** "Just now", "5 min ago", "3 h ago", "2 d ago". A time in the future counts as just now. */
    fun age(thenMs: Long, nowMs: Long): String {
        val min = (nowMs - thenMs) / 60_000
        return when {
            min < 1 -> "Just now"
            min < 60 -> "$min min ago"
            min < 24 * 60 -> "${min / 60} h ago"
            else -> "${min / (24 * 60)} d ago"
        }
    }

    fun clock(ms: Long, zone: ZoneId, is24h: Boolean, locale: Locale = Locale.getDefault()): String =
        Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm a", locale))

    /**
     * Time range of an event: "All day", "14:00 – 15:30", "2:00 – 3:30 PM" (shared AM/PM written once),
     * "14:00 – Tue 09:00" when it ends on another day, "14:00" when there is no end.
     */
    fun range(startMs: Long, endMs: Long, allDay: Boolean, zone: ZoneId, is24h: Boolean, locale: Locale = Locale.getDefault()): String {
        if (allDay) return "All day"
        if (endMs <= startMs) return clock(startMs, zone, is24h, locale)
        val s = Instant.ofEpochMilli(startMs).atZone(zone)
        val e = Instant.ofEpochMilli(endMs).atZone(zone)
        if (s.toLocalDate() != e.toLocalDate()) {
            val day = e.format(DateTimeFormatter.ofPattern("EEE", locale))
            return "${clock(startMs, zone, is24h, locale)} – $day ${clock(endMs, zone, is24h, locale)}"
        }
        if (is24h) return "${clock(startMs, zone, true, locale)} – ${clock(endMs, zone, true, locale)}"
        val f = DateTimeFormatter.ofPattern("h:mm", locale)
        val ampm = DateTimeFormatter.ofPattern("a", locale)
        return if (s.format(ampm) == e.format(ampm)) "${s.format(f)} – ${e.format(f)} ${e.format(ampm)}"
        else "${s.format(f)} ${s.format(ampm)} – ${e.format(f)} ${e.format(ampm)}"
    }

    /** "Today", "Tomorrow", "Wed 8 Oct". */
    fun dayHeader(date: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String = when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> date.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))
    }

    /** m:ss, or h:mm:ss from an hour on. */
    fun duration(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}

object Music {
    const val STALE_MS = 10 * 60 * 1000L

    /** The contract: "Nothing playing" when `updated_ms` is older than 10 minutes and the state isn't `play` (a missing row is stale, too). */
    fun isStale(updatedMs: Long?, state: String?, nowMs: Long): Boolean {
        if (updatedMs == null || updatedMs <= 0) return true
        return state != "play" && nowMs - updatedMs > STALE_MS
    }

    /** Playback position now: the reported one plus the elapsed time while playing, capped at the duration (when known). */
    fun position(positionS: Int, positionAtMs: Long, nowMs: Long, durationS: Int, playing: Boolean): Int {
        var p = positionS.coerceAtLeast(0)
        if (playing && positionAtMs > 0 && nowMs > positionAtMs) p += ((nowMs - positionAtMs) / 1000).toInt()
        return if (durationS > 0) p.coerceAtMost(durationS) else p
    }

    /** 0..1, or null when the duration is unknown. */
    fun progress(positionS: Int, durationS: Int): Float? =
        if (durationS <= 0) null else (positionS.toFloat() / durationS).coerceIn(0f, 1f)
}

// ---- agenda ----------------------------------------------------------------------------------------------

data class CalEvent(
    val id: Long, val title: String, val startMs: Long, val endMs: Long, val allDay: Boolean,
    val location: String?, val calendar: String?, val color: Int,
)

data class DayGroup(val date: LocalDate, val events: List<CalEvent>)

object Agenda {
    const val DAY_MS = 86_400_000L

    /**
     * The first and last (inclusive) day an all-day event covers. Calendar providers store all-day events at UTC
     * midnight; Gadgetbridge forwards that, so a start that is an exact multiple of 24 h is read as a UTC date.
     * Any other start is read as local time (a producer that already shifted it to local midnight).
     */
    fun allDaySpan(startMs: Long, endMs: Long, zone: ZoneId): Pair<LocalDate, LocalDate> {
        val utc = startMs % DAY_MS == 0L
        val z: ZoneId = if (utc) ZoneOffset.UTC else zone
        val first = Instant.ofEpochMilli(startMs).atZone(z).toLocalDate()
        val last = if (endMs > startMs) Instant.ofEpochMilli(endMs - 1).atZone(z).toLocalDate() else first
        return first to maxOf(first, last)
    }

    /**
     * Today and the following days ([days] in all, default 8 = today + 7), only days that have events. Per day the
     * all-day events come first, then by start time, then by title. Timed events that already ended are dropped;
     * a timed event that started before today but still runs is listed under today; a multi-day all-day event
     * is repeated on each of its days.
     */
    fun group(events: List<CalEvent>, nowMs: Long, zone: ZoneId, days: Int = 8): List<DayGroup> {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val last = today.plusDays((days - 1).toLong())
        val byDay = sortedMapOf<LocalDate, MutableList<CalEvent>>()
        for (ev in events) {
            if (ev.allDay) {
                val (first, lastDay) = allDaySpan(ev.startMs, ev.endMs, zone)
                var d = maxOf(first, today)
                while (d <= minOf(lastDay, last)) { byDay.getOrPut(d) { mutableListOf() } += ev; d = d.plusDays(1) }
            } else {
                if (maxOf(ev.endMs, ev.startMs) < nowMs) continue
                val start = Instant.ofEpochMilli(ev.startMs).atZone(zone).toLocalDate()
                val d = maxOf(start, today)
                if (d <= last) byDay.getOrPut(d) { mutableListOf() } += ev
            }
        }
        val order = compareBy<CalEvent>({ !it.allDay }, { it.startMs }, { it.title })
        return byDay.map { (d, l) -> DayGroup(d, l.sortedWith(order)) }
    }
}
