package org.circa.clock.ui

import org.circa.symbols.CircaSymbols
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.ScreenScaffold
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import androidx.wear.compose.material3.lazy.transformedHeight
import org.circa.clock.data.ClockModel
import org.circa.clock.data.Screen
import org.circa.clock.model.*

/** Page 2: preset grid while idle, the running screen otherwise. */
@Composable
internal fun TimerPage(m: ClockModel) {
    val t = m.timer
    if (t.phase == TimerPhase.IDLE) TimerPresets(m) else TimerRunning(m)
}

@Composable
private fun TimerPresets(m: ClockModel) {
    CurvedList(modifier = Modifier.testTag("page_timer")) { spec ->
        item(key = "h") { ListHeader(Modifier.transformedHeight(this, spec)) { Text("Timer", color = MaterialTheme.colorScheme.onSurface) } }
        // Seven presets plus Custom fill four rows of two.
        val cells: List<Int?> = TIMER_PRESETS_MIN + listOf<Int?>(null)
        for ((i, row) in cells.chunked(2).withIndex()) {
            item(key = "row$i") {
                Row(Modifier.fillMaxWidth().transformedHeight(this, spec), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (min in row) {
                        FilledTonalButton(
                            onClick = { if (min == null) m.push(Screen.TimerPicker(PickerState(0, 10, is24h = true))) else m.startTimer(min * 60_000L) },
                            modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag(if (min == null) "timer_custom" else "preset_$min"),
                            transformation = SurfaceTransformation(spec),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            label = { Text(if (min == null) "Custom" else if (min == 60) "1 h" else "$min min", maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimerRunning(m: ClockModel) {
    val t = m.timer
    val running = t.phase == TimerPhase.RUNNING
    val now = rememberNow(active = running, periodMs = 100)
    Box(Modifier.fillMaxSize().testTag("page_timer_running")) {
        RingArc(t.fraction(now))
        Column(Modifier.align(Alignment.Center).padding(bottom = 52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(Fmt.duration(t.remaining(now)), fontSize = 44.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                color = if (running) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("timer_digits"))
            Text("of " + Fmt.duration(t.totalMs), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp), horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            RoundButton(CircaSymbols.Filled.RestartAlt, "Reset", "timer_reset", size = 48.dp) { m.resetTimer() }
            RoundButton(if (running) CircaSymbols.Filled.Pause else CircaSymbols.Filled.PlayArrow, if (running) "Pause" else "Resume", "timer_toggle", primary = true) {
                if (running) m.pauseTimer() else m.resumeTimer()
            }
            androidx.wear.compose.material3.FilledTonalButton(
                onClick = { m.timerPlusMinute() },
                modifier = Modifier.size(48.dp).testTag("timer_plus"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                shape = androidx.compose.foundation.shape.CircleShape,
                label = { Text("+1:00", fontSize = 11.sp, maxLines = 1, softWrap = false, textAlign = TextAlign.Center) },
            )
        }
    }
}

/** Page 3: ring + digits + buttons fill the screen, laps are listed under it (crown scrolls). */
@Composable
internal fun StopwatchPage(m: ClockModel) {
    val s = m.stopwatch
    val now = rememberNow(active = s.running, periodMs = 40)
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val rotary = RotaryScrollableDefaults.behavior(listState)
    ScreenScaffold(scrollState = listState, timeText = {}, modifier = Modifier.fillMaxSize().testTag("page_stopwatch")) {
        LazyColumn(
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize().requestFocusOnHierarchyActive().rotaryScrollable(rotary, focus),
        ) {
            item(key = "main") {
                Box(Modifier.fillParentMaxSize()) {
                    RingArc(s.ringFraction(now))
                    Column(Modifier.align(Alignment.Center).padding(bottom = 44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(Fmt.stopwatch(s.elapsed(now)), fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                            modifier = Modifier.testTag("sw_digits"))
                        if (s.laps.isNotEmpty()) Text("Lap ${s.laps.size + 1}  " + Fmt.stopwatch(s.currentLapMs(now)), fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        when {
                            s.running -> {
                                RoundButton(CircaSymbols.Filled.Flag, "Lap", "sw_lap", size = 48.dp) { m.swLap() }
                                RoundButton(CircaSymbols.Filled.Pause, "Pause", "sw_toggle", primary = true) { m.swPause() }
                                RoundButton(CircaSymbols.Filled.RestartAlt, "Reset", "sw_reset", size = 48.dp) { m.swReset() }
                            }
                            s.isIdle -> RoundButton(CircaSymbols.Filled.PlayArrow, "Start", "sw_toggle", primary = true) { m.swStart() }
                            else -> {
                                RoundButton(CircaSymbols.Filled.RestartAlt, "Reset", "sw_reset", size = 48.dp) { m.swReset() }
                                RoundButton(CircaSymbols.Filled.PlayArrow, "Resume", "sw_toggle", primary = true) { m.swStart() }
                            }
                        }
                    }
                }
            }
            items(s.laps, key = { "lap${it.number}" }) { lap ->
                Row(
                    Modifier.width(128.dp).heightIn(min = 48.dp).padding(vertical = 2.dp).testTag("lap_${lap.number}"),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Lap ${lap.number}", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                    Column(horizontalAlignment = Alignment.End) {
                        Text(Fmt.stopwatch(lap.lapMs), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(Fmt.stopwatch(lap.totalMs), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item(key = "end") { Box(Modifier.height(36.dp)) }
        }
    }
}
