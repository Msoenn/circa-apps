package org.circa.settings.model

/**
 * Which page a `android.settings.*` intent opens, and where BACK goes from there. Pure, so the
 * deep-link table is unit tested (`SettingsNavTest`); `MainActivity` applies it.
 *
 * Only actions with a real round page are claimed (the manifest declares exactly these). Everything
 * else - Bluetooth pairing dialogs, developer options, date & time, ... - keeps resolving to
 * AOSP Settings, which stays installed as the backend.
 */
object SettingsNav {
    const val EXTRA_PAGE = "org.circa.settings.extra.PAGE"

    val ACTIONS: Map<String, SettingsPage> get() = CORE_ACTIONS + MORE_ACTIONS

    private val CORE_ACTIONS: Map<String, SettingsPage> = mapOf(
        "android.settings.SETTINGS" to SettingsPage.MAIN,
        "android.settings.WIRELESS_SETTINGS" to SettingsPage.CONNECTIVITY,
        "android.settings.WIFI_SETTINGS" to SettingsPage.WIFI,
        "android.net.wifi.PICK_WIFI_NETWORK" to SettingsPage.WIFI,
        "android.settings.panel.action.WIFI" to SettingsPage.WIFI,
        "android.settings.panel.action.INTERNET_CONNECTIVITY" to SettingsPage.WIFI,
        "android.settings.INTERNAL_STORAGE_SETTINGS" to SettingsPage.STORAGE,
        "android.settings.MEMORY_CARD_SETTINGS" to SettingsPage.STORAGE,
        "android.settings.DATE_SETTINGS" to SettingsPage.DATETIME,
        "android.settings.BLUETOOTH_SETTINGS" to SettingsPage.BLUETOOTH,
        "android.settings.BLUETOOTH_PAIRING_SETTINGS" to SettingsPage.BT_PAIR,
        "android.settings.AIRPLANE_MODE_SETTINGS" to SettingsPage.CONNECTIVITY,
        "android.settings.DISPLAY_SETTINGS" to SettingsPage.DISPLAY,
        "android.settings.SOUND_SETTINGS" to SettingsPage.SOUND,
        "android.settings.BATTERY_SAVER_SETTINGS" to SettingsPage.BATTERY,
        "android.intent.action.POWER_USAGE_SUMMARY" to SettingsPage.BATTERY_USAGE,
        "android.settings.SECURITY_SETTINGS" to SettingsPage.SECURITY,
        "android.settings.LOCK_SCREEN_SETTINGS" to SettingsPage.SECURITY,
        "android.settings.PRIVACY_SETTINGS" to SettingsPage.SECURITY,
        // The "set a screen lock" requests (DevicePolicyManager.ACTION_SET_NEW_PASSWORD and its parent-profile
        // twin) land on the PIN keypad; a device that already has a PIN is sent to Change PIN instead
        // (MainActivity.handleIntent), so no phone ChooseLockPassword form is ever shown.
        "android.app.action.SET_NEW_PASSWORD" to SettingsPage.PIN_NEW,
        "android.app.action.SET_NEW_PARENT_PROFILE_PASSWORD" to SettingsPage.PIN_NEW,
        "android.settings.DEVICE_INFO_SETTINGS" to SettingsPage.ABOUT,
        "android.settings.LOCALE_SETTINGS" to SettingsPage.LANGUAGE,
        "android.settings.REGIONAL_PREFERENCES_SETTINGS" to SettingsPage.LANGUAGE,
        "android.settings.ACCESSIBILITY_SETTINGS" to SettingsPage.ACCESSIBILITY,
        "android.settings.TEXT_READING_SETTINGS" to SettingsPage.FONT_SIZE,
        // ---- apps & notifications (AppsPages.kt) ----
        "android.settings.APPLICATION_SETTINGS" to SettingsPage.APPS_LIST,
        "android.settings.MANAGE_APPLICATIONS_SETTINGS" to SettingsPage.APPS_LIST,
        "android.settings.MANAGE_ALL_APPLICATIONS_SETTINGS" to SettingsPage.APPS_LIST,
        "android.settings.APPLICATION_DETAILS_SETTINGS" to SettingsPage.APP_INFO,
        "android.settings.APP_NOTIFICATION_SETTINGS" to SettingsPage.APP_INFO,
        "android.settings.CHANNEL_NOTIFICATION_SETTINGS" to SettingsPage.APP_INFO,
        "android.settings.NOTIFICATION_SETTINGS" to SettingsPage.NOTIF_APPS,
        "android.settings.ALL_APPS_NOTIFICATION_SETTINGS" to SettingsPage.NOTIF_APPS,
        "android.intent.action.MANAGE_APP_PERMISSIONS" to SettingsPage.APP_PERMS,
    )

