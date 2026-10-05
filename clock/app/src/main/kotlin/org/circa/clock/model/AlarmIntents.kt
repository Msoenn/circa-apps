package org.circa.clock.model

/** Pure helpers for the AlarmClock intents (SET_ALARM, SET_TIMER). */
object AlarmIntents {
    /** AlarmClock.EXTRA_DAYS holds java.util.Calendar days (SUNDAY=1 .. SATURDAY=7); returns our Mon-first mask. */
    fun daysMask(calendarDays: List<Int>?): Int = (calendarDays ?: emptyList()).fold(0) { m, d ->
        if (d in 1..7) m or (1 shl ((d + 5) % 7)) else m
    }

    /** SET_TIMER's EXTRA_LENGTH is in seconds (1..86400). Null when missing or out of range. */
    fun timerMs(lengthSeconds: Int): Long? = if (lengthSeconds in 1..86_400) lengthSeconds * 1000L else null

    /** An existing alarm with the same time and days is reused instead of adding a duplicate. */
    fun findDuplicate(alarms: List<Alarm>, hour: Int, minute: Int, days: Int): Alarm? =
        alarms.firstOrNull { it.hour == hour && it.minute == minute && it.days == days }
}
