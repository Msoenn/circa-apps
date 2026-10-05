package org.circa.settings.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import java.nio.ByteOrder
import org.circa.settings.model.BtKind
import org.circa.settings.model.BtLabels
import org.circa.settings.model.Pairing

/** One remote device as the Bluetooth pages draw it. */
data class BtDeviceInfo(
    val address: String,
    val label: String,
    val kind: BtKind,
    val bonded: Boolean,
    val bonding: Boolean,
    val connected: Boolean,
)

/**
 * Bluetooth for the round pages: paired list, discovery, bond / forget, connect / disconnect,
 * rename, and the answers of the pairing screen. The app is a platform-signed priv-app holding
 * BLUETOOTH_CONNECT + BLUETOOTH_SCAN (runtime) and BLUETOOTH_PRIVILEGED + MODIFY_PHONE_STATE.
 *
 * All @SystemApi / hidden calls are here, by reflection (the compile classpath is the public SDK);
 * each one has a fallback and logs why it failed. Platform-signed apps are exempt from the hidden-API
 * deny list, so reflection reaches them (verified on the emulator).
 *
 * WatchLink holds a BLE bond to the owner's phone: nothing here removes or changes a bond on its own;
 * [forget] runs only from the user's explicit Forget on that device's page.
 */
@SuppressLint("MissingPermission")
class BluetoothData(context: Context) {
    private val app = context.applicationContext
    val adapter: BluetoothAdapter? get() = app.getSystemService(BluetoothManager::class.java)?.adapter

    val isOn: Boolean get() = runCatching { adapter?.isEnabled == true }.getOrDefault(false)

    fun device(address: String?): BluetoothDevice? =
        address?.takeIf { BluetoothAdapter.checkBluetoothAddress(it) }?.let { a -> runCatching { adapter?.getRemoteDevice(a) }.getOrNull() }

    fun info(d: BluetoothDevice): BtDeviceInfo = BtDeviceInfo(
        address = d.address,
        label = labelOf(d),
        kind = BtLabels.kind(runCatching { d.bluetoothClass?.majorDeviceClass }.getOrNull()),
        bonded = runCatching { d.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false),
        bonding = runCatching { d.bondState == BluetoothDevice.BOND_BONDING }.getOrDefault(false),
        connected = isConnected(d),
    )

    fun labelOf(d: BluetoothDevice): String =
        BtLabels.label(runCatching { d.alias }.getOrNull(), runCatching { d.name }.getOrNull())

    /** Bonded devices, connected first, then by name. Empty while Bluetooth is off. */
    fun paired(): List<BtDeviceInfo> = runCatching { adapter?.bondedDevices.orEmpty() }
        .getOrDefault(emptySet())
        .map(::info)
        .sortedWith(compareBy<BtDeviceInfo>({ !it.connected }, { it.label.lowercase() }))

    // ---- discovery ----------------------------------------------------------------------------

    /** Classic inquiry + LE scan (the stack does both); results arrive as ACTION_FOUND. Needs BLUETOOTH_SCAN. */
    fun startDiscovery(): Boolean = runCatching {
        val a = adapter ?: return false
        if (a.isDiscovering) a.cancelDiscovery()
        a.startDiscovery()
    }.onFailure { Log.w(TAG, "startDiscovery", it) }.getOrDefault(false)

    fun cancelDiscovery() {
        runCatching { adapter?.takeIf { it.isDiscovering }?.cancelDiscovery() }
            .onFailure { Log.w(TAG, "cancelDiscovery", it) }
    }

    val isDiscovering: Boolean get() = runCatching { adapter?.isDiscovering == true }.getOrDefault(false)

    // ---- bonding ------------------------------------------------------------------------------

    fun createBond(address: String): Boolean {
        cancelDiscovery() // inquiry slows paging down; stock cancels it too
        return runCatching { device(address)?.createBond() == true }
            .onFailure { Log.w(TAG, "createBond", it) }.getOrDefault(false)
    }

