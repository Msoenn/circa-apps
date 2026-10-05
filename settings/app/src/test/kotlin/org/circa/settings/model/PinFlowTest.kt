package org.circa.settings.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinFlowTest {
    private fun type(s0: PinState, digits: String): Pair<PinState, PinEffect> {
        var s = s0
        var e: PinEffect = PinEffect.None
        for (d in digits) { val r = PinFlow.digit(s, d); s = r.first; e = r.second }
        return s to e
    }

    @Test fun newPinNeedsMinimumThenConfirmsAndCommits() {
        var s = PinFlow.start(PinMode.NEW)
        assertEquals(PinStage.ENTER, s.stage)
        s = type(s, "123").first
        assertTrue(!PinFlow.showSubmit(s))
        assertEquals(PinMessage.TOO_SHORT, PinFlow.submit(s).first.message)
        s = type(s, "4").first
        assertTrue(PinFlow.showSubmit(s))
        s = PinFlow.submit(s).first
        assertEquals(PinStage.CONFIRM, s.stage)
        assertEquals(4, PinFlow.placeholderDots(s))
        val (done, effect) = type(s, "1234")
        assertEquals(PinEffect.Commit("1234", null), effect)
        assertTrue(done.busy)
    }

    @Test fun mismatchGoesBackToEnter() {
        var s = type(PinFlow.start(PinMode.NEW), "1234").first
        s = PinFlow.submit(s).first
        val (back, effect) = type(s, "1235")
        assertEquals(PinEffect.None, effect)
        assertEquals(PinStage.ENTER, back.stage)
        assertEquals(PinMessage.MISMATCH, back.message)
        assertNull(back.first)
    }

    @Test fun enterIsCappedAtMaxLength() {
        val s = type(PinFlow.start(PinMode.NEW), "123456789012").first
        assertEquals(PinFlow.MAX_LENGTH, s.typed.length)
    }

    @Test fun changeVerifiesTheCurrentPinFirstAndKeepsItForTheCommit() {
        var s = PinFlow.start(PinMode.CHANGE, knownLength = 4)
        assertEquals(PinStage.CURRENT, s.stage)
        assertEquals(4, PinFlow.placeholderDots(s))
        val (typed, effect) = type(s, "1234")
        assertEquals(PinEffect.Verify("1234"), effect)
        s = PinFlow.verified(typed, ok = true).first
        assertEquals(PinStage.ENTER, s.stage)
        s = PinFlow.submit(type(s, "5678").first).first
        assertEquals(PinEffect.Commit("5678", "1234"), type(s, "5678").second)
    }

    @Test fun wrongCurrentPinClearsAndThrottleShowsWait() {
        val typed = type(PinFlow.start(PinMode.CHANGE, 4), "0000").first
        assertEquals(PinMessage.WRONG, PinFlow.verified(typed, false).first.message)
        val t = PinFlow.verified(typed, false, waitSeconds = 30).first
        assertEquals("Try again in 30 s", PinFlow.message(t))
        assertEquals("", t.typed)
    }

    @Test fun removeCommitsNullAfterVerify() {
        val typed = type(PinFlow.start(PinMode.REMOVE, 4), "1234").first
        assertEquals(PinEffect.Commit(null, "1234"), PinFlow.verified(typed, true).second)
    }

    @Test fun unknownLengthUsesTheCheckKeyToVerify() {
        val s = type(PinFlow.start(PinMode.REMOVE, null), "123456").first
        assertTrue(PinFlow.showSubmit(s))
        assertEquals(PinEffect.Verify("123456"), PinFlow.submit(s).second)
    }

    @Test fun backspaceRemovesOneDigit() {
        val s = type(PinFlow.start(PinMode.NEW), "12").first
        assertEquals("1", PinFlow.backspace(s).typed)
    }
}
