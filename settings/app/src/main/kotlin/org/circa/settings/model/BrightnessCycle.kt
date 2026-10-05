package org.circa.settings.model

/**
 * The three brightness steps the quick-settings tile cycles through. Values are in
 * `Settings.System.SCREEN_BRIGHTNESS` units (0..255); the cycle is a pure function so the wrap
 * behaviour is unit-tested rather than eyeballed.
 */
object BrightnessCycle {

    val LEVELS: List<Int> = listOf(40, 130, 230)

    /** The top of the `SCREEN_BRIGHTNESS` range, for drawing a level as a fraction. */
    const val MAX = 255

    /**
     * The next step after [current]: the lowest level above it, or the lowest level overall when
     * [current] is at (or above) the top of the range - so repeated taps cycle.
     */
    fun next(current: Int): Int =
        LEVELS.firstOrNull { it > current } ?: LEVELS.first()

    /** The tray draws the brightness tile as "on" from the middle step up. */
    fun isActive(level: Int): Boolean = level >= LEVELS[1]
}
