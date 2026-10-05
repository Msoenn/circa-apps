package org.circa.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTest {
    private val size = 384f
    private val c = size / 2f

    private val text = EditorProfile.from(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_DONE)
    private val profiles = mapOf(
        "text" to text,
        "email" to EditorProfile.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, EditorInfo.IME_ACTION_NEXT),
        "uri" to EditorProfile.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, EditorInfo.IME_ACTION_GO),
        "password" to EditorProfile.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, EditorInfo.IME_ACTION_DONE),
        "multiline" to EditorProfile.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, 0),
        "number" to EditorProfile.from(InputType.TYPE_CLASS_NUMBER, EditorInfo.IME_ACTION_DONE),
        "number-signed-decimal" to EditorProfile.from(
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL, 0),
        "datetime" to EditorProfile.from(InputType.TYPE_CLASS_DATETIME, 0),
        "phone" to EditorProfile.from(InputType.TYPE_CLASS_PHONE, 0),
    )

    /** Every page each profile can reach. */
    private fun pagesFor(p: EditorProfile) = when (p.kind) {
        InputKind.PHONE -> listOf(Page.PHONE)
        InputKind.NUMBER, InputKind.DATETIME -> listOf(Page.NUMBER)
        else -> listOf(Page.LETTERS, Page.SYMBOLS, Page.SYMBOLS2)
    }

    private fun all() = profiles.flatMap { (name, p) -> pagesFor(p).map { Triple(name, p, LayoutEngine.build(it, p, size)) } }

    @Test fun everyKeyIsInsideTheCircle() {
        for ((name, _, l) in all()) {
            for (k in l.keys) assertTrue("$name/${l.page}: key '${k.label}' ${k.type} ${k.box} leaves the circle",
                k.box.insideCircle(c, c))
            assertTrue("$name/${l.page}: preview ${l.preview}", l.preview.insideCircle(c, c))
            l.strip?.let { assertTrue("$name/${l.page}: strip $it", it.insideCircle(c, c)) }
        }
    }

    @Test fun everyKeyIsInsideTheCircleAtOtherSizes() {
        for (s in listOf(320f, 360f, 384f, 450f, 480f)) for ((_, p) in profiles) for (page in pagesFor(p)) {
            val l = LayoutEngine.build(page, p, s)
            for (k in l.keys) assertTrue("$s/$page '${k.label}'", k.box.insideCircle(s / 2, s / 2))
        }
    }

    @Test fun keysAreBigEnough() {
        // 36 dp at the watch's 192 dpi = 43.2 px; at the emulator's 160 dpi the same px are 43 dp.
        val minH = 36f * 192f / 160f
        for ((name, _, l) in all()) for (k in l.keys) {
            if (k.type == KeyType.HIDE) {
                // A small drawn chevron, but its touch target is the whole bottom cap.
                assertTrue("$name/${l.page} hide target ${k.hit}", k.hit.h >= minH && k.hit.w == size)
                continue
            }
            assertTrue("$name/${l.page} '${k.label}' is ${k.box.h} px tall", k.box.h >= minH)
            assertTrue("$name/${l.page} '${k.label}' is ${k.box.w} px wide", k.box.w >= 28f)
        }
    }

    @Test fun keysDoNotOverlapAndHitAreasDoNotOverlap() {
        for ((name, _, l) in all()) {
            for (i in l.keys.indices) for (j in i + 1 until l.keys.size) {
                val a = l.keys[i]; val b = l.keys[j]
                assertTrue("$name/${l.page}: '${a.label}' and '${b.label}' overlap", !overlap(a.box, b.box))
                assertTrue("$name/${l.page}: hit '${a.label}' and '${b.label}' overlap", !overlap(a.hit, b.hit))
            }
        }
    }

    @Test fun hitAreasCoverTheKeyboardWithoutGaps() {
        // On the text pages, every point of the key block inside the circle lands on a key.
        for ((name, _, l) in all()) {
            if (l.page == Page.NUMBER || l.page == Page.PHONE) continue
            val top = l.strip!!.b
            var y = top + 0.5f
            while (y < size) {
                var x = 0.5f
                while (x < size) {
                    val dx = x - c; val dy = y - c
                    if (dx * dx + dy * dy < c * c) assertNotNull("$name/${l.page}: no key at ($x,$y)", l.keyAt(x, y))
                    x += 3f
                }
                y += 3f
            }
        }
    }

    @Test fun eachKeyCentreHitsItself() {
        for ((name, _, l) in all()) for (k in l.keys) assertEquals("$name/${l.page}", k, l.keyAt(k.box.cx, k.box.cy))
    }

    @Test fun lettersPageHasQwertyAndTheBottomRow() {
        val l = LayoutEngine.build(Page.LETTERS, text, size)
        val letters = l.keys.filter { it.type == KeyType.CHAR && it.label[0].isLetter() }.joinToString("") { it.label }
        assertEquals("qwertyuiopasdfghjklzxcvbnm", letters)
        assertEquals(listOf(KeyType.SHIFT, KeyType.TO_SYMBOLS, KeyType.SPACE, KeyType.DELETE, KeyType.ENTER),
            l.keys.filter { it.row == 3 && it.type != KeyType.HIDE }.map { it.type })
        // Every page has the hide chevron, and touching the very bottom of the circle hits it.
        assertEquals(KeyType.HIDE, l.keyAt(c, size - 2f)?.type)
        assertEquals(KeyType.SPACE, l.keyAt(c, l.keys.first { it.type == KeyType.SPACE }.box.b + 2f)?.type)
        // The top row is the narrowest (it sits highest in the circle).
        val w = (0..2).map { r -> l.keys.first { it.row == r }.box.w }
        assertTrue("row widths $w", w[0] < w[1] && w[0] < w[2])
    }

    @Test fun emailAndUriGetExtraKeys() {
        val e = LayoutEngine.build(Page.LETTERS, profiles.getValue("email"), size).keys.map { it.label }
        assertTrue("@" in e && "." in e)
        val u = LayoutEngine.build(Page.LETTERS, profiles.getValue("uri"), size).keys.map { it.label }
        assertTrue("/" in u && "." in u)
        val t = LayoutEngine.build(Page.LETTERS, text, size).keys.map { it.label }
        assertTrue("@" !in t && "/" !in t && "," in t && "." in t)
    }

    @Test fun numericPages() {
        val n = LayoutEngine.build(Page.NUMBER, profiles.getValue("number"), size)
        assertEquals("1234567890", n.keys.filter { it.type == KeyType.CHAR }.joinToString("") { it.label })
        assertEquals(null, n.strip)
        val sd = LayoutEngine.build(Page.NUMBER, profiles.getValue("number-signed-decimal"), size)
        assertEquals("123456789-0.", sd.keys.filter { it.type == KeyType.CHAR }.joinToString("") { it.label })
        val ph = LayoutEngine.build(Page.PHONE, profiles.getValue("phone"), size)
        assertEquals("123456789*0#+", ph.keys.filter { it.type == KeyType.CHAR }.joinToString("") { it.label })
        assertTrue(ph.keys.any { it.type == KeyType.DELETE } && ph.keys.any { it.type == KeyType.ENTER })
        // Numeric keys are much bigger than letter keys.
        assertTrue(n.keys.first().box.w > 70f)
        assertEquals(Page.NUMBER, LayoutEngine.firstPage(profiles.getValue("number")))
        assertEquals(Page.PHONE, LayoutEngine.firstPage(profiles.getValue("phone")))
        assertEquals(Page.LETTERS, LayoutEngine.firstPage(profiles.getValue("password")))
    }

    @Test fun popupsAreFittedIntoTheCircle() {
        // A magnifier above 'q' (top-left corner of the keyboard) would stick out; it must be pulled in.
        val l = LayoutEngine.build(Page.LETTERS, text, size)
        for (k in l.keys) {
            val want = Box(k.box.cx - 40f, k.box.t - 60f, k.box.cx + 40f, k.box.t - 4f)
            assertTrue("popup over '${k.label}'", l.fitInCircle(want, 4f).insideCircle(c, c - 4f))
        }
        val wide = Box(0f, 100f, 300f, 150f) // eight accents above 'a'
        assertTrue(l.fitInCircle(wide, 4f).insideCircle(c, c - 4f))
    }

    private fun overlap(a: Box, b: Box) = a.l < b.r - 0.01f && b.l < a.r - 0.01f && a.t < b.b - 0.01f && b.t < a.b - 0.01f
}
