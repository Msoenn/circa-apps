package org.circa.settings.data

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.provider.Settings
import java.lang.reflect.Method
import org.circa.settings.model.BrightnessCycle

/** What a quick-settings tile draws: on, off, or not backed by a working platform here. */
enum class ToggleState { ON, OFF, UNAVAILABLE }

/** One snapshot of everything the quick-settings grid shows when the tray opens. */
data class QuickSettingsState(
    val dnd: ToggleState,
    val bluetooth: ToggleState,
    val wifi: ToggleState,
    val batterySaver: ToggleState,
    val brightness: Int,
    /** Status-row indicators (read-only): airplane mode, location services, auto brightness. */
    val airplane: Boolean = false,
    val location: Boolean = false,
    val autoBrightness: Boolean = false,
    /** The status pill under the grid: a phone is connected to WatchLink's GATT server (read-only). */
    val phoneConnected: Boolean = false,
)

/**
 * The quick-settings toggles, against the real platform APIs (copied from the launcher's tray code;
 * the Connectivity / Display / Battery pages use them). Android-dependent by design; the app is
 * platform-signed, so the signature-level permissions below are granted (see
 * settings/README.md), and every call still reports failure rather than pretending:
 * a toggle whose API is refused is [ToggleState.UNAVAILABLE] and does nothing when tapped.
 */
class QuickSettings(private val context: Context) {

    private val notificationManager: NotificationManager?
        get() = context.getSystemService(NotificationManager::class.java)

    private val bluetoothAdapter: BluetoothAdapter?
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private val wifiManager: WifiManager?
        get() = context.applicationContext.getSystemService(WifiManager::class.java)

    private val powerManager: PowerManager?
        get() = context.getSystemService(PowerManager::class.java)

    /** The @SystemApi battery-saver setter, resolved once; null when this platform has neither. */
    private val setPowerSaveModeMethod: Method? = SETTER_NAMES.firstNotNullOfOrNull { name ->
        runCatching {
            PowerManager::class.java.getMethod(name, Boolean::class.javaPrimitiveType)
        }.getOrNull()
    }

    private companion object {
        const val DEVICE_POWER = "android.permission.DEVICE_POWER"
        val SETTER_NAMES = listOf("setPowerSaveModeEnabled", "setPowerSaveMode")
    }

