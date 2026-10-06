package org.circa.exercise.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Process
import android.util.Log
import org.circa.exercise.model.AutoSample
import org.circa.exercise.model.SyncCallerPolicy
import kotlin.concurrent.thread

/**
 * WatchLink's HR sample, one broadcast per 5-minute grid point (explicit package, so no other app sees it). The
 * process starts for the broadcast, evaluates it in [AutoDetect] and goes away again: nothing keeps this app awake
 * between samples.
 *
 * Extras: `t` (ms, WatchLink's clock), `bpm`, `steps` (steps since local midnight at that moment).
 *
 * WatchLink and this app are signed with different keys, so a signature permission cannot protect the receiver.
 * Instead WatchLink sends with `BroadcastOptions.setShareIdentityEnabled(true)` and the receiver checks
 * [BroadcastReceiver.getSentFromUid] against [SyncCallerPolicy] (system uid or a system `org.circa.*` package).
 * A broadcast without a shared identity (any other app, or the shell) is dropped.
 */
class SampleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SAMPLE) return
        if (!senderAllowed(context)) { Log.w(TAG, "sample dropped: sender uid=$sentFromUid not allowed"); return }
        val t = intent.getLongExtra("t", 0L)
        val bpm = intent.getIntExtra("bpm", 0)
        val steps = intent.getLongExtra("steps", -1L)
        if (t <= 0 || bpm !in 25..250 || steps < 0) return
        val pending = goAsync()
        thread(name = "auto-detect") {
            try { AutoDetect.onSample(context.applicationContext, AutoSample(t, bpm, steps)) }
            catch (e: Exception) { Log.w(TAG, "auto-detect failed", e) }
            finally { pending.finish() }
        }
    }

    private fun senderAllowed(ctx: Context): Boolean {
        val uid = sentFromUid
        if (uid == -1) return false
        val pm = ctx.packageManager
        val pkgs = if (uid < Process.FIRST_APPLICATION_UID) emptyList() else pm.getPackagesForUid(uid)?.toList() ?: emptyList()
        val sys = pkgs.map { p -> runCatching { (pm.getApplicationInfo(p, 0).flags and ApplicationInfo.FLAG_SYSTEM) != 0 }.getOrDefault(false) }
        return SyncCallerPolicy.allowed(uid, Process.myUid(), pkgs, sys)
    }

    companion object {
        const val ACTION_SAMPLE = "org.circa.exercise.action.HR_SAMPLE"
        private const val TAG = "CircaExercise"
    }
}
