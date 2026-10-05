package org.circa.exercise.model

import java.time.LocalDate
import java.util.Locale

/**
 * The Bangle.js recorder CSV that Gadgetbridge fetches (exercise/README.md), and the track ids.
 *
 * An id is `YYYYMMDD` plus a suffix that increments per day: a, b, ... y, then za, zb, ... zy, zza ... so that a
 * plain string compare orders ids by date and then by recording order (GB asks for "every id after <lastId>").
 */
object BangleCsv {
    const val HEADER = "Time,Latitude,Longitude,Altitude,Heartrate,Confidence,Source,Steps"
    const val FIRST_ID = "19700101a"
    private val ID_RE = Regex("^[0-9]{8}[a-z]+$")

    /** One row. Null cells stay blank; numbers use '.' decimals and never contain a comma. */
    fun row(
        timeSec: Long,
        lat: Double? = null,
        lon: Double? = null,
        alt: Double? = null,
        hr: Int? = null,
        confidence: Int? = null,
        source: String? = null,
        steps: Int? = null,
    ): String {
        fun d(v: Double?, digits: Int) = if (v == null || v.isNaN()) "" else String.format(Locale.ROOT, "%.${digits}f", v)
        val hasFix = lat != null && lon != null
        return listOf(
            timeSec.toString(),
            if (hasFix) d(lat, 6) else "",
            if (hasFix) d(lon, 6) else "",
            if (hasFix) d(alt, 1) else "",
            hr?.toString() ?: "",
            if (hr != null) confidence?.toString() ?: "" else "",
            if (hr != null) source?.replace(",", "") ?: "" else "",
            steps?.toString() ?: "",
        ).joinToString(",")
    }

    /**
     * Blank the position cells (Latitude, Longitude, Altitude) of the rows whose time falls in one of [spans]
     * ([from, to] epoch seconds, inclusive): fixes later found to be outliers ([Workout.gpsDropSpans]). GB computes
     * the distance from these points, so one stale fix 1000 km away would otherwise add 1000 km. Other cells, the
     * header and the trailing newline stay as they are.
     */
    fun dropGps(csv: String, spans: List<LongArray>): String {
        if (spans.isEmpty()) return csv
        return csv.split("\n").joinToString("\n") { line ->
            val c = line.split(",")
            val t = c.firstOrNull()?.toLongOrNull()
            if (c.size < 4 || t == null || spans.none { t >= it[0] && t <= it[1] }) line
            else (listOf(c[0], "", "", "") + c.drop(4)).joinToString(",")
        }
    }

    fun isValidId(id: String?): Boolean = id != null && ID_RE.matches(id)

    /** Suffix number n (0-based) -> a..y, za..zy, zza.. */
    fun suffix(n: Int): String {
        require(n >= 0)
        return if (n < 25) ('a' + n).toString() else "z" + suffix(n - 25)
    }

    /** Inverse of [suffix]; -1 for anything else. */
    fun suffixIndex(s: String): Int {
        if (s.isEmpty()) return -1
        if (s.length == 1) return if (s[0] in 'a'..'y') s[0] - 'a' else -1
        if (s[0] != 'z') return -1
        val rest = suffixIndex(s.substring(1))
        return if (rest < 0) -1 else 25 + rest
    }

    fun datePart(date: LocalDate): String = String.format(Locale.ROOT, "%04d%02d%02d", date.year, date.monthValue, date.dayOfMonth)

    /** The next free id for [date] given the [existing] ids. */
    fun nextId(existing: Collection<String>, date: LocalDate): String {
        val prefix = datePart(date)
        val max = existing.filter { it.startsWith(prefix) && isValidId(it) }
            .maxOfOrNull { suffixIndex(it.substring(8)) } ?: -1
        return prefix + suffix(max + 1)
    }

    /** All valid ids sorting after [lastId] (null/blank = everything), ascending. */
    fun idsAfter(all: Collection<String>, lastId: String?): List<String> {
        val from = lastId?.takeIf { it.isNotBlank() } ?: ""
        return all.filter { isValidId(it) && it > from }.sorted()
    }
}
