package org.circa.watchlink

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.UUID

/**
 * Nordic UART GATT server + legacy advertising, exactly as watchlink/README.md ("Protocol")
 * All state lives on the service's handler thread; binder callbacks are posted onto it.
 */
class BleServer(private val ctx: Context, private val h: Handler, private val li: Listener) {

    interface Listener {
        fun onLinkUp(l: Link)
        fun onSubscribed(l: Link)
        fun onLine(l: Link, line: String)
        fun onLinkDown(l: Link)
        fun onServerState(state: String)
    }

    class Link(val dev: BluetoothDevice) {
        val connectedAt = SystemClock.elapsedRealtime()
        var mtu = 23
        var subscribed = false
        var cccd = byteArrayOf(0, 0)
        val asm = LineAssembler()
        var prepared = ByteArray(0)

        // outgoing byte queue
        var q = ByteArray(1024)
        var qStart = 0
        var qEnd = 0
        var inFlight = false
        var inFlightSince = 0L
        var busyRetries = 0
        var notifsSent = 0L
        var bytesSent = 0L

        fun addr(): String = dev.address

        fun qLen(): Int = qEnd - qStart

        fun enqueue(b: ByteArray) {
            if (qStart > 0 && qEnd + b.size > q.size) {
                System.arraycopy(q, qStart, q, 0, qLen())
                qEnd -= qStart; qStart = 0
            }
            if (qEnd + b.size > q.size) {
                val n = ByteArray(maxOf(q.size * 2, qEnd + b.size))
                System.arraycopy(q, 0, n, 0, qEnd)
                q = n
            }
            System.arraycopy(b, 0, q, qEnd, b.size)
            qEnd += b.size
        }

        fun payload(): Int = minOf(512, maxOf(20, mtu - 3))  // ATT values max 512 B
    }

    private val bm: BluetoothManager = ctx.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = bm.adapter
    private var server: BluetoothGattServer? = null
    private lateinit var rxChar: BluetoothGattCharacteristic
    private var advertiser: BluetoothLeAdvertiser? = null
    private var serviceAdded = false
    private var advertising = false
    private var advStarting = false
    private var running = false
    val links = LinkedHashMap<String, Link>()
    var name: String? = null

    fun isAdvertising(): Boolean = advertising

    fun isRunning(): Boolean = running

    /** Rename the adapter, then open the GATT server; advertising starts once the service is added. */
    fun start(wantName: String) {
        if (running) return
        running = true
        name = wantName
        val a = adapter
        if (a == null || !a.isEnabled) {
            state("bluetooth-off")
            running = false
            return
        }
        val cur = a.name
        Log.i(TAG, "adapter name='$cur' want='$wantName' multiAdv=" + a.isMultipleAdvertisementSupported
                + " extAdv=" + a.isLeExtendedAdvertisingSupported)
        if (wantName != cur) {
            val ok = a.setName(wantName)
            Log.i(TAG, "setName('$wantName') -> $ok")
            waitForName(wantName, 0)
        } else {
            openServer()
        }
    }

    private fun waitForName(want: String, tries: Int) {
        if (!running) return
        val cur = adapter!!.name   // non-null: only reached from start(), after its adapter null check
        if (want == cur || tries >= 25) {
            Log.i(TAG, "adapter name now '$cur' after " + (tries * 200) + " ms")
            openServer()
            return
        }
        h.postDelayed({ waitForName(want, tries + 1) }, 200)
    }

