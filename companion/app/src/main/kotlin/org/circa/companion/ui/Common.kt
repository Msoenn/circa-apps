package org.circa.companion.ui

import org.circa.symbols.CircaSymbols
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.ExperimentalWearFoundationApi
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnDefaults
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import kotlinx.coroutines.delay
import kotlin.math.abs

/** Wall-clock `now` (ms), refreshed every [periodMs] while [active]. */
@Composable
internal fun rememberWallNow(active: Boolean = true, periodMs: Long = 1000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active, periodMs) {
        now = System.currentTimeMillis()
        while (active) { delay(periodMs); now = System.currentTimeMillis() }
    }
    return now
}

/** Crown-scrolled curved list with the clock on top (the Circa Settings list pattern). */
@OptIn(ExperimentalWearFoundationApi::class)
@Composable
internal fun CurvedList(
    modifier: Modifier = Modifier,
    scrollState: TransformingLazyColumnState = rememberTransformingLazyColumnState(),
    content: TransformingLazyColumnScope.(TransformationSpec) -> Unit,
) {
    val focus = remember { FocusRequester() }
    val rotary = RotaryScrollableDefaults.snapBehavior(scrollState, hapticFeedbackEnabled = true)
    val spec = rememberTransformationSpec()
    ScreenScaffold(
        scrollState = scrollState,
        timeText = { TimeText() },
        modifier = modifier.fillMaxSize(),
    ) { padding ->
        Box(
            Modifier.fillMaxSize().requestFocusOnHierarchyActive()
                .rotaryScrollable(behavior = rotary, focusRequester = focus),
        ) {
            TransformingLazyColumn(
                state = scrollState,
                contentPadding = PaddingValues(
                    start = 9.dp, end = 9.dp,
                    top = (padding.calculateTopPadding() - 14.dp).coerceAtLeast(0.dp),
                    bottom = padding.calculateBottomPadding(),
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
                rotaryScrollableBehavior = null,
                flingBehavior = TransformingLazyColumnDefaults.snapFlingBehavior(scrollState),
                modifier = Modifier.fillMaxSize(),
            ) { content(spec) }
        }
    }
}

/** Round icon button of at least 48 dp (touch target), filled with the accent or tonal. */
@Composable
internal fun RoundButton(
    icon: ImageVector,
    description: String,
    tag: String,
    primary: Boolean = false,
    size: Dp = 52.dp,
    iconSize: Dp = 26.dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(size).testTag(tag),
        colors = if (primary) IconButtonDefaults.filledIconButtonColors() else IconButtonDefaults.filledTonalIconButtonColors(),
    ) { Icon(icon, contentDescription = description, modifier = Modifier.size(iconSize)) }
}

/** Arc around the panel edge: a dim track and an accent arc of [fraction] of a turn from 12 o'clock. */
@Composable
internal fun RingArc(fraction: Float, modifier: Modifier = Modifier.fillMaxSize(), stroke: Dp = 4.dp, color: Color = MaterialTheme.colorScheme.primary) {
    val track = MaterialTheme.colorScheme.surfaceContainer
    Canvas(modifier) {
        val w = stroke.toPx()
        val inset = 3.dp.toPx() + w / 2
        val s = Size(size.width - 2 * inset, size.height - 2 * inset)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), s, style = Stroke(w))
        if (fraction > 0f) drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, Offset(inset, inset), s,
            style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** A swipe from left to right (a quarter of the width, mostly horizontal) calls [onBack]. */
internal fun Modifier.swipeRightBack(onBack: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var dx = 0f; var dy = 0f
        val threshold = size.width * 0.25f
        var fired = false
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Final)
            val c = e.changes.firstOrNull { it.id == down.id } ?: break
            val d = c.positionChangeIgnoreConsumed()
            dx += d.x; dy += d.y
            if (!fired && dx > threshold && dx > 2 * abs(dy)) { fired = true; onBack() }
            if (!c.pressed) break
        }
    }
}

/** Centered empty/error state: optional icon, a title and a hint. */
@Composable
internal fun Message(title: String, hint: String, icon: ImageVector? = CircaSymbols.Filled.Call) {
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        androidx.compose.foundation.layout.Arrangement.Center, Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
        }
        androidx.wear.compose.material3.Text(title, style = MaterialTheme.typography.titleSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        androidx.compose.foundation.layout.Spacer(Modifier.height(2.dp))
        androidx.wear.compose.material3.Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
