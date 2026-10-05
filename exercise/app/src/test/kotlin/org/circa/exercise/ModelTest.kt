package org.circa.exercise

import org.circa.exercise.data.Storage
import org.circa.exercise.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class ZonesCaloriesTest {
    @Test fun zoneBoundaries() {
        val max = 190
        assertEquals(0, Zones.zoneOf(null, max))
        assertEquals(0, Zones.zoneOf(94, max))   // 49.5 %
        assertEquals(1, Zones.zoneOf(95, max))   // 50 %
        assertEquals(2, Zones.zoneOf(114, max))  // 60 %
        assertEquals(3, Zones.zoneOf(133, max))  // 70 %
        assertEquals(4, Zones.zoneOf(152, max))  // 80 %
        assertEquals(5, Zones.zoneOf(171, max))  // 90 %
        assertEquals(5, Zones.zoneOf(200, max))
        assertEquals(0f, Zones.scale(80, max), 0f)
        assertEquals(1f, Zones.scale(250, max), 0f)
        assertEquals(0.5f, Zones.scale(143, max), 0.01f)
    }

    @Test fun profileDefaultsAndMaxHr() {
        val p = Profile.parse(null, null, null, null, null)
        assertEquals(30, p.age(2026)); assertEquals(70.0, p.weight, 0.0); assertEquals(Sex.MALE, p.effectiveSex)
        assertEquals(190, p.maxHr(2026))
        val q = Profile.parse("1980", "62.5", "170", "female", "0")
        assertEquals(46, q.age(2026)); assertEquals(62.5, q.weight, 0.0); assertEquals(Sex.FEMALE, q.effectiveSex)
        assertEquals(174, q.maxHr(2026))
        assertEquals(181, Profile.parse("0", "x", null, "", "181").maxHr(2026))
    }

    @Test fun keytel() {
        // male, 150 bpm, 70 kg, 30 y: (-55.0969 + 94.635 + 13.916 + 6.051) / 4.184 = 14.22 kcal/min
        assertEquals(14.22, Calories.keytelPerMinute(150, 70.0, 30, Sex.MALE), 0.01)
        // female, 150 bpm, 60 kg, 30 y: (-20.4022 + 67.08 - 7.578 + 2.22) / 4.184 = 9.875
        assertEquals(9.875, Calories.keytelPerMinute(150, 60.0, 30, Sex.FEMALE), 0.01)
        assertEquals(0.0, Calories.keytelPerMinute(30, 50.0, 20, Sex.MALE), 0.0)
        assertEquals(9.8 * 70 / 3600.0, Calories.metPerSecond(9.8, 70.0), 1e-9)
    }
}

class GeoTest {
    @Test fun haversineKnownDistances() {
        // 1 degree of latitude ~ 111.2 km
        assertEquals(111_195.0, Geo.haversine(0.0, 0.0, 1.0, 0.0), 50.0)
        // Berlin Brandenburg Gate -> Fernsehturm ~ 2.2 km
        assertEquals(2_190.0, Geo.haversine(52.5163, 13.3777, 52.5208, 13.4094), 40.0)
        assertEquals(0.0, Geo.haversine(48.0, 11.0, 48.0, 11.0), 1e-9)
    }

    @Test fun paceAndFormat() {
        assertEquals(300.0, Geo.paceSecPerKm(1000.0, 300.0)!!, 1e-9)
        assertNull(Geo.paceSecPerKm(5.0, 10.0))
        assertEquals("5:00", Fmt.pace(300.0))
        assertEquals("--", Fmt.pace(null))
        assertEquals("0:07", Fmt.duration(7_400))
        assertEquals("1:02:03", Fmt.duration(3_723_000))
        assertEquals("3.21", Fmt.km(3_210.0))
        assertEquals(36.0, Geo.speedKmh(1000.0, 100.0)!!, 1e-9)
    }

    private fun workout(type: ActivityType = ActivityType.RUN) = Workout(type, 1_000_000L, 190, 30, 70.0, Sex.MALE)

    /** Metres north of 52.0,13.0. */
    private fun north(m: Double) = 52.0 + m / 111_195.0

