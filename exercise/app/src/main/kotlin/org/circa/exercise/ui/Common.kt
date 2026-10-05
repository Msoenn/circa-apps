package org.circa.exercise.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.ExperimentalWearFoundationApi
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnDefaults
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import kotlin.math.abs

internal val ROW_MIN_HEIGHT = 52.dp

/** Crown-scrolled curved pill list with the clock on top (the Circa Settings / Clock list pattern). */
@OptIn(ExperimentalWearFoundationApi::class)
@Composable
internal fun CurvedList(
    modifier: Modifier = Modifier,
    scrollState: TransformingLazyColumnState = rememberTransformingLazyColumnState(),
    showTime: Boolean = true,
    top: Dp = 0.dp,
    edgeButton: (@Composable BoxScope.() -> Unit)? = null,
    /** Snap items to the centre (lists of rows). Off for a page that ends in an edge button: a snap would stop
     *  the list short of its end and leave the button shrunk (emulator 2026-10-04). */
    snap: Boolean = edgeButton == null,
    content: TransformingLazyColumnScope.(TransformationSpec) -> Unit,
) {
    val focus = remember { FocusRequester() }
    val rotary = if (snap) RotaryScrollableDefaults.snapBehavior(scrollState, hapticFeedbackEnabled = true)
    else RotaryScrollableDefaults.behavior(scrollState)
    val spec = rememberTransformationSpec()
    val body: @Composable BoxScope.(PaddingValues) -> Unit = { padding ->
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
                flingBehavior = if (snap) TransformingLazyColumnDefaults.snapFlingBehavior(scrollState) else ScrollableDefaults.flingBehavior(),
                modifier = Modifier.fillMaxSize(),
            ) { content(spec) }
        }
    }
    if (edgeButton != null) {
        // The stock end-of-list action: the edge button hugs the bottom curve and the list scrolls clear of it.
        ScreenScaffold(scrollState = scrollState, edgeButton = edgeButton, timeText = { if (showTime) TimeText() },
            modifier = modifier.fillMaxSize(), content = body)
    } else {
        ScreenScaffold(scrollState = scrollState, timeText = { if (showTime) TimeText() }, modifier = modifier.fillMaxSize(),
            content = body)
    }
}

/**
 * Height + scale/fade morph at the top and bottom edges for items that are not Material surfaces (text rows, the
 * summary hero). Without the graphics-layer part an item's height shrinks while its content keeps drawing at full
 * size, so it overlaps its neighbour and the circle cuts its corners (emulator 2026-10-04).
 */
internal fun TransformingLazyColumnItemScope.morph(spec: TransformationSpec, m: Modifier = Modifier): Modifier =
    m.transformedHeight(this, spec).graphicsLayer { with(spec) { applyContainerTransformation(scrollProgress) } }

/**
 * Horizontal swipes: a clear rightward (or leftward) drag of a quarter of the width fires once. Observed on the Final
 * pass so scrolling children keep working (the Clock app's swipe-right-back, both directions).
 */
internal fun Modifier.swipes(onRight: () -> Unit, onLeft: () -> Unit = {}): Modifier = pointerInput(onRight, onLeft) {
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
            if (!fired && abs(dx) > threshold && abs(dx) > 2 * abs(dy)) {
                fired = true
                if (dx > 0) onRight() else onLeft()
            }
            if (!c.pressed) break
        }
    }
}

/** The round control of the design (62 dp disc, icon over a small label). */
@Composable
internal fun DiscButton(
    icon: ImageVector,
    label: String,
    container: Color,
    content: Color,
    tag: String,
    size: Dp = 62.dp,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(size).clip(CircleShape).background(container)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(24.dp))
            Text(label, color = content, fontSize = 11.sp, maxLines = 1, softWrap = false)
        }
    }
}

/** Activity glyph on a coloured disc (the list rows). */
@Composable
internal fun IconDisc(icon: ImageVector, color: Color, size: Dp = 28.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = ON_ACCENT_DARK, modifier = Modifier.size(size * 0.66f))
    }
}
