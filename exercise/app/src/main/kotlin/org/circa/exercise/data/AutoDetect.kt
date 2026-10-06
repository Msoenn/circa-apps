package org.circa.exercise.data

import android.content.Context
import android.provider.Settings
import android.util.Log
import org.circa.exercise.model.AutoDetector
import org.circa.exercise.model.AutoInterval
import org.circa.exercise.model.AutoParams
import org.circa.exercise.model.AutoSample
import org.json.JSONObject
import java.io.File
import java.time.Year

/**
 * The glue around the pure [AutoDetector]: its state file, the Settings toggle, and the one entry point both the
 * WatchLink broadcast ([SampleReceiver]) and the debug hook ([DebugReceiver]) go through, so a test sample takes
 * exactly the path a real one does.
 *
 * The state lives in `autodetect.json` (the previous sample, the elevated run, the cooldown) because the exercise
 * process is killed between 5-minute samples: every sample restores the detector from the file, evaluates, and writes
 * it back, so nothing is lost when the process dies.
 */
object AutoDetect {
    private const val TAG = "CircaExercise"
    private const val FILE = "autodetect.json"

    fun enabled(ctx: Context): Boolean =
        AutoParams.enabled(runCatching { Settings.Secure.getString(ctx.contentResolver, AutoParams.SETTING_KEY) }.getOrNull())

    /** One sample. Evaluates (a workout in progress blocks it), persists the state and starts the recording on a hit. */
    @Synchronized
    fun onSample(ctx: Context, s: AutoSample) {
        val file = File(ctx.applicationContext.filesDir, FILE)
        val det = load(file)
        if (!enabled(ctx)) {
            // Off: keep nothing, so switching it on starts from a clean slate.
            det.onSample(s, Int.MAX_VALUE, blocked = true)
            save(file, det)
            Log.i(TAG, "auto-detect: off, sample ignored")
            return
        }
        val thr = AutoParams.thresholdBpm(Live.profile(ctx), Year.now().value)
        val hit = det.onSample(s, thr, blocked = Live.isRecording(ctx))
        save(file, det)
        Log.i(TAG, "auto-detect: sample t=${s.timeMs} bpm=${s.bpm} steps=${s.steps} thr=$thr -> " +
            (hit?.let { "${it.type.id} since ${it.startMs}" } ?: "no (run=${det.elevatedRun.size})"))
        if (hit != null) ExerciseService.startAuto(ctx, hit.type, AutoInterval.encodeAll(hit.intervals), thr)
    }

    /** An auto workout ended or was discarded: start the cooldown. */
    @Synchronized
    fun noteEnded(ctx: Context, nowMs: Long) {
        val file = File(ctx.applicationContext.filesDir, FILE)
        val det = load(file)
        det.noteEnded(nowMs)
        save(file, det)
    }

    private fun load(f: File): AutoDetector =
        runCatching { if (f.exists()) AutoDetector.fromJson(JSONObject(f.readText())) else AutoDetector() }.getOrElse { AutoDetector() }

    private fun save(f: File, d: AutoDetector) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        runCatching { tmp.writeText(d.toJson().toString()); if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() } }
    }
}
