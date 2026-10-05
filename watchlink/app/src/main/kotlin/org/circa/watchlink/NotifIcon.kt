package org.circa.watchlink

/**
 * Presentation helpers for a mirrored phone notification. Gadgetbridge's Bangle.js protocol sends only the app's
 * display name (`src`, e.g. "Gmail") - no package and no icon - so [WatchLinkService] draws the large icon itself
 * (a [color] circle with the app's [letter]) and posts [appName] as the notification's substituted app name. Pure
 * Kotlin (no Android, no R), so `HostTest` pins it on the JVM.
 */
internal object NotifIcon {

    /**
     * The app name to show on a mirrored notification in place of WatchLink's own label, or null when `src` is
     * unknown (then the shade keeps the posting app's name). SMS arrives as "SMS Message"; show "Messages".
     */
    @JvmStatic
    fun appName(src: String?): String? = if (src == "SMS Message") "Messages" else src

    /** Eight Material 500 colors that read well behind white text - the avatar circle's fixed palette. */
    private val PALETTE = intArrayOf(
        0xFFF44336.toInt(),   // red
        0xFFE91E63.toInt(),   // pink
        0xFF9C27B0.toInt(),   // purple
        0xFF3F51B5.toInt(),   // indigo
        0xFF2196F3.toInt(),   // blue
        0xFF009688.toInt(),   // teal
        0xFF4CAF50.toInt(),   // green
        0xFFFF9800.toInt(),   // orange
    )

    /** Palette size; [color] is deterministic per app, so two notifications from one app share an avatar. */
    @get:JvmStatic
    val paletteSize: Int get() = PALETTE.size

    /**
     * Deterministic palette index (0..[paletteSize]-1) for an app: FNV-1a over `src`, hashed as the empty string
     * when `src` is null. Stable across processes and devices, so an app's avatar never changes.
     */
    @JvmStatic
    fun paletteIndex(src: String?): Int {
        var h = 0x811C9DC5.toInt()
        for (c in (src ?: "")) h = (h xor c.code) * 16777619
        return Math.floorMod(h, PALETTE.size)
    }

    /** The avatar circle color (ARGB) for an app, picked from [PALETTE] by [paletteIndex]. */
    @JvmStatic
    fun color(src: String?): Int = PALETTE[paletteIndex(src)]

    /** The white capital drawn on the avatar circle: the first char of `src`, or '?' when it is unknown. */
    @JvmStatic
    fun letter(src: String?): Char {
        val s = src?.trim()
        return if (s.isNullOrEmpty()) '?' else s[0].uppercaseChar()
    }
}
