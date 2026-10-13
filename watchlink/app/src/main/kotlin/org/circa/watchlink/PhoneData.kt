package org.circa.watchlink

import java.util.Base64

/**
 * Latest phone data that Gadgetbridge pushes to a Bangle.js (weather, music, calendar, navigation), parsed from the
 * `GB({...})` messages and served to the Circa apps by [PhoneDataProvider]. Contract: watchlink/DATA-CONTRACT.md.
 * Pure Kotlin (no android.*), host-tested; [PhoneStore] adds persistence and change notification.
 *
 * Message formats were confirmed against Gadgetbridge's BangleJSDeviceSupport.java (2026-10-04); see the contract doc.
 * Not thread-safe: [PhoneStore] serializes access.
 */
class PhoneData {
    /**
     * The current conditions, optionally with today's details and the daily forecast. Only a v2 reply carries the
     * details ([feelsC] to [sunsetMs]) and the forecast; a v1 push keeps whatever a v2 reply brought, since v1 has
     * neither. Each detail is null when Gadgetbridge has no value for it (its WeatherSpec default), never a sentinel.
     */
    class Weather(
        @JvmField val updatedMs: Long, @JvmField val location: String?, @JvmField val tempC: Long?,
        @JvmField val hiC: Long?, @JvmField val loC: Long?, @JvmField val code: Long?, @JvmField val text: String?,
        @JvmField val humidity: Long?, @JvmField val windKmh: Double?, @JvmField val windDir: Long?,
        @JvmField val uv: Double?, @JvmField val rainPct: Long?,
        /** Apparent temperature, whole °C. */
        @JvmField val feelsC: Long?,
        /** Dew point, whole °C. */
        @JvmField val dewC: Long?,
        /** Sea-level pressure, hPa (one decimal). */
        @JvmField val pressureHpa: Double?,
        /** Cloud cover, percent. */
        @JvmField val cloudPct: Long?,
        /** Visibility, metres (one decimal). */
        @JvmField val visibilityM: Double?,
        /** Sunrise, ms since epoch. */
        @JvmField val sunriseMs: Long?,
        /** Sunset, ms since epoch. */
        @JvmField val sunsetMs: Long?,
        @JvmField val forecastJson: String?,
    )

    class Music(
        @JvmField val updatedMs: Long, @JvmField val state: String, @JvmField val artist: String?,
        @JvmField val album: String?, @JvmField val track: String?, @JvmField val durationS: Long,
        @JvmField val positionS: Long, @JvmField val positionAtMs: Long,
    )

    class Event(
        @JvmField val id: Long, @JvmField val title: String?, @JvmField val startMs: Long, @JvmField val endMs: Long,
        @JvmField val allDay: Boolean, @JvmField val location: String?, @JvmField val calendar: String?,
        @JvmField val color: Long,
    )

    /**
     * The route Google Maps is on right now, as Gadgetbridge's `nav` message carries it. [instruction] and [action]
     * are null when Maps sends none; [distance] stays the raw display string Maps shows ("0.2 mi", "300 m", in the
     * phone's locale and units) and [distanceM] is [parseDistanceMeters] of it (-1 when unparseable).
     */
    class Nav(
        @JvmField val updatedMs: Long, @JvmField val instruction: String?, @JvmField val distance: String?,
        @JvmField val distanceM: Long, @JvmField val action: String?, @JvmField val eta: String?,
    )

    @JvmField var weather: Weather? = null
    @JvmField var music: Music? = null
    @JvmField var nav: Nav? = null
    private val events = LinkedHashMap<Long, Event>()

    /** Dispatch one parsed GB message. Returns a bit set of CH_* for what changed (0 = not a data message). */
    fun apply(m: Map<String, Any?>, nowMs: Long, tzOffsetMs: Long): Int {
        return when (Proto.str(m, "t")) {
            "weather" -> if (applyWeather(m, nowMs, tzOffsetMs)) CH_WEATHER else 0
            "musicinfo" -> { applyMusicInfo(m, nowMs); CH_MUSIC }
            "musicstate" -> { applyMusicState(m, nowMs); CH_MUSIC }
            "calendar" -> if (applyCalendar(m, nowMs)) CH_CALENDAR else 0
            "calendar-" -> if (applyCalendarDelete(m)) CH_CALENDAR else 0
            "nav" -> if (applyNav(m, nowMs)) CH_NAV else 0
            else -> 0
        }
    }

