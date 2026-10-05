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
