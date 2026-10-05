package org.circa.settings.model

import kotlin.math.abs

/** Storage page numbers (pure; StorageModelTest / via SystemInfoModelTest). */
object StorageModel {
    /** "5.3 GB", "812 MB", "0 B": decimal units like stock Settings and `df -H`. */
    fun size(bytes: Long): String {
        val b = bytes.coerceAtLeast(0)
        return when {
            b >= 1_000_000_000L -> {
                val gb = b / 1e9
                if (gb >= 100) "${gb.toLong()} GB" else "${"%.1f".format(java.util.Locale.ROOT, gb)} GB"
            }
            b >= 1_000_000L -> "${b / 1_000_000L} MB"
            b >= 1_000L -> "${b / 1_000L} kB"
            else -> "$b B"
        }
    }

    /** Used share in 0..1 (0 when total is unknown). */
    fun usedFraction(used: Long, total: Long): Float =
        if (total <= 0) 0f else (used.toDouble() / total).toFloat().coerceIn(0f, 1f)

    fun percent(used: Long, total: Long): Int = (usedFraction(used, total) * 100 + 0.5f).toInt()
}

/** One entry of the time zone picker. */
data class ZoneRow(val id: String, val city: String, val region: String, val offsetMs: Int)

object TimeZoneModel {
    /** "GMT+05:30", "GMT-03:00", "GMT+00:00". */
    fun offsetLabel(offsetMs: Int): String {
        val sign = if (offsetMs < 0) "-" else "+"
        val minutes = abs(offsetMs) / 60_000
        return "GMT$sign%02d:%02d".format(java.util.Locale.ROOT, minutes / 60, minutes % 60)
    }

    /** Picker order: by offset (west to east), then city name. */
    fun sorted(zones: List<ZoneRow>): List<ZoneRow> =
        zones.sortedWith(compareBy<ZoneRow> { it.offsetMs }.thenBy { it.city.lowercase() }.thenBy { it.id })

    /** Search: every word of [query] must match the city, region, zone id or offset label. */
    fun filter(zones: List<ZoneRow>, query: String): List<ZoneRow> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return zones
        return zones.filter { z ->
            val hay = "${z.city} ${z.region} ${z.id.replace('_', ' ')} ${offsetLabel(z.offsetMs)}".lowercase()
            words.all { hay.contains(it) }
        }
    }

    /** City from the zone id when ICU has no exemplar name: "America/Argentina/Buenos_Aires" -> "Buenos Aires". */
    fun cityFromId(id: String): String = id.substringAfterLast('/').replace('_', ' ')

    /** `time_12_24`: "24", "12", or null (= follow the locale). */
    fun parse24(raw: String?, localeDefault24: Boolean): Boolean = when (raw?.trim()) {
        "24" -> true
        "12" -> false
        else -> localeDefault24
    }
}
