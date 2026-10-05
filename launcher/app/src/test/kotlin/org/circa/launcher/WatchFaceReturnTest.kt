package org.circa.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule behind "return to the watch face": how long the screen was off decides whether the wake
 * lands on the face or stays in the app that was on screen. [WatchFaceReturn] itself only wires the
 * rule to the screen broadcasts (verified by the AOD emulator smoke test on the emulator).
 */
class WatchFaceReturnTest {

    private val t = WatchFaceReturn.SCREEN_OFF_RETURN_THRESHOLD_MS

    @Test
    fun thresholdIsFifteenSeconds() {
        assertTrue(t == 15_000L)
    }

    @Test
    fun shortScreenOffKeepsTheCurrentApp() {
        assertFalse(WatchFaceReturn.shouldReturnToFace(1_000_000L, 1_000_000L))
        assertFalse(WatchFaceReturn.shouldReturnToFace(1_000_000L, 1_000_000L + 1))
        assertFalse(WatchFaceReturn.shouldReturnToFace(1_000_000L, 1_000_000L + t - 1))
    }

    @Test
    fun screenOffAtLeastTheThresholdReturnsToTheFace() {
        assertTrue(WatchFaceReturn.shouldReturnToFace(1_000_000L, 1_000_000L + t))
        assertTrue(WatchFaceReturn.shouldReturnToFace(1_000_000L, 1_000_000L + 16_000L))
        assertTrue(WatchFaceReturn.shouldReturnToFace(1_000_000L, 1_000_000L + 3_600_000L))
    }

    @Test
    fun noScreenOffSeenNeverReturns() {
        // The launcher started while the screen was already off (or never saw the broadcast): it
        // must not steal the wake.
        assertFalse(WatchFaceReturn.shouldReturnToFace(null, 1_000_000L))
        assertFalse(WatchFaceReturn.shouldReturnToFace(null, Long.MAX_VALUE))
    }
}
