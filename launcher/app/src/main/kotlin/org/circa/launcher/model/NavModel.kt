package org.circa.launcher.model

/**
 * One `/nav` row from WatchLink's phone-data provider, reduced to what the face's navigation view needs
 * (watchlink/DATA-CONTRACT.md). [distance] is Maps' own display string, in the phone's locale and units;
 * [distanceM] is its parsed metres (-1 when WatchLink could not read it). [action] is null when Maps
 * reports none.
 */
data class NavState(
    val updatedMs: Long,
    val instruction: String?,
    val distance: String,
    val distanceM: Long,
    val action: String?,
    val eta: String?,
)

/** The Material Symbols glyph for one maneuver; the face maps each to its ImageVector. */
enum class NavGlyph {
    STRAIGHT, TURN_LEFT, TURN_RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, SHARP_LEFT, SHARP_RIGHT,
    FORK_LEFT, FORK_RIGHT, U_TURN_LEFT, U_TURN_RIGHT, ROUNDABOUT_LEFT, ROUNDABOUT_RIGHT,
    WRONG_LOCATION, FINISH, NAVIGATION,
}

/** What the face's navigation view draws while a route is active. [etaText] is null without an ETA. */
data class NavView(val glyph: NavGlyph, val distance: String, val instruction: String?, val etaText: String?)

/**
 * Selection and formatting rules for the face's navigation view. Pure and JVM-testable: no Android types.
 * The action names are Gadgetbridge's, from `BangleJSDeviceSupport.onSetNavigationInfo`'s `navActions` list.
 */
object NavModel {
    /**
     * The glyph for [action]. An unknown or missing one still means a route, so it falls back to the plain
     * navigation arrow rather than showing nothing.
     */
    fun glyph(action: String?): NavGlyph = when (action) {
        "continue" -> NavGlyph.STRAIGHT
        "left" -> NavGlyph.TURN_LEFT
        "right" -> NavGlyph.TURN_RIGHT
        "left_slight" -> NavGlyph.SLIGHT_LEFT
        "right_slight" -> NavGlyph.SLIGHT_RIGHT
        "left_sharp" -> NavGlyph.SHARP_LEFT
        "right_sharp" -> NavGlyph.SHARP_RIGHT
        "keep_left" -> NavGlyph.FORK_LEFT
        "keep_right" -> NavGlyph.FORK_RIGHT
        "uturn_left" -> NavGlyph.U_TURN_LEFT
        "uturn_right" -> NavGlyph.U_TURN_RIGHT
        "roundabout_left" -> NavGlyph.ROUNDABOUT_LEFT
        // Maps draws the straight-through and the U-turn roundabout exit as the right-hand glyph too.
        "roundabout_right", "roundabout_straight", "roundabout_uturn" -> NavGlyph.ROUNDABOUT_RIGHT
        "offroute" -> NavGlyph.WRONG_LOCATION
        "finish" -> NavGlyph.FINISH
        else -> NavGlyph.NAVIGATION
    }

    /** The navigation view for [nav], or null when there is no route (WatchLink drops a stale one). */
    fun view(nav: NavState?): NavView? {
        if (nav == null) return null
        return NavView(
            glyph = glyph(nav.action),
            distance = nav.distance.trim(),
            instruction = nav.instruction?.trim()?.takeIf { it.isNotEmpty() },
            etaText = nav.eta?.trim()?.takeIf { it.isNotEmpty() }?.let { "Arrive $it" },
        )
    }
}
