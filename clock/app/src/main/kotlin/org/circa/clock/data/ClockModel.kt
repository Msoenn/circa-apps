package org.circa.clock.data

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.circa.clock.model.*

/** Screens above the three pages. */
sealed interface Screen {
    /** Time picker for a new alarm ([alarmId] = null) or an existing one. */
    data class AlarmPicker(val alarmId: Int?, val initial: PickerState) : Screen
    data class AlarmEdit(val alarmId: Int) : Screen
    data class AlarmDays(val alarmId: Int) : Screen
    data class AlarmLabel(val alarmId: Int) : Screen
    data class TimerPicker(val initial: PickerState) : Screen
}

/** UI-facing state; every change is persisted and re-armed. Single instance per activity. */
class ClockModel(private val ctx: Context) {
    private val store = Store(ctx)
    var alarms by mutableStateOf(emptyList<Alarm>())
    var timer by mutableStateOf(TimerState())
    var stopwatch by mutableStateOf(StopwatchState())
    val stack = mutableStateListOfScreens()
    var page by mutableStateOf(0)

    init { refresh() }

    fun refresh() {
        alarms = AlarmLogic.sorted(store.alarms)
        timer = store.timer
        stopwatch = store.stopwatch
    }

    private fun now() = SystemClock.elapsedRealtime()

    // ---- alarms
    private fun saveAlarms(l: List<Alarm>) {
        store.alarms = l; alarms = AlarmLogic.sorted(l)
        Scheduler.rescheduleAlarms(ctx)
    }

    fun alarm(id: Int) = alarms.firstOrNull { it.id == id }

    fun addAlarm(hour: Int, minute: Int, days: Int = 0, label: String = ""): Alarm {
        val a = Alarm(AlarmLogic.newId(store.alarms), hour, minute, days, label)
        saveAlarms(store.alarms + a)
        return a
    }

    fun updateAlarm(id: Int, f: (Alarm) -> Alarm) = saveAlarms(store.alarms.map { if (it.id == id) f(it) else it })
    fun deleteAlarm(id: Int) = saveAlarms(store.alarms.filter { it.id != id })

    /** Switching an alarm on clears any stale snooze. */
    fun setEnabled(id: Int, on: Boolean) = updateAlarm(id) { it.copy(enabled = on, snoozedUntil = if (on) null else it.snoozedUntil) }

    // ---- timer
    private fun saveTimer(t: TimerState) {
        store.timer = t; timer = t
        Scheduler.scheduleTimer(ctx, t); Notifs.ensureChannels(ctx); Notifs.showTimer(ctx, t)
    }
    fun startTimer(ms: Long) = saveTimer(TimerState().start(ms, now()))
    fun pauseTimer() = saveTimer(timer.pause(now()))
    fun resumeTimer() = saveTimer(timer.resume(now()))
    fun resetTimer() = saveTimer(timer.reset())
    fun timerPlusMinute() = saveTimer(timer.addMinute(now()))

    // ---- stopwatch
    private fun saveSw(s: StopwatchState) { store.stopwatch = s; stopwatch = s; Notifs.ensureChannels(ctx); Notifs.showStopwatch(ctx, s) }
    fun swStart() = saveSw(stopwatch.start(now()))
    fun swPause() = saveSw(stopwatch.pause(now()))
    fun swLap() = saveSw(stopwatch.lap(now()))
    fun swReset() = saveSw(stopwatch.reset())

    // ---- navigation
    fun push(s: Screen) { stack.add(s) }
    fun pop(): Boolean { if (stack.isEmpty()) return false; stack.removeAt(stack.size - 1); return true }
}

private fun mutableStateListOfScreens() = androidx.compose.runtime.mutableStateListOf<Screen>()
