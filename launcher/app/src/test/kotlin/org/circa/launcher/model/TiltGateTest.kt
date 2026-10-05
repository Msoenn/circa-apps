package org.circa.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthetic traces for [TiltGate]: accelerometer (and gyro) streams at 50 Hz starting at the tilt
 * event, in Android device axes for a left-wrist, crown-to-hand watch (face-up reads +Z).
 */
class TiltGateTest {

    private val start = 1_000_000_000L
    private val g = TiltGate.G

    /** Accel samples every [periodMs] over [durationMs]; [f] maps time (s) to (x, y, z). */
    private fun trace(durationMs: Long = 500, periodMs: Long = 20, f: (Double) -> Triple<Double, Double, Double>): List<Sample3> =
        (0..durationMs / periodMs).map { i ->
            val tMs = i * periodMs
            val (x, y, z) = f(tMs / 1000.0)
            Sample3(start + tMs * 1_000_000L, x.toFloat(), y.toFloat(), z.toFloat())
        }

    /** A pose tilted [deg] about X from face-up (12 o'clock raised), held with noise of [noise] m/s². */
    private fun hold(deg: Double, noise: Double = 0.08, seed: Int = 1): List<Sample3> {
        val r = Random(seed)
        val rad = Math.toRadians(deg)
        return trace {
            Triple(
                r.nextDouble(-noise, noise),
                g * sin(rad) + r.nextDouble(-noise, noise),
                g * cos(rad) + r.nextDouble(-noise, noise),
            )
        }
    }

    private fun stillGyro(level: Double = 0.05) = trace { Triple(level, 0.0, 0.0) }

    // ---- accept --------------------------------------------------------------------------------

    @Test
    fun lookingHoldIsAcceptedAtEveryLevel() {
        val accel = hold(20.0)
        for (t in listOf(TiltGate.LOW, TiltGate.MEDIUM, TiltGate.HIGH)) {
            val v = TiltGate.evaluate(accel, stillGyro(), start, t)
            assertTrue("$t -> ${v.reason}", v.accept)
            assertEquals(GateReason.OK, v.reason)
        }
        val f = TiltGate.evaluate(accel, emptyList(), start, TiltGate.LOW).features
        assertTrue(f.angleDeg < 5.0)
        assertTrue(f.gyroRms.isNaN()) // no gyro: the rotation check is skipped, not failed
    }

    @Test
    fun flatFaceUpIsInsideTheLowCone() {
        // Wrist flat on a desk, face up: 20 degrees off LOOK - accepted; the hub's own 45-degree pitch
        // change requirement is what keeps typing from firing in the first place.
        assertTrue(TiltGate.evaluate(hold(0.0), stillGyro(), start, TiltGate.LOW).accept)
    }

    @Test
    fun theRaiseItselfIsIgnoredDuringTheSettleTime() {
        val r = Random(7)
        val rad = Math.toRadians(20.0)
        val accel = trace { t ->
            val jolt = if (t < 0.09) 4.0 else 0.08
            Triple(r.nextDouble(-jolt, jolt), g * sin(rad) + r.nextDouble(-jolt, jolt), g * cos(rad))
        }
        assertTrue(TiltGate.evaluate(accel, stillGyro(), start, TiltGate.LOW).accept)
    }

    // ---- reject --------------------------------------------------------------------------------

    @Test
    fun watchHeldVerticalIsNotFacingUp() {
        // Face vertical (70 degrees from LOOK): outside every cone.
        val v = TiltGate.evaluate(hold(90.0), stillGyro(), start, TiltGate.HIGH)
        assertFalse(v.accept)
        assertEquals(GateReason.FACE, v.reason)
    }

    @Test
    fun wideTiltPassesHighButNotLowOrMedium() {
        val accel = hold(62.0) // ~42 degrees from LOOK
        assertEquals(GateReason.FACE, TiltGate.evaluate(accel, stillGyro(), start, TiltGate.LOW).reason)
        assertEquals(GateReason.FACE, TiltGate.evaluate(accel, stillGyro(), start, TiltGate.MEDIUM).reason)
        assertTrue(TiltGate.evaluate(accel, stillGyro(), start, TiltGate.HIGH).accept)
    }

