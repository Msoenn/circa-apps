package org.circa.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearGestureLogicTest {

    // ---- registration gating -------------------------------------------------------------------

    @Test
    fun tiltListensOnlyWhenEnabledOffScreenAndAvailable() {
        val logic = WearGestureLogic()
        assertTrue(
            logic.tiltShouldListen(
                enabled = true, interactive = false, theaterModeOn = false, sensorAvailable = true,
            ),
        )
        // Nothing to wake: the screen is already interactive.
        assertFalse(
            logic.tiltShouldListen(
                enabled = true, interactive = true, theaterModeOn = false, sensorAvailable = true,
            ),
        )
        // The Gestures toggle is off.
        assertFalse(
            logic.tiltShouldListen(
                enabled = false, interactive = false, theaterModeOn = false, sensorAvailable = true,
            ),
        )
        // Theater mode: only POWER may wake the screen, so the tilt sensor stays unregistered.
        assertFalse(
            logic.tiltShouldListen(
                enabled = true, interactive = false, theaterModeOn = true, sensorAvailable = true,
            ),
        )
        // The build has no wrist-tilt sensor (the emulator).
        assertFalse(
            logic.tiltShouldListen(
                enabled = true, interactive = false, theaterModeOn = false, sensorAvailable = false,
            ),
        )
    }

    @Test
    fun offBodyListensOnlyWithPreferenceCredentialAndSensor() {
        val logic = WearGestureLogic()
        assertTrue(logic.offBodyShouldListen(enabled = true, secure = true, sensorAvailable = true))
        assertFalse(logic.offBodyShouldListen(enabled = false, secure = true, sensorAvailable = true))
        // No PIN set: never lock, and no reason to listen.
        assertFalse(logic.offBodyShouldListen(enabled = true, secure = false, sensorAvailable = true))
        assertFalse(logic.offBodyShouldListen(enabled = true, secure = true, sensorAvailable = false))
    }

    // ---- tilt events ---------------------------------------------------------------------------

    @Test
    fun aTriggerWakes() {
        val logic = WearGestureLogic()
        assertEquals(
            TiltAction.WAKE,
            logic.tiltAction(nowUptimeMs = 1_000, value = 1.0f, theaterModeOn = false),
        )
    }

    @Test
    fun aNonTriggerIsIgnored() {
        val logic = WearGestureLogic()
        assertEquals(
            TiltAction.IGNORE,
            logic.tiltAction(nowUptimeMs = 1_000, value = 0.0f, theaterModeOn = false),
        )
    }

    @Test
    fun aSecondTiltWithinTheDebounceWindowIsIgnored() {
        val logic = WearGestureLogic()
        assertEquals(
            TiltAction.WAKE,
            logic.tiltAction(nowUptimeMs = 1_000, value = 1.0f, theaterModeOn = false),
        )
        assertEquals(
            TiltAction.IGNORE,
            logic.tiltAction(nowUptimeMs = 1_000 + 1_499, value = 1.0f, theaterModeOn = false),
        )
        // Exactly at the window boundary the next wake is allowed again.
        assertEquals(
            TiltAction.WAKE,
            logic.tiltAction(nowUptimeMs = 1_000 + 1_500, value = 1.0f, theaterModeOn = false),
        )
    }

    @Test
    fun theDebounceWindowIsMeasuredFromTheLastWakeNotTheLastEvent() {
        val logic = WearGestureLogic()
        logic.tiltAction(nowUptimeMs = 1_000, value = 1.0f, theaterModeOn = false)
        // An ignored event must not push the window forward.
        logic.tiltAction(nowUptimeMs = 1_500, value = 1.0f, theaterModeOn = false)
        assertEquals(
            TiltAction.IGNORE,
            logic.tiltAction(nowUptimeMs = 2_400, value = 1.0f, theaterModeOn = false),
        )
        assertEquals(
            TiltAction.WAKE,
            logic.tiltAction(nowUptimeMs = 2_500, value = 1.0f, theaterModeOn = false),
        )
    }

    @Test
    fun tiltIsIgnoredWhileOffBody() {
        val logic = WearGestureLogic()
        logic.offBodyAction(value = 0.0f, secure = true, prefEnabled = true) // off the wrist
        assertEquals(
            TiltAction.IGNORE,
            logic.tiltAction(nowUptimeMs = 1_000, value = 1.0f, theaterModeOn = false),
        )
        // Back on the wrist (and past the debounce): a raise wakes again.
        logic.offBodyAction(value = 1.0f, secure = true, prefEnabled = true)
        assertEquals(
            TiltAction.WAKE,
            logic.tiltAction(nowUptimeMs = 10_000, value = 1.0f, theaterModeOn = false),
        )
    }

    @Test
    fun tiltNeverWakesInTheaterMode() {
        val logic = WearGestureLogic()
        assertEquals(
            TiltAction.IGNORE,
            logic.tiltAction(nowUptimeMs = 1_000, value = 1.0f, theaterModeOn = true),
        )
        // A raise dropped by theater mode must not arm the debounce for when it turns off again.
        assertEquals(
            TiltAction.WAKE,
            logic.tiltAction(nowUptimeMs = 1_100, value = 1.0f, theaterModeOn = false),
        )
    }

    // ---- off-body events -----------------------------------------------------------------------

    @Test
    fun leavingTheWristLocksWithThePreferenceOnAndACredential() {
        val logic = WearGestureLogic()
        assertEquals(OffBodyAction.LOCK, logic.offBodyAction(value = 0.0f, secure = true, prefEnabled = true))
    }

    @Test
    fun stayingOnTheWristNeverLocks() {
        val logic = WearGestureLogic()
        assertEquals(OffBodyAction.IGNORE, logic.offBodyAction(value = 1.0f, secure = true, prefEnabled = true))
    }

    @Test
    fun noCredentialNeverLocks() {
        val logic = WearGestureLogic()
        assertEquals(OffBodyAction.IGNORE, logic.offBodyAction(value = 0.0f, secure = false, prefEnabled = true))
    }

    @Test
    fun thePreferenceOffNeverLocks() {
        val logic = WearGestureLogic()
        assertEquals(OffBodyAction.IGNORE, logic.offBodyAction(value = 0.0f, secure = true, prefEnabled = false))
    }

    @Test
    fun offBodyStateTracksTheSensorValue() {
        val logic = WearGestureLogic()
        assertFalse(logic.offBody)
        logic.offBodyAction(value = 0.0f, secure = true, prefEnabled = true)
        assertTrue(logic.offBody)
        logic.offBodyAction(value = 1.0f, secure = true, prefEnabled = true)
        assertFalse(logic.offBody)
    }

    @Test
    fun tiltEventsRightAfterArmingAreActivationArtefacts() {
        val logic = WearGestureLogic()
        assertFalse(logic.inArmingGrace(5_000L, null))
        assertTrue(logic.inArmingGrace(5_000L, 5_000L))
        assertTrue(logic.inArmingGrace(5_999L, 5_000L))
        assertFalse(logic.inArmingGrace(6_000L, 5_000L))
        assertFalse(logic.inArmingGrace(4_000L, 5_000L)) // clock before arming: not a grace hit
    }
}
