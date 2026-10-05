package org.circa.settings.model

/**
 * The pure parts of the Settings experience (settings/README.md): option tables, labels, the
 * gesture-key availability model and the page tree. Nothing here touches Android, so it is unit
 * tested in `SettingsModelTest`; `data/SystemSettings` applies it to the real platform.
 */

/** One screen of the Settings flow; [parent] is where swipe-right / BACK goes (null = leave Settings). */
enum class SettingsPage(val id: String, val title: String, val parent: SettingsPage?) {
    MAIN("main", "Settings", null),
    CONNECTIVITY("connectivity", "Connectivity", MAIN),
    DISPLAY("display", "Display", MAIN),
    BRIGHTNESS("brightness", "Brightness", DISPLAY),
    TIMEOUT("timeout", "Screen timeout", DISPLAY),
    ACCENT("accent", "Accent colour", DISPLAY),
    GESTURES("gestures", "Gestures", MAIN),
    TILT_WAKE("tilt_wake", "Tilt-to-wake", GESTURES),
    SOUND("sound", "Sound & vibration", MAIN),
    APPS("apps", "Apps & notifications", MAIN),
    LOCATION("location", "Location", MAIN),
    SECURITY("security", "Security", MAIN),
    LOCK_TIMEOUT("lock_timeout", "Lock after", SECURITY),
    BATTERY("battery", "Battery", MAIN),
    BATTERY_USAGE("battery_usage", "Battery usage", BATTERY),
    SYSTEM("system", "System", MAIN),
    ABOUT("about", "About", SYSTEM),
    RESTART("restart", "Restart", SYSTEM),
    POWER_OFF("power_off", "Power off", SYSTEM),

    // ---- round pages that replace the AOSP Settings fallbacks (decisions.md 2026-10-03) ----
    // Pages that act on one thing (a network, a device, an app) read it from SettingsController.pageArg.
    WIFI("wifi", "Wi-Fi", CONNECTIVITY),
    WIFI_NETWORK("wifi_network", "Network", WIFI),
    WIFI_JOIN("wifi_join", "Join network", WIFI),
    BLUETOOTH("bluetooth", "Bluetooth", CONNECTIVITY),
    BT_DEVICE("bt_device", "Device", BLUETOOTH),
    BT_PAIR("bt_pair", "Pair new device", BLUETOOTH),
    APPS_LIST("apps_list", "Apps", APPS),
    APP_INFO("app_info", "App info", APPS_LIST),
    APP_PERMS("app_perms", "Permissions", APP_INFO),
    NOTIF_APPS("notif_apps", "Notifications", APPS),
    DATETIME("datetime", "Date & time", SYSTEM),
    TIMEZONE("timezone", "Time zone", DATETIME),
    STORAGE("storage", "Storage", SYSTEM),
    LANGUAGE("language", "Language", SYSTEM),
    ACCESSIBILITY("accessibility", "Accessibility", SYSTEM),
    FONT_SIZE("font_size", "Font size", ACCESSIBILITY),
    PIN_NEW("pin_new", "Set PIN", SECURITY),
    PIN_CHANGE("pin_change", "Change PIN", SECURITY),
    PIN_REMOVE("pin_remove", "Remove PIN", SECURITY),

    // ---- Wi-Fi sub-pages ----
    WIFI_SAVED("wifi_saved", "Saved networks", WIFI),
    WIFI_ADD("wifi_add", "Add network", WIFI),
    // ---- Bluetooth sub-pages: rename and forget-confirm of one paired device (pageArg = address) ----
    BT_RENAME("bt_rename", "Rename", BT_DEVICE),
    BT_FORGET("bt_forget", "Forget device", BT_DEVICE),
    // ---- Apps: force stop / disable / uninstall confirmation (pageArg = package) ----
    APP_CONFIRM("app_confirm", "Confirm", APP_INFO),
    // ---- audit batch 3 (A13): a small Default apps page, and the info page for accounts / users / sync ----
    DEFAULT_APPS("default_apps", "Default apps", APPS),
    NOT_ON_WATCH("not_on_watch", "Not on this watch", MAIN),
    // ---- Exercise (2026-10-04): the profile the Exercise app reads, and the side-button long press ----
    PROFILE("profile", "Profile", MAIN),
    /** One numeric field (pageArg = ProfileField.id). */
    PROFILE_VALUE("profile_value", "Profile", PROFILE),
    PROFILE_SEX("profile_sex", "Sex", PROFILE),
    PROFILE_MAX_HR("profile_max_hr", "Max heart rate", PROFILE),
    PROFILE_MAX_HR_VALUE("profile_max_hr_value", "Max heart rate", PROFILE_MAX_HR),
    BUTTONS("buttons", "Buttons", MAIN),
    SIDE_LONG_PRESS("side_long_press", "Long press", BUTTONS); // "Side long press" wrapped in the header

    /** Pages from [MAIN] down to this one, for tests and for "how deep am I". */
    val depth: Int get() = generateSequence(this) { it.parent }.count() - 1
}

/** Which `Settings.*` table a key lives in. */
enum class SettingsTable { GLOBAL, SECURE, SYSTEM }

data class SettingKey(val table: SettingsTable, val name: String)

/** The `screen_off_timeout` picker (stock Wear's values). */
object ScreenTimeout {
    val OPTIONS: List<Int> = listOf(15_000, 30_000, 60_000, 120_000, 300_000)

