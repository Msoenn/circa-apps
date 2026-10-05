package org.circa.watchlink

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log

/**
 * Exported read-only view of WatchLink's latest health values, for the Aurora launcher (org.circa.launcher).
 * The contract the launcher relies on is written down in watchlink/DATA-CONTRACT.md.
 *
 * Access control: the two apps are signed with different keys (WatchLink with the debug keystore, the launcher with
 * the AOSP platform testkey), so a signature permission cannot be used. Instead every [query] checks the calling UID:
 * only org.circa.launcher, WatchLink itself, or a caller in the same UID may read; anything else gets a
 * SecurityException. The `<queries>` element in the manifest declares the launcher package for package visibility.
 *
 * Values come from [HealthStore], which the service feeds from the sensor callbacks it already has; they are absent
 * (hr / steps null-or-zero as documented) while the service has not run.
 */
class HealthProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        HealthStore.attach(context ?: return false)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        enforceCaller()
        val now = HealthStore.nowMs()
        val snap = HealthStore.snapshot()
        return when (uri.lastPathSegment) {   // uri.path is "/latest"; match the segment, not the path
            PATH_LATEST -> {
                val cols = projection?.toList() ?: DEFAULT_LATEST_COLUMNS
                val hr = snap.latest(now)
                val m = MatrixCursor(cols.toTypedArray(), 1)
                m.addRow(
                    cols.map<String, Any?> { col ->
                        when (col) {
                            COL_HR_BPM -> hr?.bpm
                            COL_HR_TIME_MS -> hr?.timeMs
                            COL_STEPS_TODAY -> snap.stepsToday() ?: 0L
                            COL_STEPS_TIME_MS -> snap.stepsTimeMs()
                            else -> null
                        }
                    }.toTypedArray()
                )
                m
            }
            PATH_HISTORY -> {
                val cols = projection?.toList() ?: DEFAULT_HISTORY_COLUMNS
                val hist = snap.history(now)
                val m = MatrixCursor(cols.toTypedArray(), hist.size)
                for (s in hist) {
                    m.addRow(
                        cols.map<String, Any?> { col ->
                            when (col) {
                                COL_TIME_MS -> s.timeMs
                                COL_BPM -> s.bpm
                                else -> null
                            }
                        }.toTypedArray()
                    )
                }
                m
            }
            else -> {
                Log.i(TAG, "health provider: unknown uri $uri")
                throw IllegalArgumentException("unknown health uri: $uri")
            }
        }
    }

    override fun getType(uri: Uri): String? = when (uri.lastPathSegment) {
        PATH_LATEST -> "vnd.android.cursor.item/vnd.org.circa.watchlink.health.latest"
        PATH_HISTORY -> "vnd.android.cursor.dir/vnd.org.circa.watchlink.health.history"
        else -> null
    }

    // ---- read-only ----

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("$AUTHORITY is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("$AUTHORITY is read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("$AUTHORITY is read-only")

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? =
        throw UnsupportedOperationException("$AUTHORITY is record-based; use query()")

    /**
     * Only the launcher, WatchLink itself, or a caller sharing WatchLink's UID may read. `getPackagesForUid` can
     * return null for an unknown UID (and a UID may map to several packages), so resolve the full list.
     */
    private fun enforceCaller() {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return
        val c = context ?: throw SecurityException("health provider not created")
        val pkgs = try {
            c.packageManager.getPackagesForUid(uid)
        } catch (e: Exception) {
            null
        }
        val allowed = pkgs != null && pkgs.any { it == c.packageName || it == PACKAGE_LAUNCHER }
        if (!allowed) {
            Log.i(TAG, "health provider: denied uid $uid (packages=${pkgs?.joinToString()})")
            throw SecurityException("uid $uid is not $PACKAGE_LAUNCHER or ${c.packageName}")
        }
    }

    companion object {
        const val AUTHORITY = "org.circa.watchlink.health"
        const val PATH_LATEST = "latest"
        const val PATH_HISTORY = "history"

        @JvmField val URI_LATEST: Uri = Uri.parse("content://$AUTHORITY/$PATH_LATEST")
        @JvmField val URI_HISTORY: Uri = Uri.parse("content://$AUTHORITY/$PATH_HISTORY")

        const val COL_HR_BPM = "hr_bpm"
        const val COL_HR_TIME_MS = "hr_time_ms"
        const val COL_STEPS_TODAY = "steps_today"
        const val COL_STEPS_TIME_MS = "steps_time_ms"
        const val COL_TIME_MS = "time_ms"
        const val COL_BPM = "bpm"

        @JvmField val DEFAULT_LATEST_COLUMNS =
            listOf(COL_HR_BPM, COL_HR_TIME_MS, COL_STEPS_TODAY, COL_STEPS_TIME_MS)
        @JvmField val DEFAULT_HISTORY_COLUMNS = listOf(COL_TIME_MS, COL_BPM)

        private const val PACKAGE_LAUNCHER = "org.circa.launcher"
        private const val TAG = "WatchLink"
    }
}
