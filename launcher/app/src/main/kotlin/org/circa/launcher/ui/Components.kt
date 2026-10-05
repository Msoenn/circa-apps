package org.circa.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

/** Alpha of a "no data" icon / empty ring: visibly there, visibly inactive. */
const val NO_DATA_ALPHA = 0.45f

/**
 * A thin progress ring: the [track] circle with the [progress] arc (0..1, from 12 o'clock,
 * clockwise) on top. Null progress draws the empty track only - the "no data" state.
 */
@Composable
fun GaugeRing(
    progress: Float?,
    stroke: Dp,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    track: Color = MaterialTheme.colorScheme.outlineVariant,
) {
    Canvas(modifier) {
        val w = stroke.toPx()
        val inset = w / 2f
        val arcSize = Size(size.width - w, size.height - w)
        val topLeft = Offset(inset, inset)
        drawArc(
            color = track,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(w),
        )
        val sweep = 360f * (progress ?: 0f).coerceIn(0f, 1f)
        if (progress != null && sweep >= 1f) {
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(w, cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * Complication style A, "ring gauge": a thin ring that fills with the value, the icon inside and the
 * value below. Every instance has the same footprint whatever it shows, so a row of them shares
 * one baseline; with no data the ring is empty, the icon dimmed and the value slot blank (never
 * a "--").
 */
@Composable
fun RingComplication(
    icon: ImageVector,
    progress: Float?,
    value: String?,
    description: String,
    modifier: Modifier = Modifier,
    ringSize: Dp = 40.dp,
    stroke: Dp = 3.dp,
    iconSize: Dp = 18.dp,
    valueSize: TextUnit = 13.sp,
) {
    val hasData = value != null
    Column(
        modifier = modifier
            .width(ringSize + 6.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = if (hasData) "$description $value" else "$description, no data"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(ringSize), contentAlignment = Alignment.Center) {
            GaugeRing(
                progress = progress,
                stroke = stroke,
                modifier = Modifier
                    .size(ringSize)
                    .alpha(if (hasData) 1f else NO_DATA_ALPHA),
            )
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .size(iconSize)
                    .alpha(if (hasData) 1f else NO_DATA_ALPHA),
            )
        }
        // The value slot keeps its height when empty, so a no-data complication sits on the same
        // baseline as its neighbours.
        Text(
            text = value ?: " ",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = valueSize,
            style = tightStyle(valueSize.value),
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