    /** BluetoothDevice.removeBond() (@SystemApi, BLUETOOTH_PRIVILEGED). Only from the user's Forget tap. */
    fun forget(address: String): Boolean = callBool(device(address), "removeBond")

    /** BluetoothDevice.cancelBondProcess() (@SystemApi): the pairing screen's cross. */
    fun cancelBond(d: BluetoothDevice?): Boolean = callBool(d, "cancelBondProcess")

    // ---- connection ---------------------------------------------------------------------------

    /** BluetoothDevice.isConnected() (@SystemApi): any open ACL link. */
    fun isConnected(d: BluetoothDevice): Boolean = callBool(d, "isConnected", log = false)

    /**
     * BluetoothDevice.connect()/disconnect() (@SystemApi, API 33+, BLUETOOTH_PRIVILEGED +
     * MODIFY_PHONE_STATE): connects every supported profile, or drops them all. Returns
     * BluetoothStatusCodes.SUCCESS (0) when the request was accepted; the link changes asynchronously.
     */
    fun connect(address: String): Boolean = callInt(device(address), "connect") == 0
    fun disconnect(address: String): Boolean = callInt(device(address), "disconnect") == 0

    /** setAlias (public; BLUETOOTH_PRIVILEGED lets a non-CDM app use it). null clears to the device's name. */
    fun rename(address: String, alias: String?): Boolean = runCatching {
        device(address)?.setAlias(alias) == 0 // BluetoothStatusCodes.SUCCESS
    }.onFailure { Log.w(TAG, "setAlias", it) }.getOrDefault(false)

    // ---- pairing answers (PairingActivity) ----------------------------------------------------

    fun confirm(d: BluetoothDevice, accept: Boolean): Boolean = runCatching { d.setPairingConfirmation(accept) }
        .onFailure { Log.w(TAG, "setPairingConfirmation", it) }.getOrDefault(false)

    fun setPin(d: BluetoothDevice, pin: String): Boolean = runCatching { d.setPin(pin.toByteArray(Charsets.UTF_8)) }
        .onFailure { Log.w(TAG, "setPin", it) }.getOrDefault(false)

    /**
     * SSP passkey entry. The framework dropped BluetoothDevice.setPasskey, but the stack still takes
     * IBluetooth.setPasskey(device, accept, len=4, int bytes in native order, attribution); reached via
     * the hidden static BluetoothDevice.getService() and this app's AttributionSource.
     */
    fun setPasskey(d: BluetoothDevice, entered: String): Boolean = runCatching {
        val svcGetter = BluetoothDevice::class.java.getDeclaredMethod("getService").apply { isAccessible = true }
        val svc = svcGetter.invoke(null) ?: return false
        val attr = app.attributionSource // what the device object carries too (the app's own)
        val bytes = Pairing.passkeyBytes(entered, ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN)
        val m = svc.javaClass.methods.first { it.name == "setPasskey" && it.parameterTypes.size == 5 }
        m.invoke(svc, d, true, 4, bytes, attr) as Boolean
    }.onFailure { Log.w(TAG, "setPasskey", it) }.getOrDefault(false)

    /** BluetoothDevice.canBondWithoutDialog() (@SystemApi): a CDM-associated device that may skip consent. */
    fun canBondWithoutDialog(d: BluetoothDevice): Boolean = callBool(d, "canBondWithoutDialog", log = false)

    // ---- reflection helpers -------------------------------------------------------------------

    private fun callBool(d: BluetoothDevice?, name: String, log: Boolean = true): Boolean = d != null && runCatching {
        BluetoothDevice::class.java.getMethod(name).invoke(d) as Boolean
    }.onFailure { if (log) Log.w(TAG, name, it) }.getOrDefault(false)

    private fun callInt(d: BluetoothDevice?, name: String): Int? = d?.let {
        runCatching { BluetoothDevice::class.java.getMethod(name).invoke(it) as Int }
            .onFailure { e -> Log.w(TAG, name, e) }.getOrNull()
    }

    companion object {
        private const val TAG = "CircaBt"
    }
}
