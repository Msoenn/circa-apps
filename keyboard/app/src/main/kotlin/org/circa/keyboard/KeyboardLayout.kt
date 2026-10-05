package org.circa.keyboard

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** An axis-aligned rectangle in view pixels. Pure (no android.graphics) so the layout math runs on the JVM. */
data class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
    val w: Float get() = r - l
    val h: Float get() = b - t
    val cx: Float get() = (l + r) / 2f
    val cy: Float get() = (t + b) / 2f
    fun contains(x: Float, y: Float) = x >= l && x < r && y >= t && y < b
    fun offset(dx: Float, dy: Float) = Box(l + dx, t + dy, r + dx, b + dy)

    /** True when all four corners lie inside the circle of [radius] centred at ([c], [c]). */
    fun insideCircle(c: Float, radius: Float): Boolean {
        val dx = max(abs(l - c), abs(r - c))
        val dy = max(abs(t - c), abs(b - c))
        return dx * dx + dy * dy <= radius * radius + 0.01f
    }
}

enum class KeyType { CHAR, SHIFT, TO_SYMBOLS, TO_LETTERS, TO_MORE_SYMBOLS, SPACE, DELETE, ENTER, HIDE }

enum class Page { LETTERS, SYMBOLS, SYMBOLS2, NUMBER, PHONE }

/**
 * One key. [box] is what is drawn (always fully inside the round panel); [hit] is the touch target, which
 * extends into the gaps between keys and out to the panel edge so a near miss still lands on a key.
 */
data class Key(
    val type: KeyType,
    val label: String,
    /** Text committed for CHAR keys (the lower-case form for letters). */
    val text: String,
    val box: Box,
    val hit: Box,
    val row: Int,
)

class KeyboardLayout(
    val page: Page,
    val size: Float,
    val keys: List<Key>,
    /** The typed-text preview line at the top. */
    val preview: Box,
    /** The suggestion strip (also the password show/hide toggle), or null on the numeric pages. */
    val strip: Box?,
) {
    val center: Float get() = size / 2f
    val radius: Float get() = size / 2f

    fun keyAt(x: Float, y: Float): Key? = keys.firstOrNull { it.hit.contains(x, y) }

    /** Half the width of the circle at height [y] (0 outside it). */
    fun halfChord(y: Float): Float = halfChord(size, y)

    /** [want] (a popup) moved horizontally, then vertically if needed, until it lies inside the circle. */
    fun fitInCircle(want: Box, margin: Float = size * 0.01f): Box = fitInCircle(size, want, margin)

    companion object {
        fun halfChord(size: Float, y: Float): Float {
            val r = size / 2f
            val dy = y - r
            return if (abs(dy) >= r) 0f else sqrt(r * r - dy * dy)
        }

        fun fitInCircle(size: Float, want: Box, margin: Float): Box {
            val c = size / 2f
            val rr = c - margin
            var b = want
            // Clamp horizontally to the chord at the box's worst edge; if the box is wider than that chord,
            // pull it towards the centre vertically in small steps until it fits.
            repeat(64) {
                val worstY = if (abs(b.t - c) > abs(b.b - c)) b.t else b.b
                val hc = halfChord(rr * 2f, worstY - margin) // chord of the inset circle
                val minL = c - hc
                val maxR = c + hc
                if (b.w <= maxR - minL) {
                    val dx = when {
                        b.l < minL -> minL - b.l
                        b.r > maxR -> maxR - b.r
                        else -> 0f
                    }
                    val moved = b.offset(dx, 0f)
                    if (moved.insideCircle(c, rr)) return moved
                    b = moved
                }
                b = b.offset(0f, if (b.cy < c) size * 0.01f else -size * 0.01f)
            }
            return b
        }
    }
}

/**
 * Builds every page for a square view of [size] px holding the round panel. All geometry is in fractions of
 * the diameter so the same layout serves 384 px at any density (documented in keyboard/README.md).
 */
object LayoutEngine {
    // Vertical bands, fractions of the diameter.
    const val PREVIEW_TOP = 0.085f
    const val PREVIEW_BOTTOM = 0.195f
    const val STRIP_TOP = 0.20f
    const val STRIP_BOTTOM = 0.325f
    const val KEYS_TOP = 0.33f
    const val ROW_H = 0.125f

    const val NUM_KEYS_TOP = 0.21f
    const val NUM_ROW_H = 0.127f
    const val NUM_MAX_WIDTH = 0.72f

