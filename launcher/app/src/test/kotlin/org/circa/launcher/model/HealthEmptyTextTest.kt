package org.circa.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Test

class HealthEmptyTextTest {
    @Test
    fun distinguishesMissingProviderFromNoSample() {
        assertEquals("Waiting for WatchLink", HealthData.emptyHeartRateText(providerReachable = false))
        assertEquals("No reading yet", HealthData.emptyHeartRateText(providerReachable = true))
    }
}