    private fun openServer() {
        if (!running) return
        server = bm.openGattServer(ctx, cb)
        val s = server
        if (s == null) {
            state("gatt-open-failed")
            return
        }
        val svc = BluetoothGattService(NUS, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val tx = BluetoothGattCharacteristic(
            TX,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val rx = BluetoothGattCharacteristic(
            RX,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ
        )
        rx.addDescriptor(
            BluetoothGattDescriptor(
                CCCD,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        svc.addCharacteristic(tx)
        svc.addCharacteristic(rx)
        val ok = s.addService(svc)
        Log.i(TAG, "gatt server open, addService -> $ok")
    }

    fun stop() {
        running = false
        stopAdvertising()
        val s = server
        if (s != null) {
            for (l in ArrayList(links.values)) {
                try {
                    s.cancelConnection(l.dev)
                } catch (ignored: Exception) {
                }
            }
            s.clearServices()
            s.close()
            server = null
        }
        links.clear()
        serviceAdded = false
        state("stopped")
    }

    // ---- advertising ----
    /** Builder.setOwnAddressType(ADDRESS_TYPE_PUBLIC = 0) is @SystemApi, so it is called reflectively. */
    private fun setPublicOwnAddress(b: AdvertisingSetParameters.Builder) {
        runCatching {
            b.javaClass.getMethod("setOwnAddressType", Int::class.javaPrimitiveType).invoke(b, 0)
        }.onFailure { Log.w(TAG, "advertising: setOwnAddressType(PUBLIC) unavailable: $it") }
    }

    /** ADDRESS_TYPE_PUBLIC exists from API 34 and needs the privileged BLUETOOTH_PRIVILEGED permission. */
    private fun canUsePublicAddress(): Boolean =
        android.os.Build.VERSION.SDK_INT >= 34 &&
            ctx.checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun startAdvertising() {
        if (!running || !serviceAdded || advertising || advStarting) return
        advertiser = adapter!!.bluetoothLeAdvertiser   // non-null: only reached from start()/its callbacks
        val adv = advertiser
        if (adv == null) {
            state("no-advertiser")
            return
        }
        // Advertise from the watch's public identity address, like a real Bangle.js's fixed address. The default
        // (a rotating resolvable private address) made Gadgetbridge list the watch as a new device after every
        // rotation. ADDRESS_TYPE_PUBLIC needs BLUETOOTH_PRIVILEGED (granted to the priv-app by device/circa); without
        // it the stack falls back to the default, so this is safe on other images.
        val p = AdvertisingSetParameters.Builder()
            .setLegacyMode(true).setConnectable(true).setScannable(true)
            .setInterval(AdvertisingSetParameters.INTERVAL_MEDIUM)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .apply { if (canUsePublicAddress()) setPublicOwnAddress(this) }
            .build()
        Log.i(TAG, "advertising: own address " + (if (canUsePublicAddress()) "PUBLIC" else "default (rotating)"))
        val advData = AdvertiseData.Builder().setIncludeDeviceName(true).setIncludeTxPowerLevel(false).build()
        val scan = AdvertiseData.Builder().addServiceUuid(ParcelUuid(NUS)).build()
        advStarting = true
        Log.i(TAG, "advertising: starting (name in ADV, NUS in SCAN_RSP)")
        adv.startAdvertisingSet(p, advData, scan, null, null, advCb, h)
    }

    fun stopAdvertising() {
        val adv = advertiser
        if (adv != null && (advertising || advStarting)) {
            try {
                adv.stopAdvertisingSet(advCb)
            } catch (e: Exception) {
                Log.i(TAG, "stopAdvertisingSet: $e")
            }
        }
        if (advertising || advStarting) Log.i(TAG, "advertising: stopped")
        advertising = false
        advStarting = false
    }

    private val advCb = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
            advStarting = false
            if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                advertising = true
                if (links.isNotEmpty()) { // a central connected while we were starting
                    stopAdvertising()
                    return
                }
                state("advertising")
            } else {
                state("advertise-failed:$status")
            }
        }

        override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
            Log.i(TAG, "advertising set stopped")
        }
    }

    // ---- sending ----
    /** Queue raw bytes to one link; they are sent as notifications of at most ATT_MTU-3 bytes, one in flight. */
    fun sendBytes(l: Link, b: ByteArray) {
        if (links[l.addr()] !== l) return
        l.enqueue(b)
        pump(l)
    }

    private fun pump(l: Link) {
        val s = server
        if (s == null || !l.subscribed || l.inFlight || l.qLen() == 0) return
        val n = minOf(l.qLen(), l.payload())
        val chunk = ByteArray(n)
        System.arraycopy(l.q, l.qStart, chunk, 0, n)
        var st: Int
        try {
            st = s.notifyCharacteristicChanged(l.dev, rxChar, false, chunk)
        } catch (e: Exception) {
            Log.i(TAG, "notify exception $e")
            st = -1
        }
        if (st == BluetoothStatusCodes.SUCCESS) {
            l.qStart += n
            if (l.qStart == l.qEnd) { l.qStart = 0; l.qEnd = 0 }
            l.inFlight = true
            l.inFlightSince = SystemClock.elapsedRealtime()
            l.busyRetries = 0
            l.notifsSent++
            l.bytesSent += n
            h.postDelayed({
                if (l.inFlight && SystemClock.elapsedRealtime() - l.inFlightSince >= NOTIFY_WATCHDOG_MS) {
                    Log.i(TAG, "notify watchdog: no onNotificationSent from " + l.addr() + ", continuing")
                    l.inFlight = false
                    pump(l)
                }
            }, NOTIFY_WATCHDOG_MS)
        } else {
            if (++l.busyRetries % 50 == 1) Log.i(TAG, "notify status $st to " + l.addr() + ", retrying")
            if (l.busyRetries < 2000) {
                h.postDelayed({ pump(l) }, 20)
            } else {
                Log.i(TAG, "notify: giving up on " + l.addr() + ", dropping " + l.qLen() + " bytes")
                l.qStart = 0
                l.qEnd = 0
                l.busyRetries = 0
            }
        }
    }

    fun subscribedLinks(): List<Link> {
        val out = ArrayList<Link>()
        for (l in links.values) if (l.subscribed) out.add(l)
        return out
    }

    private fun state(s: String) {
        Log.i(TAG, "server state: $s")
        li.onServerState(s)
    }