    /** Everything the grid needs, read in one pass. */
    fun read(): QuickSettingsState = QuickSettingsState(
        dnd = dndState(),
        bluetooth = bluetoothState(),
        wifi = wifiState(),
        batterySaver = batterySaverState(),
        brightness = brightness(),
        airplane = Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.AIRPLANE_MODE_ON,
            0,
        ) != 0,
        location = context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true,
        autoBrightness = Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
        phoneConnected = phoneConnected(),
    )

    /**
     * WatchLink is a BLE GATT *server*, so the devices connected on the GATT_SERVER profile are the phone
     * (Gadgetbridge). A read of the Bluetooth service's connection table: no scan, no connect, no new BLE
     * operation. False when Bluetooth is off, the permission is missing or the call is refused.
     */
    fun phoneConnected(): Boolean = runCatching {
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return false
        if (manager.adapter?.isEnabled != true) return false
        manager.getConnectedDevices(android.bluetooth.BluetoothProfile.GATT_SERVER).isNotEmpty()
    }.getOrDefault(false)

    // ---- Do Not Disturb: NotificationManager.setInterruptionFilter (ACCESS_NOTIFICATION_POLICY) --

    fun dndState(): ToggleState {
        val manager = notificationManager ?: return ToggleState.UNAVAILABLE
        // Policy access is a user grant on top of the permission; without it the platform refuses
        // the filter change outright, so the tile is honest and shows as unavailable.
        if (!manager.isNotificationPolicyAccessGranted) return ToggleState.UNAVAILABLE
        return when (manager.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> ToggleState.OFF
            else -> ToggleState.ON
        }
    }

    fun setDnd(on: Boolean): Boolean {
        val manager = notificationManager ?: return false
        if (!manager.isNotificationPolicyAccessGranted) return false
        val filter =
            if (on) NotificationManager.INTERRUPTION_FILTER_NONE
            else NotificationManager.INTERRUPTION_FILTER_ALL
        return try {
            manager.setInterruptionFilter(filter)
            true
        } catch (e: RuntimeException) {
            false
        }
    }

    // ---- Bluetooth: BluetoothAdapter.enable/disable -------------------------------------------

    fun bluetoothState(): ToggleState {
        val adapter = bluetoothAdapter ?: return ToggleState.UNAVAILABLE
        return when (adapter.state) {
            BluetoothAdapter.STATE_ON -> ToggleState.ON
            BluetoothAdapter.STATE_OFF -> ToggleState.OFF
            BluetoothAdapter.STATE_TURNING_ON -> ToggleState.ON
            BluetoothAdapter.STATE_TURNING_OFF -> ToggleState.OFF
            else -> ToggleState.UNAVAILABLE
        }
    }

    fun setBluetooth(on: Boolean): Boolean {
        val adapter = bluetoothAdapter ?: return false
        return try {
            if (on) adapter.enable() else adapter.disable()
        } catch (e: SecurityException) {
            false
        }
    }

    // ---- Wi-Fi: WifiManager.setWifiEnabled, else the platform Wi-Fi panel ----------------------

    fun wifiState(): ToggleState {
        val manager = wifiManager ?: return ToggleState.UNAVAILABLE
        return if (manager.isWifiEnabled) ToggleState.ON else ToggleState.OFF
    }

    /**
     * Wi-Fi on/off. The platform only lets a system-privileged app (NETWORK_SETTINGS) flip the
     * radio; when the call is refused this falls back to the platform's own Wi-Fi panel, so the
     * button still does something real. Returns true when this app toggled the radio itself.
     */
    fun setWifi(on: Boolean): Boolean {
        val manager = wifiManager ?: return false
        val applied = try {
            manager.setWifiEnabled(on)
        } catch (e: SecurityException) {
            false
        }
        if (!applied) openWifiPanel()
        return applied
    }

    /** The platform's Wi-Fi picker (`Settings.Panel.ACTION_WIFI`), the fallback above. */
    private fun openWifiPanel() {
        runCatching {
            context.startActivity(
                Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    // ---- Battery saver: PowerManager.setPowerSaveModeEnabled (DEVICE_POWER) ---------------------

    fun batterySaverState(): ToggleState {
        val manager = powerManager ?: return ToggleState.UNAVAILABLE
        if (setPowerSaveModeMethod == null || !holds(DEVICE_POWER)) return ToggleState.UNAVAILABLE
        return if (manager.isPowerSaveMode) ToggleState.ON else ToggleState.OFF
    }

    /**
     * `PowerManager.setPowerSaveModeEnabled` is @SystemApi (and its older spelling
     * `setPowerSaveMode` deprecated), so neither is in the public SDK the app compiles against;
     * both need DEVICE_POWER, which the platform signature grants. Reflection is the only way in
     * from an app built against the public SDK - and a platform-signed app is exempt from the
     * hidden-API blocklist, which is why this works here.
     */
    fun setBatterySaver(on: Boolean): Boolean {
        val manager = powerManager ?: return false
        val method = setPowerSaveModeMethod ?: return false
        return runCatching {
            method.invoke(manager, on)
            true
        }.getOrDefault(false)
    }

    // ---- Brightness: Settings.System.SCREEN_BRIGHTNESS, manual mode (WRITE_SETTINGS) ------------

    fun brightness(): Int =
        Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            BrightnessCycle.LEVELS.first(),
        )

    /**
     * Step the panel brightness to the next of the three levels. Writes the manual brightness mode
     * first, so the value is what the display actually uses (auto mode would override it), and
     * returns the level now in effect.
     */
    fun cycleBrightness(): Int {
        val next = BrightnessCycle.next(brightness())
        return try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                next,
            )
            next
        } catch (e: RuntimeException) {
            brightness()
        }
    }

    /** Set the manual brightness (0..255) from the Settings level control; returns the value in effect. */
    fun setBrightness(level: Int): Int = try {
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        )
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, level)
        level
    } catch (e: RuntimeException) {
        brightness()
    }

    private fun holds(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
