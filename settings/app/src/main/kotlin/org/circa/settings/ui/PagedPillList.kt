package org.circa.settings.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.ExperimentalWearFoundationApi
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnDefaults
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.rotary.RotaryScrollableBehavior
import androidx.wear.compose.foundation.rotary.rotaryScrollable

private val LIST_SIDE_PADDING = 9.dp

/**
 * The curved, rotary-scrollable pill list (copied from the launcher's app-list screens). Compose
 * delivers crown events to the *focused* rotary node, and Wear's `requestFocusOnHierarchyActive()`
 * only requests focus inside a hierarchical focus group: the modifier order below is the Wear rotary
 * sample's, with the list's own rotary handling switched off so exactly one node owns the crown.
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