    // ---- GATT server callbacks (binder threads -> handler) ----
    private val cb = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            h.post {
                Log.i(TAG, "onServiceAdded status=$status uuid=" + service.uuid)
                val s = server
                if (status != BluetoothGatt.GATT_SUCCESS || s == null) {
                    state("service-add-failed:$status")
                    return@post
                }
                rxChar = s.getService(NUS).getCharacteristic(RX)
                serviceAdded = true
                startAdvertising()
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            h.post {
                val a = device.address
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    val l = Link(device)
                    val old = links.put(a, l)
                    Log.i(TAG, "CONNECTED " + a + " status=" + status + " bond=" + device.bondState
                            + " type=" + device.type + (if (old != null) " (replaced stale link)" else "") + " links=" + links.size)
                    stopAdvertising()
                    li.onLinkUp(l)
                    restoreCccd(l)
                    h.postDelayed({
                        if (links[l.addr()] === l && !l.subscribed && server != null) {
                            Log.i(TAG, "no CCCD subscribe from " + l.addr() + " in 30 s, disconnecting it")
                            server?.cancelConnection(l.dev)
                        }
                    }, SUBSCRIBE_GUARD_MS)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val l = links.remove(a)
                    Log.i(TAG, "DISCONNECTED " + a + " status=" + status + " links=" + links.size
                            + (if (l != null) " sent=" + l.notifsSent + " notifs/" + l.bytesSent + " B" else ""))
                    if (l != null) li.onLinkDown(l)
                    if (links.isEmpty()) startAdvertising()
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            h.post {
                val l = links[device.address]
                Log.i(TAG, "MTU " + device.address + " = " + mtu)
                if (l != null) l.mtu = mtu
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            // Respond immediately: GB's queue waits for this with no timeout (Q:204-212).
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            h.post {
                val l = links[device.address]
                if (l == null || CCCD != descriptor.uuid || value == null || value.isEmpty()) {
                    Log.i(TAG, "descriptor write ignored " + descriptor.uuid + " from " + device.address)
                    return@post
                }
                val was = l.subscribed
                l.cccd = byteArrayOf(value[0], if (value.size > 1) value[1] else 0)
                l.subscribed = (value[0].toInt() and 0x03) != 0
                Log.i(TAG, "CCCD " + device.address + " <- " + String.format("%02x %02x", l.cccd[0], l.cccd[1])
                        + " subscribed=" + l.subscribed)
                saveCccd(l)
                if (l.subscribed && !was) {
                    li.onSubscribed(l)
                    pump(l)
                }
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor,
        ) {
            val l = links[device.address]
            val v = if (l != null && CCCD == descriptor.uuid) l.cccd else byteArrayOf(0, 0)
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, v)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
        ) {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, ByteArray(0))
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            h.post {
                val l = links[device.address]
                if (l == null || TX != characteristic.uuid || value == null) return@post
                if (preparedWrite) {
                    val end = offset + value.size
                    if (l.prepared.size < end) {
                        val n = ByteArray(end)
                        System.arraycopy(l.prepared, 0, n, 0, l.prepared.size)
                        l.prepared = n
                    }
                    System.arraycopy(value, 0, l.prepared, offset, value.size)
                    return@post
                }
                deliver(l, value)
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            h.post {
                val l = links[device.address] ?: return@post
                if (execute && l.prepared.isNotEmpty()) deliver(l, l.prepared)
                l.prepared = ByteArray(0)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            h.post {
                val l = links[device.address] ?: return@post
                if (status != BluetoothGatt.GATT_SUCCESS) Log.i(TAG, "onNotificationSent status=$status")
                l.inFlight = false
                pump(l)
            }
        }
    }

    // ---- CCCD persistence for bonded clients ----
    // Core spec Vol 3 Part G 3.3.3.3: a CCCD value is persistent across connections for bonded devices. It matters
    // here because the LE link to a bonded phone can outlive our app process (install -r, kill + START_STICKY): the
    // phone never sees a disconnect, so Gadgetbridge never re-writes the CCCD, and a fresh GATT server would
    // otherwise treat it as unsubscribed and drop every watch -> phone notification.
    private fun cccdPrefs(): SharedPreferences = ctx.getSharedPreferences("cccd", Context.MODE_PRIVATE)

    private fun saveCccd(l: Link) {
        if (l.dev.bondState != BluetoothDevice.BOND_BONDED) return
        cccdPrefs().edit().putBoolean(l.addr(), l.subscribed).apply()
    }

    private fun restoreCccd(l: Link) {
        if (l.dev.bondState != BluetoothDevice.BOND_BONDED || !cccdPrefs().getBoolean(l.addr(), false)) return
        l.cccd = byteArrayOf(1, 0)
        l.subscribed = true
        Log.i(TAG, "CCCD " + l.addr() + " restored 01 00 (bonded, subscribed on a previous connection)")
        li.onSubscribed(l)
    }

    private fun deliver(l: Link, value: ByteArray) {
        for (line in l.asm.feed(value)) li.onLine(l, line)
    }

    companion object {
        const val TAG = "WatchLink"
        val NUS: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val TX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")  // phone -> watch
        val RX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")  // watch -> phone (notify)
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val SUBSCRIBE_GUARD_MS = 30_000L   // drop centrals that never enable notifications
        const val NOTIFY_WATCHDOG_MS = 1_500L

        fun ascii(s: String): ByteArray = s.toByteArray(StandardCharsets.ISO_8859_1)
    }
}
