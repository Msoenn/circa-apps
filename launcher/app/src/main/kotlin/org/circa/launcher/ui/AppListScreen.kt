package org.circa.launcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.ExperimentalWearFoundationApi
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnDefaults
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.rememberSwipeToDismissBoxState
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.rotary.RotaryScrollableBehavior
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwipeToDismissBox
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import org.circa.launcher.LauncherController
import org.circa.launcher.data.AppLoader
import org.circa.launcher.model.AppEntry

/** testTag of the recents screen root; exposed to uiautomator as `resource-id` (see [LauncherRoot]). */
const val APP_LIST_TAG = "app_list"

/** testTag of the all-apps screen root. */
const val ALL_APPS_TAG = "all_apps"

/** testTag of the accent "All apps" pill on the recents screen. */
const val ALL_APPS_PILL_TAG = "all_apps_pill"

private val PILL_MIN_HEIGHT = 52.dp
private val PILL_ICON = 36.dp

/** Horizontal margin of the list: the pills' ends then stay clear of the bezel at mid-height. */
private val LIST_SIDE_PADDING = 9.dp

/**
 * App list "recents" page (stock `RecentsActivity`): a "Recents" header with up to three recent-app
 * pills and, below them, the "All apps" pill. With no usage-stats appop the recents list is empty
 * and the section (header + pills) is simply absent - only "All apps" remains.
 *
 * Rotary scrolls the list (a deliberate extension of stock, which needs a touch scroll), the curved
 * [TimeText] sits at the top, and a right-swipe (or BACK) dismisses back to the face.
 */
@OptIn(ExperimentalWearFoundationApi::class)
@Composable
fun RecentsScreen(controller: LauncherController) {
    val context = LocalContext.current
    val recent = controller.recentApps.value
    val scrollState = rememberTransformingLazyColumnState()
    val focusRequester = remember { FocusRequester() }
    val rotaryBehavior =
        RotaryScrollableDefaults.snapBehavior(scrollState, hapticFeedbackEnabled = true)
    val spec = rememberTransformationSpec()

    SwipeToDismissBox(
        onDismissed = { controller.showFace() },
        state = rememberSwipeToDismissBoxState(),
        modifier = Modifier.fillMaxSize(),
    ) { _ ->
        // AppScaffold hosts the clock the ScreenScaffold below declares, so it can scroll away.
        AppScaffold {
        ScreenScaffold(
            scrollState = scrollState,
            // The scaffold's own time slot: the clock scrolls away with the list instead of
            // sitting on top of the pills that pass under it.
            timeText = { TimeText() },
            modifier = Modifier
                .fillMaxSize()
                .testTag(APP_LIST_TAG),
        ) { contentPadding ->
            PagedPillList(
                scrollState = scrollState,
                contentPadding = contentPadding,
                focusRequester = focusRequester,
                rotaryBehavior = rotaryBehavior,
            ) {
                if (recent.isNotEmpty()) {
                    item { Header("Recents", spec) }
                    items(
                        count = recent.size,
                        key = { recent[it].packageName + "|" + recent[it].className },
                    ) { index ->
                        val entry = recent[index]
                        AppPill(
                            entry = entry,
                            spec = spec,
                            onClick = { AppLoader.launchApp(context, entry) },
                        )
                    }
                }
                item {
                    // The accent-filled "All apps" pill (stock's is a small dark one).
                    Button(
                        onClick = { controller.openAllApps() },
                        modifier = Modifier
                            .fillMaxWidth(0.72f)
                            .heightIn(min = 48.dp)
                            .transformedHeight(this, spec)
                            .testTag(ALL_APPS_PILL_TAG),
                        transformation = SurfaceTransformation(spec),
                        label = {
                            Text(
                                "All apps",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                            )
                        },
                    )
                }
            }
        }
        }
    }
}

/**
 * App list "All apps" page (stock `AllAppsLauncherActivity`): the full list of pills, sorted by
 * label. Reached by tapping the "All apps" pill; right-swipe (or BACK) returns to recents.
 */