    @Test fun distanceIgnoresJitterAndJumps() {
        val w = workout()
        var t = 1_000_000L
        assertTrue(w.onLocation(52.0, 13.0, null, 5f, t))
        // jitter: 1 m moves are ignored
        t += 1000; w.onLocation(north(1.0), 13.0, null, 5f, t)
        assertEquals(0.0, w.distanceM, 0.0)
        // a poor fix is rejected outright
        t += 1000; assertFalse(w.onLocation(north(500.0), 13.0, null, 80f, t))
        // a jump at 400 m/s is not counted
        t += 1000; w.onLocation(north(400.0), 13.0, null, 5f, t)
        assertEquals(0.0, w.distanceM, 0.0)
        // steady 4 m/s for 300 s = 1200 m (anchor still at 0 m)
        for (i in 1..300) { t += 1000; w.onLocation(north(4.0 * i), 13.0, null, 5f, t) }
        assertEquals(1200.0, w.distanceM, 2.0)
        assertEquals(1, w.splitAt.size)
    }

    @Test fun staleFirstFixDoesNotBlockDistance() {
        // emulator 2026-10-04: the first fix was the stale default location 1000 km away; every real fix was then a
        // "jump" from that anchor and the run kept 0.00 km. Two consistent fixes far from the anchor re-anchor.
        val w = workout()
        var t = 1_000_000L
        w.onLocation(39.2, -123.1, null, 5f, t)
        for (i in 1..61) { t += 1000; w.onLocation(north(3.5 * i), 13.0, null, 5f, t) }
        assertEquals(3.5 * 59, w.distanceM, 2.0) // re-anchored at the 2nd real fix
        assertTrue(w.route.none { it[0] < 40.0 })
        // the CSV rows written while the outlier was the anchor lose their position at End
        assertEquals(1, w.gpsDropSpans.size)
        assertEquals(1_000L, w.gpsDropSpans[0][0])
        assertTrue(w.gpsDropSpans[0][1] in 1_000L..1_002L)
        val csv = BangleCsv.HEADER + "\n" + "1000,39.200000,-123.100000,0.0,120,100,int,0\n" +
            "1001,,,,121,100,int,0\n" + "1003,52.000063,13.000000,0.0,122,100,int,1\n"
        assertEquals(BangleCsv.HEADER + "\n" + "1000,,,,120,100,int,0\n" + "1001,,,,121,100,int,0\n" +
            "1003,52.000063,13.000000,0.0,122,100,int,1\n", BangleCsv.dropGps(csv, w.gpsDropSpans))
    }

    @Test fun pauseExcludesTimeAndDistance() {
        val w = workout()
        val t0 = 1_000_000L
        w.onLocation(52.0, 13.0, null, 5f, t0)
        w.onLocation(north(100.0), 13.0, null, 5f, t0 + 30_000)
        w.pause(t0 + 60_000)
        assertEquals(60_000, w.activeMs(t0 + 120_000))
        // moves while paused are not counted, nor the gap across the pause
        w.onLocation(north(500.0), 13.0, null, 5f, t0 + 90_000)
        w.resume(t0 + 120_000)
        w.onLocation(north(600.0), 13.0, null, 5f, t0 + 121_000)
        w.onLocation(north(610.0), 13.0, null, 5f, t0 + 125_000)
        assertEquals(110.0, w.distanceM, 1.0)
        assertEquals(70_000, w.activeMs(t0 + 130_000))
        assertNull(w.tick(t0 + 61_000).takeIf { w.phase == Phase.PAUSED })
        w.finish(t0 + 130_000)
        assertEquals(70_000, w.activeMs(t0 + 999_000))
    }

    @Test fun tickIntegratesZonesCaloriesAndSurvivesSnapshot() {
        val w = workout(ActivityType.STRENGTH)
        var t = 1_000_000L
        for (i in 1..60) { t += 1000; w.onHr(152, t); w.tick(t) } // zone 4
        assertEquals(60_000, w.zoneMs[4])
        assertEquals(152, w.hrAvg); assertEquals(152, w.hrMax)
        assertEquals(Calories.keytelPerMinute(152, 70.0, 30, Sex.MALE), w.kcal, 0.01)
        val copy = Workout.fromJson(JSONObject(w.toJson().toString()))
        assertEquals(w.activeMs(t), copy.activeMs(t))
        assertEquals(w.kcal, copy.kcal, 1e-9)
        assertEquals(60_000, copy.zoneMs[4])
        assertEquals(Phase.RECORDING, copy.phase)
    }

