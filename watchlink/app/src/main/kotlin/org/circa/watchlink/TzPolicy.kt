package org.circa.watchlink

import java.util.TimeZone

/**
 * Chooses the system time zone to match the phone's UTC offset (Gadgetbridge's `E.setTimeZone(<hours>)` in the
 * `setTime(...)` line). The watch has no SIM and no location provider, so Android's time_zone_detector answers
 * NOT_SUPPORTED and "Automatic time zone" alone leaves the watch in GMT; this is what makes it follow the phone.
 *
 * Pure Kotlin (only java.util.TimeZone), host-tested. The service reads Settings.Global auto_time_zone, applies the
 * result with AlarmManager.setTimeZone (needs the privileged SET_TIME_ZONE permission) and logs one line per call.
 */
object TzPolicy {
    /**
     * Common zones, in preference order (rule 3a): the first whose offset at the current instant matches wins, so a
     * whole-hour US offset lands on a US state zone rather than an Etc/GMT or a DST-free outlier.
     */
    private val PREFERRED = arrayOf(
        "America/Los_Angeles", "America/Denver", "America/Phoenix", "America/Chicago",
        "America/New_York", "America/Halifax", "America/Anchorage", "Pacific/Honolulu",
        "Europe/London", "Europe/Berlin", "Europe/Athens", "Europe/Moscow",
        "Asia/Dubai", "Asia/Kolkata", "Asia/Bangkok", "Asia/Shanghai", "Asia/Tokyo",
        "Australia/Sydney", "Pacific/Auckland", "America/Sao_Paulo",
    )

    /**
     * Zone id to apply, or null to leave the system zone alone.
     *
     * Null means: automatic time zone off ([autoEnabled] false, rule 1), or the phone sent no offset
     * ([phoneOffsetMs] null). Otherwise the id of the zone whose offset at [nowMs] equals [phoneOffsetMs]:
     * [currentZoneId] itself when it already matches (rule 2 - keep a correct named zone such as
     * America/Los_Angeles), else the first matching entry of [PREFERRED] (rule 3a), else the first matching
     * TimeZone.getAvailableIDs(offset) entry (rule 3b, also covers fractional offsets), else an Etc/GMT±H zone
     * (rule 3c).
     *
     * @param currentZoneId TimeZone.getDefault().id; null means the default zone.
     * @param phoneOffsetMs the phone's UTC offset in ms, or null when the setTime line carried no time zone.
     */
    @JvmStatic
    fun choose(autoEnabled: Boolean, currentZoneId: String?, phoneOffsetMs: Int?, nowMs: Long): String? {
        if (!autoEnabled || phoneOffsetMs == null) return null
        val current = if (currentZoneId == null) TimeZone.getDefault() else TimeZone.getTimeZone(currentZoneId)
        if (current.getOffset(nowMs) == phoneOffsetMs) return current.id
        for (id in PREFERRED) if (TimeZone.getTimeZone(id).getOffset(nowMs) == phoneOffsetMs) return id
        val available = TimeZone.getAvailableIDs(phoneOffsetMs)
        available.sort()   // getAvailableIDs order is unspecified; keep the choice reproducible
        for (id in available) if (TimeZone.getTimeZone(id).getOffset(nowMs) == phoneOffsetMs) return id
        if (phoneOffsetMs % 3_600_000 == 0) {
            val id = etcGmtZone(phoneOffsetMs / 3_600_000)
            if (id != null && TimeZone.getTimeZone(id).getOffset(nowMs) == phoneOffsetMs) return id
        }
        return null
    }

    /**
     * Fixed-offset tzdb zone for a whole-hour UTC offset, or null when tzdb has none (rule 3c). The sign is
     * inverted in the tzdb names: Etc/GMT+7 is UTC-7, Etc/GMT-5 is UTC+5. tzdb carries Etc/GMT+12 .. Etc/GMT-14.
     */
    @JvmStatic
    fun etcGmtZone(hours: Int): String? {
        if (hours !in -14..12) return null
        if (hours == 0) return "Etc/GMT"
        return "Etc/GMT" + (if (hours > 0) "-" else "+") + Math.abs(hours)
    }
}
