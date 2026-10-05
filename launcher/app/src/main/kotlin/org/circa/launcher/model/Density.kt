package org.circa.launcher.model

/**
 * Maps the physical pixel width of the display to a Compose density that renders the UI at a fixed
 * logical width (200dp), matching stock Wear sizing regardless of the underlying resolution.
 */
object Density {

    /** Logical width, in dp, that the whole UI is designed against. */
    const val TARGET_SCREEN_WIDTH_DP = 200f

    /** Density value that renders a [widthPx]-wide display as [TARGET_SCREEN_WIDTH_DP] dp wide. */
    fun densityFor(widthPx: Int): Float {
        require(widthPx > 0) { "widthPx must be positive" }
        return widthPx / TARGET_SCREEN_WIDTH_DP
    }
}
