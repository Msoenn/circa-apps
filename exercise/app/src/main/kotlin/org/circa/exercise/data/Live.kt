package org.circa.exercise.data

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.circa.exercise.model.ActivityType
import org.circa.exercise.model.Phase
import org.circa.exercise.model.Profile
import org.circa.exercise.model.Summary
import org.circa.exercise.model.WorkoutState
import org.circa.exercise.model.Zones

/** GPS indicator: blinking while searching, solid green for 3 s after the first fix, then hidden. */
enum class GpsState { NONE, SEARCHING, FIXED_NEW, FIXED }

/** An immutable view of the running workout for the UI (published once a second and on every change). */
data class LiveView(
    val type: ActivityType,
    val phase: Phase,
    val activeMs: Long,
    val hr: Int?,
    val zone: Int,
    val maxHr: Int,
    val distanceM: Double,
    val paceSecPerKm: Double?,
    val speedKmh: Double?,
    val kcal: Double,
    val steps: Long,
    val zoneMs: List<Long>,
    val gps: GpsState,
    /** Identifies the workout (its start time): the UI starts each new workout on the first page. */
    val startMs: Long = 0L,
    /** An auto-detected walk/run, and whether it still waits for Keep / Discard. */
    val auto: Boolean = false,
    val autoPending: Boolean = false,
) {
    val hrScale: Float get() = Zones.scale(hr, maxHr)
}

/**
 * Process-wide state shared by the service, the activities and the sync provider. The service is the only writer of
 * [view]; [pending] is the finished workout waiting for "Done" on the summary screen.
 */
object Live {
    val view = MutableStateFlow<LiveView?>(null)
    val pending = MutableStateFlow<Summary?>(null)

    /** Emulator-only fake heart rate (DebugReceiver); null = off. */
    @Volatile var fakeHr: Int? = null

    @Volatile private var loaded = false

    /** Load what is on disk once per process (a finished-but-unsaved summary; a running session is the service's). */
    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val st = Storage(ctx)
            pending.value = st.loadPending()
            loaded = true
        }
    }

    /** True while a workout is recording or paused (also before the service restored it after a process death). */
    fun isRecording(ctx: Context): Boolean {
        view.value?.let { return it.phase != Phase.FINISHED }
        return Storage(ctx).loadActive()?.isActive == true
    }

    /** The active workout's type (recording or paused), or null. */
    fun activeType(ctx: Context): ActivityType? {
        view.value?.let { return if (it.phase != Phase.FINISHED) it.type else null }
        return Storage(ctx).loadActive()?.takeIf { it.isActive }?.type
    }

    /**
     * The running workout for the launcher's state provider ([StateProvider]): the workout in memory, else the
     * snapshot on disk (a service that has not been restored yet), else idle. Never throws.
     */
    fun state(ctx: Context): WorkoutState {
        view.value?.takeIf { it.phase != Phase.FINISHED }?.let { return WorkoutState.of(it.type.id, it.phase, it.startMs) }
        val w = Storage(ctx).loadActive()?.takeIf { it.isActive }
        return if (w != null) WorkoutState.of(w.type.id, w.phase, w.startMs) else WorkoutState.IDLE_STATE
    }

    fun profile(ctx: Context): Profile {
        val cr = ctx.contentResolver
        fun s(k: String) = runCatching { Settings.Secure.getString(cr, k) }.getOrNull()
        return Profile.parse(
            s("circa_profile_birth_year"), s("circa_profile_weight_kg"), s("circa_profile_height_cm"),
            s("circa_profile_sex"), s("circa_profile_max_hr"),
        )
    }
}
