package org.circa.exercise.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import org.circa.exercise.model.StateCallerPolicy

/**
 * The launcher's running-workout indicator (launcher/README.md): a small read-only view of [Live.state],
 * `content://org.circa.exercise.state/current`, one row:
 *
 * | column | value |
 * |---|---|
 * | `activity` | [org.circa.exercise.model.ActivityType.id] of the running workout, "" when idle |
 * | `state` | `recording` / `paused` / `idle` ([org.circa.exercise.model.WorkoutState]) |
 * | `started_ms` | wall-clock start of the workout, 0 when idle |
 *
 * Access control is in code ([StateCallerPolicy]): only installed system (FLAG_SYSTEM) `org.circa.*` packages and this
 * app may read - the same rule WatchLink's providers use. The launcher is `org.circa.launcher`, platform-signed.
 *
 * The writer is [ExerciseService], which calls [notify] on every state change (start, pause, resume, end, discard), so
 * a [android.database.ContentObserver] on [CURRENT] is enough - the launcher does not poll.
 */
class StateProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        enforceCaller()
        val ctx = context ?: throw IllegalStateException("state provider not created")
        if (uri.lastPathSegment != PATH_CURRENT) throw IllegalArgumentException("unknown state uri: $uri")
        val cols = projection?.toList() ?: DEFAULT_COLUMNS
        val s = Live.state(ctx)
        return MatrixCursor(cols.toTypedArray(), 1).apply {
            addRow(
                cols.map<String, Any?> { col ->
                    when (col) {
                        COL_ACTIVITY -> s.activity
                        COL_STATE -> s.state
                        COL_STARTED_MS -> s.startedMs
                        else -> null
                    }
                }.toTypedArray(),
            )
        }
    }

    override fun getType(uri: Uri): String? =
        if (uri.lastPathSegment == PATH_CURRENT) "vnd.android.cursor.item/vnd.org.circa.exercise.state" else null

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
     * Only system `org.circa.*` packages (the launcher) and this app may read. `getPackagesForUid` can return null for
     * an unknown UID and a UID may map to several packages, so resolve the full list.
     */
    private fun enforceCaller() {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return
        val c = context ?: throw SecurityException("state provider not created")
        val pkgs = try {
            c.packageManager.getPackagesForUid(uid)?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        val system = pkgs.map { p ->
            runCatching { (c.packageManager.getApplicationInfo(p, 0).flags and ApplicationInfo.FLAG_SYSTEM) != 0 }
                .getOrDefault(false)
        }
        if (!StateCallerPolicy.allowed(pkgs, system, c.packageName)) {
            Log.i(TAG, "state provider: denied uid $uid (packages=${pkgs.joinToString()})")
            throw SecurityException("uid $uid may not read the workout state")
        }
    }

    companion object {
        const val TAG = "CircaExercise"
        const val AUTHORITY = "org.circa.exercise.state"
        const val PATH_CURRENT = "current"

        @JvmField val CURRENT: Uri = Uri.parse("content://$AUTHORITY/$PATH_CURRENT")

        const val COL_ACTIVITY = "activity"
        const val COL_STATE = "state"
        const val COL_STARTED_MS = "started_ms"

        @JvmField val DEFAULT_COLUMNS = listOf(COL_ACTIVITY, COL_STATE, COL_STARTED_MS)

        /** Tell observers (the launcher) the row changed; best effort. */
        fun notify(ctx: Context) {
            runCatching { ctx.contentResolver.notifyChange(CURRENT, null) }
        }
    }
}
