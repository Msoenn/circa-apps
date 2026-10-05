package org.circa.launcher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.circa.launcher.CarouselPage
import org.circa.launcher.LauncherController
import org.circa.launcher.TrayEnd

/** testTag of the home carousel; exposed to uiautomator as `resource-id` (see [LauncherRoot]). */
const val HOME_CAROUSEL_TAG = "home_carousel"

/** Drag distance that commits to the neighbouring page. */
private val SWIPE_THRESHOLD = 40.dp

/** Snap animation duration. */
private const val SNAP_MILLIS = 180

/**
 * Stock's home carousel: watch face as page 0, with the tiles as the neighbouring pages, entered by
 * swiping left (next) or right (previous) and looping in both directions (reference tour #4).
 *
 * The three pages are laid out as a strip of `slot = -1..1` around the current page; the strip is
 * offset by the drag, so the adjacent page peeks in from the side while dragging. On release past
 * [SWIPE_THRESHOLD] the strip slides a whole page over, the page index advances, and the strip snaps
 * back to zero - the incoming page is then the centre slot, so the swap is invisible.
 *
 * Only the centre slot composes real content at rest ([dragging] gates the neighbours): an
 * off-screen page must not put its text nodes into the accessibility tree (the smoke test's
 * round-panel check and screen detection read `uiautomator dump`).
 *
 * Swipe down opens the tray at its quick-settings end and swipe up at its notification end
 * (v1.2b, [TrayEnd]); the vertical draggable sits beside the horizontal one, so the carousel's own
 * swipe is unchanged.
 *
 * No rotary here: in stock the crown enters the tray from the face, on the carousel nothing a crown
 * turn should move.
 */
@Composable
fun HomeCarousel(controller: LauncherController) {
    val page = controller.page.value
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) } // px; 0 = current page centred
    var dragging by remember { mutableStateOf(false) }
    var verticalDrag by remember { mutableStateOf(0f) }
    val thresholdPx = with(density) { SWIPE_THRESHOLD.roundToPx() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag(HOME_CAROUSEL_TAG),
    ) {
        val pageWidth = maxWidth
        val pageHeight = maxHeight
        val widthPx = with(density) { pageWidth.toPx() }

        fun settle() {
            scope.launch {
                val current = offset.value
                when {
                    current <= -thresholdPx -> {
                        offset.animateTo(-widthPx, tween(SNAP_MILLIS))
                        controller.page.value = (controller.page.value + 1) % CarouselPage.COUNT
                        offset.snapTo(0f)
                    }
                    current >= thresholdPx -> {
                        offset.animateTo(widthPx, tween(SNAP_MILLIS))
                        controller.page.value =
                            Math.floorMod(controller.page.value - 1, CarouselPage.COUNT)
                        offset.snapTo(0f)
                    }
                    else -> offset.animateTo(0f, tween(SNAP_MILLIS))
                }
                dragging = false
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch { offset.snapTo(offset.value + delta) }
                    },
                    onDragStarted = { dragging = true },
                    onDragStopped = { settle() },
                )
                // Swipe down opens the tray's quick-settings end. Swipe up opens the notification
                // stream only on builds without the Circa shade (openTray; on Circa notifications
                // open from the side button only). Kept off the horizontal draggable above, so
                // the carousel's own swipe is untouched.
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta -> verticalDrag += delta },
                    onDragStarted = { verticalDrag = 0f },
                    onDragStopped = {
                        when {
                            verticalDrag <= -thresholdPx ->
                                controller.openTray(TrayEnd.NOTIFICATIONS)
                            verticalDrag >= thresholdPx ->
                                controller.openTray(TrayEnd.QUICK_SETTINGS)
                        }
                    },
                ),
        ) {
            for (slot in -1..1) {
                val index = Math.floorMod(page + slot, CarouselPage.COUNT)
                Box(
                    modifier = Modifier
                        .offset { IntOffset((slot * widthPx + offset.value).roundToInt(), 0) }
                        .size(pageWidth, pageHeight),
                ) {
                    if (slot == 0 || dragging) {
                        when (index) {
                            CarouselPage.FACE -> WatchFaceScreen(controller)
                            CarouselPage.HEALTH_TILE -> HealthTile(controller)
                            CarouselPage.MEDIA_TILE -> MediaTile(controller)
                            CarouselPage.AGENDA_TILE -> AgendaTile(controller)
                            CarouselPage.SHORTCUTS_TILE -> ShortcutsTile(controller)
                        }
                    }
                }
            }
        }
    }
}
