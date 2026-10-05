package org.circa.launcher.data

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

/**
 * The contract of the exercise app's state provider (apps/exercise, `data/StateProvider.kt`): the running workout, so
 * the face can show its small activity indicator. The authority is not ours; the exercise app enforces access by
 * calling package, so only installed system `org.circa.*` apps (this launcher) may read.
 */
object ExerciseContract {
    const val AUTHORITY = "org.circa.exercise.state"
    val URI: Uri = Uri.parse("content://$AUTHORITY")
    val CURRENT: Uri = Uri.withAppendedPath(URI, "current")

    const val COL_ACTIVITY = "activity"
    const val COL_STATE = "state"
    const val COL_STARTED_MS = "started_ms"

    const val STATE_IDLE = "idle"
}

/**
 * Reads the exercise app's state provider for the face's running indicator. The exercise service calls `notifyChange`
 * on every start / pause / resume / end, so a [ContentObserver] is enough - this repository never polls (unlike
 * [HealthRepository], which re-reads because WatchLink notifies only on changes).
 *
 * Everything degrades to "no indicator": the exercise app not installed, a `SecurityException`, a null cursor or a
 * provider error all leave [activity] null instead of throwing.
 *
 * Owned by `LauncherController`; [start] while the launcher is started, [stop] when it stops.
 */
class ExerciseStateRepository(private val context: Context) {

    private val resolver: ContentResolver = context.contentResolver
    private val handler = Handler(Looper.getMainLooper())
    private var started = false

    private val _activity: MutableState<String?> = mutableStateOf(null)

    /** The running workout's activity id (`run`, `bike`, ...), or null when nothing is recording. */
    val activity: State<String?> get() = _activity

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            if (started) refresh()
        }
    }

    /** Register the observer and read once. Idempotent; safe when the exercise app is not installed. */
    fun start() {
        if (started) return
        started = true
        runCatching { resolver.registerContentObserver(ExerciseContract.CURRENT, true, observer) }
        refresh()
    }

    /** Unregister. Idempotent; the launcher calls this from `onStop`. */
    fun stop() {
        if (!started) return
        started = false
        runCatching { resolver.unregisterContentObserver(observer) }
    }

    /** Read `/current` and publish the activity id. Never throws. */
    fun refresh() {
        _activity.value = read()
    }

    private fun read(): String? = try {
        resolver.query(ExerciseContract.CURRENT, PROJECTION, null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0)?.takeIf { it.isNotEmpty() } else null
        }
    } catch (e: Exception) {
        Log.d(TAG, "exercise state: /current not readable: $e")
        null
    }

    companion object {
        private const val TAG = "AuroraLauncher"
        private val PROJECTION = arrayOf(ExerciseContract.COL_ACTIVITY)
    }
}
