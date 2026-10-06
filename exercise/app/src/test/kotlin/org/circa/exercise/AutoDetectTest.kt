package org.circa.exercise

import org.circa.exercise.data.DebugReceiver
import org.circa.exercise.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

private const val MIN = 60_000L
private const val T0 = 1_760_000_000_000L   // an arbitrary "3:00" in epoch ms
private const val THR = 114                 // zone 2 for max HR 190 (age 30)

private fun sample(min: Double, bpm: Int, steps: Long) = AutoSample(T0 + (min * MIN).toLong(), bpm, steps)

/** Feeds a list of (minute, bpm, cumulative steps); returns the first detection (and the sample index it fired on). */
private fun AutoDetector.feed(vararg s: Triple<Double, Int, Long>, blocked: Boolean = false): Detection? {
    var hit: Detection? = null
    for ((m, b, st) in s) onSample(sample(m, b, st), THR, blocked).let { if (hit == null) hit = it }
    return hit
}

class AutoDetectorTest {
    @Test fun hillWalkIsDetectedAndKept() {
        // HR 150-160 at 100 steps/min for 12 minutes, samples every 5 min.
        val d = AutoDetector()
        assertNull(d.onSample(sample(0.0, 85, 0), THR, false))
        assertNull(d.onSample(sample(5.0, 152, 500), THR, false))
        val hit = d.onSample(sample(10.0, 158, 1000), THR, false)!!
        assertEquals(ActivityType.WALK, hit.type)
        assertEquals("start = the first elevated sample's interval start", T0, hit.startMs)
        assertEquals(2, hit.intervals.size)

        // The recording: backdated to T0, 10 min in at detection.
        val now = T0 + 10 * MIN
        val w = Workout(hit.type, hit.startMs, 190, 30, 70.0, Sex.MALE)
        w.markAuto(now, THR)
        val rows = w.backfill(hit.intervals, now)
        assertEquals(600, rows.size)
        assertEquals(1000L, w.steps)
        assertEquals(158, w.hrMax)
        assertTrue(w.zoneMs[4] > 0)               // 152 and 158 bpm of max 190 = zone 4
        assertEquals(10 * MIN, w.zoneMs.sum())
        assertEquals(10 * MIN, w.activeMs(now))
        assertTrue(w.autoPending)
        // Two more minutes of the walk with HR 155 and 100 steps/min: it is never at rest, no answer = keep, not dropped.
        var t = now
        var counter = 10_000L
        w.onStepCounter(counter)
        repeat(120) { i ->
            t += 1000
            if (i % 36 == 0) { counter += 60; w.onStepCounter(counter) }
            w.onHr(155, t); w.tick(t)
        }
        assertFalse(w.autoRestExceeded(t))
        assertTrue(AutoRules.decisionDue(now, t))
        assertFalse(AutoRules.shouldDrop(w.startMs, w.autoLastActive()))
        w.keepAuto()
        assertFalse(w.autoPending)
        assertTrue(w.auto)
    }

    @Test fun highHrWithoutStepsNeverTriggers() {
        // Stress: HR 130 all hour, a few steps.
        val d = AutoDetector()
        val hit = d.feed(
            Triple(0.0, 130, 0L), Triple(5.0, 132, 20L), Triple(10.0, 135, 45L), Triple(15.0, 131, 60L),
            Triple(20.0, 140, 80L), Triple(25.0, 138, 100L),
        )
        assertNull(hit)
        assertEquals(0, d.elevatedRun.size)
    }

    @Test fun stepsWithLowHrNeverTriggers() {
        val d = AutoDetector()
        assertNull(d.feed(Triple(0.0, 80, 0L), Triple(5.0, 95, 500L), Triple(10.0, 99, 1000L), Triple(15.0, 100, 1500L)))
    }

    @Test fun runAt160StepsPerMinute() {
        val d = AutoDetector()
        val hit = d.feed(Triple(0.0, 90, 0L), Triple(5.0, 165, 800L), Triple(10.0, 170, 1600L))!!
        assertEquals(ActivityType.RUN, hit.type)
        assertEquals(160.0, hit.intervals[0].cadence, 0.01)
    }

