package org.circa.exercise.model

/**
 * The ongoing notification's texts ([org.circa.exercise.data.Notifs]), pure so the formats are host-tested.
 *
 * While recording the notification is a chronometer (the stock shade ticks it); while paused the chronometer is off
 * and the frozen active time is drawn as text instead, "Paused · 12:34".
 */
object NotifText {
    const val RECORDING = "Recording"

    /** The paused card's second line: the frozen elapsed active time, e.g. "Paused · 12:34". */
    fun paused(activeMs: Long): String = "Paused · " + Fmt.duration(activeMs)

    /**
     * The chronometer's base: the wall-clock instant it counts from, so that `now - base` is the accumulated active
     * (unpaused) time. With pauses before, this is not the workout's start but the start shifted back by them.
     */
    fun chronoBase(now: Long, activeMs: Long): Long = now - activeMs
}
