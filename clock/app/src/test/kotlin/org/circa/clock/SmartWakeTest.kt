package org.circa.clock

import org.circa.clock.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Smart Wake: the window, the early-ring checks, and that a rung occurrence does not ring again. */
class SmartWakeTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private fun ms(d: Int, h: Int, mi: Int) = ZonedDateTime.of(2026, 10, d, h, mi, 0, 0, berlin).toInstant().toEpochMilli()

    private val daily = Alarm(1, 7, 0, Days.ALL, smartWake = true)

    @Test fun nextSmartWakeIsTheEarliestSmartAlarm() {
        val plain = Alarm(2, 6, 0, Days.ALL)
        val later = Alarm(3, 8, 0, Days.ALL, smartWake = true)
        val n = AlarmLogic.nextSmartWake(listOf(plain, later, daily), ms(19, 22, 0), berlin)!!
        assertEquals(1, n.first.id)
        assertEquals(ms(20, 7, 0), n.second)
        assertNull(AlarmLogic.nextSmartWake(listOf(plain), ms(19, 22, 0), berlin))
    }

    @Test fun mayRingOnlyInsideTheWindow() {
        val at = ms(20, 7, 0)
        assertFalse(AlarmLogic.smartWakeMayRing(daily, at, ms(20, 6, 29), berlin))
        assertTrue(AlarmLogic.smartWakeMayRing(daily, at, ms(20, 6, 30), berlin))
        assertTrue(AlarmLogic.smartWakeMayRing(daily, at, ms(20, 6, 59), berlin))
        // Less than a minute before the set time: the regular alarm rings instead.
        assertFalse(AlarmLogic.smartWakeMayRing(daily, at, at - 30_000, berlin))
    }

    @Test fun mayNotRingWhenOffOrStale() {
        val at = ms(20, 7, 0)
        val now = ms(20, 6, 45)
        assertFalse(AlarmLogic.smartWakeMayRing(daily.copy(smartWake = false), at, now, berlin))
        assertFalse(AlarmLogic.smartWakeMayRing(daily.copy(enabled = false), at, now, berlin))
        // The alarm was moved to 07:30 after the request went out: the old occurrence is stale.
        assertFalse(AlarmLogic.smartWakeMayRing(daily.copy(minute = 30), at, now, berlin))
    }

    @Test fun earlyRingSpendsTheOccurrence() {
        val at = ms(20, 7, 0)
        val after = AlarmLogic.afterSmartWake(daily, at)
        // At 06:45 the next occurrence is tomorrow, not 07:00 today.
        assertEquals(ms(21, 7, 0), AlarmLogic.nextRegular(after, ms(20, 6, 45), berlin))
        assertFalse(AlarmLogic.smartWakeMayRing(after, at, ms(20, 6, 50), berlin))
    }

    @Test fun earlyRingSwitchesAOneShotOff() {
        val once = Alarm(4, 7, 0, smartWake = true)
        assertFalse(AlarmLogic.afterSmartWake(once, ms(20, 7, 0)).enabled)
    }

    @Test fun codecKeepsSmartWakeAndReadsOldLines() {
        val a = daily.copy(smartRangAt = ms(20, 7, 0), label = "Work")
        assertEquals(listOf(a), AlarmCodec.decode(AlarmCodec.encode(listOf(a))))
        val old = "5\t7\t0\t127\t1\t10\t0\tWork"
        val d = AlarmCodec.decode(old).single()
        assertFalse(d.smartWake)
        assertNull(d.smartRangAt)
        assertEquals("Work", d.label)
    }

    @Test fun alarmSoundsUnderDndThatAllowsAlarms() {
        // DND reports the ringer as silent; a policy that lets alarms through keeps the sound (Bedtime).
        assertEquals(AlertPlan(true, true), Alert.plan(Alert.RINGER_SILENT, false, dndAllowsAlarms = true))
        // Total silence still removes it.
        assertEquals(AlertPlan(false, true), Alert.plan(Alert.RINGER_SILENT, true, dndAllowsAlarms = true))
    }
}
