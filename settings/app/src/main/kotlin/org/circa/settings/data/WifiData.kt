package org.circa.settings.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.SupplicantState
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import java.lang.reflect.Proxy
import java.net.Inet4Address
import org.circa.settings.model.JoinOutcome
import org.circa.settings.model.JoinTracker
import org.circa.settings.model.WifiCurrent
import org.circa.settings.model.WifiModel
import org.circa.settings.model.WifiSaved
import org.circa.settings.model.WifiScan
import org.circa.settings.model.WifiSecurity

/** Everything the Wi-Fi pages draw, read in one pass. */
data class WifiSnapshot(
    val enabled: Boolean,
    val scans: List<WifiScan>,
    val saved: List<WifiSaved>,
    val current: WifiCurrent?,
    val ip: String?,
    val linkMbps: Int?,
    val rssi: Int?,
)

/** A join (or connect) in flight, observed until it settles; shown on the join page and the list rows. */
data class WifiJoin(
    val ssid: String,
    val startedAt: Long,
    val outcome: JoinOutcome,
    /** Created by this join (a new network): forgotten again if the join fails, so a typo is not kept. */
    val createdNetId: Int,
    val callFailed: Boolean = false,
)

/**
 * Wi-Fi through the platform, for a platform-signed app holding NETWORK_SETTINGS (the same position as
 * AOSP Settings):
 *  * NETWORK_SETTINGS exempts the caller from the location requirement on scan results
 *    (WifiPermissionsUtil.enforceCanAccessScanResults) and from scan throttling, so no location
 *    permission or location mode is needed.
 *  * Joining uses the @SystemApi `WifiManager.connect(WifiConfiguration, ActionListener)` (what AOSP
 *    Settings calls: saves the config and connects at once, with user-connect priority), reached by
 *    reflection since the compile classpath is the public SDK; the fallback is the deprecated public
 *    addNetwork + enableNetwork(disableOthers), which still works for NETWORK_SETTINGS holders.
 *  * Forget / connect-saved use `forget(int, ActionListener)` / `connect(int, ActionListener)` the same way.
 * All reflection lives here.
 */
class WifiData(context: Context) {
    private val app = context.applicationContext
    private val wifi: WifiManager? = app.getSystemService(WifiManager::class.java)
    private val cm: ConnectivityManager? = app.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "CircaWifi"

