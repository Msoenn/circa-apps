package org.circa.watchlink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Test hook: feeds one Gadgetbridge line into the same parser as real BLE input ([PhoneStore.onLine], reached by the
 * service through the same `Proto.classify` path). Protected by android.permission.DUMP in the manifest, which only
 * the adb shell (and the system) holds. Usage:
 *   adb shell am broadcast -n org.circa.watchlink/.DebugGbLineReceiver -a org.circa.watchlink.DEBUG_GB_LINE \
 *     --es line 'GB({"t":"find","n":true})'
 * A line that starts with `{` is wrapped in `GB(...)` for convenience. `--ez connected true|false` (alone or with a
 * line) sets the /status connected flag and last_seen_ms as if a GB link were up/down (no command delivery).
 * `notify`, `notify-`, `canned_responses_sync` and `call` lines go to the running service, which posts / removes the
 * real notification (with its Reply and action buttons, or the call's Reject / Accept) and stores the canned replies.
 * A `t=nav` line goes to the running service, which stores the route and buzzes the maneuver ([WatchLinkService.debugNav]);
 * with no service running it is only stored, like every other line.
 */
class DebugGbLineReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != ACTION) return
        // Optional: set the /status connected flag (and last_seen_ms) as if a GB link were up/down. Test only; it
        // does not make commands deliverable (call() still answers not_connected without a subscribed link).
        if (i.hasExtra("connected")) {
            val on = i.getBooleanExtra("connected", false)
            PhoneStore.attach(c)
            PhoneStore.debugSetConnected(on)
            Log.i(TAG, "DEBUG_GB_LINE connected=$on")
            resultData = "connected=$on"
        }
        // Fire the exercise app's HR-sample broadcast (the real sender path): --es hr_sample "bpm,steps"
        i.getStringExtra("hr_sample")?.let { spec ->
            val f = spec.split(",").map { it.trim() }
            val bpm = f.getOrNull(0)?.toIntOrNull() ?: 0
            val steps = f.getOrNull(1)?.toLongOrNull() ?: -1L
            ExerciseLink.send(c, System.currentTimeMillis(), bpm, steps)
            Log.i(TAG, "DEBUG_GB_LINE hr_sample bpm=$bpm steps=$steps")
            resultData = "hr_sample bpm=$bpm steps=$steps"
            return
        }
        // Send the shade the sleep-state broadcast a real night start / end sends (Bedtime's "start when asleep"):
        //   --ez sleep_state true|false
        if (i.hasExtra("sleep_state")) {
            val asleep = i.getBooleanExtra("sleep_state", false)
            SleepTracking.announceSleepState(c, asleep)
            resultData = "sleep_state asleep=$asleep"
            return
        }
        // Replay a synthetic night through the sleep tracker (logged as "OUT.debug ...", nothing stored or sent):
        //   --es sleep_replay night   or   --es sleep_replay '22:00;W*3;S*36;R*2;S*6;T;S*12;W*4'  (see SleepReplay)
        i.getStringExtra("sleep_replay")?.let { spec ->
            val ok = WatchLinkService.debugSleepReplay(spec)
            Log.i(TAG, "DEBUG_GB_LINE sleep_replay service=" + (if (ok) "running" else "not running"))
            resultData = "sleep_replay service=" + (if (ok) "running" else "not running")
            return
        }
        var line = i.getStringExtra("line")?.trim()
        if (line.isNullOrEmpty()) {
            if (i.hasExtra("connected")) return
            resultData = "no line"; Log.i(TAG, "DEBUG_GB_LINE: empty"); return
        }
        if (line.startsWith("{")) line = "GB($line)"
        // Workout-track requests go to the running service (replies logged as "OUT.debug ...", not sent to GB).
        val parsed = Proto.classify(line)
        val tt = if (parsed.kind == Proto.KIND_GB) parsed.type() else null
        if (tt == "listRecs" || tt == "fetchRec") {
            val ok = parsed.msg?.let { WatchLinkService.debugTrack(it) } ?: false
            Log.i(TAG, "DEBUG_GB_LINE t=$tt service=" + (if (ok) "running" else "not running"))
            resultData = "t=$tt service=" + (if (ok) "running" else "not running")
            return
        }
        // A nav line also drives the turn vibration, which lives in the running service; without it there is
        // nothing to buzz, so the line is stored below like any other.
        if (tt == "nav" && parsed.msg?.let { WatchLinkService.debugNav(it) } == true) {
            Log.i(TAG, "DEBUG_GB_LINE t=nav service=running")
            resultData = "t=nav service=running"
            return
        }
        // Notification lines post (or remove) the real notification, which only the running service does; replies and
        // actions tapped on it are logged as "user action on watch notification ... dropped (not connected)".
        if ((tt == "notify" || tt == "notify-" || tt == "canned_responses_sync" || tt == "call") && parsed.msg != null) {
            val ok = WatchLinkService.debugNotify(tt, parsed.msg!!)
            Log.i(TAG, "DEBUG_GB_LINE t=$tt service=" + (if (ok) "running" else "not running"))
            resultData = "t=$tt service=" + (if (ok) "running" else "not running")
            return
        }
        PhoneStore.attach(c)
        val t = PhoneStore.onLine(line)
        Log.i(TAG, "DEBUG_GB_LINE t=$t")
        resultData = "t=$t"
    }

    companion object {
        const val ACTION = "org.circa.watchlink.DEBUG_GB_LINE"
        private const val TAG = "WatchLink"
    }
}
