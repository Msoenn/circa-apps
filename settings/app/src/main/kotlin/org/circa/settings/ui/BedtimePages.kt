package org.circa.settings.ui

import android.text.format.DateFormat
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.delay
import org.circa.settings.SettingsController
import org.circa.settings.data.BedtimeData
import org.circa.settings.model.BedtimeModel
import org.circa.settings.model.BedtimeState
import org.circa.settings.model.SettingsPage
import org.circa.symbols.CircaSymbols
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Bedtime (docs/watch-ui/circa/bedtime.md): on/off, the schedule and "start when asleep". The Circa shade
 * applies it; these pages only write the shared keys ([BedtimeData]).
 */

/** The Bedtime keys, re-read every second (the tile, the schedule and sleep detection change them too). */
@Composable
private fun rememberBedtime(c: SettingsController): Pair<BedtimeData, MutableState<BedtimeState>> {
    val data = remember { BedtimeData(c.appContext) }
    val state = remember { mutableStateOf(data.read()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            state.value = data.read()
        }
    }
    return data to state
}

@Composable
internal fun BedtimePage(c: SettingsController) {
    val (data, state) = rememberBedtime(c)
    val is24h = DateFormat.is24HourFormat(c.appContext)
    ListPage(c, SettingsPage.BEDTIME) { spec ->
        val s = state.value
        toggleRow("bedtime_on", spec, "Bedtime", s.on, CircaSymbols.Outlined.Bedtime, secondary = onOff(s.on)) {
            data.setOn(it)
            state.value = data.read()
        }
        toggleRow(
            "bedtime_schedule", spec, "Schedule", s.schedule.enabled, CircaSymbols.Outlined.Schedule,
            secondary = BedtimeModel.scheduleSummary(s.schedule, is24h),
        ) {
            data.setSchedule(s.schedule.copy(enabled = it))
            state.value = data.read()
        }
        navRow(
            "bedtime_start", spec, "Starts", CircaSymbols.Outlined.NightsStay,
            secondary = BedtimeModel.time(s.schedule.startMin, is24h), enabled = s.schedule.enabled,
        ) { c.openSettingsPage(SettingsPage.BEDTIME_TIME, ARG_START) }
        navRow(
            "bedtime_end", spec, "Ends", CircaSymbols.Outlined.WbSunny,
            secondary = BedtimeModel.time(s.schedule.endMin, is24h), enabled = s.schedule.enabled,
        ) { c.openSettingsPage(SettingsPage.BEDTIME_TIME, ARG_END) }
        navRow(
            "bedtime_days", spec, "Days", CircaSymbols.Outlined.Event,
            secondary = BedtimeModel.days(s.schedule.days), enabled = s.schedule.enabled,
        ) { c.openSettingsPage(SettingsPage.BEDTIME_DAYS) }
        toggleRow(
            "bedtime_on_sleep", spec, "Start when asleep", s.startWhenAsleep, CircaSymbols.Outlined.Bedtime,
            secondary = "${BedtimeModel.time(20 * 60, is24h)} - ${BedtimeModel.time(10 * 60, is24h)}",
        ) {
            data.setStartWhenAsleep(it)
            state.value = data.read()
        }
        // Three short notes: a note shows at most three lines.
        noteItem("bedtime_note", "Do Not Disturb, no always-on display, no tilt-to-wake")
        noteItem("bedtime_note_alarms", "Alarms still ring; dismissing one ends Bedtime")
        noteItem("bedtime_note_sleep", "Start when asleep needs sleep tracking")
    }
}

@Composable
internal fun BedtimeDaysPage(c: SettingsController) {
    val (data, state) = rememberBedtime(c)
    ListPage(c, SettingsPage.BEDTIME_DAYS) { spec ->
        val sch = state.value.schedule
        for (i in 0..6) {
            toggleRow("bedtime_day_$i", spec, BedtimeModel.dayName(i), sch.days and (1 shl i) != 0) {
                data.setSchedule(sch.copy(days = sch.days xor (1 shl i)))
                state.value = data.read()
            }
        }
        noteItem("bedtime_days_note", "The night that starts on these days")
    }
}

/** Start or end time (pageArg [ARG_START] / [ARG_END]): crown or -/+ in 15 min steps, the check saves. */
@Composable
internal fun BedtimeTimePage(c: SettingsController) {
    val data = remember { BedtimeData(c.appContext) }
    val start = c.pageArg.value != ARG_END
    val is24h = DateFormat.is24HourFormat(c.appContext)
    var minute by remember(start) {
        val s = data.read().schedule
        mutableIntStateOf(if (start) s.startMin else s.endMin)
    }
    PageFrame(c, SettingsPage.BEDTIME_TIME) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(settingsPageTag(SettingsPage.BEDTIME_TIME))
                .onRotaryScrollEvent { e ->
                    val px = e.verticalScrollPixels
                    val clicks = (abs(px) / 40f).roundToInt().coerceAtLeast(1) * (if (px >= 0) 1 else -1)
                    minute = BedtimeModel.adjust(minute, clicks)
                    true
                }
                .focusRequester(focus)
                .focusable(),
        ) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    if (start) "Bedtime starts" else "Bedtime ends",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Text(
                    BedtimeModel.time(minute, is24h),
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.testTag("bedtime_time_value"),
                )
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    RoundIconButton("bedtime_time_down", CircaSymbols.Filled.Remove, "Earlier", tonal = true) {
                        minute = BedtimeModel.adjust(minute, -1)
                    }
                    RoundIconButton("bedtime_time_save", CircaSymbols.Filled.Check, "Save") {
                        val s = data.read().schedule
                        data.setSchedule(if (start) s.copy(startMin = minute) else s.copy(endMin = minute))
                        c.settingsBack()
                    }
                    RoundIconButton("bedtime_time_up", CircaSymbols.Filled.Add, "Later", tonal = true) {
                        minute = BedtimeModel.adjust(minute, 1)
                    }
                }
            }
        }
    }
}

private const val ARG_START = "start"
private const val ARG_END = "end"
