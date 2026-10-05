package org.circa.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Test

class StepCounterModelTest {

    @Test
    fun firstReadingStartsTheDayAtZero() {
        val r = StepCounterModel.update(null, counter = 5_000, epochDay = 100)
        assertEquals(0, r.steps)
        assertEquals(StepState(100, 5_000, 5_000), r.state)
    }

    @Test
    fun stepsAreCounterMinusTheDaysBaseline() {
        val first = StepCounterModel.update(null, 5_000, 100)
        val later = StepCounterModel.update(first.state, 5_432, 100)
        assertEquals(432, later.steps)
        assertEquals(StepState(100, 5_000, 5_432), later.state)
    }

    @Test
    fun midnightRolloverRestartsFromTheLastCounterSeenYesterday() {
        // 2,000 steps today; the last reading before midnight was 7,000.
        val yesterday = StepState(epochDay = 100, baseline = 5_000, last = 7_000)
        // First reading of the next day: 7,150 -> 150 steps belong to the new day.
        val r = StepCounterModel.update(yesterday, 7_150, 101)
        assertEquals(150, r.steps)
        assertEquals(StepState(101, 7_000, 7_150), r.state)
        // And the baseline then holds for the rest of that day.
        assertEquals(400, StepCounterModel.update(r.state, 7_400, 101).steps)
    }

    @Test
    fun rebootResetsTheCounterSoEverythingSinceBootCounts() {
        val before = StepState(epochDay = 100, baseline = 5_000, last = 9_000)
        val r = StepCounterModel.update(before, 120, 100)
        assertEquals(120, r.steps)
        assertEquals(StepState(100, 0, 120), r.state)
    }

    @Test
    fun rebootOnTheNewDayAlsoStartsFromZero() {
        val before = StepState(epochDay = 100, baseline = 5_000, last = 9_000)
        assertEquals(60, StepCounterModel.update(before, 60, 101).steps)
    }

    @Test
    fun neverNegative() {
        val s = StepState(100, 5_000, 5_000)
        // Equal to last, below baseline can only happen with corrupt state: clamp.
        assertEquals(0, StepCounterModel.update(StepState(100, 6_000, 5_000), 5_000, 100).steps)
        assertEquals(0, StepCounterModel.update(s, 5_000, 100).steps)
    }
}
