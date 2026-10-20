package org.circa.settings.model

/**
 * Bedtime (docs/watch-ui/circa/bedtime.md): what Circa Settings shows and writes. The Circa shade
 * (SystemUI CircaBedtimeMode) owns the behaviour and reads the same keys; the schedule string format is
 * shared with it (`enabled;startMin;endMin;days`, days bit 0 = Monday, the day a night starts on).
 */
data class BedtimeSchedule(
    val enabled: Boolean = false,
    val startMin: Int = 23 * 60,
    val endMin: Int = 7 * 60,
    val days: Int = ALL_DAYS,
) {
    fun encode(): String = "${if (enabled) 1 else 0};$startMin;$endMin;$days"

    companion object {
        const val ALL_DAYS = 0b1111111
        const val WEEKDAYS = 0b0011111
        const val WEEKEND = 0b1100000
        const val DAY_MIN = 24 * 60
        val DEFAULT = BedtimeSchedule()

        fun decode(text: String?): BedtimeSchedule {
            val f = text?.trim()?.split(';') ?: return DEFAULT
            if (f.size < 4) return DEFAULT
            val start = f[1].toIntOrNull() ?: return DEFAULT
            val end = f[2].toIntOrNull() ?: return DEFAULT
            val days = f[3].toIntOrNull() ?: return DEFAULT
            if (start !in 0 until DAY_MIN || end !in 0 until DAY_MIN) return DEFAULT
            return BedtimeSchedule(f[0] == "1", start, end, days and ALL_DAYS)
        }
    }
}

/** The Bedtime page's state: on now, the schedule, and "start when asleep". */
data class BedtimeState(
    val on: Boolean = false,
    val schedule: BedtimeSchedule = BedtimeSchedule.DEFAULT,
    val startWhenAsleep: Boolean = false,
)

object BedtimeKeys {
    /** Settings.Global: the platform's Wear Bedtime switch (Settings.Global.Wearable.BEDTIME_MODE), 0/1. */
    const val STATE = "bedtime_mode"

    /** Settings.Secure: who turned it on; Circa Settings writes "manual". */
    const val SOURCE = "circa_bedtime_source"
    const val SCHEDULE = "circa_bedtime_schedule"
    const val ON_SLEEP = "circa_bedtime_on_sleep"
}

object BedtimeModel {
    /** Crown / -/+ step on the time pages. */
    const val STEP_MIN = 15
    private val SHORT = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /** [minute] moved by [steps] of [STEP_MIN], snapped to the step grid, wrapping around midnight. */
    fun adjust(minute: Int, steps: Int): Int {
        val snapped = if (steps > 0) minute / STEP_MIN * STEP_MIN else (minute + STEP_MIN - 1) / STEP_MIN * STEP_MIN
        return Math.floorMod(snapped + steps * STEP_MIN, BedtimeSchedule.DAY_MIN)
    }

    /** "23:00" or "11:00 PM". */
    fun time(minute: Int, is24h: Boolean): String {
        val h = minute / 60
        val m = minute % 60
        return if (is24h) "%02d:%02d".format(h, m)
        else "%d:%02d %s".format(if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "AM" else "PM")
    }

    /** "Every day", "Mon - Fri", "Sat, Sun", "Mon, Wed", "Never". */
    fun days(mask: Int): String = when (mask and BedtimeSchedule.ALL_DAYS) {
        0 -> "Never"
        BedtimeSchedule.ALL_DAYS -> "Every day"
        BedtimeSchedule.WEEKDAYS -> "Mon - Fri"
        BedtimeSchedule.WEEKEND -> "Sat, Sun"
        else -> (0..6).filter { mask and (1 shl it) != 0 }.joinToString(", ") { SHORT[it] }
    }

    fun dayName(index: Int): String = SHORT[index]

    /** The schedule row's second line: "23:00 - 07:00" or "Off" (the days have their own row). */
    fun scheduleSummary(s: BedtimeSchedule, is24h: Boolean): String =
        if (!s.enabled) "Off" else "${time(s.startMin, is24h)} - ${time(s.endMin, is24h)}"
}
