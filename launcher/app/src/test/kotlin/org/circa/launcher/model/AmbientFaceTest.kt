package org.circa.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientFaceTest {

    private val minute = 60_000L

    @Test
    fun burnInOffsetStaysInsideTheBurnInBox() {
        // A full day of minutes - the shift must never leave the box the layout was measured for.
        for (m in 0L until 24L * 60L) {
            val (dx, dy) = AmbientFace.burnInOffset(m * minute)
            val radius = AmbientFace.BURN_IN_RADIUS_PX
            assertTrue("dx=$dx outside +/-$radius at minute $m", dx in -radius..radius)
            assertTrue("dy=$dy outside +/-$radius at minute $m", dy in -radius..radius)
        }
    }

    @Test
    fun burnInOffsetChangesEveryMinute() {
        var previous = AmbientFace.burnInOffset(0)
        for (m in 1L until 120L) {
            val current = AmbientFace.burnInOffset(m * minute)
            assertNotEquals("minute $m repeats its predecessor's offset", previous, current)
            previous = current
        }
    }

    @Test
    fun burnInOffsetIsStableWithinAMinute() {
        for (m in 0L until 60L) {
            val start = m * minute
            val expected = AmbientFace.burnInOffset(start)
            // Any instant inside the minute (start, mid, one ms before the next minute) draws the
            // same offset: the face must not move while the clock is showing the same reading.
            assertEquals(expected, AmbientFace.burnInOffset(start))
            assertEquals(expected, AmbientFace.burnInOffset(start + 30_000))
            assertEquals(expected, AmbientFace.burnInOffset(start + minute - 1))
        }
    }

    @Test
    fun burnInOffsetCyclesAfterEightMinutes() {
        assertEquals(8, AmbientFace.BURN_IN_CYCLE_MINUTES)
        val firstCycle = (0L until 8L).map { AmbientFace.burnInOffset(it * minute) }
        assertEquals(8, firstCycle.toSet().size)
        for (m in 0L until 8L) {
            assertEquals(firstCycle[m.toInt()], AmbientFace.burnInOffset((m + 8) * minute))
            assertEquals(firstCycle[m.toInt()], AmbientFace.burnInOffset((m + 800) * minute))
        }
    }

    @Test
    fun burnInIndexIsDefinedBeforeTheEpoch() {
        // floorDiv/floorMod, not `/`/`%`: a pre-1970 clock must still give a valid index rather than
        // a negative one (and the same offset as the matching positive minute).
        assertEquals(0, AmbientFace.burnInIndex(0))
        assertEquals(7, AmbientFace.burnInIndex(-1))
        assertEquals(AmbientFace.burnInIndex(5 * minute), AmbientFace.burnInIndex(-3 * minute))
        for (m in -8L until 0L) {
            assertTrue(AmbientFace.burnInIndex(m * minute) in 0 until 8)
        }
    }
}
