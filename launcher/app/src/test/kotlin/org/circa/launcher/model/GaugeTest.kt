package org.circa.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GaugeTest {

    @Test
    fun fractionIsClampedToTheUnitInterval() {
        assertEquals(0.5f, Gauge.fraction(50, 100), 1e-6f)
        assertEquals(1f, Gauge.fraction(250, 100), 0f)
        assertEquals(0f, Gauge.fraction(-3, 100), 0f)
        assertEquals(0f, Gauge.fraction(5, 0), 0f)
    }

    @Test
    fun noDataIsNullNotZero() {
        assertNull(Gauge.battery(null))
        assertNull(Gauge.steps(null))
        assertNull(Gauge.heartRate(null))
        assertEquals(0f, Gauge.steps(0)!!, 0f)
    }

    @Test
    fun batteryAndStepsFill() {
        assertEquals(0.86f, Gauge.battery(86)!!, 1e-6f)
        assertEquals(0.4213f, Gauge.steps(4213)!!, 1e-6f)
        assertEquals(1f, Gauge.steps(25_000)!!, 0f)
    }

    @Test
    fun heartRateMapsTheRestingToHardRange() {
        assertEquals(0f, Gauge.heartRate(Gauge.HEART_MIN)!!, 0f)
        assertEquals(1f, Gauge.heartRate(Gauge.HEART_MAX)!!, 0f)
        assertEquals(0.5f, Gauge.heartRate(110)!!, 1e-6f)
        assertEquals(0f, Gauge.heartRate(30)!!, 0f)
    }

    @Test
    fun sparklineNeedsTwoSamples() {
        assertTrue(Sparkline.points(emptyList(), 100f, 20f).isEmpty())
        assertTrue(Sparkline.points(listOf(70), 100f, 20f).isEmpty())
    }

    @Test
    fun sparklineSpansTheBoxWithTheMaximumAtTheTop() {
        val p = Sparkline.points(listOf(60, 90, 75), 100f, 20f)
        assertEquals(3, p.size)
        assertEquals(0f, p.first().x, 0f)
        assertEquals(100f, p.last().x, 0f)
        assertEquals(20f, p[0].y, 0f) // minimum at the bottom
        assertEquals(0f, p[1].y, 0f) // maximum at the top
        assertEquals(10f, p[2].y, 1e-4f)
    }

    @Test
    fun flatSparklineSitsInTheMiddle() {
        val p = Sparkline.points(listOf(70, 70, 70), 90f, 20f)
        assertTrue(p.all { it.y == 10f })
    }
}
