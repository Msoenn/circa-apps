package org.circa.launcher.model

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComplicationFormatTest {

    @Test
    fun formatTime24Hour() {
        assertEquals("07:05", ComplicationFormat.formatTime(LocalTime.of(7, 5), is24Hour = true))
        assertEquals("22:47", ComplicationFormat.formatTime(LocalTime.of(22, 47), is24Hour = true))
    }

    @Test
    fun formatTime12Hour() {
        assertEquals("7:05 AM", ComplicationFormat.formatTime(LocalTime.of(7, 5), is24Hour = false))
        assertEquals("10:47 PM", ComplicationFormat.formatTime(LocalTime.of(22, 47), is24Hour = false))
    }

    @Test
    fun formatDate() {
        assertEquals("Thu 1 Oct", ComplicationFormat.formatDate(LocalDate.of(2026, 10, 1)))
    }

    @Test
    fun formatBattery() {
        assertEquals("87%", ComplicationFormat.formatBattery(87))
        assertNull(ComplicationFormat.formatBattery(null))
    }

    @Test
    fun formatHeartRate() {
        assertEquals("72", ComplicationFormat.formatHeartRate(72))
        assertNull(ComplicationFormat.formatHeartRate(null))
    }

    @Test
    fun timePartsSeparateDigitsFromAmPm() {
        assertEquals(
            ComplicationFormat.TimeParts("4:58", "PM"),
            ComplicationFormat.timeParts(LocalTime.of(16, 58), is24Hour = false),
        )
        assertEquals(
            ComplicationFormat.TimeParts("16:58", null),
            ComplicationFormat.timeParts(LocalTime.of(16, 58), is24Hour = true),
        )
    }

    @Test
    fun formatShortTimeHasNoAmPm() {
        assertEquals("16:58", ComplicationFormat.formatShortTime(LocalTime.of(16, 58), true))
        assertEquals("4:58", ComplicationFormat.formatShortTime(LocalTime.of(16, 58), false))
        assertEquals("07:05", ComplicationFormat.formatShortTime(LocalTime.of(7, 5), true))
    }

    @Test
    fun formatAlarmShortUsesZoneAndDropsAmPm() {
        // 2026-10-01T07:30:00Z in UTC.
        val millis = 1_790_839_800_000L
        assertEquals(
            "07:30",
            ComplicationFormat.formatAlarmShort(millis, is24Hour = true, ZoneId.of("UTC")),
        )
        assertEquals(
            "7:30",
            ComplicationFormat.formatAlarmShort(millis, is24Hour = false, ZoneId.of("UTC")),
        )
    }

    @Test
    fun formatStepsIsCompact() {
        assertNull(ComplicationFormat.formatSteps(null))
        assertEquals("0", ComplicationFormat.formatSteps(0))
        assertEquals("832", ComplicationFormat.formatSteps(832))
        assertEquals("1.0k", ComplicationFormat.formatSteps(1000))
        assertEquals("4.2k", ComplicationFormat.formatSteps(4213))
        assertEquals("9.9k", ComplicationFormat.formatSteps(9999))
        assertEquals("12k", ComplicationFormat.formatSteps(12_480))
    }

    @Test
    fun formatStepsFullHasThousandsSeparator() {
        assertNull(ComplicationFormat.formatStepsFull(null))
        assertEquals("4,213", ComplicationFormat.formatStepsFull(4213))
        assertEquals("832", ComplicationFormat.formatStepsFull(832))
    }
}
