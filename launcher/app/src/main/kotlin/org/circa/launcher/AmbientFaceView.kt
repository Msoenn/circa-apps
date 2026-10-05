package org.circa.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import java.time.Instant
import java.time.ZoneId
import org.circa.launcher.model.AmbientFace
import org.circa.launcher.model.ComplicationFormat

/**
 * The always-on face, drawn on a plain [Canvas] - no Compose, no layouts: a doze dream runs with the
 * application processor suspended between minutes, so the AOD face is one static frame plus one
 * redraw a minute.
 *
 * v1.4 is the "time only" option: the time, small and near-white, vertically centred - no date, no
 * battery, no complications, no outlines, no AM/PM marker. Ambient rules it follows
 *:
 *  - black background, one small near-white text, no animation, no seconds;
 *  - the whole drawing is shifted a few px once a minute ([AmbientFace.burnInOffset]) to keep the
 *    same pixels from being lit for hours, and stays well inside the inscribed circle so the shift
 *    cannot clip anything.
 *
 * The size is a fraction of the panel, so the same code draws identically on the 384px emulator
 * panel and on the watch. A filled "7:42" at this size lights about 1.5 % of the panel, far under
 * stock's 15 % ceiling.
 *
 * The colour is near-white (#E8EAED), as stock Wear's ambient digits: the panel runs at its doze
 * brightness then (Settings › Display › Always-on brightness; Low is the framework's 0), and the
 * earlier mid grey (#9AA0A6, light weight) was hard to read on the watch at that level.
 */
class AmbientFaceView(context: Context) : View(context) {

    private var nowMillis: Long = System.currentTimeMillis()
    private var is24Hour: Boolean = false

    /** Near-white, regular weight: thin light strokes vanish at doze brightness. */
    private val timePaint = Paint().apply {
        color = TIME_COLOR
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        isAntiAlias = true
    }

    init {
        setBackgroundColor(Color.BLACK)
    }

    /** New state for the frame being drawn: the wall clock and the 12/24h setting. */
    fun setFace(nowMillis: Long, is24Hour: Boolean) {
        this.nowMillis = nowMillis
        this.is24Hour = is24Hour
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(Color.BLACK)

        val unit = if (w < h) w else h
        val (dx, dy) = AmbientFace.burnInOffset(nowMillis)
        canvas.save()
        canvas.translate(dx.toFloat(), dy.toFloat())

        val local = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault())
        // Digits only, 12 or 24 h: stock's ambient face carries no AM/PM marker either.
        val text = ComplicationFormat.timeParts(local.toLocalTime(), is24Hour).digits
        timePaint.textSize = unit * TIME_TEXT_SIZE
        // Vertically centred on the glyphs' own height (the digits' ink), not on the line box.
        val metrics = timePaint.fontMetrics
        val baseline = h / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(text, w / 2f, baseline, timePaint)
        canvas.restore()
    }

    private companion object {
        /** Time size, as a fraction of the panel's short side. */
        const val TIME_TEXT_SIZE = 0.19f

        /** #E8EAED: stock Wear's near-white ambient digits. */
        val TIME_COLOR = Color.rgb(0xE8, 0xEA, 0xED)
    }
}