    @Test
    fun armHangingAtTheSideIsRejected() {
        // Hand pointing at the floor: the accelerometer's "up" is -X; face sideways.
        val r = Random(3)
        val accel = trace { Triple(-g + r.nextDouble(-0.1, 0.1), r.nextDouble(-0.1, 0.1), 0.5) }
        val v = TiltGate.evaluate(accel, stillGyro(), start, TiltGate.HIGH)
        assertFalse(v.accept)
        assertEquals(GateReason.FACE, v.reason)
    }

    @Test
    fun walkingSwingThroughThePoseIsRejected() {
        // The arm swings at 1 Hz: orientation oscillates +-50 degrees about the looking pose and the
        // swing adds ~3 m/s^2 of linear acceleration. The mean may land in the cone - the motion
        // check must catch it.
        val accel = trace { t ->
            val phase = sin(2 * PI * 1.0 * t)
            val rad = Math.toRadians(20.0 + 50.0 * phase)
            val lin = 3.0 * cos(2 * PI * 1.0 * t)
            Triple(lin, g * sin(rad), g * cos(rad) + lin * 0.5)
        }
        val gyro = trace { t -> Triple(2.0 * cos(2 * PI * t), 0.3, 0.0) }
        for (t in listOf(TiltGate.LOW, TiltGate.MEDIUM, TiltGate.HIGH)) {
            assertFalse("$t", TiltGate.evaluate(accel, gyro, start, t).accept)
        }
    }

    @Test
    fun tremorAboveLowPassesMediumAndHigh() {
        val accel = hold(20.0, noise = 1.17, seed = 11) // uniform +-1.17 per axis -> 3-D RMS ~1.0 (Low 0.9 < x < Medium 1.1)
        val f = TiltGate.evaluate(accel, stillGyro(), start, TiltGate.LOW).features
        assertTrue("accel rms ${f.accelRms}", f.accelRms > TiltGate.LOW.maxAccelRms && f.accelRms < TiltGate.MEDIUM.maxAccelRms)
        assertEquals(GateReason.MOTION, TiltGate.evaluate(accel, stillGyro(), start, TiltGate.LOW).reason)
        assertTrue(TiltGate.evaluate(accel, stillGyro(), start, TiltGate.MEDIUM).accept)
        assertTrue(TiltGate.evaluate(accel, stillGyro(), start, TiltGate.HIGH).accept)
    }

    // ---- medium: the real-wear rows the retune was built from ------------------------------------

    /** One telemetry row: features as `TiltGate.features` would compute them, mean at rest (|a| = g). */
    private fun row(angle: Double, accelRms: Double, gyroRms: Double) = TiltFeatures(
        n = 15,
        spanMs = 340,
        ax = 0.0,
        ay = 0.0,
        az = g,
        amag = g,
        angleDeg = angle,
        accelRms = accelRms,
        gyroRms = gyroRms,
    )

    @Test
    fun mediumAcceptsTheRealGlancesLowRejected() {
        // 2026-10-04 on-watch telemetry: both were rejected by Low (motion at 0.93, face at 38.9°).
        for (f in listOf(row(18.9, 0.93, 0.28), row(38.9, 0.27, 0.129))) {
            assertFalse("Low must reject $f", TiltGate.decide(f, TiltGate.LOW).accept)
            val v = TiltGate.decide(f, TiltGate.MEDIUM)
            assertTrue("$f -> ${v.reason}", v.accept)
            assertEquals(GateReason.OK, v.reason)
        }
    }

    @Test
    fun mediumRejectsTheRealSwingRow() {
        val v = TiltGate.decide(row(49.7, 9.48, 3.9), TiltGate.MEDIUM)
        assertFalse(v.accept)
        assertEquals(GateReason.FACE, v.reason)
    }

    @Test
    fun rotatingWithoutLinearMotionIsCaughtByTheGyro() {
        val v = TiltGate.evaluate(hold(20.0), stillGyro(level = 1.0), start, TiltGate.LOW)
        assertEquals(GateReason.ROTATION, v.reason)
    }

    @Test
    fun tooFewSamplesAreRejected() {
        val accel = hold(20.0).take(3) // all inside the settle time anyway
        assertEquals(GateReason.FEW, TiltGate.evaluate(accel, emptyList(), start, TiltGate.LOW).reason)
        assertEquals(GateReason.FEW, TiltGate.evaluate(emptyList(), emptyList(), start, TiltGate.LOW).reason)
    }

