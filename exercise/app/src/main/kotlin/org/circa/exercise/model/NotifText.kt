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

    /** "Walk detected" / "Run detected". */
    fun detectedTitle(type: ActivityType): String = "${type.label} detected"

    /** [since] is the backdated start as shown, e.g. "3:42 PM". */
    fun detectedText(since: String): String = "Recording since $since"

    fun savedTitle(type: ActivityType): String = "${type.label} saved"

    /** "42:10 · 3.21 km" (distance only for GPS activities). */
    fun savedText(activeMs: Long, distanceM: Double, gps: Boolean): String =
        Fmt.duration(activeMs) + if (gps && distanceM >= 10) " · " + Fmt.km(distanceM) + " km" else ""
}