    @Test fun cadenceMedianDecidesWalkOrRun() {
        fun iv(steps: Long) = AutoInterval(T0, T0 + 5 * MIN, 140, steps)
        assertEquals(ActivityType.WALK, AutoDetector.typeFor(listOf(iv(745), iv(745))))   // 149 spm
        assertEquals(ActivityType.RUN, AutoDetector.typeFor(listOf(iv(750), iv(750))))    // 150 spm
        assertEquals(ActivityType.WALK, AutoDetector.typeFor(listOf(iv(400), iv(900), iv(400))))
    }

    @Test fun oneElevatedSampleIsNotEnough() {
        val d = AutoDetector()
        assertNull(d.feed(Triple(0.0, 80, 0L), Triple(5.0, 150, 500L), Triple(10.0, 90, 520L), Triple(15.0, 150, 1020L)))
        assertEquals("a calm sample resets the run", 1, d.elevatedRun.size)
    }

    @Test fun thresholdsAreInclusive() {
        val d = AutoDetector()
        // exactly 80 spm and exactly the threshold
        assertNotNull(d.feed(Triple(0.0, 70, 0L), Triple(5.0, THR, 400L), Triple(10.0, THR, 800L)))
        val e = AutoDetector()
        assertNull(e.feed(Triple(0.0, 70, 0L), Triple(5.0, THR - 1, 400L), Triple(10.0, THR, 800L)))
        val f = AutoDetector()
        assertNull(f.feed(Triple(0.0, 70, 0L), Triple(5.0, THR, 399L), Triple(10.0, THR, 799L)))
    }

    @Test fun gapAndStepCounterResetBreakTheRun() {
        val a = AutoDetector()
        assertNull(a.feed(Triple(0.0, 70, 0L), Triple(5.0, 150, 500L), Triple(20.0, 150, 2000L)))   // 15 min gap
        val b = AutoDetector()
        assertNull(b.feed(Triple(0.0, 70, 5000L), Triple(5.0, 150, 5500L), Triple(10.0, 150, 100L)))   // midnight / reboot
        assertNull(AutoDetector().feed(Triple(0.0, 150, 500L)))   // the first sample has no interval
    }

    @Test fun noDetectionWhileAWorkoutIsRecording() {
        val d = AutoDetector()
        assertNull(d.feed(Triple(0.0, 70, 0L), Triple(5.0, 150, 500L), Triple(10.0, 150, 1000L), Triple(15.0, 150, 1500L), blocked = true))
        assertEquals(0, d.elevatedRun.size)
        // Once the manual workout is over, two FRESH elevated samples are needed (the one in between does not count).
        assertNull(d.onSample(sample(20.0, 150, 2000), THR, false))
        assertNotNull(d.onSample(sample(25.0, 150, 2500), THR, false))
    }

    @Test fun cooldownAfterAnAutoWorkoutEndedOrWasDiscarded() {
        val d = AutoDetector()
        val endAt = T0 + 20 * MIN
        d.noteEnded(endAt)
        var st = 0L
        // Elevated samples at +5, +10, ... +15 min after the end are ignored.
        for (m in listOf(25.0, 30.0)) { st += 500; assertNull("min $m", d.onSample(sample(m, 150, st), THR, false)) }
        // 35 min = 15 min after the end: the cooldown is over; this sample starts a run, the next one detects.
        st += 500; assertNull(d.onSample(sample(35.0, 150, st), THR, false))
        st += 500; assertNotNull(d.onSample(sample(40.0, 150, st), THR, false))
    }

    @Test fun cooldownIsExactlyFifteenMinutes() {
        val d = AutoDetector()
        d.noteEnded(T0)
        assertEquals(T0 + 15 * MIN, d.cooldownUntilMs)
        assertNull(d.onSample(AutoSample(T0 + 15 * MIN - 1, 150, 0), THR, false))
        assertNull(d.onSample(AutoSample(T0 + 15 * MIN + 4 * MIN, 150, 400), THR, false))   // starts the run
        assertNotNull(d.onSample(AutoSample(T0 + 15 * MIN + 9 * MIN, 150, 800), THR, false))
    }

    @Test fun stateSurvivesAProcessRestart() {
        val d = AutoDetector()
        d.noteEnded(T0 - 60 * MIN)
        d.feed(Triple(0.0, 70, 0L), Triple(5.0, 150, 500L))
        val again = AutoDetector.fromJson(JSONObject(d.toJson().toString()))
        assertEquals(1, again.elevatedRun.size)
        assertEquals(sample(5.0, 150, 500), again.previous)
        assertEquals(d.cooldownUntilMs, again.cooldownUntilMs)
        assertNotNull(again.onSample(sample(10.0, 150, 1000), THR, false))
        assertEquals(0, AutoDetector.fromJson(null).elevatedRun.size)
    }
}

