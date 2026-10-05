package org.circa.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationTimeTest {

    private val now = 1_000_000_000_000L

    @Test
    fun agesWithinTheHourReadAsNowThenMinutes() {
        assertEquals("Now", NotificationTime.format(now, now))
        assertEquals("Now", NotificationTime.format(now, now - 59_000))
        assertEquals("1m", NotificationTime.format(now, now - 60_000))
        assertEquals("59m", NotificationTime.format(now, now - 59 * 60_000))
    }

    @Test
    fun agesWithinTheDayReadAsHours() {
        assertEquals("1h", NotificationTime.format(now, now - 60 * 60_000))
        assertEquals("23h", NotificationTime.format(now, now - 23 * 60 * 60_000))
    }

    @Test
    fun agesBeyondADayReadAsDays() {
        assertEquals("1d", NotificationTime.format(now, now - 24 * 60 * 60_000))
        assertEquals("3d", NotificationTime.format(now, now - 3 * 24 * 60 * 60_000))
    }

    @Test
    fun aFutureTimestampIsNotAnAge() {
        // Clock skew between the posting app and the launcher must not render as "-1m".
        assertEquals("Now", NotificationTime.format(now, now + 5_000))
    }
}
