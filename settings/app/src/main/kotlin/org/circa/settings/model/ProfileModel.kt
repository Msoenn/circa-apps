package org.circa.settings.model

import java.util.Locale
import kotlin.math.roundToInt

/**
 * The user profile the Exercise app reads (calories, heart-rate zones) and the side-button long-press choice,
 * shared through Settings (settings/README.md). Pure: parsing, labels, value ranges and
 * stepping are unit tested in `ProfileModelTest`; `data/SystemSettings` reads and writes the keys.
 *
 * Gadgetbridge does not send a user profile to a Bangle.js, so everything here is entered on the watch.
 */
object ProfileKeys {
    /** Settings.Secure, int; 0 or absent = not set. */
    const val BIRTH_YEAR = "circa_profile_birth_year"

    /** Settings.Secure, decimal string in kg ("72.5"); absent = not set. */
    const val WEIGHT_KG = "circa_profile_weight_kg"

    /** Settings.Secure, int cm; 0 or absent = not set. */
    const val HEIGHT_CM = "circa_profile_height_cm"

    /** Settings.Secure, "male" / "female"; absent = not set. */
    const val SEX = "circa_profile_sex"

    /** Settings.Secure, int bpm; 0 or absent = automatic (estimated from age by the Exercise app). */
    const val MAX_HR = "circa_profile_max_hr"

    /** Settings.Global, "list" | "last" | "power"; absent = "list". Read by the framework and the Exercise app. */
    const val LONG_PRESS = "circa_exercise_long_press"
}

enum class Sex(val value: String, val label: String) {
    MALE("male", "Male"),
    FEMALE("female", "Female");

    companion object {
        fun fromRaw(raw: String?): Sex? = entries.firstOrNull { it.value == raw?.trim()?.lowercase(Locale.ROOT) }
        fun label(sex: Sex?): String = sex?.label ?: "Not set"
    }
}

/** What a side-button long press does (decisions.md "Exercise design final"). */
/**
 * [label]: the Buttons row's summary; [option]: the radio row (short: one line on the 200 dp circle, emulator
 * 2026-10-04); [hint]: the radio row's second line.
 */
enum class LongPressAction(val value: String, val label: String, val option: String, val hint: String) {
    LIST("list", "Exercise list", "Exercise list", "Pick an exercise"),
    LAST("last", "Start last exercise", "Start last", "3 s countdown"),
    POWER("power", "Power menu", "Power menu", "Restart, power off");

    companion object {
        val DEFAULT = LIST
        fun fromRaw(raw: String?): LongPressAction =
            entries.firstOrNull { it.value == raw?.trim()?.lowercase(Locale.ROOT) } ?: DEFAULT
    }
}

/** The stored profile; null = not set (max HR null = automatic). */
data class Profile(
    val birthYear: Int? = null,
    val weightKg: Double? = null,
    val heightCm: Int? = null,
    val sex: Sex? = null,
    val maxHr: Int? = null,
)

/**
 * The numeric profile fields, edited on a value page (crown or -/+, then the check button saves). Values are
 * kept as Double so weight can step by 0.5 kg; the others are whole numbers.
 */
enum class ProfileField(
    val id: String,
    val label: String,
    val unit: String,
    val min: Double,
    val max: Double,
    val step: Double,
) {
    BIRTH_YEAR("birth_year", "Birth year", "", 1920.0, 2100.0, 1.0),
    WEIGHT("weight", "Weight", "kg", 30.0, 250.0, 0.5),
    HEIGHT("height", "Height", "cm", 100.0, 230.0, 1.0),
    MAX_HR("max_hr", "Max heart rate", "bpm", 100.0, 230.0, 1.0),
}

object ProfileModel {
    /** Youngest allowed age for the birth-year picker. */
    private const val MIN_AGE = 5

    fun parse(
        birthYear: String?,
        weightKg: String?,
        heightCm: String?,
        sex: String?,
        maxHr: String?,
    ): Profile = Profile(
        birthYear = birthYear?.trim()?.toIntOrNull()?.takeIf { it > 0 },
        weightKg = weightKg?.trim()?.toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() },
        heightCm = heightCm?.trim()?.toIntOrNull()?.takeIf { it > 0 },
        sex = Sex.fromRaw(sex),
        maxHr = maxHr?.trim()?.toIntOrNull()?.takeIf { it > 0 },
    )

    /** Upper bound of [field] in [currentYear] (birth year: nobody younger than [MIN_AGE]). */
    fun max(field: ProfileField, currentYear: Int): Double =
        if (field == ProfileField.BIRTH_YEAR) (currentYear - MIN_AGE).toDouble() else field.max

    fun clamp(field: ProfileField, value: Double, currentYear: Int): Double =
        snap(field, value.coerceIn(field.min, max(field, currentYear)))

    /** [clicks] steps (sign = direction) from [value], clamped to the field's range. */
    fun adjust(field: ProfileField, value: Double, clicks: Int, currentYear: Int): Double =
        clamp(field, value + clicks * field.step, currentYear)

    private fun snap(field: ProfileField, value: Double): Double = (value / field.step).roundToInt() * field.step

    /** The stored value of [field], or null when not set. */
    fun valueOf(field: ProfileField, p: Profile): Double? = when (field) {
        ProfileField.BIRTH_YEAR -> p.birthYear?.toDouble()
        ProfileField.WEIGHT -> p.weightKg
        ProfileField.HEIGHT -> p.heightCm?.toDouble()
        ProfileField.MAX_HR -> p.maxHr?.toDouble()
    }

    /**
     * Where the value page starts when [field] is not set yet: a typical value, or for max HR the classic
     * 220 - age estimate (only a starting point for an override; the Exercise app computes its own automatic value).
     */
    fun startValue(field: ProfileField, p: Profile, currentYear: Int): Double {
        valueOf(field, p)?.let { return clamp(field, it, currentYear) }
        val v = when (field) {
            ProfileField.BIRTH_YEAR -> 1990.0
            ProfileField.WEIGHT -> 70.0
            ProfileField.HEIGHT -> 170.0
            ProfileField.MAX_HR -> p.birthYear?.let { 220.0 - (currentYear - it) } ?: 190.0
        }
        return clamp(field, v, currentYear)
    }

    /** The number as the value page and the list rows show it (weight with one decimal, the rest whole). */
    fun formatNumber(field: ProfileField, value: Double): String =
        if (field == ProfileField.WEIGHT) String.format(Locale.ROOT, "%.1f", value) else value.roundToInt().toString()

    /** The secondary line of [field]'s row on the Profile page. */
    fun rowLabel(field: ProfileField, p: Profile): String {
        val v = valueOf(field, p)
        return when {
            v == null && field == ProfileField.MAX_HR -> "Auto"
            v == null -> "Not set"
            field.unit.isEmpty() -> formatNumber(field, v)
            else -> "${formatNumber(field, v)} ${field.unit}"
        }
    }

    /** What is written to Settings.Secure for [field] (weight: decimal string; the others: whole numbers). */
    fun encode(field: ProfileField, value: Double): String = formatNumber(field, value)
}