    /** A timeout at or above this is "never sleeps" (the emulator image ships Int.MAX_VALUE). */
    private const val NEVER_FROM_MS = 1_800_000

    fun isPreset(ms: Int): Boolean = ms in OPTIONS

    fun label(ms: Int): String = when {
        ms >= NEVER_FROM_MS -> "Never"
        ms <= 0 -> "Never"
        ms % 60_000 == 0 -> (ms / 60_000).let { if (it == 1) "1 minute" else "$it minutes" }
        else -> "${ms / 1000} seconds"
    }
}

/** `lock_screen_lock_after_timeout` (ms the screen may be off before the PIN is required). */
object LockTimeout {
    val OPTIONS: List<Long> = listOf(0L, 5_000L, 30_000L, 60_000L, 300_000L)

    fun label(ms: Long): String = when {
        ms <= 0L -> "Immediately"
        ms % 60_000L == 0L -> (ms / 60_000L).let { if (it == 1L) "1 minute" else "$it minutes" }
        else -> "${ms / 1000L} seconds"
    }
}

/** `AudioManager` ringer modes, in the order the Sound screen lists them. */
enum class Ringer(val mode: Int, val label: String) {
    SOUND(2, "Sound"),
    VIBRATE(1, "Vibrate"),
    SILENT(0, "Silent");

    companion object {
        fun fromMode(mode: Int): Ringer = entries.firstOrNull { it.mode == mode } ?: SOUND
    }
}

/** The wake gestures stock lists under Gestures, and the settings keys that can back each one here. */
enum class Gesture(val id: String, val label: String, val candidates: List<SettingKey>) {
    /** Touch-to-wake: Wear's own key first, AOSP's double-tap-to-wake as the fallback. */
    TOUCH_TO_WAKE(
        "touch_to_wake", "Touch-to-wake",
        listOf(
            SettingKey(SettingsTable.GLOBAL, "ambient_touch_to_wake"),
            SettingKey(SettingsTable.SECURE, "double_tap_to_wake"),
        ),
    ),

    // "Rotate crown to wake" and "Screenshot" are deliberately absent: no settings key for either
    // exists on this AOSP 16 build, and a row that writes nothing would lie.
}

/** A gesture that exists on this build: the key that backs it and its current value. */
data class GestureRow(val gesture: Gesture, val key: SettingKey, val on: Boolean)

/** Tilt-to-wake sensitivity; shared with the launcher through Settings.Secure `circa_tilt_wake` ("0"/"1"/"2"). */
enum class TiltWake(val value: Int, val label: String, val hint: String) {
    OFF(0, "Off", "Press or tap only"),
    LOW(1, "Low", "Face up and held"),
    NORMAL(2, "Normal", "Easier to wake"),
}

object TiltWakeModel {
    const val SECURE_KEY = "circa_tilt_wake"
    const val LEGACY_GLOBAL_KEY = "ambient_tilt_to_wake"

    /** Our key wins when it parses to 0/1/2; otherwise the old Global toggle ("1" = Low), else Off. */
    fun resolve(secureRaw: String?, legacyGlobalRaw: String?): TiltWake =
        secureRaw?.trim()?.toIntOrNull()?.let { v -> TiltWake.entries.firstOrNull { it.value == v } }
            ?: if (legacyGlobalRaw?.trim() == "1") TiltWake.LOW else TiltWake.OFF

    /** What to keep `ambient_tilt_to_wake` at, for anything still reading the old toggle. */
    fun legacyValue(level: TiltWake): String = if (level == TiltWake.OFF) "0" else "1"
}

object GestureModel {
    /** The first candidate key that exists (has a stored value), or null: the row is hidden then. */
    fun resolve(gesture: Gesture, exists: (SettingKey) -> Boolean): SettingKey? =
        gesture.candidates.firstOrNull(exists)

    /** Settings values are "1"/"0"; anything else, including a missing value, is off. */
    fun parse(raw: String?): Boolean = raw?.trim() == "1"

    fun encode(on: Boolean): String = if (on) "1" else "0"

    fun rows(exists: (SettingKey) -> Boolean, read: (SettingKey) -> String?): List<GestureRow> =
        Gesture.entries.mapNotNull { g ->
            resolve(g, exists)?.let { GestureRow(g, it, parse(read(it))) }
        }
}

/** Brightness steps for the level control (crown and +/- buttons). */
object BrightnessLevel {
    const val MIN = 5
    const val MAX = 255
    const val STEP = 13

    fun clamp(v: Int): Int = v.coerceIn(MIN, MAX)

    /** [clicks] crown detents (sign = direction) from [current]. */
    fun adjust(current: Int, clicks: Int): Int = clamp(current + clicks * STEP)

    fun percent(level: Int): Int = (clamp(level) * 100 + MAX / 2) / MAX
}

/** Battery line shown on the Battery screen / main list. */
object BatteryLabel {
    fun level(percent: Int?): String = if (percent == null) "-" else "$percent%"
}

/** The Security page's "Lock when taken off" row: what its secondary line says and whether it can be switched. */
object LockWhenTakenOffRow {
    /** The toggle only means something with a secure credential: without one there is nothing to lock. */
    fun enabled(deviceSecure: Boolean) = deviceSecure

    fun secondary(deviceSecure: Boolean, on: Boolean): String =
        if (!deviceSecure) "Needs a PIN" else if (on) "On" else "Off"

    /** Shown checked only while it is both wanted and possible. */
    fun checked(deviceSecure: Boolean, on: Boolean) = deviceSecure && on
}
