package org.circa.watchlink

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Clock's Smart Wake requests (docs/watch-ui/circa/bedtime.md): `org.circa.watchlink.action.SMART_WAKE` with the
 * alarm's set time (`deadline`, epoch ms) and a PendingIntent (`callback`) to fire when the wearer stirs, and
 * `...SMART_WAKE_CANCEL`. Only a callback created by Clock is accepted; all WatchLink can do with it is fire it, and
 * Clock checks the occurrence again before it rings.
 */
class SmartWakeReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            ACTION_REQUEST -> {
                val cb = i.getParcelableExtra(EXTRA_CALLBACK, PendingIntent::class.java)
                val deadline = i.getLongExtra(EXTRA_DEADLINE, 0L)
                if (cb == null || cb.creatorPackage != CLOCK || deadline <= 0L) {
                    Log.w(TAG, "smart wake: request ignored (callback from ${cb?.creatorPackage}, deadline $deadline)")
                    return
                }
                val ok = WatchLinkService.smartWake(deadline, cb)
                Log.i(TAG, "smart wake: request until $deadline, service=" + (if (ok) "running" else "not running"))
            }
            ACTION_CANCEL -> WatchLinkService.smartWake(0L, null)
        }
    }

    companion object {
        const val ACTION_REQUEST = "org.circa.watchlink.action.SMART_WAKE"
        const val ACTION_CANCEL = "org.circa.watchlink.action.SMART_WAKE_CANCEL"
        const val EXTRA_DEADLINE = "deadline"
        const val EXTRA_CALLBACK = "callback"
        private const val CLOCK = "org.circa.clock"
        private const val TAG = "WatchLink"
    }
}
