package org.circa.clock.data

import android.content.Context
import android.content.SharedPreferences
import org.circa.clock.model.Alarm
import org.circa.clock.model.AlarmCodec
import org.circa.clock.model.StopwatchState
import org.circa.clock.model.TimerState

/**
 * All persistent state, in DEVICE-PROTECTED storage so alarms are readable (and ring) after a reboot before
 * the first unlock. Alarms are local to this watch: nothing here is ever imported from, or mirrored to, a phone.
 */
class Store(context: Context) {
    private val prefs: SharedPreferences = (context.applicationContext ?: context).let {
        val dp = if (it.isDeviceProtectedStorage) it else it.createDeviceProtectedStorageContext()
        dp.getSharedPreferences("clock", Context.MODE_PRIVATE)
    }

    var alarms: List<Alarm>
        get() = AlarmCodec.decode(prefs.getString("alarms", ""))
        set(v) { prefs.edit().putString("alarms", AlarmCodec.encode(v)).commit() }

    var timer: TimerState
        get() = TimerState.decode(prefs.getString("timer", null))
        set(v) { prefs.edit().putString("timer", v.encode()).commit() }

    var stopwatch: StopwatchState
        get() = StopwatchState.decode(prefs.getString("stopwatch", null))
        set(v) { prefs.edit().putString("stopwatch", v.encode()).commit() }

    /** Alarm that is ringing right now (id), or -1; timer ringing = [RING_TIMER]. */
    var ringing: Int
        get() = prefs.getInt("ringing", RING_NONE)
        set(v) { prefs.edit().putInt("ringing", v).commit() }

    companion object {
        const val RING_NONE = -1
        const val RING_TIMER = -2
    }
}