    /** Gap between drawn keys and the minimum distance of a drawn key from the panel edge. */
    const val GAP = 0.012f
    const val EDGE = 0.012f

    /** No letter key wider than this (fraction of D): the three-key-wide middle of row 3 would look silly. */
    const val MAX_KEY_W = 0.125f

    /** The hide chevron: its target starts this far below the last key row; the drawn pill is HIDE_W x HIDE_H. */
    const val HIDE_GAP = 0.025f
    const val HIDE_W = 0.16f
    const val HIDE_H = 0.065f

    private data class Spec(val type: KeyType, val label: String, val text: String = label, val weight: Float = 1f)

    private fun chars(s: String) = s.map { Spec(KeyType.CHAR, it.toString()) }
    private fun gap() = Spec(KeyType.CHAR, "", "", 1f) // an empty slot (numeric pages); never becomes a key

    fun build(page: Page, profile: EditorProfile, size: Float): KeyboardLayout {
        val preview = band(size, PREVIEW_TOP, PREVIEW_BOTTOM)
        return when (page) {
            Page.NUMBER, Page.PHONE -> buildNumeric(page, profile, size, preview)
            else -> buildText(page, profile, size, preview)
        }
    }

    private fun band(size: Float, top: Float, bottom: Float): Box {
        val t = top * size
        val b = bottom * size
        val worst = if (abs(t - size / 2) > abs(b - size / 2)) t else b
        val hc = KeyboardLayout.halfChord(size, worst) - EDGE * size
        return Box(size / 2 - hc, t, size / 2 + hc, b)
    }

    private fun enterSpec(profile: EditorProfile, weight: Float) =
        Spec(KeyType.ENTER, profile.enterLabel ?: "", "\n", weight)

    private fun textRows(page: Page, profile: EditorProfile): List<List<Spec>> {
        val space = Spec(KeyType.SPACE, "", " ", 2.4f)
        val del = Spec(KeyType.DELETE, "", "", 1.1f)
        val enter = enterSpec(profile, 1.4f)
        return when (page) {
            Page.LETTERS -> {
                val row3 = when (profile.kind) {
                    InputKind.EMAIL -> chars("@zxcvbnm.")
                    InputKind.URI -> chars("/zxcvbnm.")
                    // Comma and period on the letters page: a message needs them far more often than "?123".
                    else -> chars(",zxcvbnm.")
                }
                listOf(
                    chars("qwertyuiop"),
                    chars("asdfghjkl"),
                    row3,
                    listOf(Spec(KeyType.SHIFT, "", "", 1f), Spec(KeyType.TO_SYMBOLS, "?123", "", 1.1f), space, del, enter),
                )
            }
            Page.SYMBOLS -> listOf(
                chars("1234567890"),
                chars("@#$%&-+()"),
                chars(",*\"':;!?."),
                listOf(Spec(KeyType.TO_MORE_SYMBOLS, "=\\<", "", 1f), Spec(KeyType.TO_LETTERS, "ABC", "", 1.1f), space, del, enter),
            )
            Page.SYMBOLS2 -> listOf(
                chars("~`|•√π÷×¶∆"),
                chars("€£¥¢^°={}"),
                chars("\\_<>[]©…/"),
                listOf(Spec(KeyType.TO_SYMBOLS, "?123", "", 1f), Spec(KeyType.TO_LETTERS, "ABC", "", 1.1f), space, del, enter),
            )
            else -> error("not a text page: $page")
        }
    }

    private fun buildText(page: Page, profile: EditorProfile, size: Float, preview: Box): KeyboardLayout {
        val strip = band(size, STRIP_TOP, STRIP_BOTTOM)
        val rows = textRows(page, profile)
        val bottom = KEYS_TOP + rows.size * ROW_H
        val keys = layRows(rows, size, KEYS_TOP, ROW_H, maxRowWidth = 1f, maxKeyW = MAX_KEY_W, hitTop = strip.b,
            hitBottom = (bottom + HIDE_GAP) * size) + hideKey(size, bottom, rows.size)
        return KeyboardLayout(page, size, keys, preview, strip)
    }

