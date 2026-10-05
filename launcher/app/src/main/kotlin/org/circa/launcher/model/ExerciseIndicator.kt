package org.circa.launcher.model

/** The face indicator's glyph, one per group of activities (the exercise app's `ActivityType.id`). */
enum class ExerciseGlyph { RUN, WALK, BIKE, HIKE, STRENGTH, OTHER }

/**
 * The small running-workout indicator on the face (apps/exercise is recording or paused). Maps the activity id the
 * exercise state provider reports to the Material Symbols glyph to draw, pure so the mapping is host-tested.
 */
object ExerciseIndicator {
    /**
     * The glyph for [activity]: the specific sport where there is one (`run`/`indoor_run`, `walk`, `bike`, `hike`,
     * `strength`), the generic `exercise` for every other activity. Null for a missing/empty id (nothing recording).
     */
    fun glyph(activity: String?): ExerciseGlyph? = when (activity) {
        null, "" -> null
        "run", "indoor_run" -> ExerciseGlyph.RUN
        "walk" -> ExerciseGlyph.WALK
        "bike" -> ExerciseGlyph.BIKE
        "hike" -> ExerciseGlyph.HIKE
        "strength" -> ExerciseGlyph.STRENGTH
        else -> ExerciseGlyph.OTHER
    }
}
