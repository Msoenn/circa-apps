package org.circa.clock.ui

import org.circa.symbols.CircaSymbols
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import org.circa.clock.model.PickerColumn
import org.circa.clock.model.PickerState
import kotlin.math.abs

/**
 * Crown-wheel time picker: hour and minute columns; tap one to select it, turn the crown (or drag the column)
 * to change it, tap the check button to confirm. Used for alarm times and for the custom timer length
 * (read there as hours : minutes).
 */
@Composable
internal fun TimePicker(initial: PickerState, title: String? = null, onDone: (PickerState) -> Unit) {
    var state by remember { mutableStateOf(initial) }
    var acc by remember { mutableFloatStateOf(0f) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        Modifier.fillMaxSize().testTag("picker")
            .onRotaryScrollEvent { e ->
                acc += e.verticalScrollPixels
                while (abs(acc) >= STEP_PX) {
                    val d = if (acc > 0) 1 else -1
                    state = state.step(d); acc -= d * STEP_PX
                }
                true
            }
            .focusRequester(focus).androidx_focusable(),
    ) {
        if (title == null) TimeText() else Text(title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp))
        Row(
            Modifier.align(Alignment.TopCenter).padding(top = if (title == null) 27.dp else 36.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            WheelColumn(state, PickerColumn.HOUR, "picker_hour", { state = state.select(PickerColumn.HOUR) }) { d -> state = state.step(d) }
            Text(":", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 2.dp))
            WheelColumn(state, PickerColumn.MINUTE, "picker_minute", { state = state.select(PickerColumn.MINUTE) }) { d -> state = state.step(d) }
            if (!state.is24h) {
                Column(Modifier.width(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    AmPm("AM", !state.isPm, "picker_am") { if (state.isPm) state = state.toggleAmPm() }
                    AmPm("PM", state.isPm, "picker_pm") { if (!state.isPm) state = state.toggleAmPm() }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 9.dp)) {
            RoundButton(CircaSymbols.Filled.Check, "Confirm", "picker_ok", primary = true, size = 48.dp) { onDone(state) }
        }
    }
}

private fun Modifier.androidx_focusable() = this.focusable()
private const val STEP_PX = 56f  // one injected/real crown detent is about 64 px of scroll

@Composable
private fun AmPm(text: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.height(48.dp).width(48.dp).clickable(onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
    }
}

@Composable
private fun WheelColumn(state: PickerState, column: PickerColumn, tag: String, onSelect: () -> Unit, onStep: (Int) -> Unit) {
    val selected = state.column == column
    val (prev, next) = state.neighbours(column)
    val value = if (column == PickerColumn.HOUR) state.hourText else state.minuteText
    var drag by remember { mutableFloatStateOf(0f) }
    Column(
        Modifier.width(56.dp).testTag(tag)
            .clickable(onClick = onSelect)
            .pointerInput(column) {
                detectVerticalDragGestures(onDragStart = { onSelect() }) { _, dy ->
                    drag += dy
                    while (abs(drag) >= 40f) { if (drag < 0) { onStep(1); drag += 40f } else { onStep(-1); drag -= 40f } }
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(prev, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f), modifier = Modifier.height(24.dp))
        Box(
            Modifier.width(56.dp).height(48.dp).clip(RoundedCornerShape(24.dp))
                .background(if (selected) MaterialTheme.colorScheme.surfaceContainer else androidx.compose.ui.graphics.Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(value, fontSize = 36.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
        Text(next, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f), modifier = Modifier.height(24.dp))
    }
}
