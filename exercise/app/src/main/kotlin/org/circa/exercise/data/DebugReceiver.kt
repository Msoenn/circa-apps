package org.circa.exercise.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import org.circa.exercise.model.AutoSample

/**
 * Emulator aids. Protected by android.permission.DUMP in the manifest (only shell/system can send them) and ignored on
 * `user` builds.
 *
 * Fake heart rate (the emulator has no HR sensor):
 *   adb shell am broadcast -a org.circa.exercise.DEBUG_HR -n org.circa.exercise/.data.DebugReceiver --ei bpm 150
 *   (bpm 0 switches it off)
 *
 * Synthetic WatchLink samples into the same detector path a real one takes ([AutoDetect.onSample]), as
 * "t,bpm,steps;t,bpm,steps": t = epoch ms, or "-90s" / "-5m" relative to now; steps = cumulative.
 *   adb shell am broadcast -a org.circa.exercise.DEBUG_SAMPLES -n org.circa.exercise/.data.DebugReceiver \
 *     --es samples "-10m,120,1000;-5m,150,1500;0,152,2000"
 *   --ez reset true  clears the detector state first (previous sample, run, cooldown).
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Build.TYPE == "user") return
        when (intent.action) {
            ACTION_DEBUG_HR -> {
                val bpm = intent.getIntExtra("bpm", 0)
                Live.fakeHr = bpm.takeIf { it in 25..250 }
                Log.i("CircaExercise", "debug HR = ${Live.fakeHr}")
            }
            ACTION_DEBUG_SAMPLES -> {
                val app = context.applicationContext
                if (intent.getBooleanExtra("reset", false)) java.io.File(app.filesDir, "autodetect.json").delete()
                val now = System.currentTimeMillis()
                val samples = parse(intent.getStringExtra("samples"), now)
                resultData = "samples=${samples.size}"
                val pending = goAsync()
                Thread {
                    try { samples.forEach { AutoDetect.onSample(app, it) } }
                    catch (e: Exception) { Log.w("CircaExercise", "debug samples failed", e) }
                    finally { pending.finish() }
                }.start()
            }
        }
    }

    companion object {
        const val ACTION_DEBUG_HR = "org.circa.exercise.DEBUG_HR"
        const val ACTION_DEBUG_SAMPLES = "org.circa.exercise.DEBUG_SAMPLES"

        /** "t,bpm,steps;..." with t absolute ms or relative ("-5m", "-90s", "0"). Bad entries are skipped. */
        fun parse(spec: String?, now: Long): List<AutoSample> = spec?.split(";")?.mapNotNull { e ->
            runCatching {
                val f = e.trim().split(",")
                val t = f[0].trim()
                val ms = when {
                    t.endsWith("m") -> now + (t.dropLast(1).toDouble() * 60_000).toLong()
                    t.endsWith("s") -> now + (t.dropLast(1).toDouble() * 1_000).toLong()
                    t == "0" -> now
                    else -> t.toLong()
                }
                AutoSample(ms, f[1].trim().toInt(), f[2].trim().toLong())
            }.getOrNull()
        } ?: emptyList()
    }
}