    private fun buildNumeric(page: Page, profile: EditorProfile, size: Float, preview: Box): KeyboardLayout {
        val del = Spec(KeyType.DELETE, "", "", 1.5f)
        val enter = enterSpec(profile, 1.5f)
        val rows = if (page == Page.PHONE) listOf(
            chars("123"), chars("456"), chars("789"), chars("*0#"),
            listOf(Spec(KeyType.CHAR, "+", "+", 1f), del.copy(weight = 1f), enter.copy(weight = 1f)),
        ) else {
            val left = when {
                profile.kind == InputKind.DATETIME -> Spec(KeyType.CHAR, "/")
                profile.signed -> Spec(KeyType.CHAR, "-")
                else -> gap()
            }
            val right = when {
                profile.kind == InputKind.DATETIME -> Spec(KeyType.CHAR, ":")
                profile.decimal -> Spec(KeyType.CHAR, ".")
                else -> gap()
            }
            listOf(chars("123"), chars("456"), chars("789"), listOf(left, Spec(KeyType.CHAR, "0"), right), listOf(del, enter))
        }
        val bottom = NUM_KEYS_TOP + rows.size * NUM_ROW_H
        val keys = layRows(rows, size, NUM_KEYS_TOP, NUM_ROW_H, maxRowWidth = NUM_MAX_WIDTH, maxKeyW = 1f, hitTop = preview.b,
            hitBottom = (bottom + HIDE_GAP) * size) + hideKey(size, bottom, rows.size)
        return KeyboardLayout(page, size, keys, preview, null)
    }

    /**
     * The "hide keyboard" chevron in the otherwise unused bottom cap of the circle (the discoverable twin of the
     * swipe-right gesture). Its touch target is the whole cap below [rowsBottom] + [HIDE_GAP]; the drawn pill is small.
     */
    private fun hideKey(size: Float, rowsBottom: Float, row: Int): Key {
        val c = size / 2f
        val t = (rowsBottom + HIDE_GAP + 0.005f) * size
        val box = Box(c - HIDE_W * size / 2f, t, c + HIDE_W * size / 2f, t + HIDE_H * size)
        return Key(KeyType.HIDE, "", "", box, Box(0f, (rowsBottom + HIDE_GAP) * size, size, size), row)
    }

    /**
     * Lays [rows] out top-down from [top] (fraction of D), each [rowH] tall. A row's slots share the width of
     * the circle's chord at the row's outer edge (minus [EDGE]), capped at [maxRowWidth] * D and at
     * [maxKeyW] * D per weight unit; slots are centred. Drawn boxes are the slots inset by [GAP]/2; hit boxes
     * are the slots themselves, with the outer slots stretched to the view edges, the first row stretched up to
     * [hitTop] and the last row down to [hitBottom].
     */
    private fun layRows(
        rows: List<List<Spec>>, size: Float, top: Float, rowH: Float,
        maxRowWidth: Float, maxKeyW: Float, hitTop: Float, hitBottom: Float,
    ): List<Key> {
        val c = size / 2f
        val g = GAP * size / 2f
        val out = ArrayList<Key>()
        rows.forEachIndexed { ri, row ->
            val t = (top + ri * rowH) * size
            val b = t + rowH * size
            // The drawn keys are inset by g, so the corners that must stay inside the circle are at t+g / b-g.
            val worst = if (abs(t + g - c) > abs(b - g - c)) t + g else b - g
            val avail = 2f * (KeyboardLayout.halfChord(size, worst) - EDGE * size) + 2f * g
            val weights = row.sumOf { it.weight.toDouble() }.toFloat()
            val unit = min(min(avail, maxRowWidth * size) / weights, maxKeyW * size)
            var x = c - unit * weights / 2f
            row.forEachIndexed { ki, spec ->
                val slot = Box(x, t, x + unit * spec.weight, b)
                x = slot.r
                if (spec.label.isEmpty() && spec.type == KeyType.CHAR) return@forEachIndexed // an empty slot
                val hit = Box(
                    if (ki == 0) 0f else slot.l,
                    if (ri == 0) hitTop else slot.t,
                    if (ki == row.lastIndex) size else slot.r,
                    if (ri == rows.lastIndex) hitBottom else slot.b,
                )
                val box = Box(slot.l + g, slot.t + g, slot.r - g, slot.b - g)
                out += Key(spec.type, spec.label, spec.text, box, hit, ri)
            }
        }
        return out
    }

    /** The page an editor opens on. */
    fun firstPage(profile: EditorProfile): Page = when (profile.kind) {
        InputKind.PHONE -> Page.PHONE
        InputKind.NUMBER, InputKind.DATETIME -> Page.NUMBER
        else -> Page.LETTERS
    }
}
