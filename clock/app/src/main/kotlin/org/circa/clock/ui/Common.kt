package org.circa.clock.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.ExperimentalWearFoundationApi
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnDefaults
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import kotlinx.coroutines.delay

internal val ROW_MIN_HEIGHT = 52.dp

/** A frame clock: `now` (SystemClock.elapsedRealtime) refreshed every [periodMs] while [active]. */
@Composable
internal fun rememberNow(active: Boolean, periodMs: Long = 50): Long {
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(active) {
        now = android.os.SystemClock.elapsedRealtime()
        while (active) { delay(periodMs); now = android.os.SystemClock.elapsedRealtime() }
    }
    return now
}

/** Crown-scrolled curved pill list with the clock on top (the Circa Settings list pattern). */
@OptIn(ExperimentalWearFoundationApi::class)
@Composable
internal fun CurvedList(
    modifier: Modifier = Modifier,
    scrollState: TransformingLazyColumnState = rememberTransformingLazyColumnState(),
    showTime: Boolean = true,
    top: Dp = 0.dp,
    content: TransformingLazyColumnScope.(TransformationSpec) -> Unit,
) {
    val focus = remember { FocusRequester() }
    val rotary = RotaryScrollableDefaults.snapBehavior(scrollState, hapticFeedbackEnabled = true)
    val spec = rememberTransformationSpec()
    ScreenScaffold(
        scrollState = scrollState,
        timeText = { if (showTime) TimeText() },
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
                    top = (padding.calculateTopPadding() - 14.dp).coerceAtLeast(0.dp) + top,
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
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(size).testTag(tag),
        colors = if (primary) IconButtonDefaults.filledIconButtonColors() else IconButtonDefaults.filledTonalIconButtonColors(),
    ) { Icon(icon, contentDescription = description, modifier = Modifier.size(26.dp)) }
}

/** Progress ring around the panel edge: a dim track and an accent arc of [fraction] of a turn, starting at 12 o'clock. */
@Composable
internal fun RingArc(fraction: Float, modifier: Modifier = Modifier.fillMaxSize(), stroke: Dp = 6.dp, color: Color = MaterialTheme.colorScheme.primary) {
    val track = MaterialTheme.colorScheme.surfaceContainer
    Canvas(modifier) {
        val w = stroke.toPx()
        val inset = 5.dp.toPx() + w / 2
        val s = Size(size.width - 2 * inset, size.height - 2 * inset)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), s, style = Stroke(w))
        if (fraction > 0f) drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, Offset(inset, inset), s,
            style = Stroke(w, cap = StrokeCap.Round))
    }
}
