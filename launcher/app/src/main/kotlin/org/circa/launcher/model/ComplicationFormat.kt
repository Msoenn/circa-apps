package org.circa.launcher.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Formatting rules for the watch-face complications. Pure and JVM-testable; uses java.time only
 * (available since API 26, far below minSdk 34).
 */
object ComplicationFormat {

    private val TIME_24H = DateTimeFormatter.ofPattern("HH:mm")
    private val TIME_12H_DIGITS = DateTimeFormatter.ofPattern("h:mm")
    private val TIME_12H_SUFFIX = DateTimeFormatter.ofPattern("a")
    private val DATE = DateTimeFormatter.ofPattern("EEE d MMM")

    /**
     * A clock reading split so the face can draw the digits large and the AM/PM marker small:
     * 12-hour gives ("4:58", "PM"), 24-hour gives ("16:58", null).
     */
    data class TimeParts(val digits: String, val suffix: String?) {
        /** The full reading as one string, e.g. "4:58 PM" or "16:58". */
        val text: String get() = if (suffix == null) digits else "$digits $suffix"
    }

    /** Digits only ("16:58" / "4:58"), for the small watch-face complications. */
    fun formatShortTime(time: LocalTime, is24Hour: Boolean): String =
        time.format(if (is24Hour) TIME_24H else TIME_12H_DIGITS)

    /** Split clock reading; see [TimeParts]. */
    fun timeParts(time: LocalTime, is24Hour: Boolean): TimeParts =
        if (is24Hour) {
            TimeParts(formatShortTime(time, is24Hour = true), null)
        } else {
            TimeParts(
                formatShortTime(time, is24Hour = false),
                time.format(TIME_12H_SUFFIX),
            )
        }

    /** e.g. "22:47" (24h) or "7:30 AM" (12h). */
    fun formatTime(time: LocalTime, is24Hour: Boolean): String =
        timeParts(time, is24Hour).text

    /** e.g. "Thu 1 Oct". */
    fun formatDate(date: LocalDate): String = date.format(DATE)

    /** e.g. "87%"; null when unknown (the UI then shows its no-data state, never a "--"). */
    fun formatBattery(percent: Int?): String? = percent?.let { "$it%" }

    /** e.g. "72"; null when there is no reading. */
    fun formatHeartRate(bpm: Int?): String? = bpm?.toString()

    /** Compact step count for a complication: "832", "4.2k", "12k"; null without a reading. */
    fun formatSteps(steps: Int?): String? = when {
        steps == null -> null
        steps < 1000 -> steps.toString()
        steps < 10_000 -> "${steps / 1000}.${(steps % 1000) / 100}k"
        else -> "${steps / 1000}k"
    }

    /** Full step count for the health tile, with a thousands separator: "4,213". */
    fun formatStepsFull(steps: Int?): String? = steps?.let { String.format(java.util.Locale.US, "%,d", it) }

    /**
     * Next-alarm wall-clock time in [zone], digits only ("7:30" / "07:30"). The AM/PM marker is
     * dropped on purpose: the complication is a 30dp circle, and a five-character reading is the
     * widest value that still fits under it.
     */
    fun formatAlarmShort(epochMillis: Long, is24Hour: Boolean, zone: ZoneId): String {
        val time = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalTime()
        return formatShortTime(time, is24Hour)
    }
}
