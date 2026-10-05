package org.circa.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AodBrightnessTest {
    @Test fun unsetOrUnknownIsNormal() {
        assertEquals(AodBrightness.NORMAL, AodBrightness.resolve(null))
        assertEquals(AodBrightness.NORMAL, AodBrightness.resolve(""))
        assertEquals(AodBrightness.NORMAL, AodBrightness.resolve("9"))
        assertEquals(AodBrightness.NORMAL, AodBrightness.resolve("high"))
    }

    @Test fun storedNumbersMatchSettings() {
        assertEquals(AodBrightness.LOW, AodBrightness.resolve("0"))
        assertEquals(AodBrightness.NORMAL, AodBrightness.resolve(" 1 "))
        assertEquals(AodBrightness.HIGH, AodBrightness.resolve("2"))
    }

    @Test fun levelsRiseWithinTheStockDozeRange() {
        val b = AodBrightness.entries.map { it.dozeBrightness }
        assertEquals(b.sorted(), b)
        assertEquals(0f, b.first(), 0f)
        // The watch's doze_normal curve tops out at 0.0428.
        assertTrue(b.last() <= 0.0435f)
    }
}