    // ---- weather ----

    private fun applyWeather(m: Map<String, Any?>, now: Long, tz: Long): Boolean {
        val old = weather
        val v = Proto.num(m, "v", 1)
        if (v == 2L) {
            val d = Proto.str(m, "d") ?: return false
            val b = try { Base64.getMimeDecoder().decode(d) } catch (e: IllegalArgumentException) { return false }
            val w = decodeV2(b, Proto.str(m, "l"), Proto.str(m, "c"), now, tz) ?: return false
            weather = w
            return true
        }
        fun k(key: String): Long? = num(m[key])?.let { Math.round(it - 273.15) }
        fun n(key: String): Long? = num(m[key])?.let { Math.round(it) }
        weather = Weather(
            now, Proto.str(m, "loc"), k("temp"), k("hi"), k("lo"), n("code"), Proto.str(m, "txt"), n("hum"),
            num(m["wind"]), n("wdir"), num(m["uv"]), n("rain"),
            // v1 carries neither today's details nor the forecast: keep what a v2 reply brought.
            old?.feelsC, old?.dewC, old?.pressureHpa, old?.cloudPct, old?.visibilityM, old?.sunriseMs, old?.sunsetMs,
            old?.forecastJson,
        )
        return true
    }

    // ---- music ----

    private fun applyMusicInfo(m: Map<String, Any?>, now: Long) {
        val o = music
        music = Music(
            now, o?.state ?: "stop", Proto.str(m, "artist") ?: "", Proto.str(m, "album") ?: "",
            Proto.str(m, "track") ?: "", Proto.num(m, "dur", 0), o?.positionS ?: 0, o?.positionAtMs ?: now,
        )
    }

    private fun applyMusicState(m: Map<String, Any?>, now: Long) {
        val o = music
        val st = Proto.str(m, "state")
        val state = if (st == "play" || st == "pause" || st == "stop") st else "stop"
        music = Music(
            now, state, o?.artist ?: "", o?.album ?: "", o?.track ?: "", o?.durationS ?: 0,
            Proto.num(m, "position", 0), now,
        )
    }

    // ---- calendar ----

    private fun applyCalendar(m: Map<String, Any?>, now: Long): Boolean {
        if (m["id"] !is Number) return false
        val type = Proto.num(m, "type", 0)
        if (type == 1L || type == 2L) return false   // GB sunrise/sunset pseudo events are not agenda items
        val id = Proto.num(m, "id", 0)
        val start = Proto.num(m, "timestamp", 0) * 1000
        val end = start + Proto.num(m, "durationInSeconds", 0) * 1000
        events[id] = Event(
            id, Proto.str(m, "title") ?: "", start, end, Proto.bool(m, "allDay", false), Proto.str(m, "location") ?: "",
            Proto.str(m, "calName") ?: "", Proto.num(m, "color", 0),
        )
        pruneCalendar(now)
        while (events.size > MAX_EVENTS) {   // drop the earliest start first: the agenda looks forward
            val victim = events.values.minByOrNull { it.startMs } ?: break
            events.remove(victim.id)
        }
        return true
    }

    private fun applyCalendarDelete(m: Map<String, Any?>): Boolean {
        val id = m["id"]
        var changed = false
        if (id is List<*>) {
            for (x in id) if (x is Number && events.remove(x.toLong()) != null) changed = true
        } else if (id is Number) {
            changed = events.remove(id.toLong()) != null
        }
        return changed
    }

    /** Drops events that ended more than 24 h ago. True when something was dropped. */
    fun pruneCalendar(nowMs: Long): Boolean {
        val cut = nowMs - CAL_KEEP_MS
        return events.values.removeIf { it.endMs < cut }
    }