    @Test
    fun freeFallOrHardAccelerationFailsTheGravityCheck() {
        val accel = trace { Triple(0.0, 2.0, 5.0) } // |a| = 5.4: not gravity alone
        assertEquals(GateReason.GRAVITY, TiltGate.evaluate(accel, stillGyro(), start, TiltGate.LOW).reason)
    }

    @Test
    fun windowCompletesAtTheLevelsWindow() {
        assertFalse(TiltGate.windowComplete(start + 449_000_000L, start, TiltGate.LOW))
        assertTrue(TiltGate.windowComplete(start + 450_000_000L, start, TiltGate.LOW))
        assertFalse(TiltGate.windowComplete(start + 449_000_000L, start, TiltGate.MEDIUM))
        assertTrue(TiltGate.windowComplete(start + 450_000_000L, start, TiltGate.MEDIUM))
        assertFalse(TiltGate.windowComplete(start + 449_000_000L, start, TiltGate.HIGH))
        assertTrue(TiltGate.windowComplete(start + 450_000_000L, start, TiltGate.HIGH))
    }

    @Test
    fun samplesOutsideTheWindowAreIgnored() {
        // Looking pose inside the window, arm at the side long after it: still accepted.
        val inside = hold(20.0)
        val late = (0 until 10).map { Sample3(start + 900_000_000L + it * 20_000_000L, -9.8f, 0f, 0f) }
        assertTrue(TiltGate.evaluate(inside + late, emptyList(), start, TiltGate.LOW).accept)
    }

    // ---- settings ------------------------------------------------------------------------------

    @Test
    fun sensitivityResolvesSecureKeyThenLegacyToggle() {
        // Unset (or unknown) means the new default, Medium; the legacy toggle only forces Off at "0".
        assertEquals(TiltSensitivity.MEDIUM, TiltSensitivity.resolve(null, null, null))
        assertEquals(TiltSensitivity.MEDIUM, TiltSensitivity.resolve(null, "1", null))
        assertEquals(TiltSensitivity.OFF, TiltSensitivity.resolve(null, "0", null))
        assertEquals(TiltSensitivity.OFF, TiltSensitivity.resolve("0", "1", null))
        assertEquals(TiltSensitivity.MEDIUM, TiltSensitivity.resolve("garbage", "1", null))
        assertEquals(TiltSensitivity.OFF, TiltSensitivity.resolve("7", "0", null))
        // High keeps the legacy "normal" value 2; Medium takes the free value 3.
        assertEquals(TiltSensitivity.HIGH, TiltSensitivity.resolve("2", "0", null))
        assertEquals(TiltSensitivity.HIGH, TiltSensitivity.resolve("normal", "0", null))
        assertEquals(TiltSensitivity.MEDIUM, TiltSensitivity.resolve("3", "0", null))
        assertNull(TiltGate.thresholds(TiltSensitivity.OFF))
        assertEquals(TiltGate.LOW, TiltGate.thresholds(TiltSensitivity.LOW))
        assertEquals(TiltGate.MEDIUM, TiltGate.thresholds(TiltSensitivity.MEDIUM))
        assertEquals(TiltGate.HIGH, TiltGate.thresholds(TiltSensitivity.HIGH))
    }

    @Test
    fun storedLowMigratesToMediumOnce() {
        // The old default was Low and was never chosen explicitly: it reads as Medium until the
        // one-time migration has run (flag set).
        assertEquals(TiltSensitivity.MEDIUM, TiltSensitivity.resolve("1", null, null))
        assertEquals(TiltSensitivity.LOW, TiltSensitivity.resolve("1", null, "1"))
        assertEquals(
            mapOf(
                TiltSensitivity.LEVEL_KEY to "3",
                TiltSensitivity.MIGRATION_KEY to "1",
            ),
            TiltSensitivity.migrationWrites("1", null),
        )
        assertEquals(emptyMap<String, String>(), TiltSensitivity.migrationWrites("1", "1"))
        // Anything else only records that the migration has run, so a later explicit Low is honoured.
        assertEquals(
            mapOf(TiltSensitivity.MIGRATION_KEY to "1"),
            TiltSensitivity.migrationWrites("2", null),
        )
        assertEquals(
            mapOf(TiltSensitivity.MIGRATION_KEY to "1"),
            TiltSensitivity.migrationWrites(null, null),
        )
    }

    // ---- wrist-down ----------------------------------------------------------------------------

