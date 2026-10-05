package org.circa.launcher.model

/**
 * The launcher's accent colour: the one colour that drives active toggles, notification titles,
 * complication rings, the highlighted pill and the sparkline (v1.4). Chosen in the face picker and
 * persisted by [LauncherSettings]; the UI reads it from `MaterialTheme.colorScheme.primary`, which
 * `ui/Theme.kt` builds from the selected value, so nothing else hard-codes a highlight colour.
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

        /** The accent whose colour is [argb] (`Settings.Secure circa_accent_color`), or null. */
        fun fromArgb(argb: Int?): Accent? = entries.firstOrNull { argb != null && it.argb.toInt() == argb }
    }
}

/** The switchable watch faces, in the order the face picker shows them. */
enum class FaceStyle(val id: String, val label: String) {
    /** Mockup B: big light digital time, date, three ring complications. The primary face. */
    DIGITAL("digital", "Big digital"),

    /** Mockup A: stock-like concentric minute rings, hour, minutes in an outlined pill. */
    CONCENTRIC("concentric", "Concentric"),

    /** Mockup D: analog hands and markers with two small complications. */
    ANALOG("analog", "Analog");

    companion object {
        val DEFAULT = DIGITAL

        fun fromId(id: String?): FaceStyle = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