    fun calendar(nowMs: Long): List<Event> {
        pruneCalendar(nowMs)
        return events.values.sortedWith(compareBy({ it.startMs }, { it.id }))
    }

    fun calendarIds(): List<Long> = ArrayList(events.keys)

    // ---- navigation ----

    /** [Proto.str] with an empty string read as absent: GB omits a JSON key rather than sending it empty. */
    private fun text(m: Map<String, Any?>, k: String): String? = Proto.str(m, k)?.takeIf { it.isNotEmpty() }

    /**
     * One `nav` message (GB `onSetNavigationInfo` per Maps notification update): the maneuver Maps shows, or GB's
     * blank `{"t":"nav"}` when the route ended. Unlike weather, music and calendar it is never persisted (a route
     * cannot survive a restart), and [navState] drops it once GB has been quiet for [NAV_STALE_MS].
     */
    private fun applyNav(m: Map<String, Any?>, now: Long): Boolean {
        val instr = text(m, "instr")
        val action = text(m, "action")
        val distance = text(m, "distance")
        if (instr == null && action == null && distance == null) {   // blank message: no instruction, no action, no distance
            val had = nav != null
            nav = null
            return had
        }
        nav = Nav(now, instr, distance, parseDistanceMeters(distance), action, text(m, "eta"))
        return true
    }

    /** The current route, or null when there is none or nothing arrived for [NAV_STALE_MS] (a new message revives it). */
    fun navState(nowMs: Long): Nav? = nav?.takeIf { nowMs - it.updatedMs <= NAV_STALE_MS }

    // ---- persistence ----

    fun toJson(): String {
        val root = LinkedHashMap<String, Any?>()
        weather?.let { w ->
            root["weather"] = linkedMapOf<String, Any?>(
                "u" to w.updatedMs, "loc" to w.location, "t" to w.tempC, "hi" to w.hiC, "lo" to w.loC, "code" to w.code,
                "txt" to w.text, "hum" to w.humidity, "wind" to w.windKmh, "wdir" to w.windDir, "uv" to w.uv,
                "rain" to w.rainPct, "feels" to w.feelsC, "dew" to w.dewC, "pres" to w.pressureHpa,
                "cloud" to w.cloudPct, "vis" to w.visibilityM, "srise" to w.sunriseMs, "sset" to w.sunsetMs,
                "fc" to w.forecastJson,
            )
        }
        music?.let { x ->
            root["music"] = linkedMapOf<String, Any?>(
                "u" to x.updatedMs, "state" to x.state, "artist" to x.artist, "album" to x.album, "track" to x.track,
                "dur" to x.durationS, "pos" to x.positionS, "posAt" to x.positionAtMs,
            )
        }
        root["events"] = events.values.map { e ->
            linkedMapOf<String, Any?>(
                "id" to e.id, "title" to e.title, "s" to e.startMs, "e" to e.endMs, "ad" to e.allDay,
                "loc" to e.location, "cal" to e.calendar, "color" to e.color,
            )
        }
        return JsonOut.encode(root)
    }

