package org.circa.watchlink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * Autostart after boot (BOOT_COMPLETED) and after an app update (MY_PACKAGE_REPLACED, e.g. `adb install -r`),
 * but only if the user last pressed Start (WatchLinkService.wantRunning). The service itself decides which
 * foreground-service types it can hold from this background context; see WatchLinkService.goForeground.
 * LOCKED_BOOT_COMPLETED is deliberately not used: the watch has no lock screen, so credential storage (prefs,
 * history.csv) is unlocked right at boot, and the app is not directBootAware.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val a = i.action.toString()
        val want = WatchLinkService.wantRunning(c)
        Log.i(WatchLinkService.TAG, "AUTOSTART " + a + " uptime=" + SystemClock.elapsedRealtime() + " ms want_running=" + want
                + " running=" + WatchLinkService.running)
        if (Intent.ACTION_BOOT_COMPLETED != a && Intent.ACTION_MY_PACKAGE_REPLACED != a) return
        if (!want) {
            Log.i(WatchLinkService.TAG, "AUTOSTART skipped: user has not pressed Start (or pressed Stop)")
            return
        }
        try {
            c.startForegroundService(Intent(c, WatchLinkService::class.java)
                .setAction(WatchLinkService.ACTION_START).putExtra(WatchLinkService.EXTRA_ORIGIN, a))
        } catch (e: RuntimeException) {
            Log.i(WatchLinkService.TAG, "AUTOSTART startForegroundService failed: $e")
        }
    }
}
