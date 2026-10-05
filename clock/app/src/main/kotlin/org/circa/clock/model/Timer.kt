package org.circa.clock.model

/** Presets of the timer grid, in minutes. */
val TIMER_PRESETS_MIN = listOf(1, 3, 5, 10, 15, 30, 60)

enum class TimerPhase { IDLE, RUNNING, PAUSED }

/**
 * One countdown timer, driven by `SystemClock.elapsedRealtime()` values passed in (so it keeps counting in
 * the background and across process death, and tests need no clock). [endAt] is valid while RUNNING,
 * [remainingMs] while PAUSED.
 */
data class TimerState(
    val phase: TimerPhase = TimerPhase.IDLE,
    val totalMs: Long = 0,
    val endAt: Long = 0,
    val pausedRemainingMs: Long = 0,
) {
    fun remaining(now: Long): Long = when (phase) {
        TimerPhase.IDLE -> totalMs
        TimerPhase.RUNNING -> (endAt - now).coerceAtLeast(0)
        TimerPhase.PAUSED -> pausedRemainingMs
    }

    /** 1.0 when full, 0.0 when done: the ring's sweep. */
    fun fraction(now: Long): Float = if (totalMs <= 0) 0f else (remaining(now).toFloat() / totalMs).coerceIn(0f, 1f)

    fun start(durationMs: Long, now: Long) = TimerState(TimerPhase.RUNNING, durationMs, now + durationMs, 0)
    fun pause(now: Long) = if (phase == TimerPhase.RUNNING) copy(phase = TimerPhase.PAUSED, pausedRemainingMs = remaining(now)) else this
    fun resume(now: Long) = if (phase == TimerPhase.PAUSED) copy(phase = TimerPhase.RUNNING, endAt = now + pausedRemainingMs) else this
    fun reset() = TimerState()

    /** +1 minute: lengthens the countdown and the ring's total, running or paused. */
    fun addMinute(now: Long): TimerState = when (phase) {
        TimerPhase.RUNNING -> copy(totalMs = totalMs + 60_000, endAt = endAt + 60_000)
        TimerPhase.PAUSED -> copy(totalMs = totalMs + 60_000, pausedRemainingMs = pausedRemainingMs + 60_000)
        TimerPhase.IDLE -> this
    }

    fun isDone(now: Long) = phase == TimerPhase.RUNNING && now >= endAt

    fun encode() = "${phase.name}\t$totalMs\t$endAt\t$pausedRemainingMs"

    companion object {
        fun decode(s: String?): TimerState = runCatching {
            val f = s!!.split("\t")
            TimerState(TimerPhase.valueOf(f[0]), f[1].toLong(), f[2].toLong(), f[3].toLong())
        }.getOrDefault(TimerState())
    }
}