    /** Replaces the state with [json] (from [toJson]); anything unreadable leaves it empty. */
    fun load(json: String) {
        weather = null; music = null; events.clear()
        try {
            val root = JsParser.parse(json) as? Map<*, *> ?: return
            (root["weather"] as? Map<*, *>)?.let { w ->
                weather = Weather(
                    lng(w["u"]) ?: 0, w["loc"] as? String, lng(w["t"]), lng(w["hi"]), lng(w["lo"]), lng(w["code"]),
                    w["txt"] as? String, lng(w["hum"]), num(w["wind"]), lng(w["wdir"]), num(w["uv"]), lng(w["rain"]),
                    lng(w["feels"]), lng(w["dew"]), num(w["pres"]), lng(w["cloud"]), num(w["vis"]), lng(w["srise"]),
                    lng(w["sset"]), w["fc"] as? String,
                )
            }
            (root["music"] as? Map<*, *>)?.let { x ->
                music = Music(
                    lng(x["u"]) ?: 0, x["state"] as? String ?: "stop", x["artist"] as? String, x["album"] as? String,
                    x["track"] as? String, lng(x["dur"]) ?: 0, lng(x["pos"]) ?: 0, lng(x["posAt"]) ?: 0,
                )
            }
            for (o in root["events"] as? List<*> ?: emptyList<Any?>()) {
                val e = o as? Map<*, *> ?: continue
                val id = lng(e["id"]) ?: continue
                events[id] = Event(
                    id, e["title"] as? String, lng(e["s"]) ?: 0, lng(e["e"]) ?: 0, e["ad"] == true,
                    e["loc"] as? String, e["cal"] as? String, lng(e["color"]) ?: 0,
                )
            }
        } catch (e: Exception) {
            weather = null; music = null; events.clear()
        }
    }

