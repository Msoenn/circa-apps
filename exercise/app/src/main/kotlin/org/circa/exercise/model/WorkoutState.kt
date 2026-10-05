package org.circa.exercise.model

/**
 * The running workout as the launcher's state provider exposes it (`data/StateProvider.kt`,
 * `content://org.circa.exercise.state/current`): the activity id ([ActivityType.id]), whether the workout is
 * recording or paused, and its wall-clock start. Idle (nothing recording) is the empty row: no activity, state
 * [IDLE], start 0.
 */
data class WorkoutState(
    val activity: String,
    val state: String,
    val startedMs: Long,
) {
    companion object {
        const val RECORDING = "recording"
        const val PAUSED = "paused"
        const val IDLE = "idle"

        val IDLE_STATE = WorkoutState("", IDLE, 0L)

        fun of(activityId: String?, phase: Phase, startedMs: Long): WorkoutState = when (phase) {
            Phase.RECORDING -> WorkoutState(activityId ?: "", RECORDING, startedMs)
            Phase.PAUSED -> WorkoutState(activityId ?: "", PAUSED, startedMs)
            Phase.FINISHED -> IDLE_STATE
        }
    }
}