    @Test fun zoneChangeAnnouncedAfterHold() {
        val w = workout(ActivityType.OTHER)
        var t = 1_000_000L
        var announcements = 0
        for (i in 1..20) { t += 1000; w.onHr(120, t); if (w.tick(t)!!.zoneAnnounced) announcements++ } // Z2
        for (i in 1..3) { t += 1000; w.onHr(135, t); if (w.tick(t)!!.zoneAnnounced) announcements++ } // Z3 blip
        for (i in 1..3) { t += 1000; w.onHr(120, t); if (w.tick(t)!!.zoneAnnounced) announcements++ }
        assertEquals(1, announcements) // entering Z2 only; the 3 s blip is not announced
        for (i in 1..10) { t += 1000; w.onHr(135, t); if (w.tick(t)!!.zoneAnnounced) announcements++ }
        assertEquals(2, announcements)
    }
}

class BadgesTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private fun at(d: Int, h: Int = 8) = ZonedDateTime.of(2026, 10, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()
    private fun s(type: ActivityType, day: Int, km: Double, min: Long, hard: Long = 0) = Summary(
        null, type, at(day), at(day) + min * 60_000, min * 60_000, km * 1000, 300.0, 140, 170,
        listOf(0L, 0L, 0L, 0L, hard, 0L), emptyList(), emptyList(), 0,
    )

    @Test fun firstOfItsKind() {
        val b = Badges.compute(s(ActivityType.HIKE, 4, 5.0, 60), emptyList(), zone)
        assertEquals(listOf(BadgeKind.FIRST), b.map { it.kind })
    }

    @Test fun longestFastestAndStreak() {
        val hist = listOf(s(ActivityType.RUN, 1, 5.0, 30), s(ActivityType.RUN, 2, 4.0, 22), s(ActivityType.WALK, 3, 2.0, 30))
        val cur = s(ActivityType.RUN, 4, 6.0, 30, hard = 120_000)
        val kinds = Badges.compute(cur, hist, zone).map { it.kind }
        assertTrue(BadgeKind.LONGEST_DISTANCE in kinds)
        assertTrue(BadgeKind.FASTEST in kinds)        // 5:00/km beats 5:30
        assertTrue(BadgeKind.ZONE_MINUTES in kinds)
        assertTrue(BadgeKind.STREAK in kinds)
        assertEquals(4, Badges.streakDays(cur, hist, zone))
        assertEquals("New longest run!", Badges.headline(cur.copy(badges = Badges.compute(cur, hist, zone))))
        // the summary leaves out the chip the headline already announces
        assertEquals(BadgeKind.LONGEST_DISTANCE, Badges.headlineKind(cur.copy(badges = Badges.compute(cur, hist, zone))))
    }

    @Test fun noBadgeForAnOrdinaryRun() {
        val hist = listOf(s(ActivityType.RUN, 1, 10.0, 45))
        val cur = s(ActivityType.RUN, 3, 5.0, 30)
        assertTrue(Badges.compute(cur, hist, zone).isEmpty())
        assertEquals("Nice run!", Badges.headline(cur))
        assertEquals(null, Badges.headlineKind(cur))
    }

    @Test fun summaryJsonRoundTrip() {
        val a = s(ActivityType.BIKE, 4, 20.0, 50).copy(id = "20261004a", route = listOf(doubleArrayOf(52.0, 13.0), doubleArrayOf(52.1, 13.1)),
            splitsMs = listOf(150_000L, 140_000L), badges = listOf(Badge(BadgeKind.STREAK, "2-day")))
        val b = Summary.fromJson(JSONObject(a.toJson().toString()))
        assertEquals(a.id, b.id); assertEquals(a.type, b.type); assertEquals(a.splitsMs, b.splitsMs)
        assertEquals(a.badges, b.badges); assertEquals(2, b.route.size); assertEquals(52.1, b.route[1][0], 0.0)
    }
}

