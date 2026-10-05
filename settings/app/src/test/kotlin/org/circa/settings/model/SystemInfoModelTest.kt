package org.circa.settings.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemInfoModelTest {
    @Test fun sizes() {
        assertEquals("5.7 GB", StorageModel.size(5_704_000_000L))
        assertEquals("377 MB", StorageModel.size(377_420_000L))
        assertEquals("128 GB", StorageModel.size(128_000_000_000L))
        assertEquals("0 B", StorageModel.size(-5))
        assertEquals(6, StorageModel.percent(368, 6082))
        assertEquals(0f, StorageModel.usedFraction(1, 0))
    }

    @Test fun offsets() {
        assertEquals("GMT+05:30", TimeZoneModel.offsetLabel(19_800_000))
        assertEquals("GMT-07:00", TimeZoneModel.offsetLabel(-25_200_000))
        assertEquals("GMT+00:00", TimeZoneModel.offsetLabel(0))
    }

    private val zones = listOf(
        ZoneRow("Europe/Berlin", "Berlin", "Germany", 7_200_000),
        ZoneRow("America/Los_Angeles", "Los Angeles", "United States", -25_200_000),
        ZoneRow("Europe/Amsterdam", "Amsterdam", "Netherlands", 7_200_000),
        ZoneRow("Asia/Kolkata", "Kolkata", "India", 19_800_000),
    )

    @Test fun sortByOffsetThenName() {
        assertEquals(
            listOf("Los Angeles", "Amsterdam", "Berlin", "Kolkata"),
            TimeZoneModel.sorted(zones).map { it.city },
        )
    }

    @Test fun search() {
        assertEquals(listOf("Berlin"), TimeZoneModel.filter(zones, "germ").map { it.city })
        assertEquals(listOf("Los Angeles"), TimeZoneModel.filter(zones, "los ang").map { it.city })
        assertEquals(listOf("Kolkata"), TimeZoneModel.filter(zones, "+05:30").map { it.city })
        assertEquals(4, TimeZoneModel.filter(zones, "  ").size)
        assertEquals("Buenos Aires", TimeZoneModel.cityFromId("America/Argentina/Buenos_Aires"))
    }

    @Test fun twentyFour() {
        assertTrue(TimeZoneModel.parse24("24", false))
        assertFalse(TimeZoneModel.parse24("12", true))
        assertTrue(TimeZoneModel.parse24(null, true))
    }
}
