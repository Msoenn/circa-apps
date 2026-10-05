package org.circa.clock

import org.circa.clock.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ClockModelTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private fun ms(z: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, z).toInstant().toEpochMilli()

    @Test fun onceAlarmLaterToday() {
        val a = Alarm(1, 7, 0)
        assertEquals(ms(berlin, 2026, 10, 5, 7, 0), AlarmLogic.nextRegular(a, ms(berlin, 2026, 10, 5, 6, 59), berlin))
    }
    @Test fun onceAlarmAlreadyPastGoesTomorrow() {
        val a = Alarm(1, 7, 0)
        assertEquals(ms(berlin, 2026, 10, 6, 7, 0), AlarmLogic.nextRegular(a, ms(berlin, 2026, 10, 5, 7, 0), berlin))
    }
    @Test fun weekdaysSkipWeekend() {
        val a = Alarm(1, 7, 0, Days.WEEKDAYS)
        // Fri 2026-10-09 08:00 -> Mon 12th
        assertEquals(ms(berlin, 2026, 10, 12, 7, 0), AlarmLogic.nextRegular(a, ms(berlin, 2026, 10, 9, 8, 0), berlin))
    }
    @Test fun weekendFromWednesday() {
        val a = Alarm(1, 9, 30, Days.WEEKEND)
        assertEquals(ms(berlin, 2026, 10, 10, 9, 30), AlarmLogic.nextRegular(a, ms(berlin, 2026, 10, 7, 12, 0), berlin))
    }
    @Test fun sameWeekdayNextWeek() {
        val a = Alarm(1, 7, 0, Days.toggle(0, 0)) // Monday only
        assertEquals(ms(berlin, 2026, 10, 12, 7, 0), AlarmLogic.nextRegular(a, ms(berlin, 2026, 10, 5, 7, 0), berlin))
    }
    @Test fun disabledHasNoTrigger() {
        assertNull(AlarmLogic.nextRegular(Alarm(1, 7, 0, enabled = false), 0, berlin))
    }
    @Test fun dstSpringForwardKeepsWallClock() {
        // Berlin switches 2026-03-29 02:00 -> 03:00. Daily 07:00 alarm stays 07:00 local (23 h later).
        val a = Alarm(1, 7, 0, Days.ALL)
        val t = AlarmLogic.nextRegular(a, ms(berlin, 2026, 3, 28, 7, 30), berlin)!!
        assertEquals(ms(berlin, 2026, 3, 29, 7, 0), t)
        assertEquals(23 * 3_600_000L + 30 * 60_000L - 30 * 60_000L, t - ms(berlin, 2026, 3, 28, 7, 0) )
    }
    @Test fun dstGapAlarmRingsAfterGap() {
        val a = Alarm(1, 2, 30, Days.ALL)
        val t = AlarmLogic.nextRegular(a, ms(berlin, 2026, 3, 29, 0, 10), berlin)!!
        assertEquals(ZonedDateTime.of(2026, 3, 29, 3, 30, 0, 0, berlin).toInstant().toEpochMilli(), t)
    }
    @Test fun dstFallBackFirstOccurrence() {
        // 2026-10-25 03:00 -> 02:00; a 02:30 alarm rings once at the first 02:30 (CEST).
        val a = Alarm(1, 2, 30, Days.ALL)
        val t = AlarmLogic.nextRegular(a, ms(berlin, 2026, 10, 25, 0, 0), berlin)!!
        assertEquals(ZonedDateTime.of(2026, 10, 25, 2, 30, 0, 0, berlin).toInstant().toEpochMilli(), t)
    }
    @Test fun timeZoneChangeMovesTrigger() {
        val a = Alarm(1, 7, 0)
        val now = ms(berlin, 2026, 10, 5, 12, 0)
        val ny = ZoneId.of("America/New_York")
        assertNotEquals(AlarmLogic.nextRegular(a, now, berlin), AlarmLogic.nextRegular(a, now, ny))
    }
    @Test fun snoozeAddsMinutesAndWins() {
        val a = Alarm(1, 7, 0, Days.ALL)
        val now = ms(berlin, 2026, 10, 5, 7, 0)
        val s = AlarmLogic.snooze(a, now)
        assertEquals(now + 600_000, s.snoozedUntil)
        assertEquals(now + 600_000, AlarmLogic.nextTrigger(s, now, berlin))
    }
    @Test fun snoozeCustomLength() {
        val s = AlarmLogic.snooze(Alarm(1, 7, 0, snoozeMinutes = 5), 1000)
        assertEquals(1000L + 300_000L, s.snoozedUntil)
    }
    @Test fun afterFireOnceSwitchesOffButSnoozeStillRings() {
        val a = Alarm(1, 7, 0)
        val fired = AlarmLogic.afterFire(a)
        assertFalse(fired.enabled)
        val s = AlarmLogic.snooze(fired, 5_000_000L)
        assertEquals(5_000_000L + 600_000L, AlarmLogic.nextTrigger(s, 5_000_000L, berlin))
    }
    @Test fun afterFireRepeatingStaysOn() {
        assertTrue(AlarmLogic.afterFire(Alarm(1, 7, 0, Days.ALL, snoozedUntil = 5)).enabled)
        assertNull(AlarmLogic.afterFire(Alarm(1, 7, 0, Days.ALL, snoozedUntil = 5)).snoozedUntil)
    }
    @Test fun earliestPicksSoonest() {
        val now = ms(berlin, 2026, 10, 5, 6, 0)
        val r = AlarmLogic.earliest(listOf(Alarm(1, 8, 0), Alarm(2, 6, 30), Alarm(3, 5, 0, enabled = false)), now, berlin)!!
        assertEquals(2, r.first.id)
    }
    @Test fun codecRoundTrip() {
        val l = listOf(Alarm(1, 7, 5, Days.WEEKDAYS, "Gym\tnow & go", true, 5, 12345), Alarm(2, 23, 59, enabled = false))
        assertEquals(l, AlarmCodec.decode(AlarmCodec.encode(l)))
        assertEquals(emptyList<Alarm>(), AlarmCodec.decode(""))
    }
    @Test fun summaries() {
        assertEquals("Once", Days.summary(0)); assertEquals("Every day", Days.summary(Days.ALL))
        assertEquals("Mon - Fri", Days.summary(Days.WEEKDAYS)); assertEquals("Mon, Wed", Days.summary(0b101))
    }
    @Test fun untilText() {
        assertEquals("9 h 12 min", AlarmLogic.untilText(0, (9 * 60 + 11) * 60_000L + 30_000))
        assertEquals("1 min", AlarmLogic.untilText(0, 10_000))
    }

    // ---- timer
    @Test fun timerCountsDownAndFinishes() {
        val t = TimerState().start(60_000, 1000)
        assertEquals(45_000, t.remaining(16_000)); assertFalse(t.isDone(60_999)); assertTrue(t.isDone(61_000))
        assertEquals(0, t.remaining(100_000))
    }
    @Test fun timerPauseResume() {
        val t = TimerState().start(60_000, 0).pause(20_000)
        assertEquals(40_000, t.remaining(99_999))
        val r = t.resume(100_000)
        assertEquals(40_000, r.remaining(100_000)); assertEquals(140_000, r.endAt)
    }
    @Test fun timerPlusMinuteAndFraction() {
        val t = TimerState().start(60_000, 0).addMinute(30_000)
        assertEquals(120_000, t.totalMs); assertEquals(90_000, t.remaining(30_000))
        assertEquals(0.75f, t.fraction(30_000), 0.001f)
        assertEquals(TimerState(), t.reset())
    }
    @Test fun timerCodec() {
        val t = TimerState().start(5000, 77).pause(80)
        assertEquals(t, TimerState.decode(t.encode()))
        assertEquals(TimerState(), TimerState.decode("garbage"))
    }
    @Test fun formatDuration() {
        assertEquals("1:00", Fmt.duration(60_000)); assertEquals("0:01", Fmt.duration(1)); assertEquals("1:00:00", Fmt.duration(3_600_000))
        assertEquals("00:01.50", Fmt.stopwatch(1500)); assertEquals("1:02:03.04", Fmt.stopwatch(3_723_040))
    }

    // ---- stopwatch
    @Test fun stopwatchRunsAndPauses() {
        val s = StopwatchState().start(100)
        assertEquals(400, s.elapsed(500))
        val p = s.pause(600)
        assertEquals(500, p.elapsed(10_000))
        assertEquals(700, p.start(1000).elapsed(1200))
    }
    @Test fun stopwatchLaps() {
        var s = StopwatchState().start(0)
        s = s.lap(1000).lap(3500).lap(4000)
        assertEquals(listOf(3, 2, 1), s.laps.map { it.number })
        assertEquals(listOf(500L, 2500L, 1000L), s.laps.map { it.lapMs })
        assertEquals(4000, s.laps.first().totalMs)
        assertEquals(1000, s.currentLapMs(5000))
    }
    @Test fun stopwatchLapIgnoredWhenPaused() {
        assertEquals(0, StopwatchState().start(0).pause(10).lap(20).laps.size)
        assertTrue(StopwatchState().reset().isIdle)
    }
    @Test fun stopwatchCodecAndRing() {
        val s = StopwatchState().start(0).lap(1500).lap(2000)
        assertEquals(s, StopwatchState.decode(s.encode()))
        assertEquals(0.5f, StopwatchState().start(0).ringFraction(90_000), 0.001f)
    }

    // ---- picker
    @Test fun pickerWraps() {
        var p = PickerState(23, 59)
        assertEquals(0, p.step(1).hour); assertEquals(59, p.step(1).minute)
        p = p.select(PickerColumn.MINUTE)
        assertEquals(0, p.step(1).minute); assertEquals(23, p.step(1).hour)
        assertEquals(59, PickerState(0, 0, PickerColumn.MINUTE).step(-1).minute)
        assertEquals(23, PickerState(0, 0).step(-1).hour)
        assertEquals(1, PickerState(0, 0).step(25).hour)
    }
    @Test fun pickerFormats() {
        assertEquals("07", PickerState(7, 5).hourText); assertEquals("05", PickerState(7, 5).minuteText)
        val p12 = PickerState(0, 5, is24h = false)
        assertEquals("12", p12.hourText); assertFalse(p12.isPm)
        assertEquals("1", PickerState(13, 0, is24h = false).hourText); assertTrue(PickerState(13, 0, is24h = false).isPm)
        assertEquals(12, p12.toggleAmPm().hour)
        assertEquals("23" to "01", PickerState(0, 0).neighbours(PickerColumn.HOUR))
        assertEquals("7:05", Fmt.time(7, 5, false)); assertEquals("12:00", Fmt.time(12, 0, false)); assertEquals("00:00", Fmt.time(0, 0, true))
    }

    // ---- alert
    @Test fun alertFollowsRinger() {
        assertEquals(AlertPlan(true, true), Alert.plan(Alert.RINGER_NORMAL, false))
        assertEquals(AlertPlan(false, true), Alert.plan(Alert.RINGER_VIBRATE, false))
        assertEquals(AlertPlan(false, true), Alert.plan(Alert.RINGER_SILENT, false))
        assertEquals(AlertPlan(false, true), Alert.plan(Alert.RINGER_NORMAL, true))
    }
    @Test fun vibrationEscalates() {
        val w = Alert.escalatingWave()
        val on = w.amplitudes.filter { it > 0 }
        assertEquals(on.sorted(), on); assertTrue(on.first() < on.last())
        assertEquals(w.timings.size, w.amplitudes.size)
        assertTrue(w.amplitudes[w.repeatIndex] > 0)
    }
}

class AlarmIntentsTest {
    @Test fun calendarDaysMapToMonFirstMask() {
        // Calendar: SUNDAY=1, MONDAY=2 ... SATURDAY=7
        assertEquals(Days.WEEKDAYS, AlarmIntents.daysMask(listOf(2, 3, 4, 5, 6)))
        assertEquals(Days.WEEKEND, AlarmIntents.daysMask(listOf(1, 7)))
        assertEquals(0, AlarmIntents.daysMask(null)); assertEquals(0, AlarmIntents.daysMask(listOf(0, 9)))
    }
    @Test fun timerLength() {
        assertEquals(90_000L, AlarmIntents.timerMs(90)); assertNull(AlarmIntents.timerMs(0)); assertNull(AlarmIntents.timerMs(90_000))
    }
    @Test fun duplicates() {
        val l = listOf(Alarm(3, 7, 0, Days.WEEKDAYS))
        assertEquals(3, AlarmIntents.findDuplicate(l, 7, 0, Days.WEEKDAYS)?.id); assertNull(AlarmIntents.findDuplicate(l, 7, 1, 0))
    }
}
