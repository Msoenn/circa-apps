package org.circa.keyboard

import android.text.InputType.TYPE_CLASS_DATETIME
import android.text.InputType.TYPE_CLASS_NUMBER
import android.text.InputType.TYPE_CLASS_PHONE
import android.text.InputType.TYPE_CLASS_TEXT
import android.text.InputType.TYPE_NULL
import android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
import android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
import android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
import android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
import android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
import android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
import android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
import android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
import android.text.InputType.TYPE_TEXT_VARIATION_URI
import android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
import android.text.InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
import android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
import android.view.inputmethod.EditorInfo.IME_ACTION_DONE
import android.view.inputmethod.EditorInfo.IME_ACTION_GO
import android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
import android.view.inputmethod.EditorInfo.IME_ACTION_NONE
import android.view.inputmethod.EditorInfo.IME_ACTION_PREVIOUS
import android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
import android.view.inputmethod.EditorInfo.IME_ACTION_SEND
import android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED
import android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION
import android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorProfileTest {
    private fun p(type: Int, opts: Int = 0, label: String? = null) = EditorProfile.from(type, opts, label)

    @Test fun plainText() {
        val t = p(TYPE_CLASS_TEXT or TYPE_TEXT_FLAG_CAP_SENTENCES)
        assertEquals(InputKind.TEXT, t.kind)
        assertTrue(t.suggestions); assertTrue(t.learn); assertFalse(t.secret); assertFalse(t.numeric)
    }

    @Test fun passwordsGetNoSuggestionsNoLearningAndDots() {
        for (v in listOf(TYPE_TEXT_VARIATION_PASSWORD, TYPE_TEXT_VARIATION_WEB_PASSWORD)) {
            val t = p(TYPE_CLASS_TEXT or v, IME_ACTION_DONE)
            assertEquals(InputKind.PASSWORD, t.kind)
            assertFalse(t.suggestions); assertFalse(t.learn); assertTrue(t.secret)
        }
        val visible = p(TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        assertEquals(InputKind.PASSWORD, visible.kind)
        assertFalse(visible.suggestions); assertFalse(visible.learn); assertFalse(visible.secret)
        val pin = p(TYPE_CLASS_NUMBER or TYPE_NUMBER_VARIATION_PASSWORD)
        assertEquals(InputKind.NUMBER, pin.kind)
        assertTrue(pin.secret); assertFalse(pin.learn); assertFalse(pin.suggestions)
    }

    @Test fun numberPhoneDatetime() {
        val n = p(TYPE_CLASS_NUMBER)
        assertEquals(InputKind.NUMBER, n.kind); assertTrue(n.numeric); assertFalse(n.suggestions)
        assertFalse(n.signed); assertFalse(n.decimal)
        val sd = p(TYPE_CLASS_NUMBER or TYPE_NUMBER_FLAG_SIGNED or TYPE_NUMBER_FLAG_DECIMAL)
        assertTrue(sd.signed); assertTrue(sd.decimal)
        assertEquals(InputKind.PHONE, p(TYPE_CLASS_PHONE).kind)
        assertEquals(InputKind.DATETIME, p(TYPE_CLASS_DATETIME).kind)
        assertTrue(p(TYPE_CLASS_DATETIME).numeric)
    }

    @Test fun emailAndUri() {
        for (v in listOf(TYPE_TEXT_VARIATION_EMAIL_ADDRESS, TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)) {
            val e = p(TYPE_CLASS_TEXT or v)
            assertEquals(InputKind.EMAIL, e.kind); assertFalse(e.suggestions); assertFalse(e.learn)
        }
        assertEquals(InputKind.URI, p(TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_URI).kind)
    }

    @Test fun noSuggestionsFlagAndNoPersonalizedLearning() {
        val ns = p(TYPE_CLASS_TEXT or TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        assertFalse(ns.suggestions); assertFalse(ns.learn)
        val incog = p(TYPE_CLASS_TEXT, IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertTrue(incog.suggestions); assertFalse(incog.learn)
    }

    @Test fun enterKeyLabelsFollowImeOptions() {
        val cases = mapOf(
            IME_ACTION_GO to ("Go" to EnterAction.GO),
            IME_ACTION_NEXT to ("Next" to EnterAction.NEXT),
            IME_ACTION_DONE to ("Done" to EnterAction.DONE),
            IME_ACTION_SEND to ("Send" to EnterAction.SEND),
            IME_ACTION_SEARCH to ("Search" to EnterAction.SEARCH),
            IME_ACTION_PREVIOUS to ("Prev" to EnterAction.PREVIOUS),
        )
        for ((action, expected) in cases) {
            val t = p(TYPE_CLASS_TEXT, action)
            assertEquals(expected.first, t.enterLabel)
            assertEquals(expected.second, t.enter)
            assertEquals(action, t.actionId)
        }
    }

    @Test fun enterWithoutAction() {
        val none = p(TYPE_CLASS_TEXT, IME_ACTION_NONE)
        assertEquals(EnterAction.NONE, none.enter); assertNull(none.enterLabel); assertNull(none.actionId)
        val unspecified = p(TYPE_CLASS_TEXT, IME_ACTION_UNSPECIFIED)
        assertEquals(EnterAction.NONE, unspecified.enter); assertNull(unspecified.actionId)
        val multi = p(TYPE_CLASS_TEXT or TYPE_TEXT_FLAG_MULTI_LINE, IME_ACTION_UNSPECIFIED)
        assertEquals(EnterAction.NEWLINE, multi.enter); assertTrue(multi.multiline); assertNull(multi.enterLabel)
        val multiDone = p(TYPE_CLASS_TEXT or TYPE_TEXT_FLAG_MULTI_LINE, IME_ACTION_DONE)
        assertEquals(EnterAction.DONE, multiDone.enter)
        val noEnter = p(TYPE_CLASS_TEXT, IME_ACTION_SEND or IME_FLAG_NO_ENTER_ACTION)
        assertEquals(EnterAction.NEWLINE, noEnter.enter); assertNull(noEnter.actionId)
    }

    @Test fun customActionLabelWins() {
        assertEquals("Join", p(TYPE_CLASS_TEXT, IME_ACTION_GO, "Join").enterLabel)
        assertEquals("Go", p(TYPE_CLASS_TEXT, IME_ACTION_GO, "  ").enterLabel)
    }

    @Test fun typeNullFallsBackToText() {
        val t = p(TYPE_NULL)
        assertEquals(InputKind.TEXT, t.kind); assertFalse(t.secret)
    }

    @Test fun accents() {
        assertEquals("e", Accents.of("e").first())
        assertTrue("é" in Accents.of("e"))
        assertTrue("É" in Accents.of("E"))
        assertTrue("ß" in Accents.of("S")) // no single-char upper case: kept as is
        assertTrue(Accents.of("q").isEmpty())
    }
}
