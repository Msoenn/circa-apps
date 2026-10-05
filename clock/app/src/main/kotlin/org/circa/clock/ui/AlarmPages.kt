package org.circa.clock.ui

import org.circa.symbols.CircaSymbols
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SplitSwitchButton
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.transformedHeight
import org.circa.clock.data.ClockModel
import org.circa.clock.data.Screen
import org.circa.clock.model.Alarm
import org.circa.clock.model.Days
import org.circa.clock.model.Fmt
import org.circa.clock.model.PickerState

/** Page 1: the alarm list - "New alarm" pill, then one split-switch pill per alarm. */
@Composable
internal fun AlarmListPage(m: ClockModel, is24h: Boolean) {
    CurvedList(modifier = Modifier.testTag("page_alarm")) { spec ->
        item(key = "new") {
            FilledTonalButton(
                onClick = { m.push(Screen.AlarmPicker(null, PickerState(7, 0, is24h = is24h))) },
                modifier = Modifier.fillMaxWidth().heightIn(min = ROW_MIN_HEIGHT).transformedHeight(this, spec).testTag("alarm_new"),
                transformation = SurfaceTransformation(spec),
                icon = { Icon(CircaSymbols.Filled.Add, null, Modifier.size(24.dp)) },
                label = { Text("New alarm", maxLines = 1) },
            )
        }
        if (m.alarms.isEmpty()) {
            item(key = "empty") {
                Text("No alarms", Modifier.padding(top = 8.dp).transformedHeight(this, spec), textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        for (a in m.alarms) {
            item(key = "alarm${a.id}") {
                SplitSwitchButton(
                    checked = a.enabled,
                    onCheckedChange = { m.setEnabled(a.id, it) },
                    toggleContentDescription = "Alarm ${Fmt.time(a.hour, a.minute, is24h)} on",
                    onContainerClick = { m.push(Screen.AlarmEdit(a.id)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).transformedHeight(this, spec).testTag("alarm_row_${a.id}"),
                    transformation = SurfaceTransformation(spec),
                    label = {
                        Text(Fmt.time(a.hour, a.minute, is24h) + if (is24h) "" else " " + Fmt.ampm(a.hour), maxLines = 1,
                            style = MaterialTheme.typography.titleLarge)
                    },
                    secondaryLabel = {
                        Text((if (a.label.isNotBlank()) a.label + ", " else "") + Days.summary(a.days), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                )
            }
        }
    }
}

/** One alarm: time, repeat days, label, snooze length, delete. */
@Composable
internal fun AlarmEditPage(m: ClockModel, id: Int, is24h: Boolean) {
    val a = m.alarm(id) ?: run { LaunchedEffect(Unit) { m.pop() }; return }
    CurvedList(modifier = Modifier.testTag("page_alarm_edit")) { spec ->
        item(key = "time") {
            FilledTonalButton(
                onClick = { m.push(Screen.AlarmPicker(id, PickerState(a.hour, a.minute, is24h = is24h))) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).transformedHeight(this, spec).testTag("edit_time"),
                transformation = SurfaceTransformation(spec),
                label = {
                    Text(Fmt.time(a.hour, a.minute, is24h) + if (is24h) "" else " " + Fmt.ampm(a.hour), maxLines = 1,
                        style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary)
                },
            )
        }
        item(key = "repeat") {
            FilledTonalButton(
                onClick = { m.push(Screen.AlarmDays(id)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = ROW_MIN_HEIGHT).transformedHeight(this, spec).testTag("edit_repeat"),
                transformation = SurfaceTransformation(spec),
                label = { Text("Repeat", maxLines = 1) },
                secondaryLabel = { Text(Days.summary(a.days), maxLines = 1) },
            )
        }
        item(key = "label") {
            FilledTonalButton(
                onClick = { m.push(Screen.AlarmLabel(id)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = ROW_MIN_HEIGHT).transformedHeight(this, spec).testTag("edit_label"),
                transformation = SurfaceTransformation(spec),
                label = { Text("Label", maxLines = 1) },
                secondaryLabel = { Text(a.label.ifBlank { "None" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
        item(key = "snooze") {
            FilledTonalButton(
                onClick = {
                    val c = Alarm.SNOOZE_CHOICES
                    m.updateAlarm(id) { it.copy(snoozeMinutes = c[(c.indexOf(it.snoozeMinutes) + 1) % c.size]) }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = ROW_MIN_HEIGHT).transformedHeight(this, spec).testTag("edit_snooze"),
                transformation = SurfaceTransformation(spec),
                label = { Text("Snooze", maxLines = 1) },
                secondaryLabel = { Text("${a.snoozeMinutes} min", maxLines = 1) },
            )
        }
        item(key = "delete") {
            FilledTonalButton(
                onClick = { m.deleteAlarm(id); m.pop() },
                modifier = Modifier.fillMaxWidth().heightIn(min = ROW_MIN_HEIGHT).transformedHeight(this, spec).testTag("edit_delete"),
                transformation = SurfaceTransformation(spec),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF4A2A2C),
                    contentColor = androidx.compose.ui.graphics.Color(0xFFF2B8B5)),
                icon = { Icon(CircaSymbols.Filled.Delete, null, Modifier.size(24.dp), tint = androidx.compose.ui.graphics.Color(0xFFF2B8B5)) },
                label = { Text("Delete", maxLines = 1) },
            )
        }
    }
}

/** Repeat days: seven switch rows, Monday first. */
@Composable
internal fun AlarmDaysPage(m: ClockModel, id: Int) {
    val a = m.alarm(id) ?: run { LaunchedEffect(Unit) { m.pop() }; return }
    CurvedList(modifier = Modifier.testTag("page_alarm_days"), showTime = false) { spec ->
        item(key = "h") {
            ListHeader(Modifier.transformedHeight(this, spec)) { Text("Repeat", color = MaterialTheme.colorScheme.onSurface) }
        }
        for (i in 0..6) {
            item(key = "d$i") {
                SwitchButton(
                    checked = a.days and (1 shl i) != 0,
                    onCheckedChange = { m.updateAlarm(id) { x -> x.copy(days = Days.toggle(x.days, i)) } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = ROW_MIN_HEIGHT).transformedHeight(this, spec).testTag("day_$i"),
                    transformation = SurfaceTransformation(spec),
                    label = { Text(java.time.DayOfWeek.of(i + 1).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()), maxLines = 1) },
                )
            }
        }
    }
}

/** Label: a text field on the system keyboard; Done (or back) keeps what was typed. */
@Composable
internal fun AlarmLabelPage(m: ClockModel, id: Int) {
    val a = m.alarm(id) ?: run { LaunchedEffect(Unit) { m.pop() }; return }
    var text by remember { mutableStateOf(a.label) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { m.updateAlarm(id) { it.copy(label = text.trim()) } } }
    Box(Modifier.fillMaxSize().testTag("page_alarm_label"), contentAlignment = Alignment.Center) {
        Text("Label", Modifier.align(Alignment.TopCenter).padding(top = 22.dp), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BasicTextField(
            value = text, onValueChange = { text = it.take(24) }, singleLine = true,
            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { m.pop() }),
            modifier = Modifier.padding(horizontal = 28.dp).fillMaxWidth().heightIn(min = 52.dp)
                .clip(RoundedCornerShape(26.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 16.dp, vertical = 14.dp).focusRequester(focus).testTag("label_field"),
        )
    }
}