class AutoWorkoutTest {
    private fun auto(startMin: Double, nowMin: Double): Pair<Workout, Long> {
        val start = T0 + (startMin * MIN).toLong(); val now = T0 + (nowMin * MIN).toLong()
        val w = Workout(ActivityType.WALK, start, 190, 30, 70.0, Sex.MALE)
        w.markAuto(now, THR)
        w.backfill(listOf(AutoInterval(start, now, 140, ((nowMin - startMin) * 100).toLong())), now)
        return w to now
    }

    /** Ticks [seconds] seconds at rest (HR 70, no steps) from [from]; returns the first time it ended, else null. */
    private fun rest(w: Workout, from: Long, seconds: Int): Long? {
        var t = from
        repeat(seconds) { t += 1000; w.onHr(70, t); w.tick(t); if (w.autoRestExceeded(t)) return t }
        return null
    }

    @Test fun endsAfterFiveMinutesAtRest() {
        val (w, now) = auto(0.0, 10.0)
        val ended = rest(w, now, 600)!!
        val restMs = ended - w.autoLastActive()
        assertEquals(AutoParams.REST_END_MS, restMs)
        assertFalse(AutoRules.shouldDrop(w.startMs, w.autoLastActive()))
    }

    @Test fun restTrackerNeedsTheFullFiveMinutes() {
        val r = RestTracker(0L, THR)
        r.onTick(1_000, 70, 0)
        for (s in 2..60) r.onTick(s * 1000L, 70, 0)
        assertFalse(r.shouldEnd(r.lastActiveMs + 299_999))
        assertTrue(r.shouldEnd(r.lastActiveMs + 300_000))
    }

    @Test fun walkingOrHighHrKeepsItAlive() {
        val r = RestTracker(0L, THR)
        var steps = 0L
        // 50 steps/min walking, HR low: not rest (>= 40)
        for (s in 1..600) { if (s % 6 == 0) steps += 5; r.onTick(s * 1000L, 80, steps) }
        assertFalse(r.shouldEnd(600_000))
        // standing still with HR 130 (>= threshold): not rest
        val q = RestTracker(0L, THR)
        for (s in 1..600) q.onTick(s * 1000L, 130, 0)
        assertFalse(q.shouldEnd(600_000))
        // 30 steps/min (< 40) and a low HR: rest
        val z = RestTracker(0L, THR)
        var st = 0L
        for (s in 1..600) { if (s % 2 == 0 && s % 4 == 0) st += 1; z.onTick(s * 1000L, 80, st) }
        assertTrue(z.shouldEnd(600_000))
    }

    @Test fun shortBurstIsDropped() {
        // A 7-minute burst (samples 1 minute apart detected it at minute 2; it then stops at minute 7).
        val (w, now) = auto(0.0, 2.0)
        var t = now
        var counter = 0L
        w.onStepCounter(counter)
        repeat(300) { i -> t += 1000; if (i % 6 == 0) { counter += 10; w.onStepCounter(counter) }; w.onHr(130, t); w.tick(t) }   // to 7:00
        val ended = rest(w, t, 600)!!
        assertTrue(ended > t)
        assertEquals((7 * MIN).toDouble(), (w.autoLastActive() - w.startMs).toDouble(), 60_000.0)   // the 60 s step window lags a little
        assertTrue(AutoRules.shouldDrop(w.startMs, w.autoLastActive()))
    }

    @Test fun exactlyTenMinutesIsKept() {
        assertFalse(AutoRules.shouldDrop(T0, T0 + 10 * MIN))
        assertTrue(AutoRules.shouldDrop(T0, T0 + 10 * MIN - 1))
    }

    @Test fun pausingDoesNotEndIt() {
        val (w, now) = auto(0.0, 10.0)
        w.pause(now)
        var t = now
        repeat(900) { t += 1000; w.tick(t) }
        assertFalse(w.autoRestExceeded(t))
        w.resume(t)
        assertEquals(t, w.autoLastActive())
    }

    @Test fun decisionTimer() {
        assertFalse(AutoRules.decisionDue(T0, T0 + 119_999))
        assertTrue(AutoRules.decisionDue(T0, T0 + 120_000))
    }

