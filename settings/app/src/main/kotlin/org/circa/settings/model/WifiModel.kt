package org.circa.settings.model

/**
 * The pure part of the Wi-Fi pages: what a scan result's security is, how scan results and saved
 * configurations merge into the rows the Wi-Fi page lists, signal bars, labels and the join outcome.
 * `data/WifiData` feeds it from WifiManager; `WifiModelTest` covers it.
 */

/** What joining a network needs. [typeId] is `WifiConfiguration.SECURITY_TYPE_*` for the config we create. */
enum class WifiSecurity(val label: String, val typeId: Int, val needsPassword: Boolean) {
    OPEN("Open", 0, false),
    /** Enhanced Open: encrypted without a password. */
    OWE("Enhanced open", 6, false),
    WEP("WEP", 1, true),
    PSK("WPA2", 2, true),
    /** WPA2/WPA3 transition BSS: joined as PSK, the framework upgrades to SAE where it can. */
    PSK_SAE("WPA2/WPA3", 2, true),
    SAE("WPA3", 4, true),
    /** Enterprise (802.1X) needs certificates and an identity: listed, not joinable from the watch. */
    EAP("Enterprise", 3, true);

    val secured: Boolean get() = this != OPEN && this != OWE

    /** Whether the watch can join it from the password page (no 802.1X setup on a 200 dp screen). */
    val joinable: Boolean get() = this != EAP

    companion object {
        /**
         * From `ScanResult.capabilities` ("[WPA2-PSK-CCMP][RSN-PSK+SAE-CCMP][ESS]", "[ESS]",
         * "[RSN-OWE-CCMP]", ...). An open BSS that advertises an OWE transition partner
         * ("[OWE_TRANSITION]") is still joined as open: the framework moves to the OWE BSS itself.
         */
        fun fromCapabilities(caps: String): WifiSecurity {
            val c = caps.uppercase()
            val sae = c.contains("SAE")
            val psk = c.contains("PSK")
            return when {
                c.contains("EAP") || c.contains("802.1X") -> EAP
                psk && sae -> PSK_SAE
                sae -> SAE
                psk -> PSK
                c.contains("WEP") -> WEP
                c.contains("OWE") && !c.contains("OWE_TRANSITION") -> OWE
                else -> OPEN
            }
        }

        /** From the `WifiConfiguration.SECURITY_TYPE_*` set a saved config carries. */
        fun fromSecurityTypes(types: Collection<Int>): WifiSecurity = when {
            3 in types || 5 in types || 9 in types -> EAP
            // A PSK config also carries an SAE "auto-upgrade" param (WifiConfigManager adds it), so
            // PSK+SAE on a saved config is a WPA2 network the watch may upgrade, not a transition setup.
            2 in types -> PSK
            4 in types -> SAE
            1 in types -> WEP
            // An open config carries an OWE "upgrade" param too (open + owe^): still open.
            0 in types -> OPEN
            6 in types -> OWE
            else -> OPEN
        }
    }
}

/** One BSS from the last scan. */
data class WifiScan(val ssid: String, val rssi: Int, val security: WifiSecurity)

/** One saved configuration. */
data class WifiSaved(val netId: Int, val ssid: String, val security: WifiSecurity)

/** The network the watch is on or trying to join (from WifiInfo). */
data class WifiCurrent(val netId: Int, val ssid: String?, val connected: Boolean)

enum class WifiRowState(val label: String) {
    CONNECTED("Connected"),
    CONNECTING("Connecting…"),
    SAVED("Saved"),
    SECURED("Secured"),
    OPEN("Open"),
}

/** One row of the Wi-Fi page: an SSID in range, merged across its BSSs and its saved config. */
data class WifiEntry(
    val ssid: String,
    /** 0..3 bars, -1 = not in range (saved list only). */
    val level: Int,
    val security: WifiSecurity,
    /** Saved config id, or -1. */
    val netId: Int,
    val state: WifiRowState,
) {
    val saved: Boolean get() = netId >= 0
}

object WifiModel {
    const val MIN_PSK_LENGTH = 8
    const val MAX_PSK_LENGTH = 63

