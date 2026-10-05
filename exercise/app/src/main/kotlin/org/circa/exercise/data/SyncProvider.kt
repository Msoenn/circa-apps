package org.circa.exercise.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ApplicationInfo
import org.circa.exercise.model.SyncCallerPolicy
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/**
 * Sync contract for WatchLink (exercise/README.md). Authority `org.circa.exercise.sync`,
 * exported without a permission but caller-checked ([SyncCallerPolicy]): system uids (< 10000), this app, and
 * preinstalled (FLAG_SYSTEM) `org.circa.*` packages (WatchLink). Everything goes through [call]:
 *
 * - `list`  (arg = last synced id, may be null)  -> string array `ids`: saved ids sorting after it, ascending
 * - `csv`   (arg = id)                            -> string `csv`: the whole Bangle recorder CSV (null if unknown)
 * - `state`                                        -> boolean `active`, string `act` (GB activity name, "" when idle)
 */
class SyncProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun checkCaller() {
        val uid = Binder.getCallingUid()
        val pm = context?.packageManager ?: throw SecurityException("no context")
        val pkgs = if (uid < Process.FIRST_APPLICATION_UID || uid == Process.myUid()) emptyList()
        else pm.getPackagesForUid(uid)?.toList() ?: emptyList()
        val sys = pkgs.map { p ->
            runCatching { (pm.getApplicationInfo(p, 0).flags and ApplicationInfo.FLAG_SYSTEM) != 0 }.getOrDefault(false)
        }
        if (!SyncCallerPolicy.allowed(uid, Process.myUid(), pkgs, sys)) throw SecurityException("uid $uid may not read workouts")
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        checkCaller()
        val ctx = context ?: return null
        val token = Binder.clearCallingIdentity()
        try {
            val st = Storage(ctx)
            return when (method) {
                METHOD_LIST -> Bundle().apply {
                    putStringArray(KEY_IDS, org.circa.exercise.model.BangleCsv.idsAfter(st.ids(), arg).toTypedArray())
                }
                METHOD_CSV -> Bundle().apply { putString(KEY_CSV, arg?.let { st.csv(it) }) }
                METHOD_STATE -> Bundle().apply {
                    val t = Live.activeType(ctx)
                    putBoolean(KEY_ACTIVE, t != null)
                    putString(KEY_ACT, t?.gbName ?: "")
                }
                else -> throw IllegalArgumentException("unknown method $method")
            }
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? { checkCaller(); return null }
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = throw UnsupportedOperationException()

    companion object {
        const val AUTHORITY = "org.circa.exercise.sync"
        const val METHOD_LIST = "list"
        const val METHOD_CSV = "csv"
        const val METHOD_STATE = "state"
        const val KEY_IDS = "ids"
        const val KEY_CSV = "csv"
        const val KEY_ACTIVE = "active"
        const val KEY_ACT = "act"
    }
}
