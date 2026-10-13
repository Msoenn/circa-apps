package org.circa.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Test

class StepCounterModelTest {

    @Test
    fun firstReadingStartsTheDayAtZero() {
        val r = StepCounterModel.update(null, counter = 5_000, epochDay = 100, bootCount = -1)
        assertEquals(0, r.steps)
        assertEquals(StepState(100, 5_000, 5_000, -1), r.state)
    }

    @Test
    fun stepsAreCounterMinusTheDaysBaseline() {
        val first = StepCounterModel.update(null, 5_000, 100, -1)
        val later = StepCounterModel.update(first.state, 5_432, 100, -1)
        assertEquals(432, later.steps)
        assertEquals(StepState(100, 5_000, 5_432, -1), later.state)
    }

    @Test
    fun midnightRolloverRestartsFromTheLastCounterSeenYesterday() {
        // 2,000 steps today; the last reading before midnight was 7,000.
        val yesterday = StepState(epochDay = 100, baseline = 5_000, last = 7_000)
        // First reading of the next day: 7,150 -> 150 steps belong to the new day.
        val r = StepCounterModel.update(yesterday, 7_150, 101, -1)
        assertEquals(150, r.steps)
        assertEquals(StepState(101, 7_000, 7_150, -1), r.state)
        // And the baseline then holds for the rest of that day.
        assertEquals(400, StepCounterModel.update(r.state, 7_400, 101, -1).steps)
    }

    @Test
    fun sameDayRebootCarriesTodaysStepsOver() {
        // 3,000 steps walked today, then a reboot resets the counter to 0.
        val before = StepState(epochDay = 100, baseline = 0, last = 3_000, bootCount = 7)
        val r = StepCounterModel.update(before, counter = 120, epochDay = 100, bootCount = 8)
        assertEquals(3_120, r.steps)
        assertEquals(StepState(100, -3_000, 120, 8), r.state)
        // The carried baseline keeps counting for the rest of the day.
        assertEquals(3_200, StepCounterModel.update(r.state, 200, 100, 8).steps)
    }

    @Test
    fun rebootIsDetectedByBootCountEvenWhenTheCounterIsAboveLast() {
        // 100 steps so far today; the counter after the reboot (5,200) is already above the last reading.
        val before = StepState(epochDay = 100, baseline = 5_000, last = 5_100, bootCount = 7)
        val r = StepCounterModel.update(before, counter = 5_200, epochDay = 100, bootCount = 8)
        assertEquals(5_300, r.steps)
        assertEquals(StepState(100, -100, 5_200, 8), r.state)
    }

    @Test
    fun rebootOnANewDayStartsFromTheCounterOnly() {
        val before = StepState(epochDay = 100, baseline = 5_000, last = 9_000, bootCount = 7)
        val r = StepCounterModel.update(before, counter = 60, epochDay = 101, bootCount = 8)
        assertEquals(60, r.steps)
        assertEquals(StepState(101, 0, 60, 8), r.state)
    }

    @Test
    fun aDroppedCounterCarriesOverWhenTheBootCountIsUnknown() {
        val before = StepState(epochDay = 100, baseline = 0, last = 3_000, bootCount = -1)
        val r = StepCounterModel.update(before, counter = 120, epochDay = 100, bootCount = -1)
        assertEquals(3_120, r.steps)
        assertEquals(StepState(100, -3_000, 120, -1), r.state)
    }

    @Test
    fun neverNegative() {
        val s = StepState(100, 5_000, 5_000)
        // Equal to last, below baseline can only happen with corrupt state: clamp.
        assertEquals(0, StepCounterModel.update(StepState(100, 6_000, 5_000), 5_000, 100, -1).steps)
        assertEquals(0, StepCounterModel.update(s, 5_000, 100, -1).steps)
    }
}
