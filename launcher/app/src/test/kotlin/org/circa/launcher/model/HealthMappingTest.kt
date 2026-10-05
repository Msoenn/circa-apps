package org.circa.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.circa.launcher.model.HealthMapping.LatestRow
import org.junit.Test

class HealthMappingTest {

    // ---- /latest -> model ----------------------------------------------------------------------

    @Test
    fun noRowMeansNoData() {
        assertEquals(HealthData.EMPTY, HealthMapping.latest(null))
    }

    @Test
    fun liveReadingAndObservedStepsBecomeTheModel() {
        val data = HealthMapping.latest(
            LatestRow(hrBpm = 72, hrTimeMs = 1_700_000_000_000, stepsToday = 4_213, stepsTimeMs = 1_700_000_000_500),
        )
        assertEquals(72, data.hrBpm)
        assertEquals(1_700_000_000_000L, data.hrTimeMs)
        assertEquals(4_213, data.stepsToday)
    }

    @Test
    fun aBpmWithoutItsTimestampIsTreatedAsStale() {
        // WatchLink always pairs a live bpm with hr_time_ms; a row with one but not the other is not live.
        val data = HealthMapping.latest(LatestRow(hrBpm = 72, hrTimeMs = null, stepsToday = 0, stepsTimeMs = 5))
        assertNull(data.hrBpm)
        assertNull(data.hrTimeMs)
    }

    @Test
    fun neverObservedStepsAreNotAZero() {
        // steps_today = 0 with no steps_time_ms is the provider's "counter not read yet": it must not
        // win over the launcher's own step sensor.
        val data = HealthMapping.latest(LatestRow(hrBpm = null, hrTimeMs = null, stepsToday = 0, stepsTimeMs = null))
        assertNull(data.stepsToday)
    }

    @Test
    fun anObservedZeroIsRealData() {
        val data = HealthMapping.latest(LatestRow(hrBpm = null, hrTimeMs = null, stepsToday = 0, stepsTimeMs = 99))
        assertEquals(0, data.stepsToday)
    }

    @Test
    fun stepsAreClampedToInt() {
        val big = HealthMapping.latest(
            LatestRow(hrBpm = null, hrTimeMs = null, stepsToday = 5_000_000_000L, stepsTimeMs = 1),
        )
        assertEquals(Int.MAX_VALUE, big.stepsToday)
    }

    @Test
    fun anEmptyOrPartialLatestRowLeavesHistoryToTheCaller() {
        assertEquals(emptyList<HrSample>(), HealthMapping.latest(LatestRow(null, null, null, null)).history)
    }

    // ---- /history -> model ---------------------------------------------------------------------

    @Test
    fun historyKeepsTheOrderAndDropsNonPositiveSamples() {
        val rows = listOf(
            HrSample(10, 66),
            HrSample(20, 0),
            HrSample(30, 74),
            HrSample(40, -1),
        )
        assertEquals(listOf(HrSample(10, 66), HrSample(30, 74)), HealthMapping.history(rows))
    }

    // ---- step fallback order -------------------------------------------------------------------

    @Test
    fun stepsPreferWatchLinkThenFallBackToTheSensor() {
        assertEquals(4_213, HealthMapping.preferredSteps(watchLink = 4_213, sensor = 1_000))
        assertEquals(1_000, HealthMapping.preferredSteps(watchLink = null, sensor = 1_000))
        assertEquals(0, HealthMapping.preferredSteps(watchLink = 0, sensor = 1_000))
        assertNull(HealthMapping.preferredSteps(watchLink = null, sensor = null))
    }
}
