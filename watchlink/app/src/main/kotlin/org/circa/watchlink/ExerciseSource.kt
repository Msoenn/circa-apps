package org.circa.watchlink

import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * Finished workouts and the live workout state, from the Circa Exercise app's provider
 * (`content://org.circa.exercise.sync`, ContentResolver.call):
 *  - "list"  arg = last synced id -> Bundle String[] "ids" (ids after it, ascending)
 *  - "csv"   arg = id             -> Bundle String "csv"
 *  - "state"                      -> Bundle boolean "active", String "act" (RUNNING/WALKING/..., "" when idle)
 * A missing app, a refused call or any exception reads as "nothing": an empty list, no CSV, no workout.
 */
class ExerciseSource(context: Context) {
    private val app = context.applicationContext

    private fun call(method: String, arg: String?): android.os.Bundle? = try {
        app.contentResolver.call(URI, method, arg, null)
    } catch (e: Exception) {
        Log.i(TAG, "EXERCISE $method failed: " + e.javaClass.simpleName)
        null
    }

    fun idsAfter(lastId: String): List<String> =
        call("list", lastId)?.getStringArray("ids")?.filter { it.isNotBlank() } ?: emptyList()

    fun csv(id: String): String? = call("csv", id)?.getString("csv")

    /** The GB activity kind of the running workout, or null when none is recording. */
    fun activeKind(): String? {
        val b = call("state", null) ?: return null
        if (!b.getBoolean("active", false)) return null
        return b.getString("act")?.trim()?.takeIf { it.isNotEmpty() }
    }

    companion object {
        const val AUTHORITY = "org.circa.exercise.sync"
        val URI: Uri = Uri.parse("content://$AUTHORITY")
        private const val TAG = "WatchLink"
    }
}
