package org.circa.exercise.model

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

enum class Sex { MALE, FEMALE }

/**
 * The user's profile, as Circa Settings writes it to Settings.Secure (`circa_profile_*`). Every field may be
 * unset; the effective values fall back to male / 30 years / 70 kg and max HR = 220 - age.
 */
data class Profile(
    val birthYear: Int? = null,
    val weightKg: Double? = null,
    val heightCm: Int? = null,
    val sex: Sex? = null,
    val maxHrOverride: Int? = null,
) {
    fun age(currentYear: Int): Int = birthYear?.let { (currentYear - it).coerceIn(10, 100) } ?: DEFAULT_AGE
    val weight: Double get() = weightKg?.takeIf { it in 20.0..300.0 } ?: DEFAULT_WEIGHT
    val effectiveSex: Sex get() = sex ?: Sex.MALE
    fun maxHr(currentYear: Int): Int = maxHrOverride?.takeIf { it in 100..240 } ?: (220 - age(currentYear))

    companion object {
        const val DEFAULT_AGE = 30
        const val DEFAULT_WEIGHT = 70.0

        /** From the raw Settings.Secure strings (null = absent). 0 / blank / garbage = unset. */
        fun parse(birthYear: String?, weightKg: String?, heightCm: String?, sex: String?, maxHr: String?): Profile =
            Profile(
                birthYear = birthYear?.trim()?.toIntOrNull()?.takeIf { it in 1900..2100 },
                weightKg = weightKg?.trim()?.toDoubleOrNull()?.takeIf { it > 0 },
                heightCm = heightCm?.trim()?.toIntOrNull()?.takeIf { it > 0 },
                sex = when (sex?.trim()?.lowercase()) { "male" -> Sex.MALE; "female" -> Sex.FEMALE; else -> null },
                maxHrOverride = maxHr?.trim()?.toIntOrNull()?.takeIf { it > 0 },
            )
    }
}

/** Five heart-rate zones as a share of max HR: Z1 50-60 %, Z2 60-70, Z3 70-80, Z4 80-90, Z5 >= 90. */
object Zones {
    const val COUNT = 5
    private val LOWER = doubleArrayOf(0.5, 0.6, 0.7, 0.8, 0.9)

    /** 0 = below zone 1 (or no HR), else 1..5. */
    fun zoneOf(hr: Int?, maxHr: Int): Int {
        if (hr == null || hr <= 0 || maxHr <= 0) return 0
        val f = hr.toDouble() / maxHr
        var z = 0
        for (i in LOWER.indices) if (f >= LOWER[i]) z = i + 1
        return z
    }

    /** Lower bound in bpm of zone [z] (1..5). */
    fun lowerBpm(z: Int, maxHr: Int): Int = Math.round(LOWER[z - 1] * maxHr).toInt()

    /**
     * Position of [hr] on the zone scale, 0..1: 0 = the bottom of zone 1 (50 % max), 1 = max HR. Each zone is a fifth
     * of the scale, which is exactly how the inner ring draws its five bands.
     */
    fun scale(hr: Int?, maxHr: Int): Float {
        if (hr == null || hr <= 0 || maxHr <= 0) return 0f
        return ((hr.toDouble() / maxHr - 0.5) / 0.5).coerceIn(0.0, 1.0).toFloat()
    }
}

/** Energy. */
object Calories {
    /**
     * Keytel et al. (2005) energy expenditure from heart rate, kcal per minute, never negative.
     * male:   (-55.0969 + 0.6309 HR + 0.1988 W + 0.2017 A) / 4.184
     * female: (-20.4022 + 0.4472 HR - 0.1263 W + 0.074 A) / 4.184
     */
    fun keytelPerMinute(hr: Int, weightKg: Double, age: Int, sex: Sex): Double {
        val kj = when (sex) {
            Sex.MALE -> -55.0969 + 0.6309 * hr + 0.1988 * weightKg + 0.2017 * age
            Sex.FEMALE -> -20.4022 + 0.4472 * hr - 0.1263 * weightKg + 0.074 * age
        }
        return max(0.0, kj / 4.184)
    }

    /** MET fallback for seconds without a heart rate: kcal = MET x kg x hours. */
    fun metPerSecond(met: Double, weightKg: Double): Double = met * weightKg / 3600.0
}

/** Distance and pace. */
object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle distance in metres (haversine). */
    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Seconds per km, or null when there is too little distance to say (< 10 m). */
    fun paceSecPerKm(distanceM: Double, seconds: Double): Double? =
        if (distanceM < 10 || seconds <= 0) null else seconds / (distanceM / 1000.0)

    /** km/h, or null with too little data. */
    fun speedKmh(distanceM: Double, seconds: Double): Double? =
        if (distanceM < 10 || seconds <= 0) null else distanceM / seconds * 3.6
}

/** Display formatting (ASCII digits, no locale grouping, so values never wrap or change width oddly). */
object Fmt {
    /** 0:07, 24:13, 1:02:03. */
    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(java.util.Locale.ROOT, "%d:%02d", m, sec)
    }

    /** 3.21 (km, two decimals). */
    fun km(m: Double): String = String.format(java.util.Locale.ROOT, "%.2f", m / 1000.0)

    /** 5:12 (min:sec per km), or "--". */
    fun pace(secPerKm: Double?): String {
        if (secPerKm == null || secPerKm.isNaN() || secPerKm > 99 * 60) return "--"
        val s = Math.round(secPerKm)
        return String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    fun speed(kmh: Double?): String = if (kmh == null) "--" else String.format(java.util.Locale.ROOT, "%.1f", kmh)

    /** 6:12 style minutes:seconds for zone time; hours as 1:02:03. */
    fun zoneTime(ms: Long): String = duration(ms)
}
