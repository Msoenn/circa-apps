package org.circa.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DensityTest {

    @Test
    fun densityForMapsWidthToTargetLogicalWidth() {
        // 384px physical at density 1.2 = 320dp; target density renders it as 200dp.
        assertEquals(1.92f, Density.densityFor(384), 0.0001f)
    }

    @Test
    fun densityForIsIdentityAtTargetWidth() {
        assertEquals(1.0f, Density.densityFor(200), 0.0001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun densityForRejectsNonPositiveWidth() {
        Density.densityFor(0)
    }
}