    /**
     * The rest of what AOSP Settings exports that a user can reach, sent to the nearest round page
     * (a feature without its own page, e.g. NFC or Do Not Disturb rules, lands on its topic's page rather
     * than on the phone UI). Generated from settings/README.md's sweep; what is not here
     * (SIM, enterprise, setup, developer options, accounts, request dialogs) stays with AOSP Settings.
     */
    private val MORE_ACTIONS: Map<String, SettingsPage> = mapOf(
        "android.settings.NETWORK_PROVIDER_SETTINGS" to SettingsPage.WIFI,
        "android.settings.WIFI_DETAILS_SETTINGS" to SettingsPage.WIFI,
        "android.settings.WIFI_IP_SETTINGS" to SettingsPage.WIFI,
        "android.settings.WIFI_SCANNING_SETTINGS" to SettingsPage.WIFI,
        "android.settings.LOCATION_SCANNING_SETTINGS" to SettingsPage.WIFI,
        "android.net.wifi.action.REQUEST_SCAN_ALWAYS_AVAILABLE" to SettingsPage.WIFI,
        "android.settings.WIFI_CALLING_SETTINGS" to SettingsPage.WIFI,
        "android.settings.WIFI_SAVED_NETWORK_SETTINGS" to SettingsPage.WIFI_SAVED,
        "android.settings.BLUETOOTH_DASHBOARD_SETTINGS" to SettingsPage.BLUETOOTH,
        "android.settings.HEARING_DEVICES_SETTINGS" to SettingsPage.BLUETOOTH,
        "android.settings.HEARING_DEVICES_PAIRING_SETTINGS" to SettingsPage.BLUETOOTH,
        "android.settings.DATA_ROAMING_SETTINGS" to SettingsPage.CONNECTIVITY,
        "android.settings.DATA_USAGE_SETTINGS" to SettingsPage.CONNECTIVITY,
        "android.settings.DATA_SAVER_SETTINGS" to SettingsPage.CONNECTIVITY,
        "android.settings.MOBILE_DATA_USAGE" to SettingsPage.CONNECTIVITY,
        "android.settings.MOBILE_NETWORK_LIST" to SettingsPage.CONNECTIVITY,
        "android.settings.MANAGE_ALL_SIM_PROFILES_SETTINGS" to SettingsPage.CONNECTIVITY,
        "android.settings.NETWORK_OPERATOR_SETTINGS" to SettingsPage.CONNECTIVITY,
        "android.settings.MMS_MESSAGE_SETTING" to SettingsPage.CONNECTIVITY,
        "android.settings.TETHER_SETTINGS" to SettingsPage.CONNECTIVITY,
        "android.settings.VPN_SETTINGS" to SettingsPage.CONNECTIVITY,
        "android.net.vpn.SETTINGS" to SettingsPage.CONNECTIVITY,
        "android.settings.NFC_SETTINGS" to SettingsPage.CONNECTIVITY,
        "android.settings.NFC_PAYMENT_SETTINGS" to SettingsPage.CONNECTIVITY,
        "android.settings.panel.action.NFC" to SettingsPage.CONNECTIVITY,
        "android.settings.CAST_SETTINGS" to SettingsPage.CONNECTIVITY,
        "android.settings.VOICE_CONTROL_AIRPLANE_MODE" to SettingsPage.CONNECTIVITY,
        "android.settings.REQUEST_CHANGE_WIFI_STATE" to SettingsPage.CONNECTIVITY,
        "android.settings.LOCATION_SOURCE_SETTINGS" to SettingsPage.LOCATION,
        "android.settings.ADAPTIVE_BRIGHTNESS_SETTINGS" to SettingsPage.DISPLAY,
        "android.settings.DARK_THEME_SETTINGS" to SettingsPage.DISPLAY,
        "android.settings.NIGHT_DISPLAY_SETTINGS" to SettingsPage.DISPLAY,
        "android.settings.REDUCE_BRIGHT_COLORS_SETTINGS" to SettingsPage.DISPLAY,
        "android.settings.COLOR_MODE_SETTINGS" to SettingsPage.DISPLAY,
        "android.settings.AUTO_ROTATE_SETTINGS" to SettingsPage.DISPLAY,
        "android.settings.DREAM_SETTINGS" to SettingsPage.DISPLAY,
        "android.settings.WALLPAPER_SETTINGS" to SettingsPage.DISPLAY,
        "android.settings.TURN_SCREEN_ON_SETTINGS" to SettingsPage.DISPLAY,
        "android.settings.SCREEN_TIMEOUT_SETTINGS" to SettingsPage.TIMEOUT,
        "android.settings.ACTION_OTHER_SOUND_SETTINGS" to SettingsPage.SOUND,
        "android.settings.panel.action.VOLUME" to SettingsPage.SOUND,
        "android.settings.ZEN_MODE_SETTINGS" to SettingsPage.SOUND,
        "android.settings.ZEN_MODE_PRIORITY_SETTINGS" to SettingsPage.SOUND,
        "android.settings.ZEN_MODE_AUTOMATION_SETTINGS" to SettingsPage.SOUND,
        "android.settings.ACTION_CONDITION_PROVIDER_SETTINGS" to SettingsPage.SOUND,
        "android.settings.AUTOMATIC_ZEN_RULE_SETTINGS" to SettingsPage.SOUND,
        "android.settings.VOICE_CONTROL_DO_NOT_DISTURB_MODE" to SettingsPage.SOUND,
        "android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS" to SettingsPage.SOUND,
        "android.settings.VOICE_CONTROL_BATTERY_SAVER_MODE" to SettingsPage.BATTERY,
        "android.settings.ACCESSIBILITY_COLOR_CONTRAST_SETTINGS" to SettingsPage.ACCESSIBILITY,
        "android.settings.ACCESSIBILITY_COLOR_MOTION_SETTINGS" to SettingsPage.ACCESSIBILITY,
        "android.settings.ACCESSIBILITY_DETAILS_SETTINGS" to SettingsPage.ACCESSIBILITY,
        "android.settings.ACCESSIBILITY_SHORTCUT_SETTINGS" to SettingsPage.ACCESSIBILITY,
        "android.settings.ACCESSIBILITY_SETTINGS_FOR_SUW" to SettingsPage.ACCESSIBILITY,
        "android.settings.CAPTIONING_SETTINGS" to SettingsPage.ACCESSIBILITY,
        "android.settings.COLOR_INVERSION_SETTINGS" to SettingsPage.ACCESSIBILITY,
        "android.settings.MAGNIFICATION_SETTINGS" to SettingsPage.ACCESSIBILITY,
        "android.settings.FLASH_NOTIFICATIONS_SETTINGS" to SettingsPage.ACCESSIBILITY,
        "android.settings.LANGUAGE_SETTINGS" to SettingsPage.LANGUAGE,
        "android.settings.REGION_SETTINGS" to SettingsPage.LANGUAGE,
        "android.settings.APP_LOCALE_SETTINGS" to SettingsPage.APPS_LIST,
        "android.settings.MEASUREMENT_SYSTEM_SETTINGS" to SettingsPage.LANGUAGE,
        "android.settings.NUMBERING_SYSTEM_SETTINGS" to SettingsPage.LANGUAGE,
        "android.settings.TEMPERATURE_UNIT_SETTINGS" to SettingsPage.LANGUAGE,
        "android.settings.FIRST_DAY_OF_WEEK_SETTINGS" to SettingsPage.LANGUAGE,
        "android.settings.INPUT_METHOD_SETTINGS" to SettingsPage.LANGUAGE,
        "android.settings.INPUT_METHOD_SUBTYPE_SETTINGS" to SettingsPage.LANGUAGE,
        "android.settings.USER_DICTIONARY_SETTINGS" to SettingsPage.LANGUAGE,
        "android.settings.HARD_KEYBOARD_SETTINGS" to SettingsPage.LANGUAGE,
        "android.settings.HARD_KEYBOARD_LAYOUT_PICKER_SETTINGS" to SettingsPage.LANGUAGE,
        "android.settings.VOICE_INPUT_SETTINGS" to SettingsPage.LANGUAGE,
        "android.intent.action.QUICK_CLOCK" to SettingsPage.DATETIME,
        "android.settings.STORAGE_MANAGER_SETTINGS" to SettingsPage.STORAGE,
        "android.intent.action.MANAGE_PACKAGE_STORAGE" to SettingsPage.STORAGE,
        "android.settings.SPECIAL_ACCESS_SETTINGS" to SettingsPage.APPS_LIST,
        "android.settings.USAGE_ACCESS_SETTINGS" to SettingsPage.APPS_LIST,
        "android.settings.MANAGE_UNKNOWN_APP_SOURCES" to SettingsPage.APPS_LIST,
        "android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION" to SettingsPage.APPS_LIST,
        // ---- audit batch 3 (A13) ----
        "android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION" to SettingsPage.APP_PERMS,
        "android.settings.MANAGE_DEFAULT_APPS_SETTINGS" to SettingsPage.DEFAULT_APPS,
        "android.settings.HOME_SETTINGS" to SettingsPage.DEFAULT_APPS,
        "android.settings.SYNC_SETTINGS" to SettingsPage.NOT_ON_WATCH,
        "android.settings.ACCOUNT_SYNC_SETTINGS" to SettingsPage.NOT_ON_WATCH,
        "android.settings.ADD_ACCOUNT_SETTINGS" to SettingsPage.NOT_ON_WATCH,
        "android.settings.USER_SETTINGS" to SettingsPage.NOT_ON_WATCH,
        "android.settings.WIFI_ADD_NETWORKS" to SettingsPage.WIFI_ADD,
        "android.settings.REQUEST_MANAGE_MEDIA" to SettingsPage.APPS_LIST,
        "android.settings.REQUEST_SCHEDULE_EXACT_ALARM" to SettingsPage.APPS_LIST,
        "android.settings.MANAGE_DOMAIN_URLS" to SettingsPage.APPS_LIST,
        "android.settings.MANAGE_CLONED_APPS_SETTINGS" to SettingsPage.APPS_LIST,
        "android.settings.APP_MEMORY_USAGE" to SettingsPage.APPS_LIST,
        "android.settings.MANAGE_USER_ASPECT_RATIO_SETTINGS" to SettingsPage.APPS_LIST,
        "android.settings.MANAGE_APP_LONG_RUNNING_JOBS" to SettingsPage.APPS_LIST,
        "android.settings.action.MANAGE_OVERLAY_PERMISSION" to SettingsPage.APPS_LIST,
        "android.settings.action.MANAGE_WRITE_SETTINGS" to SettingsPage.APPS_LIST,
        "android.settings.APP_OPEN_BY_DEFAULT_SETTINGS" to SettingsPage.APPS_LIST,
        "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS" to SettingsPage.APPS_LIST,
        "android.settings.IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS" to SettingsPage.APPS_LIST,
        "android.settings.PICTURE_IN_PICTURE_SETTINGS" to SettingsPage.APPS_LIST,
        "android.settings.NOTIFICATION_HISTORY" to SettingsPage.NOTIF_APPS,
        "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS" to SettingsPage.NOTIF_APPS,
        "android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS" to SettingsPage.NOTIF_APPS,
        "android.settings.NOTIFICATION_ASSISTANT_SETTINGS" to SettingsPage.NOTIF_APPS,
        "android.settings.NOTIFICATION_BUBBLE_SETTINGS" to SettingsPage.NOTIF_APPS,
        "android.settings.APP_NOTIFICATION_BUBBLE_SETTINGS" to SettingsPage.NOTIF_APPS,
        "android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS" to SettingsPage.NOTIF_APPS,
        "android.settings.ACTION_APP_NOTIFICATION_REDACTION" to SettingsPage.NOTIF_APPS,
        "android.settings.ALL_APPS_NOTIFICATION_SETTINGS_FOR_REVIEW" to SettingsPage.NOTIF_APPS,
        "android.settings.CONVERSATION_SETTINGS" to SettingsPage.NOTIF_APPS,
        "android.settings.MANAGE_ADAPTIVE_NOTIFICATIONS" to SettingsPage.NOTIF_APPS,
        "android.settings.LOCK_SCREEN_NOTIFICATIONS_SETTINGS" to SettingsPage.NOTIF_APPS,
        "android.settings.NOTIFICATION_BUNDLES" to SettingsPage.NOTIF_APPS,
        "android.settings.NOTIFICATION_SUMMARIZATION" to SettingsPage.NOTIF_APPS,
        "android.settings.ACTION_MEDIA_CONTROLS_SETTINGS" to SettingsPage.NOTIF_APPS,
        "android.settings.BIOMETRIC_ENROLL" to SettingsPage.SECURITY,
        "android.settings.FACE_ENROLL" to SettingsPage.SECURITY,
        "android.settings.FACE_SETTINGS" to SettingsPage.SECURITY,
        "android.settings.FINGERPRINT_ENROLL" to SettingsPage.SECURITY,
        "android.settings.FINGERPRINT_SETTINGS" to SettingsPage.SECURITY,
        "android.settings.FINGERPRINT_SETTINGS_V2" to SettingsPage.SECURITY,
        "android.settings.FINGERPRINT_SETUP" to SettingsPage.SECURITY,
        "android.settings.COMBINED_BIOMETRICS_SETTINGS" to SettingsPage.SECURITY,
        "android.settings.PRIVACY_ADVANCED_SETTINGS" to SettingsPage.SECURITY,
        "android.settings.PRIVACY_CONTROLS" to SettingsPage.SECURITY,
        "android.settings.REQUEST_ENABLE_CONTENT_CAPTURE" to SettingsPage.SECURITY,
        "android.settings.CONTENT_PROTECTION_SETTINGS" to SettingsPage.SECURITY,
        "android.settings.ADVANCED_MEMORY_PROTECTION_SETTINGS" to SettingsPage.SECURITY,
        "android.settings.CELLULAR_NETWORK_SECURITY" to SettingsPage.SECURITY,
        "android.settings.DEVICE_NAME" to SettingsPage.ABOUT,
        "android.settings.LICENSE" to SettingsPage.ABOUT,
        "android.settings.MODULE_LICENSES" to SettingsPage.ABOUT,
    )

