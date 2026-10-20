package org.circa.settings.model

/**
 * WatchLink's heart-rate alerts and phone-left-behind alert (settings/README.md). Pure: the Settings keys,
 * their defaults, the option tables and the labels are unit tested in `ProfileModelTest`;
 * `data/SystemSettings` reads and writes the keys, WatchLink reads them.
 */
object HealthKeys {
    /** Settings.Secure, int bpm; absent = 120, 0 = off. */
    const val HR_HIGH_BPM = "circa_hr_high_bpm"

    /** Settings.Secure, int bpm; absent = 40, 0 = off. */
    const val HR_LOW_BPM = "circa_hr_low_bpm"

    /** Settings.Secure, int; 0 = off, anything else or absent = on (default). */
    const val PHONE_LEFT_ALERT = "circa_phone_left_alert"
}

object HeartAlertSettings {
    const val HIGH_DEFAULT = 120
    const val LOW_DEFAULT = 40

    /** The high-threshold radio page: off, then 100..150 bpm in steps of 10. */
    @JvmField
    val HIGH_OPTIONS = listOf(0, 100, 110, 120, 130, 140, 150)

    /** The low-threshold radio page: off, then 35..50 bpm in steps of 5. */
    @JvmField
    val LOW_OPTIONS = listOf(0, 35, 40, 45, 50)

    /** `circa_hr_high_bpm`: absent or unreadable = [HIGH_DEFAULT], 0 = off. */
    fun high(raw: String?): Int = parse(raw, HIGH_DEFAULT)

    /** `circa_hr_low_bpm`: absent or unreadable = [LOW_DEFAULT], 0 = off. */
    fun low(raw: String?): Int = parse(raw, LOW_DEFAULT)

    private fun parse(raw: String?, fallback: Int): Int =
        raw?.trim()?.toIntOrNull()?.takeIf { it >= 0 } ?: fallback

    /** One radio row: "Off" or "120 bpm". */
    fun option(bpm: Int): String = if (bpm == 0) "Off" else "$bpm bpm"

    /** The Profile page's secondary line: "High 120 · Low 40", with each side "off" when disabled. */
    fun summary(high: Int, low: Int): String = when {
        high == 0 && low == 0 -> "Off"
        high == 0 -> "High off · Low $low"
        low == 0 -> "High $high · Low off"
        else -> "High $high · Low $low"
    }
}

object PhoneLeftAlertSetting {
    /** `circa_phone_left_alert`: only an explicit 0 turns the alert off. */
    fun on(raw: String?): Boolean = raw?.trim() != "0"
}