    private fun feed(d: WristDownDetector, fromMs: Long, toMs: Long, x: Float, z: Float): Long? {
        var t = fromMs
        while (t <= toMs) {
            if (d.onSample(Sample3(start + t * 1_000_000L, x, 0f, z))) return t
            t += 50
        }
        return null
    }

    @Test
    fun loweringTheArmIsDetectedAfterTheHold() {
        val d = WristDownDetector()
        assertNull(feed(d, 0, 1_000, 0f, 9.5f)) // looking
        val firedAt = feed(d, 1_050, 2_000, -8.5f, 2f) // arm dropped
        assertEquals(1_050L + WristDownDetector.HOLD_MS, firedAt)
    }

    @Test
    fun faceTurnedDownCounts() {
        val d = WristDownDetector()
        assertNull(feed(d, 0, 500, 0f, 9.5f))
        assertTrue(feed(d, 550, 1_500, 0f, -6f) != null)
    }

    @Test
    fun aJoltOrTheRaiseOvershootDoesNotCount() {
        val d = WristDownDetector()
        // Down pose only inside the grace period: ignored.
        assertNull(feed(d, 0, 350, -9f, 0f))
        // A brief 100 ms dip, then back to looking: not held long enough.
        assertNull(feed(d, 400, 600, 0f, 9.5f))
        assertNull(feed(d, 650, 750, -9f, 0f))
        assertNull(feed(d, 800, 2_000, 0f, 9.5f))
    }

    @Test
    fun verticalLookingPoseIsNotWristDown() {
        val d = WristDownDetector()
        assertNull(feed(d, 0, 3_000, 0f, 0.5f)) // face vertical toward the eyes: x~0, z~0
    }

    // ---- glance + telemetry --------------------------------------------------------------------

    @Test
    fun quickSleepOnlyWhenUntouchedAndOnTheLauncher() {
        assertTrue(GlancePolicy.mayQuickSleep(interacted = false, launcherFocused = true, leftLauncher = false))
        assertFalse(GlancePolicy.mayQuickSleep(interacted = true, launcherFocused = true, leftLauncher = false))
        assertFalse(GlancePolicy.mayQuickSleep(interacted = false, launcherFocused = false, leftLauncher = false))
        assertFalse(GlancePolicy.mayQuickSleep(interacted = false, launcherFocused = true, leftLauncher = true))
    }

    @Test
    fun telemetryLineHasTheHeadersColumns() {
        val f = TiltGate.evaluate(hold(20.0), stillGyro(), start, TiltGate.LOW).features
        val wake = TiltLog.line(1L, 2L, "hub", TiltSensitivity.LOW, "wake", "ok", f, 480L, 1_200L, 30_000L, GlanceEnd.USER)
        val cols = TiltLog.HEADER.split(",").size
        assertEquals(cols, wake.split(",").size)
        val parts = wake.split(",")
        assertEquals("1", parts[17]) // interacted5
        assertEquals("user", parts[19])
        assertFalse(wake.contains("E-") || wake.contains("NaN"))

        val skip = TiltLog.line(1L, 2L, "hub", TiltSensitivity.LOW, "skip", "debounce", null, null, null, null, null)
        assertEquals(cols, skip.split(",").size)
        assertEquals("", skip.split(",")[17])

        val untouched = TiltLog.line(1L, 2L, "hub", TiltSensitivity.LOW, "wake", "ok", f, 480L, null, 5_600L, GlanceEnd.QUICK)
        assertEquals("0", untouched.split(",")[17])
        val late = TiltLog.line(1L, 2L, "hub", TiltSensitivity.LOW, "wake", "ok", f, 480L, 7_000L, 30_000L, GlanceEnd.USER)
        assertEquals("0", late.split(",")[17])
    }

    @Test
    fun telemetryTrimKeepsTheNewestLines() {
        val small = listOf(TiltLog.HEADER) + List(TiltLog.MAX_LINES + TiltLog.TRIM_SLACK) { "l$it" }
        assertNull(TiltLog.trim(small))
        val big = listOf(TiltLog.HEADER) + List(TiltLog.MAX_LINES + TiltLog.TRIM_SLACK + 1) { "l$it" }
        val kept = TiltLog.trim(big)!!
        assertEquals(TiltLog.MAX_LINES + 1, kept.size)
        assertEquals(TiltLog.HEADER, kept.first())
        assertEquals("l${TiltLog.MAX_LINES + TiltLog.TRIM_SLACK}", kept.last())
    }
}