    /** `WifiConfiguration.SSID` / `WifiInfo.getSSID()` quote UTF-8 names; "<unknown ssid>" means none. */
    fun unquote(raw: String?): String? {
        if (raw == null) return null
        val s = if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) raw.substring(1, raw.length - 1) else raw
        return s.takeUnless { it.isBlank() || it == "<unknown ssid>" }
    }

    fun quote(ssid: String) = "\"$ssid\""

    /** 4-level bars (0..3) from RSSI, using AOSP's Wi-Fi thresholds (-88/-77/-66/-55 dBm). */
    fun level(rssi: Int): Int = when {
        rssi >= -55 -> 3
        rssi >= -66 -> 2
        rssi >= -77 -> 1
        else -> 0
    }

    fun levelLabel(level: Int): String = when (level) {
        3 -> "Excellent"
        2 -> "Good"
        1 -> "Fair"
        0 -> "Weak"
        else -> "Not in range"
    }

    /**
     * The Wi-Fi page's rows: one per non-blank SSID in range (strongest BSS wins), its saved config
     * attached when the SSID matches one; the network the watch is on first, then strongest first,
     * then by name.
     */
    fun entries(scans: List<WifiScan>, saved: List<WifiSaved>, current: WifiCurrent?): List<WifiEntry> {
        val savedBySsid = saved.associateBy { it.ssid }
        return scans
            .filter { it.ssid.isNotBlank() }
            .groupBy { it.ssid }
            .map { (ssid, bss) ->
                val best = bss.maxBy { it.rssi }
                val conf = savedBySsid[ssid]
                WifiEntry(
                    ssid = ssid,
                    level = level(best.rssi),
                    // What is on the air (a saved config's types include auto-upgrade params).
                    security = best.security,
                    netId = conf?.netId ?: -1,
                    state = rowState(ssid, conf, best.security, current),
                ) to best.rssi
            }
            .sortedWith(
                compareByDescending<Pair<WifiEntry, Int>> { it.first.state == WifiRowState.CONNECTED || it.first.state == WifiRowState.CONNECTING }
                    .thenByDescending { it.first.level }
                    .thenByDescending { it.second }
                    .thenBy { it.first.ssid.lowercase() },
            )
            .map { it.first }
    }

    /** Every saved network, in range or not, with its current state: for "Saved networks". */
    fun savedEntries(scans: List<WifiScan>, saved: List<WifiSaved>, current: WifiCurrent?): List<WifiEntry> {
        val best = scans.filter { it.ssid.isNotBlank() }.groupBy { it.ssid }.mapValues { (_, b) -> b.maxOf { it.rssi } }
        return saved.distinctBy { it.ssid }.map { conf ->
            WifiEntry(
                ssid = conf.ssid,
                level = best[conf.ssid]?.let(::level) ?: -1,
                security = conf.security,
                netId = conf.netId,
                state = rowState(conf.ssid, conf, conf.security, current),
            )
        }.sortedBy { it.ssid.lowercase() }
    }

    private fun rowState(ssid: String, conf: WifiSaved?, security: WifiSecurity, current: WifiCurrent?): WifiRowState {
        val onIt = current != null && (current.ssid == ssid || (conf != null && current.netId == conf.netId))
        return when {
            onIt && current!!.connected -> WifiRowState.CONNECTED
            onIt -> WifiRowState.CONNECTING
            conf != null -> WifiRowState.SAVED
            security.secured -> WifiRowState.SECURED
            else -> WifiRowState.OPEN
        }
    }

    /** What tapping a row does. */
    enum class Tap { DETAILS, CONNECT_OPEN, ASK_PASSWORD, UNSUPPORTED }

    fun tap(e: WifiEntry): Tap = when {
        e.saved || e.state == WifiRowState.CONNECTED -> Tap.DETAILS
        !e.security.joinable -> Tap.UNSUPPORTED
        e.security.needsPassword -> Tap.ASK_PASSWORD
        else -> Tap.CONNECT_OPEN
    }

    /** Null = acceptable; else the message to show under the field. */
    fun passwordProblem(security: WifiSecurity, password: String): String? = when {
        !security.needsPassword -> null
        security == WifiSecurity.WEP -> if (password.isEmpty()) "Enter the password" else null
        password.length < MIN_PSK_LENGTH -> "At least $MIN_PSK_LENGTH characters"
        password.length > MAX_PSK_LENGTH -> "At most $MAX_PSK_LENGTH characters"
        else -> null
    }
}

/** Where a join attempt stands. */
enum class JoinOutcome(val label: String, val done: Boolean) {
    CONNECTING("Connecting…", false),
    CONNECTED("Connected", true),
    WRONG_PASSWORD("Wrong password", true),
    FAILED("Couldn't connect", true),
}

object JoinTracker {
    /** WifiConfiguration.NetworkSelectionStatus disable reasons (hidden constants, stable values). */
    const val DISABLED_ASSOCIATION_REJECTION = 1
    const val DISABLED_AUTHENTICATION_FAILURE = 2
    const val DISABLED_DHCP_FAILURE = 3
    const val DISABLED_AUTHENTICATION_NO_CREDENTIALS = 5
    const val DISABLED_BY_WRONG_PASSWORD = 8

    /** Give up after this long without an IP on the network. */
    const val TIMEOUT_MS = 30_000L

    /**
     * The outcome of joining [targetSsid], from what the platform shows now: the network the watch is
     * on (with an IP = connected), the target config's disable reason, a failure reported by the
     * connect call, and how long we have waited.
     */
    fun outcome(
        targetSsid: String,
        current: WifiCurrent?,
        disableReason: Int,
        connectCallFailed: Boolean,
        elapsedMs: Long,
    ): JoinOutcome = when {
        current?.ssid == targetSsid && current.connected -> JoinOutcome.CONNECTED
        disableReason == DISABLED_BY_WRONG_PASSWORD -> JoinOutcome.WRONG_PASSWORD
        disableReason == DISABLED_AUTHENTICATION_FAILURE -> JoinOutcome.WRONG_PASSWORD
        disableReason == DISABLED_ASSOCIATION_REJECTION || disableReason == DISABLED_DHCP_FAILURE ||
            disableReason == DISABLED_AUTHENTICATION_NO_CREDENTIALS -> JoinOutcome.FAILED
        connectCallFailed -> JoinOutcome.FAILED
        elapsedMs >= TIMEOUT_MS -> JoinOutcome.FAILED
        else -> JoinOutcome.CONNECTING
    }
}
