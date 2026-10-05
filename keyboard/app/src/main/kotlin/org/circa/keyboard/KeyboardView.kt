package org.circa.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.TextUtils
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min

enum class Shift { OFF, ONCE, LOCK }

/**
 * The whole keyboard: one full-screen round View drawn on a Canvas (no widgets, no Compose), so a keystroke
 * costs one invalidate. The service owns the state and pushes it in through [update]; touches come back
 * through [Listener]. Every size is a fraction of the panel diameter (see [LayoutEngine]).
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onKey(key: Key, text: String)
        fun onDeleteRepeat()
        fun onSuggestion(index: Int)
        fun onToggleReveal()
        /** Swipe right, or the chevron in the bottom cap. */
        fun onSwipeHide()
    }

    /** What the service shows; replaced as a whole on every change. */
    data class State(
        val profile: EditorProfile,
        val page: Page,
        val shift: Shift,
        val before: String,
        val after: String,
        val hint: String?,
        val revealed: Boolean,
        val suggestions: List<String>,
        /** [suggestions] are fixed shortcut chips (email/URL), shown in order without a "best" highlight. */
        val chips: Boolean = false,
        val accent: Int,
    )

    private val debuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    var state: State? = null
        private set
    private var layout: KeyboardLayout? = null

    fun update(s: State) {
        val old = state
        state = s
        if (old == null || old.page != s.page || old.profile != s.profile) layout = null
        invalidate()
    }

    private fun layoutFor(s: State): KeyboardLayout {
        val size = min(width, height).toFloat()
        val l = layout
        if (l != null && l.size == size) return l
        return LayoutEngine.build(s.page, s.profile, size).also {
            layout = it
            if (debuggable) {
                // Debug builds only: key centres for scripted emulator tests (keyboard/README.md).
                android.util.Log.d("CircaKbLayout", "page=${it.page} " + it.keys.joinToString(" ") { k ->
                    "${k.type.name.take(3)}:${k.label.ifEmpty { k.type.name }}@${k.box.cx.toInt()},${k.box.cy.toInt()}"
                })
            }
        }
    }

    // ---------------------------------------------------------------- measuring
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Full screen: the watch is too small for an app + keyboard split. The IME window gives us the whole
        // display; take all of it (height included, the IME frame would otherwise wrap us to 0).
        val dm = resources.displayMetrics
        val w = MeasureSpec.getSize(widthMeasureSpec).takeIf { it > 0 } ?: dm.widthPixels
        setMeasuredDimension(w, dm.heightPixels)
    }

    // ---------------------------------------------------------------- paints
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val textMedium = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val rect = RectF()

    /** Platform VectorDrawables for the Material Symbols glyphs, inflated once (keyboard has no AndroidX). */
    private val glyphs = HashMap<Int, Drawable?>()

    // ---------------------------------------------------------------- touch state
    private var pressed: Key? = null
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var swiping = false
    private var accents: List<String> = emptyList()
    private var accentBoxes: List<Box> = emptyList()
    private var accentSel = 0
    private var stripDown = -1
    private var deleteRepeating = false

    private val longPress = Runnable {
        val k = pressed ?: return@Runnable
        val s = state ?: return@Runnable
        if (k.type == KeyType.CHAR) {
            val alts = Accents.of(displayLabel(k, s))
            if (alts.size > 1) {
                accents = alts
                accentBoxes = accentBoxesFor(k, alts.size)
                accentSel = 0
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                invalidate()
            }
        }
    }

    private val repeatDelete = object : Runnable {
        override fun run() {
            if (pressed?.type != KeyType.DELETE) return
            deleteRepeating = true
            listener.onDeleteRepeat()
            postDelayed(this, REPEAT_MS)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val s = state ?: return false
        val l = layoutFor(s)
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; downTime = SystemClock.uptimeMillis(); swiping = false
                accents = emptyList(); deleteRepeating = false
                stripDown = -1
                val strip = l.strip
                if (strip != null && y < strip.b && y >= strip.t - l.size * 0.02f) {
                    stripDown = stripIndex(l, s, x)
                    pressed = null
                } else if (y < l.preview.b) {
                    pressed = null
                } else {
                    press(l.keyAt(x, y))
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - downX
                if (!swiping && accents.isEmpty() && !deleteRepeating &&
                    dx > l.size * SWIPE_START && abs(y - downY) < dx * 0.6f
                ) {
                    swiping = true
                    cancelPress()
                }
                if (swiping) return true
                if (accents.isNotEmpty()) {
                    val i = accentBoxes.indexOfFirst { x >= it.l && x < it.r }
                    accentSel = when {
                        i >= 0 -> i
                        x < accentBoxes.first().l -> 0
                        else -> accentBoxes.lastIndex
                    }
                    invalidate()
                } else if (stripDown < 0 && y >= l.preview.b && pressed?.type != KeyType.DELETE) {
                    val k = l.keyAt(x, y)
                    if (k != pressed) press(k)
                }
            }
            MotionEvent.ACTION_UP -> {
                val dx = x - downX
                if (swiping) {
                    swiping = false
                    if (dx > l.size * SWIPE_HIDE) listener.onSwipeHide()
                    invalidate()
                    return true
                }
                if (stripDown >= 0) {
                    val i = stripIndex(l, s, x)
                    if (i == stripDown) {
                        if (s.profile.secret) listener.onToggleReveal() else listener.onSuggestion(cellToIndex(s, i))
                    }
                    stripDown = -1
                } else if (accents.isNotEmpty()) {
                    pressed?.let { listener.onKey(it, accents[accentSel]) }
                    accents = emptyList()
                } else {
                    val k = pressed
                    if (k?.type == KeyType.HIDE) listener.onSwipeHide()
                    else if (k != null && !(k.type == KeyType.DELETE && deleteRepeating)) {
                        if (k.type != KeyType.DELETE) listener.onKey(k, outputFor(k, s))
                    }
                }
                cancelPress()
            }
            MotionEvent.ACTION_CANCEL -> {
                swiping = false; stripDown = -1; accents = emptyList(); cancelPress()
            }
        }
        return true
    }

    private fun press(k: Key?) {
        removeCallbacks(longPress)
        removeCallbacks(repeatDelete)
        pressed = k
        if (k != null) {
            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            if (k.type == KeyType.DELETE) {
                // Delete acts on press (so holding it starts deleting at once), then repeats.
                listener.onKey(k, "")
                postDelayed(repeatDelete, LONG_PRESS_MS)
            } else {
                postDelayed(longPress, LONG_PRESS_MS)
            }
        }
        invalidate()
    }

    private fun cancelPress() {
        removeCallbacks(longPress)
        removeCallbacks(repeatDelete)
        pressed = null
        deleteRepeating = false
        invalidate()
    }

    /** Called by the service when the editor goes away mid-touch. */
    fun reset() {
        swiping = false; stripDown = -1; accents = emptyList(); cancelPress()
    }

    private fun stripCells(l: KeyboardLayout, s: State): Int =
        if (s.profile.secret) 1 else s.suggestions.size.coerceIn(0, 3)

    private fun stripIndex(l: KeyboardLayout, s: State, x: Float): Int {
        val strip = l.strip ?: return -1
        val n = stripCells(l, s)
        if (n == 0) return -1
        if (s.profile.secret) return 0
        val edges = stripEdges(l, s, strip)
        for (i in 0 until n) if (x < edges[i + 1]) return i
        return n - 1
    }

    /**
     * Cell edges of the suggestion strip: each cell is as wide as its word needs (at least a fifth of the strip),
     * and the spare room is shared out evenly, so a long word gets more room instead of being ellipsized.
     */
    private fun stripEdges(l: KeyboardLayout, s: State, strip: Box): FloatArray {
        val n = stripCells(l, s)
        val edges = FloatArray(n + 1)
        if (n == 0) return edges
        textMedium.textSize = l.size * 0.056f
        val need = FloatArray(n) { i ->
            maxOf(textMedium.measureText(s.suggestions[cellToIndex(s, i)]) + l.size * 0.035f, strip.w * 0.2f)
        }
        val total = need.sum()
        val spare = (strip.w - total) / n
        edges[0] = strip.l
        for (i in 0 until n) {
            val w = if (spare >= 0) need[i] + spare else need[i] * strip.w / total
            edges[i + 1] = edges[i] + w
        }
        edges[n] = strip.r
        return edges
    }

    private fun displayLabel(k: Key, s: State): String =
        if (k.type == KeyType.CHAR && s.page == Page.LETTERS && s.shift != Shift.OFF) k.label.uppercase() else k.label

    private fun outputFor(k: Key, s: State): String = when (k.type) {
        KeyType.CHAR -> displayLabel(k, s)
        KeyType.SPACE -> " "
        else -> ""
    }

    private fun accentBoxesFor(k: Key, n: Int): List<Box> {
        val size = min(width, height).toFloat()
        val w = k.box.w.coerceAtLeast(size * 0.09f)
        val h = k.box.h * 1.05f
        val total = Box(k.box.cx - w * n / 2f, k.box.t - h - size * 0.02f, k.box.cx + w * n / 2f, k.box.t - size * 0.02f)
        val fit = KeyboardLayout.fitInCircle(size, total, size * 0.015f)
        return List(n) { i -> Box(fit.l + i * w, fit.t, fit.l + (i + 1) * w, fit.b) }
    }

    // ---------------------------------------------------------------- drawing
    override fun onDraw(c: Canvas) {
        val s = state ?: return
        val l = layoutFor(s)
        val size = l.size
        c.drawColor(Color.BLACK)
        drawPreview(c, l, s)
        l.strip?.let { drawStrip(c, l, it, s) }
        for (k in l.keys) drawKey(c, l, k, s, k == pressed)
        val p = pressed
        if (accents.isNotEmpty() && p != null) drawAccents(c, size, s)
        else if (p != null && (p.type == KeyType.CHAR) && !swiping) drawMagnifier(c, l, p, s)
    }

    private fun drawPreview(c: Canvas, l: KeyboardLayout, s: State) {
        val box = l.preview
        val size = l.size
        val ts = size * 0.068f
        text.textSize = ts
        text.textAlign = Paint.Align.LEFT
        val baseline = box.cy + ts * 0.36f
        val fg = Color.rgb(0xE3, 0xE3, 0xE8)
        val shown = { t: String -> if (s.profile.secret && !s.revealed) "•".repeat(t.codePointCount(0, t.length)) else t }
        val before = shown(s.before)
        val after = shown(s.after)
        c.save()
        c.clipRect(box.l, box.t, box.r, box.b)
        if (before.isEmpty() && after.isEmpty()) {
            val hint = s.hint ?: when {
                s.profile.secret -> "Password"
                s.profile.numeric -> "Number"
                else -> "Type…"
            }
            text.color = Color.rgb(0x8F, 0x90, 0x99)
            val hintText = TextUtils.ellipsize(hint, text, box.w - size * 0.04f, TextUtils.TruncateAt.END).toString()
            val tw = text.measureText(hintText)
            val x0 = box.cx - tw / 2f
            c.drawText(hintText, x0, baseline, text)
            drawCaret(c, x0 - size * 0.008f, box, ts, s)
        } else {
            text.color = fg
            val wb = textWidth(before)
            val wa = textWidth(after)
            // Centre the text when it fits. Otherwise scroll so the caret stays visible (at most 3/4 across),
            // and never leave empty room at the right while text is hidden at the left.
            val pad = size * 0.015f
            val avail = box.w - 2 * pad
            var xStart = if (wb + wa <= avail) box.cx - (wb + wa) / 2f
            else if (wb <= avail * 0.75f) box.l + pad else box.l + pad + avail * 0.75f - wb
            if (wb + wa > avail && xStart + wb + wa < box.r - pad) xStart = box.r - pad - wb - wa
            drawSegments(c, before, xStart, baseline, fg)
            drawSegments(c, after, xStart + wb, baseline, fg)
            drawCaret(c, xStart + wb, box, ts, s)
            // Fade the clipped edges into the black background.
            fadeEdge(c, box.l, box, true)
            fadeEdge(c, box.r, box, false)
        }
        c.restore()
        text.textAlign = Paint.Align.CENTER
    }

    private fun fadeEdge(c: Canvas, x: Float, box: Box, left: Boolean) {
        val w = box.w * 0.08f
        fill.shader = android.graphics.LinearGradient(
            if (left) x else x - w, 0f, if (left) x + w else x, 0f,
            if (left) Color.BLACK else Color.TRANSPARENT, if (left) Color.TRANSPARENT else Color.BLACK,
            android.graphics.Shader.TileMode.CLAMP,
        )
        c.drawRect(if (left) x else x - w, box.t, if (left) x + w else x, box.b, fill)
        fill.shader = null
    }

    private fun drawCaret(c: Canvas, x: Float, box: Box, ts: Float, s: State) {
        fill.color = s.accent
        val h = ts * 1.05f
        c.drawRect(x, box.cy - h / 2f, x + ts * 0.07f, box.cy + h / 2f, fill)
    }

    private fun drawStrip(c: Canvas, l: KeyboardLayout, strip: Box, s: State) {
        val size = l.size
        val n = stripCells(l, s)
        if (n == 0) return
        textMedium.textSize = size * 0.056f
        val baseline = strip.cy + textMedium.textSize * 0.36f
        if (s.profile.secret) {
            // Password field: the strip is the show/hide toggle (no suggestions are ever made here).
            val label = if (s.revealed) "Hide password" else "Show password"
            val pillW = min(strip.w * 0.78f, textMedium.measureText(label) + size * 0.16f)
            rect.set(strip.cx - pillW / 2f, strip.t + strip.h * 0.14f, strip.cx + pillW / 2f, strip.b - strip.h * 0.14f)
            fill.color = if (stripDown == 0) blend(s.accent, SURFACE, 0.55f) else blend(s.accent, SURFACE, 0.22f)
            c.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, fill)
            drawGlyph(
                c,
                if (s.revealed) R.drawable.circa_ic_visibility_off_outlined else R.drawable.circa_ic_visibility_outlined,
                s.accent, rect.left + rect.height() * 0.62f, rect.centerY(), rect.height() * 0.62f,
            )
            textMedium.color = s.accent
            c.drawText(label, rect.centerX() + rect.height() * 0.3f, baseline, textMedium)
            return
        }
        val edges = stripEdges(l, s, strip)
        for (i in 0 until n) {
            val left = edges[i]
            val right = edges[i + 1]
            val cx = (left + right) / 2f
            if (i == stripDown) {
                rect.set(left + size * 0.006f, strip.t + strip.h * 0.12f, right - size * 0.006f, strip.b - strip.h * 0.12f)
                fill.color = SURFACE_HIGH
                c.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, fill)
            }
            // The best guess sits in the middle cell, highlighted (stock order: 2nd, 1st, 3rd). Chips: plain order.
            val best = !s.chips && (n == 3 && i == 1 || n < 3 && i == 0)
            textMedium.color = if (best) s.accent else ON_SURFACE
            // Words that still do not fit shrink (down to 70 %) before they are ellipsized.
            val full = s.suggestions[cellToIndex(s, i)]
            val room = right - left - size * 0.02f
            textMedium.textSize = size * 0.056f
            val wFull = textMedium.measureText(full)
            if (wFull > room) textMedium.textSize = (textMedium.textSize * room / wFull).coerceAtLeast(size * 0.04f)
            val word = TextUtils.ellipsize(full, textMedium, room, TextUtils.TruncateAt.END).toString()
            c.drawText(word, cx, strip.cy + textMedium.textSize * 0.36f, textMedium)
            if (i > 0) {
                fill.color = OUTLINE_VARIANT
                c.drawRect(left - 0.6f, strip.t + strip.h * 0.3f, left + 0.6f, strip.b - strip.h * 0.3f, fill)
            }
        }
    }

    private fun cellToIndex(s: State, cell: Int): Int {
        val n = s.suggestions.size.coerceIn(0, 3)
        return if (s.chips) cell else stripOrder(n)[cell]
    }

    private fun drawKey(c: Canvas, l: KeyboardLayout, k: Key, s: State, down: Boolean) {
        val size = l.size
        val b = k.box
        rect.set(b.l, b.t, b.r, b.b)
        val radius = min(b.w, b.h) * 0.32f
        val fn = k.type != KeyType.CHAR && k.type != KeyType.SPACE
        fill.color = when {
            k.type == KeyType.HIDE -> if (down) SURFACE_PRESSED else SURFACE_FN
            k.type == KeyType.ENTER -> if (down) blend(s.accent, Color.WHITE, 0.25f) else s.accent
            k.type == KeyType.SHIFT && s.shift == Shift.LOCK -> blend(s.accent, SURFACE, 0.35f)
            down -> SURFACE_PRESSED
            fn -> SURFACE_FN
            else -> SURFACE
        }
        c.drawRoundRect(rect, radius, radius, fill)
        val fg = if (k.type == KeyType.ENTER) ON_ACCENT else ON_SURFACE
        when (k.type) {
            KeyType.CHAR -> {
                text.color = fg
                text.textSize = if (s.profile.numeric) size * 0.085f else size * 0.062f
                val label = displayLabel(k, s)
                c.drawText(label, b.cx, b.cy + text.textSize * 0.36f, text)
            }
            KeyType.SPACE ->
                drawGlyph(c, R.drawable.circa_ic_space_bar_outlined, ON_SURFACE_VARIANT, b.cx, b.cy, b.w * 0.45f)
            KeyType.SHIFT -> drawShift(c, b, s)
            KeyType.HIDE ->
                drawGlyph(c, R.drawable.circa_ic_keyboard_hide_outlined, ON_SURFACE_VARIANT, b.cx, b.cy, min(b.w, b.h) * 0.6f)
            KeyType.DELETE -> drawBackspace(c, b, fg)
            KeyType.ENTER -> {
                val label = s.profile.enterLabel
                if (label == null || s.profile.enter == EnterAction.SEARCH) drawEnterIcon(c, b, fg, s.profile.enter == EnterAction.SEARCH)
                else {
                    textMedium.color = fg
                    textMedium.textSize = size * 0.05f
                    val t = TextUtils.ellipsize(label, textMedium, b.w - size * 0.012f, TextUtils.TruncateAt.END).toString()
                    c.drawText(t, b.cx, b.cy + textMedium.textSize * 0.36f, textMedium)
                }
            }
            else -> {
                textMedium.color = fg
                textMedium.textSize = size * 0.047f
                c.drawText(k.label, b.cx, b.cy + textMedium.textSize * 0.36f, textMedium)
            }
        }
    }

    private fun drawShift(c: Canvas, b: Box, s: State) {
        val on = s.shift != Shift.OFF
        val col = if (on) s.accent else ON_SURFACE
        val res = when (s.shift) {
            Shift.LOCK -> R.drawable.circa_ic_keyboard_capslock
            Shift.ONCE -> R.drawable.circa_ic_shift
            Shift.OFF -> R.drawable.circa_ic_shift_outlined
        }
        drawGlyph(c, res, col, b.cx, b.cy, min(b.w, b.h) * 0.5f)
    }

    private fun drawBackspace(c: Canvas, b: Box, col: Int) {
        drawGlyph(c, R.drawable.circa_ic_backspace_outlined, col, b.cx, b.cy, min(b.w, b.h) * 0.6f)
    }

    private fun drawEnterIcon(c: Canvas, b: Box, col: Int, search: Boolean) {
        val res = if (search) R.drawable.circa_ic_search_outlined else R.drawable.circa_ic_keyboard_return_outlined
        drawGlyph(c, res, col, b.cx, b.cy, min(b.w, b.h) * 0.55f)
    }

    /**
     * Draws the generated Material Symbols drawable [resId] centred on (cx, cy) in a [side]-px square,
     * tinted [color]. The keyboard has no AndroidX, so the glyphs are platform VectorDrawables.
     */
    private fun drawGlyph(c: Canvas, resId: Int, color: Int, cx: Float, cy: Float, side: Float) {
        val d = glyphs.getOrPut(resId) { context.getDrawable(resId) } ?: return
        d.setTint(color)
        val half = side / 2f
        d.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        d.draw(c)
    }

    /** Width of [t] in [text], with every newline standing in for the keyboard_return glyph. */
    private fun textWidth(t: String): Float {
        var w = 0f
        var start = 0
        for (i in t.indices) if (t[i] == '\n') {
            w += text.measureText(t, start, i) + text.textSize * NL_ADVANCE
            start = i + 1
        }
        return w + text.measureText(t, start, t.length)
    }

    /** Draws [t], drawing the keyboard_return glyph in place of every newline. */
    private fun drawSegments(c: Canvas, t: String, x: Float, baseline: Float, color: Int) {
        var cur = x
        var start = 0
        for (i in t.indices) if (t[i] == '\n') {
            if (i > start) {
                c.drawText(t, start, i, cur, baseline, text)
                cur += text.measureText(t, start, i)
            }
            drawGlyph(c, R.drawable.circa_ic_keyboard_return_outlined, color, cur + text.textSize / 2f, baseline - text.textSize * 0.36f, text.textSize)
            cur += text.textSize * NL_ADVANCE
            start = i + 1
        }
        if (start < t.length) c.drawText(t, start, t.length, cur, baseline, text)
    }

    /** The pressed-key bubble: the key's label, magnified, just above the finger. */
    private fun drawMagnifier(c: Canvas, l: KeyboardLayout, k: Key, s: State) {
        val size = l.size
        val w = k.box.w * 1.5f + size * 0.03f
        val h = k.box.h * 1.25f
        val want = Box(k.box.cx - w / 2f, k.box.t - h - size * 0.012f, k.box.cx + w / 2f, k.box.t - size * 0.012f)
        val b = l.fitInCircle(want, size * 0.012f)
        rect.set(b.l, b.t, b.r, b.b)
        fill.color = SURFACE_POPUP
        c.drawRoundRect(rect, min(b.w, b.h) * 0.3f, min(b.w, b.h) * 0.3f, fill)
        stroke.color = s.accent
        stroke.strokeWidth = size * 0.005f
        c.drawRoundRect(rect, min(b.w, b.h) * 0.3f, min(b.w, b.h) * 0.3f, stroke)
        text.color = Color.WHITE
        text.textSize = size * 0.1f
        c.drawText(displayLabel(k, s), b.cx, b.cy + text.textSize * 0.36f, text)
    }

    private fun drawAccents(c: Canvas, size: Float, s: State) {
        if (accentBoxes.isEmpty()) return
        // Dim everything else so only the choices read (the strip text would otherwise peek out beside them).
        fill.color = Color.argb(0x99, 0, 0, 0)
        c.drawRect(0f, 0f, size, size, fill)
        val all = Box(accentBoxes.first().l, accentBoxes.first().t, accentBoxes.last().r, accentBoxes.first().b)
        rect.set(all.l - size * 0.008f, all.t - size * 0.008f, all.r + size * 0.008f, all.b + size * 0.008f)
        fill.color = SURFACE_POPUP
        c.drawRoundRect(rect, all.h * 0.3f, all.h * 0.3f, fill)
        text.textSize = size * 0.07f
        accentBoxes.forEachIndexed { i, b ->
            if (i == accentSel) {
                rect.set(b.l, b.t, b.r, b.b)
                fill.color = s.accent
                c.drawRoundRect(rect, b.h * 0.28f, b.h * 0.28f, fill)
            }
            text.color = if (i == accentSel) ON_ACCENT else Color.WHITE
            c.drawText(accents[i], b.cx, b.cy + text.textSize * 0.36f, text)
        }
    }

    companion object {
        const val LONG_PRESS_MS = 380L
        const val REPEAT_MS = 70L
        /** A horizontal drag past this fraction of the diameter turns into the hide swipe... */
        const val SWIPE_START = 0.16f
        /** ...and hides the keyboard if released past this one. */
        const val SWIPE_HIDE = 0.3f
        /** Width of the keyboard_return glyph that stands in for a newline in the editor strip, in text sizes. */
        const val NL_ADVANCE = 1.02f

        // Stock Pixel Watch dark surfaces (same values as Circa Settings' Theme.kt).
        val SURFACE = Color.rgb(0x2F, 0x30, 0x36)
        val SURFACE_FN = Color.rgb(0x24, 0x25, 0x2A)
        val SURFACE_HIGH = Color.rgb(0x3A, 0x3B, 0x42)
        val SURFACE_PRESSED = Color.rgb(0x55, 0x57, 0x60)
        val SURFACE_POPUP = Color.rgb(0x46, 0x47, 0x4F)
        val ON_SURFACE = Color.rgb(0xE3, 0xE3, 0xE8)
        val ON_SURFACE_VARIANT = Color.rgb(0xC7, 0xC6, 0xCD)
        val OUTLINE_VARIANT = Color.rgb(0x46, 0x46, 0x4C)
        val ON_ACCENT = Color.rgb(0x1B, 0x1B, 0x21)

        /** Strip cell -> suggestion index: with three, the best one sits in the middle. */
        fun stripOrder(n: Int): IntArray = if (n == 3) intArrayOf(1, 0, 2) else IntArray(n) { it }

        fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
            (Color.red(a) * t + Color.red(b) * (1 - t)).toInt(),
            (Color.green(a) * t + Color.green(b) * (1 - t)).toInt(),
            (Color.blue(a) * t + Color.blue(b) * (1 - t)).toInt(),
        )
    }
}
