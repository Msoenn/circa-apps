package org.circa.settings.model

/**
 * The pure parts of the Apps / App info / Permissions / Notifications pages: which apps the list
 * shows, what each row says, which actions an app may offer, and how runtime permissions group the
 * way stock's permission controller groups them. `data/AppsData` reads and writes the platform;
 * everything here is unit tested in `AppsModelTest`.
 */

/** One installed package as the Apps pages need it (read by `data/AppsData`). */
data class AppEntry(
    val pkg: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    /** ApplicationInfo.FLAG_SYSTEM (a preinstalled app, updated or not). */
    val system: Boolean,
    /** Not disabled (PackageManager enabled setting is DEFAULT or ENABLED and the app flag is set). */
    val enabled: Boolean,
    /** Has a MAIN/LAUNCHER activity, so the launcher shows it. */
    val launchable: Boolean,
    val uid: Int,
)

object AppsModel {
    /** Circa Settings never offers an action on itself (it would kill the page the user is on). */
    const val SELF = "org.circa.settings"

    /**
     * System packages that must never be disabled from the watch: the shell (launcher, settings,
     * system UI), telephony and the framework. Disabling one of them can leave the watch without a
     * home screen or unbootable. Prefixes end in '.'; anything else is an exact name.
     */
    val PROTECTED: List<String> = listOf(
        "android",
        "org.circa.",
        "org.lineageos.",
        "lineageos.platform",
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.android.shell",
        "com.android.providers.",
        "com.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.android.inputmethod.",
        "com.android.networkstack",
        "com.android.bluetooth",
        "com.android.server.",
        "com.android.location.fused",
        "com.android.externalstorage",
        "com.android.wifi.",
        "com.android.se",
        "com.android.nfc",
        "com.android.keychain",
        "com.android.webview",
        "com.google.android.webview",
        "com.android.extservices",
        "com.android.ons",
        "com.android.emergency",
        "com.android.sdksetup",
        "com.android.wallpaperbackup",
        "com.android.statementservice",
    )

    fun isProtected(pkg: String): Boolean = PROTECTED.any { p ->
        if (p.endsWith(".")) pkg.startsWith(p) else pkg == p
    }

    /** What the list shows by default: what a user sees as "an app" (launchable, or installed by the user). */
    fun userVisible(a: AppEntry): Boolean = a.launchable || !a.system

    /** The list: user-visible apps (all apps with [showSystem]), sorted by label like stock. */
    fun visible(all: List<AppEntry>, showSystem: Boolean): List<AppEntry> =
        all.filter { showSystem || userVisible(it) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, AppEntry::label).thenBy { it.pkg })

    /** The list row's second line. A disabled app says so first: that is why it may look broken. */
    fun secondary(a: AppEntry): String = when {
        !a.enabled -> "Disabled"
        !a.versionName.isNullOrBlank() -> versionShort(a.versionName)
        a.system -> "System"
        else -> "Version ${a.versionCode}"
    }

    /** "1.2.3 (build ...)" style names are long; the row has one line, the first word is the version. */
    fun versionShort(name: String): String = name.trim().substringBefore(' ').take(24)

    /** App info's Version row: "1.2.3 (42)", or just the code when there is no name. */
    fun versionLine(name: String?, code: Long): String =
        if (name.isNullOrBlank()) code.toString() else "${name.trim()} ($code)"

    /** Uninstall is offered for apps the user installed (not preinstalled ones), never for this app. */
    fun canUninstall(a: AppEntry): Boolean = a.pkg != SELF && !a.system

    /**
     * Disable / enable: any app except this one, and except preinstalled apps on the protected list
     * (a user-installed test app may be disabled; the baked launcher may not).
     */
    fun canDisable(a: AppEntry): Boolean = a.pkg != SELF && !(a.system && isProtected(a.pkg))

    /** Force stop: anything but this app (the platform refuses "android" itself, harmless). */
    fun canForceStop(a: AppEntry): Boolean = a.pkg != SELF && a.pkg != "android"
}

/** A runtime permission an app requests, and what the platform holds for it. */
data class RuntimePerm(
    val name: String,
    val granted: Boolean,
    /** System-, policy- or (for display) role-fixed: the platform refuses or reverts a change. */
    val fixed: Boolean,
)

/** One switch on the Permissions page: a stock permission group and the app's permissions in it. */
data class PermGroup(val label: String, val perms: List<RuntimePerm>) {
    /** On when any permission of the group is granted (stock: "Allowed" if any is). */
    val granted: Boolean get() = perms.any { it.granted }

    /** Switchable when at least one permission of the group can be changed. */
    val changeable: Boolean get() = perms.any { !it.fixed }

    /** The permissions a switch to [grant] changes: the ones not fixed and not already there. */
    fun toChange(grant: Boolean): List<RuntimePerm> = perms.filter { !it.fixed && it.granted != grant }
}

