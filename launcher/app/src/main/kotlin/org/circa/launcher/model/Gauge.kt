package org.circa.launcher.model

/**
 * How full a complication ring is. Each function returns null when there is no data, which is how
 * the UI knows to draw the "no data" state (empty ring, dimmed icon) rather than a zero.
 */
object Gauge {

    /** Daily step goal the steps ring fills against. */
    const val STEP_GOAL = 10_000

    /** Heart-rate range the ring maps onto (resting to hard effort). */
    const val HEART_MIN = 40
    const val HEART_MAX = 180

    fun fraction(value: Int, max: Int): Float =
        if (max <= 0) 0f else (value.toFloat() / max).coerceIn(0f, 1f)

    fun battery(percent: Int?): Float? = percent?.let { fraction(it, 100) }

    fun steps(steps: Int?): Float? = steps?.let { fraction(it, STEP_GOAL) }

    fun heartRate(bpm: Int?): Float? = bpm?.let {
        ((it - HEART_MIN).toFloat() / (HEART_MAX - HEART_MIN)).coerceIn(0f, 1f)
    }
}

/** Sparkline geometry: heart-rate samples to points in a width x height box. */
object Sparkline {

    data class Point(val x: Float, val y: Float)

    /**
     * Evenly spaced points, the largest value at the top. A flat series sits on the vertical middle;
     * fewer than two samples gives no line.
     */
    fun points(values: List<Int>, width: Float, height: Float): List<Point> {
        if (values.size < 2) return emptyList()
        val min = values.min()
        val max = values.max()
        val span = (max - min).toFloat()
        return values.mapIndexed { i, v ->
            val x = width * i / (values.size - 1)
            val y = if (span == 0f) height / 2f else height * (1f - (v - min) / span)
            Point(x, y)
        }
    }
}