    @Test fun autoSurvivesSnapshotAndMarksTheSummary() {
        val (w, now) = auto(0.0, 10.0)
        val back = Workout.fromJson(JSONObject(w.toJson().toString()))
        assertTrue(back.auto); assertTrue(back.autoPending)
        assertEquals(w.autoDetectedAt, back.autoDetectedAt)
        assertEquals(w.autoLastActive(), back.autoLastActive())
        back.keepAuto()
        back.finish(now + 1000)
        val s = Summary.from(back)
        assertTrue(s.auto)
        assertTrue(Summary.fromJson(JSONObject(s.toJson().toString())).auto)
        // a manual workout has no auto flag
        val m = Workout(ActivityType.RUN, T0, 190, 30, 70.0, Sex.MALE)
        m.finish(T0 + 1000)
        assertFalse(Summary.fromJson(JSONObject(Summary.from(m).toJson().toString())).auto)
        assertFalse(m.auto)
    }

    @Test fun backfillWritesOneRowPerSecondWithHrAndSteps() {
        val start = T0; val now = T0 + 10 * MIN
        val w = Workout(ActivityType.WALK, start, 190, 30, 70.0, Sex.MALE)
        val rows = w.backfill(listOf(AutoInterval(T0, T0 + 5 * MIN, 140, 500), AutoInterval(T0 + 5 * MIN, now, 150, 503)), now)
        assertEquals(600, rows.size)
        val cells = rows.map { it.split(",") }
        assertTrue(cells.all { it.size == 8 })
        assertEquals(2, cells.count { it[4].isNotEmpty() })
        assertEquals(1003, cells.sumOf { it[7].toInt() })
        assertEquals((T0 / 1000).toString(), cells.first()[0])
        assertEquals(((now / 1000) - 1).toString(), cells.last()[0])
        assertEquals(1003L, w.steps)
        // no GPS before now
        assertTrue(cells.all { it[1].isEmpty() && it[2].isEmpty() })
    }
}

class AutoSettingsTest {
    @Test fun thresholdFollowsTheProfile() {
        assertEquals(110, AutoParams.thresholdBpm(Profile(), 2026))                         // no profile
        assertEquals(114, AutoParams.thresholdBpm(Profile(birthYear = 1996), 2026))         // 220-30 = 190 -> 60 %
        assertEquals(120, AutoParams.thresholdBpm(Profile(maxHrOverride = 200), 2026))      // override
        assertEquals(96, AutoParams.thresholdBpm(Profile(birthYear = 1966), 2026))          // 220-60 = 160 -> 96
    }

    @Test fun settingDefaultsToOn() {
        assertTrue(AutoParams.enabled(null))
        assertTrue(AutoParams.enabled("1"))
        assertFalse(AutoParams.enabled("0"))
        assertFalse(AutoParams.enabled(" 0 "))
    }

    @Test fun debugSampleSpecParsing() {
        val now = 1_000_000_000L
        val s = DebugReceiver.parse("-10m,120,1000; -5m,150,1500;0,152,2000;-90s,100,5;1234,80,3;bad;1,2", now)
        assertEquals(listOf(now - 10 * MIN, now - 5 * MIN, now, now - 90_000, 1234L), s.map { it.timeMs })
        assertEquals(listOf(120, 150, 152, 100, 80), s.map { it.bpm })
        assertEquals(2000L, s[2].steps)
        assertTrue(DebugReceiver.parse(null, now).isEmpty())
    }

    @Test fun intervalsRoundTrip() {
        val l = listOf(AutoInterval(1, 2, 3, 4), AutoInterval(5, 6, 7, 8))
        assertEquals(l, AutoInterval.decodeAll(AutoInterval.encodeAll(l)))
        assertTrue(AutoInterval.decodeAll(null).isEmpty())
        assertTrue(AutoInterval.decodeAll("x;1,2").isEmpty())
    }

    @Test fun notificationTexts() {
        assertEquals("Walk detected", NotifText.detectedTitle(ActivityType.WALK))
        assertEquals("Run detected", NotifText.detectedTitle(ActivityType.RUN))
        assertEquals("Recording since 3:42 PM", NotifText.detectedText("3:42 PM"))
        assertEquals("42:10 · 3.21 km", NotifText.savedText(2_530_000, 3210.0, true))
    }
}
