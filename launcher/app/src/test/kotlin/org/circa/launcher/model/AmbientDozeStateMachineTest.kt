package org.circa.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientDozeStateMachineTest {

    private val minute = 60_000L

    @Test
    fun startHoldsThePanelInDoze() {
        val machine = AmbientDozeStateMachine()
        val hold = machine.onStarted(1_000L)

        assertEquals(AmbientDozeStateMachine.Phase.HOLDING, machine.phase)
        assertEquals(AmbientDozeStateMachine.Screen.DOZE, machine.screen)
        assertEquals(AmbientDozeStateMachine.ENTRY_HOLD_MILLIS, hold)
        assertEquals(1_000L + AmbientDozeStateMachine.ENTRY_HOLD_MILLIS, machine.holdUntilMillis)
    }

    @Test
    fun holdElapsedSuspendsThePanel() {
        val machine = AmbientDozeStateMachine()
        machine.onStarted(0L)

        machine.onHoldElapsed()

        assertEquals(AmbientDozeStateMachine.Phase.SUSPENDED, machine.phase)
        assertEquals(AmbientDozeStateMachine.Screen.DOZE_SUSPEND, machine.screen)
        assertEquals(AmbientDozeStateMachine.NEVER, machine.holdUntilMillis)
    }

    @Test
    fun minuteTickDrawsBeforeSuspending() {
        val machine = AmbientDozeStateMachine()
        machine.onStarted(0L)
        machine.onHoldElapsed()

        val hold = machine.onMinuteTick(minute)

        assertEquals(AmbientDozeStateMachine.Screen.DOZE, machine.screen)
        assertEquals(AmbientDozeStateMachine.REDRAW_HOLD_MILLIS, hold)
        assertEquals(minute + AmbientDozeStateMachine.REDRAW_HOLD_MILLIS, machine.holdUntilMillis)

        machine.onHoldElapsed()
        assertEquals(AmbientDozeStateMachine.Screen.DOZE_SUSPEND, machine.screen)
    }

    @Test
    fun tickDuringAHoldExtendsIt() {
        // A tick can land while the entry (or a previous redraw) hold is still running: the newest
        // frame needs its own time on the panel, so the hold restarts rather than being ignored.
        val machine = AmbientDozeStateMachine()
        machine.onStarted(0L)
        assertNotEquals(AmbientDozeStateMachine.NEVER, machine.holdUntilMillis)

        val now = AmbientDozeStateMachine.ENTRY_HOLD_MILLIS - 1
        machine.onMinuteTick(now)

        assertEquals(AmbientDozeStateMachine.Screen.DOZE, machine.screen)
        assertEquals(now + AmbientDozeStateMachine.REDRAW_HOLD_MILLIS, machine.holdUntilMillis)
    }

    @Test
    fun holdsAreConfigurable() {
        val machine = AmbientDozeStateMachine(entryHoldMillis = 7L, redrawHoldMillis = 3L)

        assertEquals(7L, machine.onStarted(10L))
        assertEquals(17L, machine.holdUntilMillis)
        machine.onHoldElapsed()
        assertEquals(3L, machine.onMinuteTick(100L))
        assertEquals(103L, machine.holdUntilMillis)
    }

    @Test
    fun stoppedLeavesTheMachineIdleAndSuspended() {
        val machine = AmbientDozeStateMachine()
        machine.onStarted(0L)

        machine.onStopped()

        assertEquals(AmbientDozeStateMachine.Screen.DOZE_SUSPEND, machine.screen)
        assertEquals(AmbientDozeStateMachine.NEVER, machine.holdUntilMillis)
    }

    @Test
    fun minuteTickIsAlignedToTheNextWholeMinute() {
        assertEquals(minute, AmbientDozeStateMachine.millisUntilNextMinute(0L))
        assertEquals(minute - 1, AmbientDozeStateMachine.millisUntilNextMinute(1L))
        assertEquals(1L, AmbientDozeStateMachine.millisUntilNextMinute(minute - 1))
        assertEquals(minute, AmbientDozeStateMachine.millisUntilNextMinute(minute))
        assertEquals(minute, AmbientDozeStateMachine.millisUntilNextMinute(minute * 1_000L))
        assertEquals(30_000L, AmbientDozeStateMachine.millisUntilNextMinute(minute * 1_000L + 30_000L))
    }

    @Test
    fun minuteTickIsAlignedBeforeTheEpochToo() {
        // Same floorDiv rule as AmbientFace's burn-in index: a clock set before 1970 must still point
        // at the *next* minute, not the previous one.
        assertEquals(1L, AmbientDozeStateMachine.millisUntilNextMinute(-1L))
        assertEquals(minute, AmbientDozeStateMachine.millisUntilNextMinute(-minute))
        assertEquals(1L, AmbientDozeStateMachine.millisUntilNextMinute(-minute - 1))
        for (t in -3 * minute until 0L) {
            val until = AmbientDozeStateMachine.millisUntilNextMinute(t)
            assertTrue("next minute from $t is $until ms away", until in 1..minute)
        }
    }
}
