package org.circa.settings.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryModelTest {
    private fun app(p: String, mah: Double, fg: Long) = RawAppUsage(10000, p, p, mah, fg)

    @Test fun durations() {
        assertEquals("0 min", BatteryUsageModel.duration(0))
        assertEquals("< 1 min", BatteryUsageModel.duration(59_000))
        assertEquals("12 min", BatteryUsageModel.duration(12 * 60_000L))
        assertEquals("1 h", BatteryUsageModel.duration(3_600_000L))
        assertEquals("1 h 5 min", BatteryUsageModel.duration(3_900_000L))
    }

    @Test fun rowsSortByPowerAndShowPercentOfTotal() {
        val rows = BatteryUsageModel.rows(listOf(app("a", 10.0, 0), app("b", 30.0, 120_000), app("c", 60.0, 0)))
        assertEquals(listOf("c", "b", "a"), rows.map { it.label })
        assertEquals(listOf(60, 30, 10), rows.map { it.percent })
        assertEquals("30% · 2 min", rows[1].secondary)
    }

    @Test fun withoutPowerRowsFallBackToForegroundTime() {
        val rows = BatteryUsageModel.rows(listOf(app("a", 0.0, 120_000), app("b", 0.0, 600_000), app("idle", 0.0, 5_000)))
        assertEquals(listOf("b", "a"), rows.map { it.label })
        assertNull(rows[0].percent)
        assertEquals("10 min", rows[0].secondary)
    }

    @Test fun rowsAreLimited() {
        val many = (1..40).map { app("p$it", it.toDouble(), 0) }
        assertEquals(BatteryUsageModel.MAX_ROWS, BatteryUsageModel.rows(many).size)
    }

    @Test fun screenOnTime() {
        val on = BatteryUsageModel.SCREEN_ON
        val off = BatteryUsageModel.SCREEN_OFF
        assertEquals(30L, BatteryUsageModel.screenOnMs(listOf(on to 10L, off to 40L), 0, 100))
        // off first: the screen was on since the window start; on without off runs to the end
        assertEquals(10L + 50L, BatteryUsageModel.screenOnMs(listOf(off to 10L, on to 50L), 0, 100))
        assertEquals(0L, BatteryUsageModel.screenOnMs(emptyList(), 0, 100))
    }

    @Test fun lines() {
        assertEquals("50% · Charging", BatteryUsageModel.statusLine(50, true))
        assertEquals("- · On battery", BatteryUsageModel.statusLine(null, false))
        assertNull(BatteryUsageModel.remaining(-1, false))
        assertEquals("About 2 h left", BatteryUsageModel.remaining(7_200_000, false))
        assertEquals("Last 24 hours", BatteryUsageModel.periodLabel(1000, null))
    }
}