    /** Settings.EXTRA_APP_PACKAGE, and the pre-O extra name Settings still accepts. */
    const val EXTRA_APP_PACKAGE = "android.provider.extra.APP_PACKAGE"
    const val EXTRA_APP_PACKAGE_LEGACY = "app_package"

    /** Intent.EXTRA_PACKAGE_NAME (MANAGE_APP_PERMISSIONS). */
    const val EXTRA_PACKAGE_NAME = "android.intent.extra.PACKAGE_NAME"

    /** Pages that act on one app and are useless without its package name. */
    private val NEEDS_PACKAGE = setOf(SettingsPage.APP_INFO, SettingsPage.APP_PERMS)

    /**
     * What a deep link's page acts on (SettingsController.pageArg): the package of the app-detail
     * actions, from the `package:<name>` data URI or the action's package extra; null otherwise.
     * [extra] reads a string extra of the intent.
     */
    fun argFor(action: String?, dataUri: String?, extra: (String) -> String?): String? {
        val fromData = dataUri?.takeIf { it.startsWith("package:") }?.removePrefix("package:")
            ?.substringBefore('#')?.trim()?.takeIf { it.isNotEmpty() }
        val pkg = when (action) {
            "android.settings.APPLICATION_DETAILS_SETTINGS" -> fromData
            "android.settings.APP_NOTIFICATION_SETTINGS",
            "android.settings.CHANNEL_NOTIFICATION_SETTINGS",
            -> extra(EXTRA_APP_PACKAGE) ?: extra(EXTRA_APP_PACKAGE_LEGACY) ?: fromData
            "android.intent.action.MANAGE_APP_PERMISSIONS" -> extra(EXTRA_PACKAGE_NAME) ?: fromData
            // Special-access actions (overlay, write settings, battery optimisation, ...) name their app the same way.
            "android.settings.SYNC_SETTINGS", "android.settings.ACCOUNT_SYNC_SETTINGS",
            "android.settings.ADD_ACCOUNT_SETTINGS" -> "accounts"
            "android.settings.USER_SETTINGS" -> "users"
            else -> if (ACTIONS[action] == SettingsPage.APPS_LIST || ACTIONS[action] == SettingsPage.APP_PERMS) fromData else null
        }
        return pkg?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** A per-app page without a package falls back to the app list rather than an empty page. */
    fun entryPage(page: SettingsPage, arg: String?): SettingsPage =
        when {
            page in NEEDS_PACKAGE && arg == null -> SettingsPage.APPS_LIST
            page == SettingsPage.APPS_LIST && arg != null -> SettingsPage.APP_INFO
            else -> page
        }

    /**
     * The page an intent opens. MAIN/LAUNCHER and unknown actions open the main list; an explicit
     * [EXTRA_PAGE] (a page id) wins over the action.
     */
    fun pageFor(action: String?, pageExtra: String? = null): SettingsPage {
        pageExtra?.let { id -> SettingsPage.entries.firstOrNull { it.id == id }?.let { return it } }
        return ACTIONS[action] ?: SettingsPage.MAIN
    }

    /**
     * BACK / swipe-right from [current] when Settings was entered at [entry]: the parent page, or
     * null = leave (finish the activity). A deep link (entry below the main list) leaves from its own
     * page rather than climbing to the main list the caller never asked for.
     */
    fun back(current: SettingsPage, entry: SettingsPage): SettingsPage? =
        if (current == entry) null else current.parent
}

/**
 * The settings Circa Settings shares with the launcher and the Circa shade, in `Settings.Secure`
 * (the launcher's own SharedPreferences are private to it).
 */
object SharedKeys {
    /** ARGB int; the shade's existing contract (frameworks CircaTray), the launcher follows it too. */
    const val ACCENT = "circa_accent_color"

    /** "1"/"0"; missing = on (stock default). The launcher's off-body lock reads it. */
    const val LOCK_WHEN_TAKEN_OFF = "circa_lock_when_taken_off"

    fun parseLockWhenTakenOff(raw: String?): Boolean = raw?.trim() != "0"
}
