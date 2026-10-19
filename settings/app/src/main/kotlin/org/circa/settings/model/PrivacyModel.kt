package org.circa.settings.model

/**
 * The privacy and security keys the framework reads (settings/README.md): auto restart, USB data
 * mode, Wi-Fi auto-off, the internet check and the scrambled PIN pad, plus the one-shot migration
 * that gives existing watches the same defaults. Pure: parsing, labels, option lists and the
 * migration plan are unit tested in `PrivacyModelTest`; `data/SystemSettings` reads and writes the
 * keys. This app only stores the choices; the system applies them.
 */

/** Settings.Global `circa_auto_reboot_ms`: restart after this long; unset = 18 h, 0 = off. */
object AutoReboot {
    const val KEY = "circa_auto_reboot_ms"
    private const val HOUR_MS = 3_600_000L
    const val DEFAULT_MS = 18 * HOUR_MS

    val OPTIONS: List<Long> = listOf(0L, 8, 12, 18, 24, 36, 72).map { it * HOUR_MS }

    fun parse(raw: String?): Long = raw?.trim()?.toLongOrNull()?.takeIf { it >= 0L } ?: DEFAULT_MS

    /** The radio row: "Off", "8 hours". */
    fun option(ms: Long): String = if (ms <= 0L) "Off" else "${ms / HOUR_MS} hours"

    /** The Security row's summary: "After 18 hours" or "Off". */
    fun summary(ms: Long): String = if (ms <= 0L) "Off" else "After ${ms / HOUR_MS} hours"

    /** The row needs a PIN: a restart re-locks the watch, which only matters with one. */
    fun enabled(deviceSecure: Boolean) = deviceSecure

    fun secondary(deviceSecure: Boolean, ms: Long): String = if (!deviceSecure) "Needs a PIN" else summary(ms)
}

/** Settings.Global `circa_usb_data`: what the USB port may do. Unset or "1" = on, "0" = charging only, "2" = only while unlocked. */
enum class UsbDataMode(val value: String, val label: String, val option: String, val hint: String?) {
    ON("1", "On", "On", null),
    CHARGING_WHEN_LOCKED("2", "Charging only when locked", "Charging only when locked", null),
    CHARGING_ONLY("0", "Charging only", "Charging only", "USB debugging off; use Wi-Fi");

    companion object {
        val DEFAULT = ON

        fun parse(raw: String?): UsbDataMode = entries.firstOrNull { it.value == raw?.trim() } ?: DEFAULT
    }
}

/** Settings.Global `circa_wifi_auto_off_ms`: turn Wi-Fi off after this long unused; unset = 15 min, 0 = off. */
object WifiAutoOff {
    const val KEY = "circa_wifi_auto_off_ms"
    private const val MIN_MS = 60_000L
    const val DEFAULT_MS = 15 * MIN_MS

    val OPTIONS: List<Long> = listOf(0L, 5, 15, 30, 60).map { it * MIN_MS }

    fun parse(raw: String?): Long = raw?.trim()?.toLongOrNull()?.takeIf { it >= 0L } ?: DEFAULT_MS

    fun option(ms: Long): String = if (ms <= 0L) "Off" else "${ms / MIN_MS} min"

    fun summary(ms: Long): String = if (ms <= 0L) "Off" else "After ${ms / MIN_MS} min"
}

/** Settings.Global `captive_portal_mode`: 0 = off, 1 = standard. The Circa default is off, so unset reads as off. */
enum class InternetCheck(val value: Int, val label: String, val hint: String?) {
    OFF(0, "Off", "Hotel sign-in pages won't be detected"),
    STANDARD(1, "Standard (Google)", null);

    companion object {
        const val KEY = "captive_portal_mode"
        val DEFAULT = OFF

        /** Only an explicit 1 is standard; unset and the "avoid" value 2 show as off. */
        fun parse(raw: String?): InternetCheck = if (raw?.trim() == "1") STANDARD else OFF
    }
}

/**
 * Settings.Global `non_persistent_mac_randomization_force_enabled`: 1 = a new address per network.
 * Unset and every other value read as off; the Circa default is 1 (first-boot defaults and [PrivacyMigration]).
 */
object WifiMacRandomization {
    const val KEY = "non_persistent_mac_randomization_force_enabled"

    fun parse(raw: String?): Boolean = raw?.trim() == "1"
    fun encode(on: Boolean): String = if (on) "1" else "0"
}

/**
 * Settings.Global `wifi_scan_always_enabled` and `ble_scan_always_enabled`: background scanning is on
 * only while both are 1, and writing either state writes both keys. Unset reads as off.
 */
object BackgroundScanning {
    const val WIFI_KEY = "wifi_scan_always_enabled"
    const val BLE_KEY = "ble_scan_always_enabled"

    fun parse(wifi: String?, ble: String?): Boolean = wifi?.trim() == "1" && ble?.trim() == "1"
    fun encode(on: Boolean): String = if (on) "1" else "0"
}

/** LineageSettings.System `lockscreen_scramble_pin_layout`, 0/1, default 0. */
object ScramblePinPad {
    const val KEY = "lockscreen_scramble_pin_layout"

    fun parse(raw: String?): Boolean = raw?.trim() == "1"
    fun encode(on: Boolean): String = if (on) "1" else "0"

    fun enabled(deviceSecure: Boolean) = deviceSecure
    fun checked(deviceSecure: Boolean, on: Boolean) = deviceSecure && on
    const val SECONDARY = "Digits move each time"
}

/**
 * The one-shot privacy migration for watches that predate these defaults, guarded by
 * Settings.Global [FLAG]. Keys that are only written when unset keep a user's own choice; the keys in
 * [OVERWRITE_DEFAULT] are also rewritten while still at the platform default of 1.
 */
object PrivacyMigration {
    const val FLAG = "circa_privacy_defaults_v1"

    private const val CAPTIVE_PORTAL = InternetCheck.KEY
    private const val MAC_RANDOMIZATION = WifiMacRandomization.KEY
    private const val BLE_SCAN = BackgroundScanning.BLE_KEY
    private const val WIFI_SCAN = BackgroundScanning.WIFI_KEY
    private const val WIFI_WAKEUP = "wifi_wakeup_enabled"

    /** Written when unset or still the platform default (1). */
    private val OVERWRITE_DEFAULT: List<Pair<String, String>> =
        listOf(CAPTIVE_PORTAL to "0", BLE_SCAN to "0", WIFI_WAKEUP to "0")

    /** Written only when unset. */
    private val ONLY_UNSET: List<Pair<String, String>> =
        listOf(MAC_RANDOMIZATION to "1", WIFI_SCAN to "0")

    /**
     * The Settings.Global writes to make given [flag] and the current values from [current]
     * (name -> raw value, null = unset). Empty once the flag is set; the caller then also writes
     * [FLAG] = 1 (whenever [flag] is null, even if no key needed a write).
     */
    fun plan(flag: String?, current: (String) -> String?): List<Pair<String, String>> {
        if (flag != null) return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        for ((k, v) in OVERWRITE_DEFAULT) {
            val cur = current(k)?.trim()
            if (cur == null || cur == "1") out += k to v
        }
        for ((k, v) in ONLY_UNSET) if (current(k) == null) out += k to v
        return out
    }
}