    companion object {
        const val CH_WEATHER = 1
        const val CH_MUSIC = 2
        const val CH_CALENDAR = 4
        const val CH_NAV = 8
        const val MAX_EVENTS = 200
        const val CAL_KEEP_MS = 24L * 3600 * 1000

        /** GB sends a `nav` message per Maps update while a route is active; silence this long means it ended. */
        const val NAV_STALE_MS = 5 * 60 * 1000L

        private fun num(v: Any?): Double? = (v as? Number)?.toDouble()
        private fun lng(v: Any?): Long? = (v as? Number)?.toLong()

        /** Maps' number then its unit, or the unit then the number; the separator may be a non-breaking space. */
        private val DIST_NUM_UNIT = Regex("""(\d+(?:[.,]\d+)?)[\s\u00A0]*(mi|km|ft|yd|m)""", RegexOption.IGNORE_CASE)
        private val DIST_UNIT_NUM = Regex("""(mi|km|ft|yd|m)[\s\u00A0]*(\d+(?:[.,]\d+)?)""", RegexOption.IGNORE_CASE)

        /**
         * The metres of Maps' distance string: a number (`.` or `,` as the decimal mark) and a unit among
         * m / km / ft / yd / mi, in either order. -1 when it is not a distance, e.g. GB's null distance or a
         * string from a locale WatchLink does not know. The whole string must be the distance.
         */
        @JvmStatic
        fun parseDistanceMeters(s: String?): Long {
            val t = s?.trim()?.lowercase() ?: return -1
            DIST_NUM_UNIT.matchEntire(t)?.let { return meters(it.groupValues[1], it.groupValues[2]) }
            DIST_UNIT_NUM.matchEntire(t)?.let { return meters(it.groupValues[2], it.groupValues[1]) }
            return -1
        }

        private fun meters(number: String, unit: String): Long {
            val perUnit = when (unit) {
                "m" -> 1.0
                "km" -> 1000.0
                "ft" -> 0.3048
                "yd" -> 0.9144
                else -> 1609.344           // mi
            }
            return Math.round(number.replace(',', '.').toDouble() * perUnit)
        }

        private val THUNDER = intArrayOf(200, 201, 202, 210, 211, 212, 221, 230, 231, 232)
        private val DRIZZLE = intArrayOf(300, 301, 302, 310, 311, 312, 313, 314, 321)
        private val RAIN = intArrayOf(500, 501, 502, 503, 504, 511, 520, 521, 522, 531)
        private val SNOW = intArrayOf(600, 601, 602, 611, 612, 613, 615, 616, 620, 621, 622)
        private val ATMOS = intArrayOf(701, 711, 721, 731, 741, 751, 761, 762, 771, 781)
        private val CLEAR = intArrayOf(800, 801, 802, 803, 804)

        /** Inverse of Gadgetbridge's conditionCodeMapping (OpenWeatherMap code -> 1 byte). 0 = unknown. */
        @JvmStatic
        fun owmCode(b: Int): Int {
            fun pick(a: IntArray, base: Int): Int = if (b - base in a.indices) a[b - base] else 0
            return when {
                b < 32 -> pick(THUNDER, 0)
                b < 64 -> pick(DRIZZLE, 32)
                b < 96 -> 0
                b < 128 -> pick(RAIN, 96)
                b < 160 -> pick(SNOW, 128)
                b < 192 -> pick(ATMOS, 160)
                b < 224 -> pick(CLEAR, 192)
                else -> 0
            }
        }

        /**
         * Decodes the `d` payload of a v2 weather message (GB handleWeatherV2): a fixed 38-byte current block, then
         * (only when the request had `"f":true`) hourly arrays and daily arrays. Temperatures are K-273 as signed bytes.
         * The daily arrays carry no dates; GB's forecasts list starts tomorrow, so day i is local midnight tomorrow + i days.
         * Returns null when the block is too short.
         *
         * The current block also carries GB's today's details at the end of it (dew point, pressure, cloud cover,
         * visibility, sun times, feels-like). GB always writes them, using its WeatherSpec defaults (0 K, 0 hPa, 0 m,
         * 0 %, epoch 0) for a field no provider filled, so those read as null here
         * ([UNKNOWN_C] is the clamped 0 K; 0 is impossible for the others).
         */
        @JvmStatic
        fun decodeV2(b: ByteArray, loc: String?, text: String?, nowMs: Long, tzOffsetMs: Long): Weather? {
            if (b.size < 38) return null
            fun u8(i: Int) = b[i].toInt() and 0xff
            fun s8(i: Int) = b[i].toInt()
            fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
            fun u32(i: Int) = u8(i).toLong() or (u8(i + 1).toLong() shl 8) or (u8(i + 2).toLong() shl 16) or
                (u8(i + 3).toLong() shl 24)
            fun temp8(i: Int): Long? = s8(i).takeIf { it != UNKNOWN_C }?.toLong()
            val wind = u16(7) / 100.0
            var forecast: String? = null
            var p = 38
            if (b.size > p) {
                val h = u8(p++)
                if (h > 0) p += 4
                p += 6 * h
                if (p < b.size) {
                    val n = u8(p++)
                    if (n > 0 && p + 6 * n <= b.size) {
                        val localNow = nowMs + tzOffsetMs
                        val tomorrow = (Math.floorDiv(localNow, DAY_MS) + 1) * DAY_MS - tzOffsetMs
                        val days = ArrayList<Any?>()
                        for (i in 0 until n) {
                            days.add(linkedMapOf<String, Any?>(
                                "day_ms" to tomorrow + i * DAY_MS, "hi_c" to s8(p + i).toLong(), "lo_c" to s8(p + n + i).toLong(),
                                "code" to owmCode(u8(p + 2 * n + i)).toLong(), "rain_pct" to u8(p + 5 * n + i).toLong(),
                            ))
                        }
                        forecast = JsonOut.encode(days)
                    }
                }
            }
            return Weather(
                nowMs, loc, s8(0).toLong(), s8(1).toLong(), s8(2).toLong(), owmCode(u8(6)).toLong(), text, u8(3).toLong(),
                wind, u16(9).toLong(), u8(5) / 10.0, u8(4).toLong(),
                temp8(37),                                                              // feels-like
                temp8(11),                                                              // dew point
                u16(12).takeIf { it != 0 }?.let { it / 10.0 },                          // pressure
                u8(14).takeIf { it != 0 }?.toLong(),                                    // cloud cover
                u32(15).takeIf { it != 0L }?.let { it / 10.0 },                         // visibility, metres
                u32(19).takeIf { it != 0L }?.let { it * 1000 },                         // sunrise, epoch s -> ms
                u32(23).takeIf { it != 0L }?.let { it * 1000 },                         // sunset
                forecast,
            )
        }

        private const val DAY_MS = 24L * 3600 * 1000

        /** The byte GB's signed 1-byte temperature fields hold for its WeatherSpec default of 0 K (clamped). */
        private const val UNKNOWN_C = -128
    }
}
