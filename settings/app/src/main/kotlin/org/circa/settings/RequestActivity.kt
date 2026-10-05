package org.circa.settings

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.circa.settings.data.AppsData
import org.circa.settings.data.QuickSettings
import org.circa.settings.data.SystemSettings
import org.circa.settings.model.RequestKind
import org.circa.settings.model.RequestModel
import org.circa.settings.ui.RequestScreen
import org.circa.settings.ui.RequestUi
import org.circa.settings.ui.Theme

/**
 * Round confirm screens for what apps ask the system to do: turn Bluetooth / Wi-Fi on or off, make the
 * watch discoverable, and uninstall an app (audit A05 / A06). They replace AOSP Settings'
 * RequestPermissionActivity / RequestToggleWiFiActivity and PackageInstaller's UninstallerActivity, which are
 * phone dialogs. The manifest claims the same actions at priority 100. The results follow the AOSP contracts:
 * RESULT_OK / RESULT_CANCELED (enable, disable, uninstall; a failed uninstall gives RESULT_FIRST_USER),
 * and the visible seconds as the result code for REQUEST_DISCOVERABLE.
 */
class RequestActivity : ComponentActivity() {

    private var ui by mutableStateOf<RequestUi>(RequestUi.Closed)
    private lateinit var kind: RequestKind
    private var seconds = RequestModel.DEFAULT_DISCOVERABLE_S
    private var targetPkg: String? = null
    private val appsData by lazy { AppsData(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        onBackPressedDispatcher.addCallback(this) { done(Activity.RESULT_CANCELED) }

        val k = RequestModel.kindFor(intent?.action)
        if (k == null) { done(Activity.RESULT_CANCELED); return }
        kind = k
        seconds = RequestModel.discoverableSeconds(
            if (intent.hasExtra(RequestModel.EXTRA_DISCOVERABLE_DURATION))
                intent.getIntExtra(RequestModel.EXTRA_DISCOVERABLE_DURATION, 0) else null)
        val caller = callingPackage ?: referrer?.takeIf { it.scheme == "android-app" }?.host
        val callerLabel = caller?.let { appsData.app(it)?.label }

        // Already in the requested state: answer OK without asking, like AOSP.
        if ((kind == RequestKind.BT_ENABLE && adapter()?.isEnabled == true) ||
            (kind == RequestKind.BT_DISABLE && adapter()?.isEnabled != true) ||
            (kind == RequestKind.WIFI_ENABLE && wifi()?.isWifiEnabled == true) ||
            (kind == RequestKind.WIFI_DISABLE && wifi()?.isWifiEnabled != true)) {
            done(Activity.RESULT_OK); return
        }

        if (kind == RequestKind.UNINSTALL) {
            val pkg = RequestModel.packageFromData(intent.dataString)
            targetPkg = pkg
            val entry = pkg?.let { appsData.app(it) }
            val refusal = when {
                pkg == null || entry == null -> "That app isn't installed"
                caller != null && !mayRequestDelete(caller) -> "This app can't ask to uninstall apps"
                else -> RequestModel.uninstallRefusal(pkg, entry.system)
            }
            ui = if (refusal != null) RequestUi.Refused(refusal, pkg)
            else RequestUi.Ask(RequestModel.caption(callerLabel), RequestModel.title(kind, target = entry!!.label), pkg)
        } else {
            ui = RequestUi.Ask(RequestModel.caption(callerLabel), RequestModel.title(kind, seconds), null, RequestModel.detail(kind, seconds))
        }

        val accent = SystemSettings(this, QuickSettings(this)).read().accent
        setContent {
            val cfg = LocalConfiguration.current
            val sys = LocalDensity.current
            val widthPx = (cfg.screenWidthDp * sys.density).toInt()
            CompositionLocalProvider(
                LocalDensity provides Density(org.circa.settings.model.Density.densityFor(widthPx), sys.fontScale),
            ) {
                Theme(accent) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.semantics { testTagsAsResourceId = true },
                    ) { RequestScreen(ui, onNo = { done(Activity.RESULT_CANCELED) }, onYes = ::accept, onClose = { done(Activity.RESULT_CANCELED) }) }
                }
            }
        }
    }

    private fun adapter(): BluetoothAdapter? = getSystemService(BluetoothManager::class.java)?.adapter
    private fun wifi(): WifiManager? = getSystemService(WifiManager::class.java)

    /** Third-party callers need REQUEST_DELETE_PACKAGES (the same rule as PackageInstaller's UninstallerActivity). */
    private fun mayRequestDelete(caller: String): Boolean =
        packageManager.checkPermission("android.permission.REQUEST_DELETE_PACKAGES", caller) == PackageManager.PERMISSION_GRANTED ||
            packageManager.checkPermission("android.permission.DELETE_PACKAGES", caller) == PackageManager.PERMISSION_GRANTED

    private fun accept() {
        val current = ui as? RequestUi.Ask ?: return
        ui = RequestUi.Working(current.title)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { perform() }.onFailure { Log.w(TAG, "perform $kind", it) }.getOrDefault(Activity.RESULT_CANCELED) }
            done(result)
        }
    }

    /** Does the request; returns the activity result code. */
    private suspend fun perform(): Int = when (kind) {
        RequestKind.BT_ENABLE -> if (setBluetooth(true)) Activity.RESULT_OK else Activity.RESULT_CANCELED
        RequestKind.BT_DISABLE -> if (setBluetooth(false)) Activity.RESULT_OK else Activity.RESULT_CANCELED
        RequestKind.BT_DISCOVERABLE -> {
            if (setBluetooth(true) && makeDiscoverable(seconds)) seconds else Activity.RESULT_CANCELED
        }
        RequestKind.WIFI_ENABLE -> if (setWifi(true)) Activity.RESULT_OK else Activity.RESULT_CANCELED
        RequestKind.WIFI_DISABLE -> if (setWifi(false)) Activity.RESULT_OK else Activity.RESULT_CANCELED
        RequestKind.UNINSTALL -> {
            val pkg = targetPkg
            if (pkg != null && appsData.uninstall(pkg) || (pkg != null && !appsData.isInstalled(pkg))) Activity.RESULT_OK
            else Activity.RESULT_FIRST_USER
        }
    }

    private suspend fun setBluetooth(on: Boolean): Boolean {
        val a = adapter() ?: return false
        if (a.isEnabled == on) return true
        if (try { if (on) a.enable() else a.disable() } catch (e: SecurityException) { false }.not()) return false
        return pollUntil { a.isEnabled == on }
    }

    /**
     * BluetoothAdapter.setDiscoverableTimeout(Duration) then setScanMode(int) (both @SystemApi, BLUETOOTH_PRIVILEGED;
     * the old setScanMode(int, long) is gone on Android 16). Both return 0 (BluetoothStatusCodes.SUCCESS) on success.
     */
    private fun makeDiscoverable(seconds: Int): Boolean = runCatching {
        val a = adapter() ?: return false
        val c = BluetoothAdapter::class.java
        val t = c.getMethod("setDiscoverableTimeout", java.time.Duration::class.java)
            .invoke(a, java.time.Duration.ofSeconds(seconds.toLong())) as Int
        val m = c.getMethod("setScanMode", Int::class.javaPrimitiveType)
            .invoke(a, BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) as Int
        Log.i(TAG, "discoverable $seconds s: timeout=$t scanMode=$m")
        t == 0 && m == 0
    }.onFailure { Log.w(TAG, "setScanMode", it) }.getOrDefault(false)

    private suspend fun setWifi(on: Boolean): Boolean {
        val w = wifi() ?: return false
        if (w.isWifiEnabled == on) return true
        if (try { w.setWifiEnabled(on) } catch (e: SecurityException) { false }.not()) return false
        return pollUntil { w.isWifiEnabled == on }
    }

    /** Bounded poll (about 10 s) for [cond]. */
    private suspend fun pollUntil(cond: () -> Boolean): Boolean {
        repeat(50) { if (cond()) return true; delay(200) }
        return cond()
    }

    private fun done(code: Int) {
        setResult(code, if (code > 0) Intent() else null)
        finish()
    }

    companion object { private const val TAG = "CircaRequest" }
}