@OptIn(ExperimentalWearFoundationApi::class)
@Composable
fun AllAppsScreen(controller: LauncherController) {
    val context = LocalContext.current
    val apps = controller.apps.value
    val scrollState = rememberTransformingLazyColumnState()
    val focusRequester = remember { FocusRequester() }
    val rotaryBehavior =
        RotaryScrollableDefaults.snapBehavior(scrollState, hapticFeedbackEnabled = true)
    val spec = rememberTransformationSpec()

    SwipeToDismissBox(
        onDismissed = { controller.openRecents() },
        state = rememberSwipeToDismissBoxState(),
        modifier = Modifier.fillMaxSize(),
    ) { _ ->
        // AppScaffold hosts the clock the ScreenScaffold below declares, so it can scroll away.
        AppScaffold {
        ScreenScaffold(
            scrollState = scrollState,
            timeText = { TimeText() },
            modifier = Modifier
                .fillMaxSize()
                .testTag(ALL_APPS_TAG),
        ) { contentPadding ->
            PagedPillList(
                scrollState = scrollState,
                contentPadding = contentPadding,
                focusRequester = focusRequester,
                rotaryBehavior = rotaryBehavior,
            ) {
                // A header, so the first row does not crowd the curved time above it.
                item { Header(if (apps.isEmpty()) "No apps" else "All apps", spec) }
                items(
                    count = apps.size,
                    key = { apps[it].packageName + "|" + apps[it].className },
                ) { index ->
                    val entry = apps[index]
                    AppPill(
                        entry = entry,
                        spec = spec,
                        onClick = { AppLoader.launchApp(context, entry) },
                    )
                }
            }
        }
        }
    }
}

/**
 * The curved, rotary-scrollable pill list both app-list screens share. Compose delivers crown
 * events to the *focused* rotary node (`FocusOwner.dispatchRotaryEvent`), and Wear's
 * `requestFocusOnHierarchyActive()` only requests focus inside a hierarchical focus group: the
 * modifier order below is the Wear rotary sample's, with the list's own rotary handling switched off
 * so exactly one node owns the crown.
 */
@OptIn(ExperimentalWearFoundationApi::class)
@Composable
internal fun PagedPillList(
    scrollState: TransformingLazyColumnState,
    contentPadding: PaddingValues,
    focusRequester: FocusRequester,
    rotaryBehavior: RotaryScrollableBehavior,
    content: TransformingLazyColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .requestFocusOnHierarchyActive()
            .rotaryScrollable(
                behavior = rotaryBehavior,
                focusRequester = focusRequester,
            ),
    ) {
        TransformingLazyColumn(
            state = scrollState,
            contentPadding = PaddingValues(
                start = LIST_SIDE_PADDING,
                end = LIST_SIDE_PADDING,
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding(),
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            // The crown is handled by the rotaryScrollable on the container above.
            rotaryScrollableBehavior = null,
            flingBehavior = TransformingLazyColumnDefaults.snapFlingBehavior(scrollState),
            modifier = Modifier.fillMaxSize(),
            content = content,
        )
    }
}

/** A list header that shrinks and fades with the circle like the pills. */
@Composable
private fun TransformingLazyColumnItemScope.Header(text: String, spec: TransformationSpec) {
    ListHeader(
        modifier = Modifier.transformedHeight(this, spec),
        transformation = SurfaceTransformation(spec),
    ) { Text(text) }
}

/**
 * One app row: a tonal pill with the app icon filling its circle at the left and the label. The
 * pill's width follows the circle through the list's transformation (it narrows toward the top and
 * bottom bezel), so no end is ever cut off.
 */
@Composable
private fun TransformingLazyColumnItemScope.AppPill(
    entry: AppEntry,
    spec: TransformationSpec,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    FilledTonalButton(
        onClick = onClick,
        onLongClick = { AppLoader.openAppInfo(context, entry) },
        onLongClickLabel = "App info",
        icon = { AppIcon(entry = entry, size = PILL_ICON) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PILL_MIN_HEIGHT)
            .transformedHeight(this, spec),
        transformation = SurfaceTransformation(spec),
        label = { Text(entry.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}
