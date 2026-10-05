package org.circa.clock.model

/**
 * Circa's accent colour (the launcher's five). Stored system-wide as an ARGB int in
 * `Settings.Secure circa_accent_color` ([SharedKeys.ACCENT]), which the launcher and the Circa shade
 * both read; this app themes itself with it too.
 */
enum class Accent(val id: String, val label: String, val argb: Long) {
    LAVENDER("lavender", "Lavender", 0xFFC5CBFF),
    BLUE("blue", "Blue", 0xFF8AB4F8),
    GREEN("green", "Green", 0xFF81C995),
    AMBER("amber", "Amber", 0xFFFDD663),
    MONO("mono", "Mono", 0xFFE8EAED);

    companion object {
        val DEFAULT = BLUE

        /** The accent stored under [id]; an unknown or missing id falls back to [DEFAULT]. */
        fun fromId(id: String?): Accent = entries.firstOrNull { it.id == id } ?: DEFAULT

        /** The accent whose colour is [argb] (as stored in `Settings.Secure circa_accent_color`), or null. */
        fun fromArgb(argb: Int?): Accent? = entries.firstOrNull { argb != null && it.argb.toInt() == argb }
    }
}
