package org.circa.exercise.model

/**
 * The activities the app records. [gps] = the location provider is used (distance, pace, route); [gbName] is the
 * Bangle/Gadgetbridge activity name the sync provider reports while recording (exercise/README.md);
 * [met] is the fallback energy cost (kcal per kg per hour) for seconds without a heart rate; [speedNotPace]
 * shows km/h instead of min/km.
 */
enum class ActivityType(
    val id: String,
    val label: String,
    val noun: String,
    val gps: Boolean,
    val gbName: String,
    val met: Double,
    val speedNotPace: Boolean = false,
) {
    RUN("run", "Run", "run", true, "RUNNING", 9.8),
    WALK("walk", "Walk", "walk", true, "WALKING", 3.5),
    BIKE("bike", "Bike", "ride", true, "CYCLING", 7.5, speedNotPace = true),
    HIKE("hike", "Hike", "hike", true, "HIKING", 6.0),
    STRENGTH("strength", "Strength", "strength session", false, "EXERCISING", 5.0),
    // "Other" records time + HR only (design round 2, activity list option A).
    OTHER("other", "Other", "workout", false, "EXERCISING", 4.0),
    INDOOR_RUN("indoor_run", "Indoor run", "indoor run", false, "RUNNING", 9.0),
    SWIM("swim", "Swim", "swim", false, "SWIMMING", 7.0),
    YOGA("yoga", "Yoga", "yoga session", false, "EXERCISING", 2.5),
    HIIT("hiit", "HIIT", "HIIT session", false, "EXERCISING", 8.0),
    ELLIPTICAL("elliptical", "Elliptical", "elliptical session", false, "EXERCISING", 5.0),
    ROWING("rowing", "Rowing", "row", false, "EXERCISING", 7.0);

    companion object {
        /** The short list, in order (decisions.md "Exercise design final"). */
        val MAIN = listOf(RUN, WALK, BIKE, HIKE, STRENGTH, OTHER)
        /** Behind the "More" row. */
        val MORE = listOf(INDOOR_RUN, SWIM, YOGA, HIIT, ELLIPTICAL, ROWING)

        fun fromId(id: String?): ActivityType? = entries.firstOrNull { it.id == id }

        /** The list rows: the last used activity on top (if any), then the short list without it. */
        fun listOrder(last: ActivityType?): List<ActivityType> =
            if (last == null) MAIN else listOf(last) + MAIN.filter { it != last }
    }
}
