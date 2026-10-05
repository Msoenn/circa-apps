package org.circa.clock.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.HorizontalPagerScaffold
import org.circa.clock.data.ClockModel
import org.circa.clock.data.Screen
import org.circa.clock.model.PickerState
import org.circa.clock.model.TimerState
import kotlin.math.abs

/** Root: three swipeable pages (Alarm, Timer, Stopwatch) with page dots, screens stacked above them. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClockApp(m: ClockModel, is24h: Boolean, onExit: () -> Unit) {
    val pager = rememberPagerState(initialPage = m.page) { 3 }
    LaunchedEffect(m.page) { if (pager.currentPage != m.page) pager.scrollToPage(m.page) }
    LaunchedEffect(pager.currentPage) { m.page = pager.currentPage }
    val atRoot = m.stack.isEmpty()
    val back by rememberUpdatedState({ if (!m.pop()) onExit() })
    val canBack by rememberUpdatedState(!atRoot || pager.currentPage == 0)

    AppScaffold(timeText = {}, modifier = Modifier.fillMaxSize().swipeRightBack({ canBack }) { back() }) {
        val top = m.stack.lastOrNull()
        if (top == null) {
            HorizontalPagerScaffold(pagerState = pager) {
                HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                    when (page) {
                        0 -> AlarmListPage(m, is24h)
                        1 -> TimerPage(m)
                        else -> StopwatchPage(m)
                    }
                }
            }
        } else when (top) {
            is Screen.AlarmPicker -> TimePicker(top.initial) { p ->
                m.pop()
                if (top.alarmId == null) {
                    val a = m.addAlarm(p.hour, p.minute)
                    m.push(Screen.AlarmEdit(a.id))
                } else m.updateAlarm(top.alarmId) { it.copy(hour = p.hour, minute = p.minute, enabled = true, snoozedUntil = null) }
            }
            is Screen.AlarmEdit -> AlarmEditPage(m, top.alarmId, is24h)
            is Screen.AlarmDays -> AlarmDaysPage(m, top.alarmId)
            is Screen.AlarmLabel -> AlarmLabelPage(m, top.alarmId)
            is Screen.TimerPicker -> TimePicker(top.initial, title = "Hours : minutes") { p ->
                val ms = (p.hour * 60L + p.minute) * 60_000L
                m.pop()
                if (ms > 0) m.startTimer(ms)
            }
        }
    }
}

/**
 * Swipe right = back / exit. Observes the gesture on the Final pass so scrolling children keep working; a
 * clear rightward drag that starts while [enabled] fires [onBack] once.
 */
private fun Modifier.swipeRightBack(enabled: () -> Boolean, onBack: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val allowed = enabled()
        var dx = 0f; var dy = 0f
        val threshold = size.width * 0.25f
        var fired = false
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Final)
            val c = e.changes.firstOrNull { it.id == down.id } ?: break
            val d = c.positionChangeIgnoreConsumed()
            dx += d.x; dy += d.y
            if (allowed && !fired && dx > threshold && dx > 2 * abs(dy)) { fired = true; onBack() }
            if (!c.pressed) break
        }
    }
}
