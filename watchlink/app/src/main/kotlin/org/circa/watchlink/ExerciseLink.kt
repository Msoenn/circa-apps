package org.circa.watchlink

import android.app.BroadcastOptions
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Tells Circa Exercise about each 5-minute HR sample, so it can auto-detect walks and runs (decisions.md "Exercise
 * auto-detection (2026-10-06)"). One explicit-package broadcast per grid sample that produced a reading: nothing runs
 * between samples, and the receiver in the exercise app starts its process only for the broadcast.
 *
 * Extras: `t` (ms, WatchLink's phone-synced clock), `bpm`, `steps` (steps since local midnight at that moment).
 * The two apps are signed with different keys, so the broadcast opts into sharing the sender's identity
 * ([BroadcastOptions.setShareIdentityEnabled], API 34) and the receiver checks it is a system `org.circa.*` app.
 */
object ExerciseLink {
    const val PACKAGE = "org.circa.exercise"
    const val ACTION = "org.circa.exercise.action.HR_SAMPLE"
    private const val TAG = "WatchLink"

    fun send(c: Context, timeMs: Long, bpm: Int, steps: Long) {
        if (bpm <= 0 || steps < 0) return
        val i = Intent(ACTION).setPackage(PACKAGE)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            .putExtra("t", timeMs).putExtra("bpm", bpm).putExtra("steps", steps)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                c.sendBroadcast(i, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
            } else {
                c.sendBroadcast(i)
            }
            Log.d(TAG, "exercise link: sample sent")
        } catch (e: RuntimeException) {
            Log.i(TAG, "exercise link failed: $e")
        }
    }
}
