package org.circa.clock.model

data class Lap(val number: Int, val lapMs: Long, val totalMs: Long)

/** Stopwatch on `elapsedRealtime` values. [startedAt] is valid while [running]. */
data class StopwatchState(
    val running: Boolean = false,
    val startedAt: Long = 0,
    val accumulatedMs: Long = 0,
    val laps: List<Lap> = emptyList(),
) {
    fun elapsed(now: Long): Long = accumulatedMs + if (running) (now - startedAt).coerceAtLeast(0) else 0
    val isIdle get() = !running && accumulatedMs == 0L

    fun start(now: Long) = if (running) this else copy(running = true, startedAt = now)
    fun pause(now: Long) = if (running) copy(running = false, accumulatedMs = elapsed(now)) else this
    fun reset() = StopwatchState()

    /** Records a lap at [now]; the lap time is measured from the previous lap (or from zero). */
    fun lap(now: Long): StopwatchState {
        if (!running) return this
        val total = elapsed(now)
        val prev = laps.firstOrNull()?.totalMs ?: 0L
        return copy(laps = listOf(Lap(laps.size + 1, total - prev, total)) + laps)
    }

    /** Elapsed time since the last lap (what the lap counter shows), or since start with no laps. */
    fun currentLapMs(now: Long): Long = elapsed(now) - (laps.firstOrNull()?.totalMs ?: 0L)

    /** Sweep of the ring: one turn per minute. */
    fun ringFraction(now: Long): Float = (elapsed(now) % 60_000) / 60_000f

    fun encode() = listOf(if (running) 1 else 0, startedAt, accumulatedMs,
        laps.joinToString(",") { "${it.number}:${it.lapMs}:${it.totalMs}" }).joinToString("\t")

    companion object {
        fun decode(s: String?): StopwatchState = runCatching {
            val f = s!!.split("\t")
            StopwatchState(f[0] == "1", f[1].toLong(), f[2].toLong(),
                f.getOrElse(3) { "" }.split(",").filter { it.isNotEmpty() }.map {
                    val p = it.split(":"); Lap(p[0].toInt(), p[1].toLong(), p[2].toLong())
                })
        }.getOrDefault(StopwatchState())
    }
}
