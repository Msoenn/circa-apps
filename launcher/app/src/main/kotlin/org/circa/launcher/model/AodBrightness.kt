package org.circa.launcher.model

/**
 * The always-on face's panel brightness, chosen in Circa Settings › Display › Always-on brightness
 * (`Settings.Secure circa_aod_brightness`, the same numbers as Settings' `AodBrightness`).
 *
 * [dozeBrightness] is what the doze dream hands `DreamService.setDozeScreenBrightness(float)`: a
 * value in the framework's 0..1 brightness range. The watch's own display config has a doze curve
 * (`doze_normal`) that runs from 0.0 in the dark up to 0.0428 in bright light; Low is its dark end
 * (and the framework's doze default, `config_screenBrightnessDoze` = 0), High its top, Normal between.
 */
enum class AodBrightness(val value: Int, val dozeBrightness: Float) {
    LOW(0, 0.0f),
    NORMAL(1, 0.02f),
    HIGH(2, 0.043f);

    companion object {
        const val SECURE_KEY = "circa_aod_brightness"

        /** The stored level; unset or unknown means [NORMAL]. */
        fun resolve(raw: String?): AodBrightness =
            raw?.trim()?.toIntOrNull()?.let { v -> entries.firstOrNull { it.value == v } } ?: NORMAL
    }
}