class BangleCsvTest {
    @Test fun rowFormat() {
        assertEquals("Time,Latitude,Longitude,Altitude,Heartrate,Confidence,Source,Steps", BangleCsv.HEADER)
        assertEquals("1700000000,52.520800,13.409400,34.0,142,100,int,3",
            BangleCsv.row(1_700_000_000, 52.5208, 13.4094, 34.0, 142, 100, "int", 3))
        assertEquals("1700000001,,,,,,,0", BangleCsv.row(1_700_000_001, steps = 0))
        // a lat without lon is no fix
        assertEquals("1700000002,,,,90,,,", BangleCsv.row(1_700_000_002, lat = 1.0, hr = 90))
        val cells = BangleCsv.HEADER.split(",").size
        assertEquals(cells, BangleCsv.row(1, 1.0, 2.0, null, null, null, null, null).split(",").size)
    }

    @Test fun workoutRowsHaveHeaderCellCount() {
        val w = Workout(ActivityType.RUN, 1_000_000L, 190, 30, 70.0, Sex.MALE)
        w.onLocation(52.0, 13.0, 30.0, 4f, 1_000_500L)
        w.onHr(140, 1_000_600L)
        val r = w.tick(1_001_000L)!!.csvRow
        assertEquals(8, r.split(",").size)
        assertTrue(r.startsWith("1001,52.000000,13.000000,30.0,140,100,int,"))
        // the fix is written once
        assertTrue(w.tick(1_002_000L)!!.csvRow.startsWith("1002,,,,140"))
    }

    @Test fun idAllocation() {
        val d = LocalDate.of(2026, 10, 4)
        assertEquals("20261004a", BangleCsv.nextId(emptyList(), d))
        assertEquals("20261004c", BangleCsv.nextId(listOf("20261004a", "20261004b", "20261003f"), d))
        assertEquals("20261005a", BangleCsv.nextId(listOf("20261004a"), d.plusDays(1)))
        // after y comes za, zb ...: still sorts after every earlier id of the day
        val ids = mutableListOf<String>()
        repeat(60) { ids += BangleCsv.nextId(ids, d) }
        assertEquals("20261004y", ids[24]); assertEquals("20261004za", ids[25]); assertEquals("20261004zza", ids[50])
        assertEquals(ids, ids.sorted())
        assertEquals(ids.toSet().size, ids.size)
    }

    @Test fun idsAfter() {
        val all = listOf("20261004b", "20261003a", "20261004a", "bogus", "20261004za")
        assertEquals(listOf("20261003a", "20261004a", "20261004b", "20261004za"), BangleCsv.idsAfter(all, BangleCsv.FIRST_ID))
        assertEquals(listOf("20261004b", "20261004za"), BangleCsv.idsAfter(all, "20261004a"))
        assertEquals(4, BangleCsv.idsAfter(all, null).size)
        assertTrue(BangleCsv.idsAfter(all, "20261004za").isEmpty())
        assertFalse(BangleCsv.isValidId("../x"))
    }
}

class SyncPolicyTest {
    @Test fun callers() {
        assertTrue(SyncCallerPolicy.allowed(1000, 10100, emptyList(), emptyList()))
        assertTrue(SyncCallerPolicy.allowed(10100, 10100, emptyList(), emptyList()))
        assertTrue(SyncCallerPolicy.allowed(10123, 10100, listOf("org.circa.watchlink"), listOf(true)))
        assertTrue(SyncCallerPolicy.allowed(10124, 10100, listOf("org.circa.settings"), listOf(true)))
        assertFalse(SyncCallerPolicy.allowed(10125, 10100, listOf("org.circa.fake"), listOf(false)))
        assertFalse(SyncCallerPolicy.allowed(10126, 10100, listOf("com.example"), listOf(true)))
    }

    @Test fun activityNamesAndListOrder() {
        assertEquals("CYCLING", ActivityType.BIKE.gbName)
        assertEquals("EXERCISING", ActivityType.YOGA.gbName)
        assertEquals("SWIMMING", ActivityType.SWIM.gbName)
        assertEquals(ActivityType.MAIN, ActivityType.listOrder(null))
        assertEquals(listOf(ActivityType.BIKE, ActivityType.RUN, ActivityType.WALK, ActivityType.HIKE, ActivityType.STRENGTH, ActivityType.OTHER),
            ActivityType.listOrder(ActivityType.BIKE))
        assertEquals(ActivityType.YOGA, ActivityType.listOrder(ActivityType.YOGA).first())
        assertFalse(ActivityType.STRENGTH.gps || ActivityType.INDOOR_RUN.gps || ActivityType.SWIM.gps)
    }
}