        /** The join in flight, shared by the pages (it outlives the join page). */
        val join: MutableState<WifiJoin?> = mutableStateOf(null)
    }

    fun read(): WifiSnapshot {
        val m = wifi ?: return WifiSnapshot(false, emptyList(), emptyList(), null, null, null, null)
        val enabled = runCatching { m.isWifiEnabled }.getOrDefault(false)
        val scans = if (!enabled) emptyList() else runCatching {
            m.scanResults.mapNotNull { r ->
                @Suppress("DEPRECATION")
                val ssid = r.wifiSsid?.toString()?.let(WifiModel::unquote) ?: WifiModel.unquote(r.SSID)
                ssid?.let { WifiScan(it, r.level, WifiSecurity.fromCapabilities(r.capabilities ?: "")) }
            }
        }.onFailure { Log.w(TAG, "scan results", it) }.getOrDefault(emptyList())
        // For privileged callers the service returns one entry per security type of a config (an open
        // network with its OWE upgrade comes back twice, same networkId): merge them per networkId.
        val saved = configs().groupBy { it.networkId }.mapNotNull { (id, cs) ->
            WifiModel.unquote(cs.first().SSID)?.let { ssid ->
                WifiSaved(id, ssid, WifiSecurity.fromSecurityTypes(cs.flatMap(::securityTypes).toSet()))
            }
        }
        @Suppress("DEPRECATION")
        val info = if (enabled) runCatching { m.connectionInfo }.getOrNull() else null
        val wifiNet = runCatching {
            cm?.allNetworks?.firstOrNull { n ->
                cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        }.onFailure { Log.w(TAG, "networks", it) }.getOrNull()
        val ip = wifiNet?.let { n ->
            runCatching {
                cm?.getLinkProperties(n)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address }?.hostAddress
            }.onFailure { Log.w(TAG, "link properties", it) }.getOrNull()
        } ?: info?.let {
            // Fallback without ConnectivityManager: WifiInfo's IPv4 address (little-endian int).
            @Suppress("DEPRECATION")
            val a = it.ipAddress
            if (a == 0) null else "${a and 0xff}.${a shr 8 and 0xff}.${a shr 16 and 0xff}.${a shr 24 and 0xff}"
        }
        val current = info?.takeIf { it.networkId != -1 || it.supplicantState != SupplicantState.DISCONNECTED }?.let {
            val ssid = WifiModel.unquote(it.ssid) ?: saved.firstOrNull { s -> s.netId == it.networkId }?.ssid
            // "Connected" = associated and the Wi-Fi network has an address, like the status bar.
            WifiCurrent(it.networkId, ssid, it.supplicantState == SupplicantState.COMPLETED && ip != null)
        }?.takeIf { it.ssid != null && (it.netId != -1 || it.connected) }
        return WifiSnapshot(
            enabled = enabled,
            scans = scans,
            saved = saved,
            current = current,
            ip = ip,
            linkMbps = info?.linkSpeed?.takeIf { it > 0 && current?.connected == true },
            rssi = info?.rssi?.takeIf { current?.connected == true },
        )
    }

    /** Saved configurations (getConfiguredNetworks: empty for ordinary apps since Q, full for NETWORK_SETTINGS). */
    @Suppress("DEPRECATION")
    private fun configs(): List<WifiConfiguration> =
        runCatching { wifi?.configuredNetworks.orEmpty() }.onFailure { Log.w(TAG, "configs", it) }.getOrDefault(emptyList())

    private fun securityTypes(c: WifiConfiguration): List<Int> {
        // getSecurityParamsList is hidden; isSecurityType(int) is hidden too: probe each type by reflection.
        val types = runCatching {
            val probe = WifiConfiguration::class.java.getMethod("isSecurityType", Int::class.javaPrimitiveType)
            (0..13).filter { probe.invoke(c, it) as Boolean }
        }.getOrElse {
            @Suppress("DEPRECATION")
            when {
                c.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SAE) -> listOf(4)
                c.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK) -> listOf(2)
                c.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.OWE) -> listOf(6)
                else -> listOf(0)
            }
        }
        return types
    }

    fun startScan(): Boolean = runCatching {
        @Suppress("DEPRECATION")
        wifi?.startScan() == true
    }.getOrDefault(false)

    fun setEnabled(on: Boolean): Boolean = runCatching {
        @Suppress("DEPRECATION")
        wifi?.setWifiEnabled(on) == true
    }.getOrDefault(false)

    // ---- joining ----------------------------------------------------------------------------------

    /**
     * A new network: build its config, save and connect it, and start observing the outcome. A visible
     * network whose join fails is forgotten again (a mistyped password is not kept); a [hidden] one
     * added by hand stays saved (it may simply be out of range now), as in AOSP Settings.
     */
    fun joinNew(ssid: String, security: WifiSecurity, password: String?, hidden: Boolean = false) {
        val existing = configs().firstOrNull { WifiModel.unquote(it.SSID) == ssid }?.networkId ?: -1
        val conf = WifiConfiguration().apply {
            SSID = WifiModel.quote(ssid)
            hiddenSSID = hidden
            setSecurityParams(security.typeId)
            if (security.needsPassword && password != null) {
                if (security == WifiSecurity.WEP) {
                    @Suppress("DEPRECATION")
                    wepKeys[0] = if (password.matches(Regex("[0-9A-Fa-f]{10}|[0-9A-Fa-f]{26}"))) password else WifiModel.quote(password)
                    @Suppress("DEPRECATION")
                    wepTxKeyIndex = 0
                } else {
                    preSharedKey = WifiModel.quote(password)
                }
            }
        }
        if (existing >= 0) conf.networkId = existing
        join.value = WifiJoin(ssid, System.currentTimeMillis(), JoinOutcome.CONNECTING, createdNetId = if (existing >= 0 || hidden) -2 else -1)
        val viaApi = callWithListener("connect", WifiConfiguration::class.java, conf) { ok -> onCallResult(ssid, ok) }
        if (!viaApi) {
            @Suppress("DEPRECATION")
            val id = runCatching { wifi?.addNetwork(conf) ?: -1 }.getOrDefault(-1)
            @Suppress("DEPRECATION")
            val ok = id >= 0 && runCatching { wifi?.enableNetwork(id, true) == true }.getOrDefault(false)
            onCallResult(ssid, ok)
        }
    }

    /** A saved network: connect to it. */
    fun connectSaved(netId: Int, ssid: String) {
        join.value = WifiJoin(ssid, System.currentTimeMillis(), JoinOutcome.CONNECTING, createdNetId = -2)
        val viaApi = callWithListener("connect", Int::class.javaPrimitiveType!!, netId) { ok -> onCallResult(ssid, ok) }
        if (!viaApi) {
            @Suppress("DEPRECATION")
            onCallResult(ssid, runCatching { wifi?.enableNetwork(netId, true) == true }.getOrDefault(false))
        }
    }

    fun forget(netId: Int) {
        if (join.value?.let { j -> configs().any { it.networkId == netId && WifiModel.unquote(it.SSID) == j.ssid } } == true) {
            join.value = null
        }
        val viaApi = callWithListener("forget", Int::class.javaPrimitiveType!!, netId) { }
        if (!viaApi) {
            @Suppress("DEPRECATION")
            runCatching { wifi?.removeNetwork(netId) }
        }
    }

    /**
     * Disconnect from [ssid] and keep it off: a bare disconnect() is undone by auto-join within
     * seconds, so first `disableEphemeralNetwork` (@SystemApi; AOSP Settings' WifiEntry.disconnect does
     * the same), which puts the SSID on the user-disconnect blocklist until the user connects again.
     */
    fun disconnect(ssid: String): Boolean = runCatching {
        join.value = null
        runCatching {
            WifiManager::class.java.getMethod("disableEphemeralNetwork", String::class.java)
                .invoke(wifi, WifiModel.quote(ssid))
        }.onFailure { Log.w(TAG, "disableEphemeralNetwork", it) }
        @Suppress("DEPRECATION")
        wifi?.disconnect() == true
    }.getOrDefault(false)

    private fun onCallResult(ssid: String, ok: Boolean) {
        val j = join.value ?: return
        if (j.ssid != ssid) return
        if (!ok) Log.w(TAG, "connect call failed for a network")
        // Record the netId the call created (to forget it if the join fails).
        val created = if (j.createdNetId == -1) {
            configs().firstOrNull { WifiModel.unquote(it.SSID) == ssid }?.networkId ?: -1
        } else j.createdNetId
        join.value = j.copy(callFailed = !ok, createdNetId = created)
    }

    /**
     * Re-evaluate the join in flight against what the platform shows now (called every second by the
     * pages). A new network whose join failed is forgotten again.
     */
    fun pollJoin(snapshot: WifiSnapshot) {
        val j = join.value ?: return
        if (j.outcome.done) return
        val conf = configs().firstOrNull { WifiModel.unquote(it.SSID) == j.ssid }
        val outcome = JoinTracker.outcome(
            targetSsid = j.ssid,
            current = snapshot.current,
            disableReason = conf?.let(::disableReason) ?: 0,
            connectCallFailed = j.callFailed,
            elapsedMs = System.currentTimeMillis() - j.startedAt,
        )
        if (outcome == j.outcome) return
        if (outcome != JoinOutcome.CONNECTED && j.createdNetId >= 0) forget(j.createdNetId)
        join.value = j.copy(outcome = outcome)
    }

    /** `getNetworkSelectionStatus().getNetworkSelectionDisableReason()` (@SystemApi). */
    private fun disableReason(c: WifiConfiguration): Int = runCatching {
        val status = WifiConfiguration::class.java.getMethod("getNetworkSelectionStatus").invoke(c)
        status!!.javaClass.getMethod("getNetworkSelectionDisableReason").invoke(status) as Int
    }.getOrDefault(0)

    /**
     * `WifiManager.<name>(arg, ActionListener)` by reflection, with a dynamic proxy for the listener
     * (a @SystemApi interface). Returns false when the method is missing or refused, so the caller
     * can fall back to the public API.
     */
    private fun callWithListener(name: String, argType: Class<*>, arg: Any, done: (Boolean) -> Unit): Boolean {
        val m = wifi ?: return false
        return runCatching {
            val listenerType = Class.forName("android.net.wifi.WifiManager\$ActionListener")
            val listener = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { proxy, method, args ->
                when (method.name) {
                    "onSuccess" -> main.post { done(true) }
                    "onFailure" -> {
                        Log.w(TAG, "$name failed: reason ${args?.getOrNull(0)}")
                        main.post { done(false) }
                    }
                    "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                    "equals" -> return@newProxyInstance proxy === args?.getOrNull(0)
                    "toString" -> return@newProxyInstance "CircaWifiListener"
                }
                null
            }
            WifiManager::class.java.getMethod(name, argType, listenerType).invoke(m, arg, listener)
            true
        }.onFailure { Log.w(TAG, "$name via API failed, using fallback", it) }.getOrDefault(false)
    }
}
