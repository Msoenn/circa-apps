package org.circa.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.Density
import org.circa.launcher.LauncherController
import org.circa.launcher.Screen
import org.circa.launcher.model.Density as DensityUtil

/**
 * Root composable. Overrides the display density so the whole UI renders at a fixed 200dp logical
 * width regardless of physical resolution, then switches between the home carousel (face + tiles)
 * and the two app-list screens.
 *
 * `testTagsAsResourceId` publishes the screens' test tags as `resource-id` in the accessibility
 * tree, which is how `the launcher emulator smoke test` recognises the current screen (and how it can
 * check that every text node stays inside the circle).
 */
@Composable
fun LauncherRoot(controller: LauncherController) {
    val configuration = LocalConfiguration.current
    val systemDensity = LocalDensity.current

    val widthPx = remember(configuration.screenWidthDp, systemDensity.density) {
        (configuration.screenWidthDp * systemDensity.density).toInt()
    }
    val targetDensity = remember(widthPx) { DensityUtil.densityFor(widthPx) }
    val density = Density(targetDensity, systemDensity.fontScale)

    CompositionLocalProvider(LocalDensity provides density) {
        Theme(controller.accent.value) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .semantics { testTagsAsResourceId = true },
            ) {
                if (controller.locked.value) {
                    // Locked: only the face, nothing else is composed (no tray, lists, notifications).
                    LockedFaceScreen(controller)
                } else when (controller.screen.value) {
                    // The tray is an overlay over the face rather than a screen of its own; only one
                    // of the two is composed at rest, so a hidden page never adds nodes to the
                    // accessibility tree the smoke test reads.
                    Screen.HOME ->
                        if (controller.trayOpen.value) {
                            TrayScreen(controller)
                        } else {
                            HomeCarousel(controller)
                        }
                    Screen.RECENTS -> RecentsScreen(controller)
                    Screen.ALL_APPS -> AllAppsScreen(controller)
                    Screen.FACE_PICKER -> FacePickerScreen(controller)
                }
            }
        }
    }
}
