package org.circa.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.ExperimentalWearFoundationApi
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.rotary.RotaryScrollableBehavior
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.curvedText
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.circa.launcher.LauncherController
import org.circa.launcher.model.Accent
import org.circa.launcher.model.FaceStyle

const val FACE_PICKER_TAG = "face_picker"
const val PICKER_NAME_TAG = "picker_name"
const val COLOUR_BUTTON_TAG = "picker_colour"
fun pickerFaceTag(style: FaceStyle) = "picker_face_${style.id}"
fun accentTag(accent: Accent) = "accent_${accent.id}"

private val PREVIEW = 116.dp
private val PAGE_GAP = 12.dp

/**
 * Stock's face picker (reached by long-pressing the face): the faces side by side as round
 * previews - swipe or turn the crown to move between them, tap one to choose it - with the face's
 * name curved over the top, and below it a "Colour" button that opens the accent choices (the previews
 * recolour once one is picked). BACK returns to the face without changing anything.
 */
@OptIn(ExperimentalWearFoundationApi::class)
@Composable
fun FacePickerScreen(controller: LauncherController) {
    val styles = FaceStyle.entries
    val pagerState = rememberPagerState(
        initialPage = styles.indexOf(controller.face.value),
        pageCount = { styles.size },
    )
    // Previews show the phone complications but are not interactive: a tap there chooses the face.
    val data = rememberFaceData(controller).copy(onWeatherTap = null, onEventTap = null, onLongPress = null)
    val focusRequester = remember { FocusRequester() }
    val rotary = remember(pagerState) { PagerRotaryBehavior(pagerState) }
    val current = styles[pagerState.currentPage]

    var choosingColour by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag(FACE_PICKER_TAG)
            .requestFocusOnHierarchyActive()
            .rotaryScrollable(behavior = rotary, focusRequester = focusRequester),
    ) {
        if (choosingColour) {
            AccentGrid(
                selected = controller.accent.value,
                onSelect = {
                    controller.chooseAccent(it)
                    choosingColour = false
                },
            )
            return@Box
        }
        HorizontalPager(
            state = pagerState,
            pageSize = PageSize.Fixed(PREVIEW),
            pageSpacing = PAGE_GAP,
            contentPadding = PaddingValues(horizontal = (200.dp - PREVIEW) / 2),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 30.dp).size(width = 200.dp, height = PREVIEW),
        ) { page ->
            val style = styles[page]
            FacePreview(
                style = style,
                data = data,
                selected = style == controller.face.value,
                onClick = { controller.chooseFace(style) },
            )
        }

        // The face's name over the top of the circle, as stock draws it.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(200.dp)
                .testTag(PICKER_NAME_TAG)
                .semantics { contentDescription = current.label },
        ) {
            val nameColor = MaterialTheme.colorScheme.onSurface
            CurvedLayout(modifier = Modifier.fillMaxSize()) {
                curvedText(
                    text = current.label,
                    color = nameColor,
                    fontSize = 16.sp,
                )
            }
        }

        ColourButton(
            accent = controller.accent.value,
            onClick = { choosingColour = true },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 146.dp),
        )
    }
}

/** One round face preview: the real face drawn at full size and scaled down into the circle. */
@Composable
private fun FacePreview(style: FaceStyle, data: FaceData, selected: Boolean, onClick: () -> Unit) {
    val scale = PREVIEW / 200.dp
    Box(
        modifier = Modifier
            .size(PREVIEW)
            .clip(CircleShape)
            .background(Color.Black)
            .border(
                width = if (selected) 2.5.dp else 1.5.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            )
            .clickable(role = Role.Button, onClickLabel = "Use ${style.label}", onClick = onClick)
            .clearAndSetSemantics { contentDescription = style.label }
            .testTag(pickerFaceTag(style)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .requiredSize(200.dp)
                .graphicsLayer(scaleX = scale, scaleY = scale),
        ) {
            WatchFace(style, data)
        }
    }
}

/**
 * The "Colour" button under the previews: 48 dp tall, showing the current accent dot. Five accent dots
 * cannot all be 48 dp targets on a 200 dp circle, so the colours live on their own screen
 * ([AccentGrid]) that this opens. 96 dp wide keeps the flat bottom edge inside the circle.
 */
@Composable
private fun ColourButton(accent: Accent, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .size(width = 96.dp, height = 48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .testTag(COLOUR_BUTTON_TAG)
            .semantics { contentDescription = "Colour, ${accent.label}" }
            .clickable(role = Role.Button, onClickLabel = "Choose colour", onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp).clip(CircleShape).background(Color(accent.argb)))
        Spacer(Modifier.size(7.dp))
        Text("Colour", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, maxLines = 1)
    }
}

/**
 * The accent choices as 52 dp targets (three over two, centred on the round screen), the selected one
 * ringed; tapping one picks it and returns to the previews, which recolour. A curved "Colour" title
 * sits over the top.
 */
@Composable
private fun AccentGrid(selected: Accent, onSelect: (Accent) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.TopCenter).size(200.dp)) {
            val titleColor = MaterialTheme.colorScheme.onSurface
            CurvedLayout(modifier = Modifier.fillMaxSize()) {
                curvedText(text = "Colour", color = titleColor, fontSize = 16.sp)
            }
        }
        Column(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 62.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            for (row in Accent.entries.chunked(3)) {
                Row {
                    for (accent in row) AccentDot(accent, accent == selected, onSelect)
                }
            }
        }
    }
}

@Composable
private fun AccentDot(accent: Accent, isSelected: Boolean, onSelect: (Accent) -> Unit) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .testTag(accentTag(accent))
            .semantics {
                contentDescription = "${accent.label} accent"
                role = Role.RadioButton
                this.selected = isSelected
            }
            .clickable(role = Role.RadioButton, onClickLabel = "Use ${accent.label}") { onSelect(accent) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(if (isSelected) 42.dp else 34.dp)
                .then(
                    if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier,
                )
                .padding(if (isSelected) 4.dp else 0.dp)
                .clip(CircleShape)
                .background(Color(accent.argb)),
        )
    }
}

/**
 * The crown for the picker: each detent's worth of rotation moves the pager one face. Wear's
 * built-in snap behaviours are for lists and its own pager; this is the small accumulator that
 * does the same job for a plain Compose pager.
 */
@OptIn(ExperimentalWearFoundationApi::class)
private class PagerRotaryBehavior(private val state: PagerState) : RotaryScrollableBehavior {
    private var accumulated = 0f

    override suspend fun CoroutineScope.performScroll(
        timestampMillis: Long,
        delta: Float,
        inputDeviceId: Int,
        orientation: Orientation,
    ) {
        accumulated += delta
        if (abs(accumulated) < STEP_PX) return
        val direction = if (accumulated > 0) 1 else -1
        accumulated = 0f
        val target = (state.currentPage + direction).coerceIn(0, state.pageCount - 1)
        if (target != state.currentPage && !state.isScrollInProgress) {
            launch { state.animateScrollToPage(target) }
        }
    }

    private companion object {
        const val STEP_PX = 20f
    }
}
