package org.circa.launcher.model

/**
 * The ambient (AOD) face's pure rules: how the whole drawing moves. The face itself is drawn by
 * `AmbientFaceView` on a `Canvas`; what is testable on the JVM and what the AOD's burn-in
 * protection is defined by lives here.
 *
 * Stock's rule is that a face keeps its content in a fixed
 * layout while the *system* shifts the panel, or - when the panel cannot - the face shifts its own
 * drawing. The emulator panel and the watch's AMOLED both stay in place, and this dream draws
 * everything itself, so the shift is ours: the whole face moves a few pixels once a minute inside a
 * small box, which is what stops one set of pixels from being lit for hours.
 */
object AmbientFace {

    /** Burn-in: the whole drawing stays within +/- this many px of the centred position. */
    const val BURN_IN_RADIUS_PX = 4

    /**
     * One offset per minute of the burn-in cycle: a ring inside the +/-[BURN_IN_RADIUS_PX] box. The
     * cycle is eight minutes long, consecutive minutes never share an offset, and the offset only
     * changes once a minute - an ambient face must not animate.
     */
    private val BURN_IN_CYCLE: List<Pair<Int, Int>> = listOf(
        -4 to -4,
        0 to -4,
        4 to -4,
        4 to 0,
        4 to 4,
        0 to 4,
        -4 to 4,
        -4 to 0,
    )

    /** Minutes in one full burn-in cycle; the offset repeats after this many minutes. */
    val BURN_IN_CYCLE_MINUTES: Int get() = BURN_IN_CYCLE.size

    /**
     * Index into the burn-in cycle for the minute that contains [epochMillis]. `floorDiv`/`floorMod`
     * rather than `/`/`%`: a clock set before 1970 must still land on a valid index.
     */
    fun burnInIndex(epochMillis: Long): Int =
        Math.floorMod(Math.floorDiv(epochMillis, 60_000L), BURN_IN_CYCLE.size.toLong()).toInt()

    /**
     * The (dx, dy) the whole face is drawn at in the minute containing [epochMillis]. Both
     * components are within +/-[BURN_IN_RADIUS_PX], and the value changes exactly once a minute.
     */
    fun burnInOffset(epochMillis: Long): Pair<Int, Int> = BURN_IN_CYCLE[burnInIndex(epochMillis)]
}