/** "Last used" is set by saving only; a workout the user discards must not change it. */
class LastUsedTest {
    private fun dir(): File = Files.createTempDirectory("circa-last-used").toFile().apply { deleteOnExit() }

    private fun workout(type: ActivityType) = Workout(type, 1_000_000L, 190, 30, 70.0, Sex.MALE)

    private fun summary(type: ActivityType) = Summary(
        id = null, type = type, startMs = 1_000_000L, endMs = 1_600_000L, activeMs = 600_000L,
        distanceM = 0.0, kcal = 0.0, hrAvg = 0, hrMax = 0, zoneMs = List(6) { 0L },
        splitsMs = emptyList(), route = emptyList(), steps = 0L,
    )

    @Test fun discardedWorkoutIsNotLastUsed() {
        val st = Storage(dir())
        st.begin(workout(ActivityType.BIKE))
        assertNull("starting a workout is not saving it", st.lastType())
        st.discard()
        assertNull(st.lastType())
    }

    @Test fun savedWorkoutIsLastUsedAndALaterDiscardKeepsIt() {
        val st = Storage(dir())
        st.begin(workout(ActivityType.WALK))
        st.commit(summary(ActivityType.WALK))
        assertEquals(ActivityType.WALK, st.lastType())
        // A newer workout that the user discards leaves the saved one as "Last used".
        st.begin(workout(ActivityType.BIKE))
        st.discard()
        assertEquals(ActivityType.WALK, st.lastType())
    }
}

/** The ongoing notification's text and chronometer base (model/NotifText.kt). */
class NotifTextTest {
    @Test fun pausedTextShowsFrozenActiveTime() {
        assertEquals("Paused · 12:34", NotifText.paused(754_000L))
        assertEquals("Paused · 0:07", NotifText.paused(7_000L))
        assertEquals("Paused · 1:02:03", NotifText.paused(3_723_000L))
    }

    @Test fun chronoBaseCountsOnlyActiveTime() {
        // 10 min in with 2 min of earlier pause: the base is shifted back by the paused time, so the chronometer
        // reads 8 min of active time.
        assertEquals(1_000_000L, NotifText.chronoBase(1_600_000L, 600_000L))
        assertEquals(500_000L, NotifText.chronoBase(1_600_000L, 1_100_000L))
    }
}

/** The state provider's row mapping (model/WorkoutState.kt). */
class WorkoutStateTest {
    @Test fun phaseMapsToProviderState() {
        assertEquals(WorkoutState.RECORDING, WorkoutState.of("run", Phase.RECORDING, 123L).state)
        assertEquals(WorkoutState.PAUSED, WorkoutState.of("walk", Phase.PAUSED, 123L).state)
        assertEquals(WorkoutState.IDLE, WorkoutState.of("run", Phase.FINISHED, 123L).state)
        assertEquals("bike", WorkoutState.of("bike", Phase.PAUSED, 123L).activity)
        assertEquals(123L, WorkoutState.of("bike", Phase.PAUSED, 123L).startedMs)
        // Idle is always the empty row.
        assertEquals(WorkoutState.IDLE_STATE, WorkoutState.of("run", Phase.FINISHED, 123L))
        assertEquals("", WorkoutState.IDLE_STATE.activity)
        assertEquals(0L, WorkoutState.IDLE_STATE.startedMs)
    }
}

/** The state provider's caller check (model/StateCallerPolicy.kt), the rule WatchLink uses. */
class StateCallerPolicyTest {
    @Test fun onlySystemOrgCircaCallers() {
        assertTrue(StateCallerPolicy.allowed(listOf("org.circa.launcher"), listOf(true), "org.circa.exercise"))
        assertTrue(StateCallerPolicy.allowed(listOf("org.circa.exercise"), listOf(true), "org.circa.exercise"))
        // Shell (no matching package) and ordinary apps are denied, even system ones.
        assertFalse(StateCallerPolicy.allowed(emptyList(), emptyList(), "org.circa.exercise"))
        assertFalse(StateCallerPolicy.allowed(listOf("com.android.shell"), listOf(true), "org.circa.exercise"))
        assertFalse(StateCallerPolicy.allowed(listOf("com.example"), listOf(true), "org.circa.exercise"))
        // A non-system app that merely reuses the prefix is denied.
        assertFalse(StateCallerPolicy.allowed(listOf("org.circa.fake"), listOf(false), "org.circa.exercise"))
    }
}
