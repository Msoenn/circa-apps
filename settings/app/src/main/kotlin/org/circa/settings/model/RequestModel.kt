package org.circa.settings.model

/** The system requests Circa Settings answers with a round confirm screen instead of AOSP's phone dialog. */
enum class RequestKind { BT_ENABLE, BT_DISABLE, BT_DISCOVERABLE, WIFI_ENABLE, WIFI_DISABLE, UNINSTALL }

/** Pure parts of `RequestActivity` (tested in `RequestModelTest`; the manifest filters must match [ACTIONS]). */
object RequestModel {
    const val BT_ENABLE = "android.bluetooth.adapter.action.REQUEST_ENABLE"
    const val BT_DISABLE = "android.bluetooth.adapter.action.REQUEST_DISABLE"
    const val BT_DISCOVERABLE = "android.bluetooth.adapter.action.REQUEST_DISCOVERABLE"
    const val WIFI_ENABLE = "android.net.wifi.action.REQUEST_ENABLE"
    const val WIFI_DISABLE = "android.net.wifi.action.REQUEST_DISABLE"
    const val DELETE = "android.intent.action.DELETE"
    const val UNINSTALL_PACKAGE = "android.intent.action.UNINSTALL_PACKAGE"
    const val EXTRA_DISCOVERABLE_DURATION = "android.bluetooth.adapter.extra.DISCOVERABLE_DURATION"

    val ACTIONS: Map<String, RequestKind> = mapOf(
        BT_ENABLE to RequestKind.BT_ENABLE,
        BT_DISABLE to RequestKind.BT_DISABLE,
        BT_DISCOVERABLE to RequestKind.BT_DISCOVERABLE,
        WIFI_ENABLE to RequestKind.WIFI_ENABLE,
        WIFI_DISABLE to RequestKind.WIFI_DISABLE,
        DELETE to RequestKind.UNINSTALL,
        UNINSTALL_PACKAGE to RequestKind.UNINSTALL,
    )

    fun kindFor(action: String?): RequestKind? = ACTIONS[action]

    const val DEFAULT_DISCOVERABLE_S = 120
    const val MAX_DISCOVERABLE_S = 3600

    /** The requested visibility time: missing or negative = 120 s, 0 (always) and anything long = 1 hour. */
    fun discoverableSeconds(extra: Int?): Int = when {
        extra == null || extra < 0 -> DEFAULT_DISCOVERABLE_S
        extra == 0 || extra > MAX_DISCOVERABLE_S -> MAX_DISCOVERABLE_S
        else -> extra
    }

    fun secondsLabel(s: Int): String = when {
        s % 3600 == 0 -> if (s / 3600 == 1) "1 hour" else "${s / 3600} hours"
        s % 60 == 0 -> if (s / 60 == 1) "1 minute" else "${s / 60} minutes"
        else -> "$s seconds"
    }

    /** The question on the screen. [app] is the caller's label (shown above it by the screen). */
    fun title(kind: RequestKind, discoverableS: Int = DEFAULT_DISCOVERABLE_S, target: String? = null): String = when (kind) {
        RequestKind.BT_ENABLE -> "Turn on Bluetooth?"
        RequestKind.BT_DISABLE -> "Turn off Bluetooth?"
        RequestKind.BT_DISCOVERABLE -> "Make watch visible?"
        RequestKind.WIFI_ENABLE -> "Turn on Wi-Fi?"
        RequestKind.WIFI_DISABLE -> "Turn off Wi-Fi?"
        RequestKind.UNINSTALL -> "Uninstall ${target ?: "this app"}?"
    }

    /** A second, smaller line under the question, or null. */
    fun detail(kind: RequestKind, discoverableS: Int = DEFAULT_DISCOVERABLE_S): String? = when (kind) {
        RequestKind.BT_DISCOVERABLE -> "Nearby devices can find it for ${secondsLabel(discoverableS)}"
        else -> null
    }

    fun caption(app: String?): String? = app?.takeIf { it.isNotBlank() }?.let { "$it asks to" }

    /** `package:com.foo` -> `com.foo`. */
    fun packageFromData(data: String?): String? =
        data?.takeIf { it.startsWith("package:") }?.removePrefix("package:")?.substringBefore('#')?.trim()?.takeIf { it.isNotEmpty() }

    /** Why an app cannot be removed from the watch, or null when it can. */
    fun uninstallRefusal(pkg: String, system: Boolean): String? = when {
        pkg == AppsModel.SELF -> "Settings can't be uninstalled"
        system -> "This app is part of the watch software"
        else -> null
    }
}
