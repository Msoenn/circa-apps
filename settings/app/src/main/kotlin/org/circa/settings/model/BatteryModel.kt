package org.circa.settings.model

/** One app's share of the battery as `data/BatteryData` read it (raw platform numbers). */
data class RawAppUsage(
    val uid: Int,
    /** The package that owns the uid (the first one for a shared uid), or null for a bare uid. */
    val pkg: String?,
    val label: String,
    /** Consumed power in mAh from BatteryUsageStats; 0 when the device has no power profile (emulator). */
    val powerMah: Double,
    /** Time the app was in the foreground since the stats start (UsageStats), ms. */
    val foregroundMs: Long,
)

/** A row of the Battery usage page. */
data class UsageRow(val pkg: String?, val label: String, val percent: Int?, val foregroundMs: Long, val secondary: String)

/** Pure parts of the Battery usage page (tested in `BatteryModelTest`). */
object BatteryUsageModel {
    /** Apps used less than this and drawing no power are not worth a row. */
    const val MIN_FOREGROUND_MS = 60_000L
    const val MAX_ROWS = 20

    /** "1 h 5 min", "12 min", "< 1 min" (a screen-on time or an app's foreground time). */
    fun duration(ms: Long): String {
        if (ms < 60_000L) return if (ms <= 0L) "0 min" else "< 1 min"
        val totalMin = ms / 60_000L
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h == 0L -> "$m min"
            m == 0L -> "$h h"
            else -> "$h h $m min"
        }
    }

    /** Rows sorted by power, then by foreground time; percent of the shown total power (null without power data). */
    fun rows(raw: List<RawAppUsage>, limit: Int = MAX_ROWS): List<UsageRow> {
        val kept = raw.filter { it.powerMah > 0.0 || it.foregroundMs >= MIN_FOREGROUND_MS }
            .sortedWith(compareByDescending<RawAppUsage> { it.powerMah }.thenByDescending { it.foregroundMs })
            .take(limit)
        val total = kept.sumOf { it.powerMah }
        return kept.map { a ->
            val pct = if (total > 0.0) percentOf(a.powerMah, total) else null
            UsageRow(a.pkg, a.label, pct, a.foregroundMs, secondary(pct, a.foregroundMs))
        }
    }

    private fun percentOf(part: Double, total: Double): Int = Math.round(part / total * 100.0).toInt()

    fun secondary(percent: Int?, foregroundMs: Long): String {
        val parts = mutableListOf<String>()
        if (percent != null) parts += if (percent < 1) "< 1%" else "$percent%"
        if (foregroundMs >= 1L) parts += duration(foregroundMs)
        return parts.joinToString(" · ").ifEmpty { "No use" }
    }

    /** UsageEvents SCREEN_INTERACTIVE / SCREEN_NON_INTERACTIVE. */
    const val SCREEN_ON = 15
    const val SCREEN_OFF = 16

    /**
     * Screen-on time in [from, to] from time-ordered (eventType, timestamp) pairs. When the first screen
     * event is "off", the screen was on from [from] until then.
     */
    fun screenOnMs(events: List<Pair<Int, Long>>, from: Long, to: Long): Long {
        val screen = events.filter { it.first == SCREEN_ON || it.first == SCREEN_OFF }.sortedBy { it.second }
        var onSince: Long? = if (screen.firstOrNull()?.first == SCREEN_OFF) from else null
        var total = 0L
        for ((type, t) in screen) {
            val at = t.coerceIn(from, to)
            if (type == SCREEN_ON) { if (onSince == null) onSince = at }
            else if (onSince != null) { total += (at - onSince).coerceAtLeast(0L); onSince = null }
        }
        if (onSince != null) total += (to - onSince).coerceAtLeast(0L)
        return total
    }

    /** "since the last full charge, 5 h 20 min ago" / "in the last 24 h". */
    fun periodLabel(now: Long, sinceMs: Long?): String =
        if (sinceMs == null) "Last 24 hours" else "Since last full charge, ${duration((now - sinceMs).coerceAtLeast(0L))} ago"

    fun statusLine(percent: Int?, charging: Boolean): String {
        val level = BatteryLabel.level(percent)
        return if (charging) "$level · Charging" else "$level · On battery"
    }

    /** The remaining-time line, or null when the platform has no estimate. */
    fun remaining(ms: Long?, charging: Boolean): String? =
        ms?.takeIf { it > 0L }?.let { (if (charging) "Full in " else "About ") + duration(it) + (if (charging) "" else " left") }
}
