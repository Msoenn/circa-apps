package org.circa.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.circa.launcher.CarouselPage
import org.circa.launcher.LauncherController
import org.circa.launcher.TrayEnd

/** testTag of the locked face; exposed to uiautomator as `resource-id` (see [LauncherRoot]). */
const val LOCKED_FACE_TAG = "locked_face"

/** Drag distance that counts as a swipe. */
private val LOCK_SWIPE_THRESHOLD = 40.dp

/**
 * What the launcher draws while the keyguard is locked: the watch face and nothing else (no
 * carousel pages, tray, notifications, app lists or face picker are composed). Every gesture asks
 * the system for the PIN ([LauncherController.requireUnlock]) and, after a successful unlock,
 * continues to where the gesture was headed: tap just unlocks, long-press opens the face picker,
 * swipe left/right moves the carousel, swipe down/up opens the tray ends. (The crown press is
 * handled by the activity: it unlocks into the app list.)
 */
@Composable
fun LockedFaceScreen(controller: LauncherController) {
    val data = rememberFaceData(controller)
    val thresholdPx = with(LocalDensity.current) { LOCK_SWIPE_THRESHOLD.toPx() }

    fun swiped(dx: Float, dy: Float) {
        val horizontal = kotlin.math.abs(dx) >= kotlin.math.abs(dy)
        when {
            horizontal && dx <= -thresholdPx -> controller.requireUnlock {
                controller.page.value = (controller.page.value + 1) % CarouselPage.COUNT
            }
            horizontal && dx >= thresholdPx -> controller.requireUnlock {
                controller.page.value =
                    Math.floorMod(controller.page.value - 1, CarouselPage.COUNT)
            }
            // The Circa shade opens over the keyguard itself (notification text redacted), as on stock Wear.
            // With the Circa shade a swipe up opens nothing (openTray; notifications = the side button).
            !horizontal && dy >= thresholdPx -> controller.openTray(TrayEnd.QUICK_SETTINGS)
            !horizontal && dy <= -thresholdPx -> controller.openTray(TrayEnd.NOTIFICATIONS)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag(LOCKED_FACE_TAG)
            .semantics {
                onClick(label = "Unlock") { controller.requireUnlock { }; true }
                onLongClick(label = "Unlock") { controller.requireUnlock { controller.openFacePicker() }; true }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { controller.requireUnlock { } },
                    onLongPress = { controller.requireUnlock { controller.openFacePicker() } },
                )
            }
            .pointerInput(Unit) {
                var dx = 0f
                var dy = 0f
                detectDragGestures(
                    onDragStart = { dx = 0f; dy = 0f },
                    onDrag = { change, amount ->
                        change.consume()
                        dx += amount.x
                        dy += amount.y
                    },
                    onDragEnd = { swiped(dx, dy) },
                )
            },
    ) {
        WatchFace(controller.face.value, data)
    }
}
