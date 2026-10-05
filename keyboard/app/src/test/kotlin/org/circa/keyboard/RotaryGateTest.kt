package org.circa.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crown (rotary encoder) must reach the focused app while the keyboard window is hidden; a hidden
 * IME that consumed it left every list unscrollable (audit A09). The service asks [RotaryGate] before it
 * touches the event.
 */
class RotaryGateTest {

    @Test
    fun `rotary while keyboard hidden is not consumed`() {
        // The bug: shown=false but the event is a rotary scroll -> the app must keep the crown.
        assertFalse(RotaryGate.shouldConsume(isRotaryScroll = true, inputViewShown = false, inputStarted = true))
        assertFalse(RotaryGate.shouldConsume(isRotaryScroll = true, inputViewShown = false, inputStarted = false))
    }

    @Test
    fun `rotary with no editor is not consumed`() {
        // Window up but no input connection (transition, finishing): pass it on.
        assertFalse(RotaryGate.shouldConsume(isRotaryScroll = true, inputViewShown = true, inputStarted = false))
    }

    @Test
    fun `non-rotary motion is never consumed`() {
        assertFalse(RotaryGate.shouldConsume(isRotaryScroll = false, inputViewShown = true, inputStarted = true))
        assertFalse(RotaryGate.shouldConsume(isRotaryScroll = false, inputViewShown = false, inputStarted = false))
    }

    @Test
    fun `rotary while keyboard shown and editing is consumed`() {
        assertTrue(RotaryGate.shouldConsume(isRotaryScroll = true, inputViewShown = true, inputStarted = true))
    }
}
