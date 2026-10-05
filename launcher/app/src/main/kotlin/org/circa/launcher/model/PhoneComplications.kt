package org.circa.launcher.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.circa.symbols.WxIcon

/** One `/weather` row, reduced to what the face needs. */
data class WeatherReading(val updatedMs: Long, val tempC: Int, val code: Int)

/** One `/calendar` row, reduced to what the face and the agenda tile need. `color` is an ARGB int (0 = unknown). */
data class CalendarEvent(
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val allDay: Boolean,
    val color: Int = 0,
)

/** What the weather complication draws: glyph + "21°", dimmed when stale. */
data class WeatherView(val icon: WxIcon, val text: String, val stale: Boolean, val description: String)

/** What the next-event complication draws: a short title, then "14:30" / "Now" / "All day" / "Tmrw 9 AM". */
data class EventView(val title: String, val whenText: String, val description: String)

/**
 * Selection and formatting rules for the phone-fed face complications (weather, next event). Pure and
 * JVM-testable: no Android types, java.time only. The contract for the data is
 * watchlink/DATA-CONTRACT.md.
 */
object PhoneComplications {
    /** Weather older than this is drawn dimmed. */
    const val WEATHER_STALE_MS = 3 * 60 * 60 * 1000L

    /** Weather older than this is hidden: a day-old temperature on the face would mislead. */
    const val WEATHER_HIDE_MS = 24 * 60 * 60 * 1000L

    const val TITLE_MAX_CHARS = 18

    private val FAHRENHEIT_COUNTRIES = setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW", "FM", "MH", "PR", "GU", "VI", "AS")

    /** Same list as Circa Companion's Weather, so the face and the app agree. */
    fun usesFahrenheit(country: String?): Boolean = country?.uppercase(Locale.ROOT) in FAHRENHEIT_COUNTRIES

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
        else -> WxIcon.CLOUDS
    }

    private fun iconName(icon: WxIcon) = when (icon) {
        WxIcon.CLEAR -> "Clear"
        WxIcon.PARTLY -> "Partly cloudy"
        WxIcon.CLOUDS -> "Cloudy"
        WxIcon.RAIN -> "Rain"
        WxIcon.STORM -> "Thunderstorm"
        WxIcon.SNOW -> "Snow"
        WxIcon.FOG -> "Fog"
    }

    /** "21°" in the display unit (whole degrees, rounded half up); the unit letter is left out like stock Wear. */
    fun temp(celsius: Int, fahrenheit: Boolean): String =
        "${if (fahrenheit) Math.round(celsius * 9f / 5f + 32f) else celsius}°"

    /** The weather complication, or null (hide it) when there is no reading or it is more than a day old. */
    fun weather(w: WeatherReading?, nowMs: Long, fahrenheit: Boolean): WeatherView? {
        if (w == null) return null
        val age = nowMs - w.updatedMs
        if (age > WEATHER_HIDE_MS) return null
        val icon = icon(w.code)
        val text = temp(w.tempC, fahrenheit)
        val stale = age > WEATHER_STALE_MS
        return WeatherView(icon, text, stale, "Weather, ${iconName(icon)}, $text" + if (stale) ", out of date" else "")
    }

    /** Clip to [max] characters with an ellipsis; whitespace runs collapse. */
    fun shortTitle(title: String, max: Int = TITLE_MAX_CHARS): String {
        val t = title.trim().replace(Regex("\\s+"), " ")
        return if (t.length <= max) t else t.substring(0, max - 1).trimEnd() + "…"
    }

    /**
     * Calendar providers (and Gadgetbridge) store all-day events at UTC midnight: a start that is an exact
     * multiple of 24 h is read as a UTC date, any other start as local time (same rule as Circa Companion's Agenda).
     * Returns the first and last (inclusive) day the event covers.
     */
    fun allDaySpan(startMs: Long, endMs: Long, zone: ZoneId): Pair<LocalDate, LocalDate> {
        val z: ZoneId = if (startMs % 86_400_000L == 0L) ZoneOffset.UTC else zone
        val first = Instant.ofEpochMilli(startMs).atZone(z).toLocalDate()
        val last = Instant.ofEpochMilli(maxOf(endMs - 1, startMs)).atZone(z).toLocalDate()
        return first to maxOf(first, last)
    }

    /**
     * The next event to show, or null (hide the complication). Order of preference: a timed event that is on now
     * or starts later today, then an all-day event covering today, then a timed event starting tomorrow.
     * Nothing further out is shown, and ended events never are.
     */
    fun nextEvent(
        events: List<CalendarEvent>,
        nowMs: Long,
        zone: ZoneId,
        is24Hour: Boolean,
    ): EventView? {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val tomorrow = today.plusDays(1)
        val timed = events.filter { !it.allDay && maxOf(it.endMs, it.startMs) > nowMs }.sortedBy { it.startMs }
        fun dayOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        fun clock(ms: Long) = ComplicationFormat.formatTime(Instant.ofEpochMilli(ms).atZone(zone).toLocalTime(), is24Hour)
        // "9 AM" on the hour (12 h): the line has to fit "Tmrw 9 AM" and still leave room for a title.
        fun clockCompact(ms: Long): String {
            val t = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()
            return if (!is24Hour && t.minute == 0) clock(ms).replace(":00", "") else clock(ms)
        }

        timed.firstOrNull { it.startMs <= nowMs || dayOf(it.startMs) == today }?.let {
            val whenText = if (it.startMs <= nowMs) "Now" else clock(it.startMs)
            return view(it.title, whenText)
        }
        events.filter { it.allDay }.sortedBy { it.startMs }.firstOrNull {
            val (first, last) = allDaySpan(it.startMs, it.endMs, zone)
            today in first..last
        }?.let { return view(it.title, "All day") }
        timed.firstOrNull { dayOf(it.startMs) == tomorrow }?.let {
            return view(it.title, "Tmrw " + clockCompact(it.startMs), "Tomorrow " + clock(it.startMs))
        }
        return null
    }

    private fun view(title: String, whenText: String, spoken: String = whenText): EventView {
        val t = shortTitle(title)
        return EventView(t, whenText, "Next event, $t, $spoken")
    }
}
