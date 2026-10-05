package org.circa.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Test

class BrightnessCycleTest {

    @Test
    fun stepsUpThenWrapsToTheLowestLevel() {
        assertEquals(130, BrightnessCycle.next(40))
        assertEquals(230, BrightnessCycle.next(130))
        assertEquals(40, BrightnessCycle.next(230))
    }

    @Test
    fun anUnlistedLevelStepsUpToTheNextOne() {
        // Auto brightness or another app may have written any value in 0..255.
        assertEquals(130, BrightnessCycle.next(100))
        assertEquals(230, BrightnessCycle.next(131))
        assertEquals(40, BrightnessCycle.next(255))
        assertEquals(40, BrightnessCycle.next(0))
    }

    @Test
    fun activeFromTheMiddleLevelUp() {
        assertEquals(false, BrightnessCycle.isActive(40))
        assertEquals(true, BrightnessCycle.isActive(130))
        assertEquals(true, BrightnessCycle.isActive(230))
    }
}
