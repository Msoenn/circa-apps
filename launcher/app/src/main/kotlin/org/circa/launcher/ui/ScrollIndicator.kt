package org.circa.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.material3.MaterialTheme

/** Angular span of the track, centred on 3 o'clock, and the shortest thumb. */
private const val TRACK_DEGREES = 44f
private const val MIN_THUMB_DEGREES = 9f

/**
 * Stock's curved scroll indicator: a short arc on the right rim with a bright thumb that shows
 * where the viewport sits in the content. Unlike Wear's own `ScrollIndicator` (which fades out
 * when scrolling stops) this stays visible whenever there is something to scroll, so a still
 * screenshot - and a glance at the watch - shows that the panel continues. The content length
 * is estimated from the visible items' average height, which is exact enough for a thumb.
 */
@Composable
fun CurvedScrollIndicator(state: TransformingLazyColumnState, modifier: Modifier = Modifier) {
    val info = state.layoutInfo
    val visible = info.visibleItems
    val total = info.totalItemsCount
    if (visible.isEmpty() || total == 0) return
    val viewport = info.viewportSize.height.toFloat()
    val average = visible.map { it.measuredHeight }.average().toFloat()
    val first = visible.first()
    val content = visible.sumOf { it.measuredHeight } + average * (total - visible.size)
    if (content <= viewport) return
    val scrolled = (first.index * average - first.offset).coerceIn(0f, content - viewport)
    val thumbFraction = (viewport / content).coerceIn(0f, 1f)
    val position = scrolled / (content - viewport)

    val track = MaterialTheme.colorScheme.outlineVariant
    val thumb = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.fillMaxSize()) {
        val stroke = 4.dp.toPx()
        val radius = size.minDimension / 2f - stroke / 2f - 3.dp.toPx()
        val topLeft = Offset(size.width / 2f - radius, size.height / 2f - radius)
        val arcSize = Size(radius * 2, radius * 2)
        val start = -TRACK_DEGREES / 2f
        drawArc(track, start, TRACK_DEGREES, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        val thumbDegrees = (TRACK_DEGREES * thumbFraction).coerceIn(MIN_THUMB_DEGREES, TRACK_DEGREES)
        val thumbStart = start + (TRACK_DEGREES - thumbDegrees) * position
        drawArc(thumb, thumbStart, thumbDegrees, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}
