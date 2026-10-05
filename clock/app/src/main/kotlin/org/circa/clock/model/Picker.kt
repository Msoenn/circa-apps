package org.circa.clock.model

/** Which column of the time picker the crown edits. */
enum class PickerColumn { HOUR, MINUTE }

/**
 * Crown-wheel time picker state. Hours wrap 0..23 (24 h) and are shown 1..12 plus AM/PM when [is24h] is
 * false; minutes wrap 0..59. Wrapping never carries into the other column (like stock Wear).
 */
data class PickerState(
    val hour: Int,
    val minute: Int,
    val column: PickerColumn = PickerColumn.HOUR,
    val is24h: Boolean = true,
) {
    fun step(delta: Int): PickerState = when (column) {
        PickerColumn.HOUR -> copy(hour = Math.floorMod(hour + delta, 24))
        PickerColumn.MINUTE -> copy(minute = Math.floorMod(minute + delta, 60))
    }

    fun select(c: PickerColumn): PickerState = copy(column = c)

    /** Number shown in the hour column. */
    val hourText: String
        get() = if (is24h) "%02d".format(hour) else (if (hour % 12 == 0) 12 else hour % 12).toString()
    val minuteText: String get() = "%02d".format(minute)
    val isPm: Boolean get() = hour >= 12

    /** Toggle AM/PM (12 h picker): moves the hour by 12. */
    fun toggleAmPm(): PickerState = copy(hour = (hour + 12) % 24)

    /** Neighbour values for the wheel (previous, next) in the active column's display format. */
    fun neighbours(c: PickerColumn): Pair<String, String> {
        val prev = step2(c, -1)
        val next = step2(c, +1)
        return prev to next
    }

    private fun step2(c: PickerColumn, d: Int): String {
        val s = copy(column = c).step(d)
        return if (c == PickerColumn.HOUR) s.hourText else s.minuteText
    }
}

/** Formatting helpers shared by the lists and the ringing screen. */
object Fmt {
    fun time(hour: Int, minute: Int, is24h: Boolean): String =
        if (is24h) "%02d:%02d".format(hour, minute)
        else "${if (hour % 12 == 0) 12 else hour % 12}:%02d".format(minute)

    fun ampm(hour: Int): String = if (hour >= 12) "PM" else "AM"

    /** m:ss, or h:mm:ss from one hour on. */
    fun duration(ms: Long): String {
        val s = (ms.coerceAtLeast(0) + 999) / 1000 // a timer shows the second it is still inside
        val h = s / 3600
        val m = s % 3600 / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** Stopwatch time m:ss.cc (centiseconds), floor rather than ceil. */
    fun stopwatch(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        val h = t / 3_600_000
        val m = t % 3_600_000 / 60_000
        val s = t % 60_000 / 1000
        val c = t % 1000 / 10
        return if (h > 0) "%d:%02d:%02d.%02d".format(h, m, s, c) else "%02d:%02d.%02d".format(m, s, c)
    }
}