object PermGroups {
    private const val P = "android.permission."

    /**
     * Permission -> stock group label (PermissionController's platform groups; since Android 10 the
     * framework's own PermissionInfo.group is UNDEFINED for most, so stock hardcodes this too).
     */
    private val GROUPS: Map<String, String> = buildMap {
        fun g(label: String, vararg perms: String) = perms.forEach { put(P + it, label) }
        g("Camera", "CAMERA", "BACKGROUND_CAMERA")
        g("Microphone", "RECORD_AUDIO", "RECORD_BACKGROUND_AUDIO")
        g("Location", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION")
        g("Contacts", "READ_CONTACTS", "WRITE_CONTACTS", "GET_ACCOUNTS")
        g("Calendar", "READ_CALENDAR", "WRITE_CALENDAR")
        g("Call logs", "READ_CALL_LOG", "WRITE_CALL_LOG", "PROCESS_OUTGOING_CALLS")
        g(
            "Phone", "READ_PHONE_STATE", "READ_PHONE_NUMBERS", "CALL_PHONE", "ANSWER_PHONE_CALLS",
            "ADD_VOICEMAIL", "USE_SIP", "ACCEPT_HANDOVER",
        )
        g("SMS", "SEND_SMS", "RECEIVE_SMS", "READ_SMS", "RECEIVE_WAP_PUSH", "RECEIVE_MMS", "READ_CELL_BROADCASTS")
        g(
            "Nearby devices", "BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE",
            "NEARBY_WIFI_DEVICES", "UWB_RANGING", "RANGING",
        )
        g("Notifications", "POST_NOTIFICATIONS")
        g("Body sensors", "BODY_SENSORS", "BODY_SENSORS_BACKGROUND")
        g("Physical activity", "ACTIVITY_RECOGNITION")
        g("Files", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE")
        g("Photos and videos", "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_VISUAL_USER_SELECTED", "ACCESS_MEDIA_LOCATION")
        g("Music and audio", "READ_MEDIA_AUDIO")
    }

    /** Stock's order on the app permissions page (most sensitive first). */
    private val ORDER = listOf(
        "Location", "Camera", "Microphone", "Body sensors", "Physical activity", "Nearby devices",
        "Notifications", "Contacts", "Calendar", "Phone", "Call logs", "SMS",
        "Photos and videos", "Music and audio", "Files",
    )

    /**
     * The group label for [perm]; [platformLabel] is what the framework says (used for permissions
     * this table does not know). Android 16's split health permissions (`android.permission.health.*`,
     * e.g. READ_HEART_RATE) are the watch's body sensors.
     */
    fun labelFor(perm: String, platformLabel: String? = null): String =
        GROUPS[perm]
            ?: if (perm.startsWith(P + "health.")) "Body sensors"
            else platformLabel?.takeIf { it.isNotBlank() } ?: "Other"

    /** Group [perms] by label, in stock's order, unknown groups after the known ones by name. */
    fun group(perms: List<RuntimePerm>, platformLabel: (String) -> String? = { null }): List<PermGroup> =
        perms.groupBy { labelFor(it.name, platformLabel(it.name)) }
            .map { (label, ps) -> PermGroup(label, ps) }
            .sortedWith(compareBy<PermGroup> { ORDER.indexOf(it.label).let { i -> if (i < 0) ORDER.size else i } }.thenBy { it.label })
}

/** The Notifications page's per-app switch: the POST_NOTIFICATIONS grant behind it. */
object NotifModel {
    const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

    /**
     * Apps listed on the Notifications page: user-visible ones (launchable or user-installed), and a
     * system app only when it asks for POST_NOTIFICATIONS, has posted before ([posted]: it has
     * notification channels) and is not core system plumbing ("Android System"); minus this app. This
     * keeps "Ad Privacy" style packages out (audit A15).
     */
    fun listed(a: AppEntry, requestsPost: Boolean, posted: Boolean = true): Boolean =
        a.pkg != AppsModel.SELF && a.enabled &&
            (AppsModel.userVisible(a) || (requestsPost && posted && !AppsModel.isProtected(a.pkg)))

    /**
     * Whether the switch can change anything: NotificationManagerService only flips the
     * POST_NOTIFICATIONS grant, and ignores apps that do not request it or where it is fixed.
     */
    fun changeable(requestsPost: Boolean, fixed: Boolean): Boolean = requestsPost && !fixed

    /** The switch's second line; a locked switch says why (the app never asks, or the system fixed it). */
    fun secondary(on: Boolean, requestsPost: Boolean, fixed: Boolean): String = when {
        !requestsPost -> if (on) "On" else "Not requested by app"
        fixed -> "Set by system"
        on -> "On"
        else -> "Off"
    }
}
