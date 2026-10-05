package org.circa.exercise.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Emulator aid: inject a fake heart rate (the emulator has no HR sensor). Protected by android.permission.DUMP in the
 * manifest (only shell/system can send it) and ignored on `user` builds.
 *
 *   adb shell am broadcast -a org.circa.exercise.DEBUG_HR -n org.circa.exercise/.data.DebugReceiver --ei bpm 150
 *   (bpm 0 switches it off)
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Build.TYPE == "user") return
        if (intent.action != ACTION_DEBUG_HR) return
        val bpm = intent.getIntExtra("bpm", 0)
        Live.fakeHr = bpm.takeIf { it in 25..250 }
        Log.i("CircaExercise", "debug HR = ${Live.fakeHr}")
    }

    companion object { const val ACTION_DEBUG_HR = "org.circa.exercise.DEBUG_HR" }
}
