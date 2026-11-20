package app.tether.link

import app.tether.core.LinkSecurity
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE peripheral: advertises the Tether service, serves the PSM characteristic (encrypted read,
 * which triggers one-time bonding), accepts L2CAP channels, and offers the GATT fallback pipe.
 */
@SuppressLint("MissingPermission")
class BlePeripheral(private val ctx: Context, private val onTransport: (Transport) -> Unit) {
    companion object {
        const val TAG = "Tether"
        val SERVICE: UUID = UUID.fromString("5ABF5C3C-2BF1-4B1C-9E8E-5D153F835BD5")
        val PSM_CHAR: UUID = UUID.fromString("6C0D1EB1-67F4-4181-9752-9CC90D6EFF88")
        val RX_CHAR: UUID = UUID.fromString("214E1CD9-F397-4A0F-BB6C-ED1D8C7A38A5")
        val TX_CHAR: UUID = UUID.fromString("7BA52139-0888-4234-A49D-0271E0F2A647")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        /** Notify-only "wake" characteristic: a notification wakes a suspended iOS app reliably. */
        val WAKE_CHAR: UUID = UUID.fromString("9A3F1C52-7E0B-4D8A-B1E6-2C5D4F7A8E31")
    }

    private val manager = ctx.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager.adapter
    private var server: BluetoothGattServer? = null
    private var l2cap: BluetoothServerSocket? = null
    private var advSet: AdvertisingSet? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private val gattPipes = ConcurrentHashMap<String, GattTransport>()
    private var wakeChar: BluetoothGattCharacteristic? = null
    private val wakeSubscribers = ConcurrentHashMap.newKeySet<String>()
    private val devices = ConcurrentHashMap<String, BluetoothDevice>()
    private val tuners = ConcurrentHashMap<String, android.bluetooth.BluetoothGatt>()
    private val mtus = ConcurrentHashMap<String, Int>()
    /** Last connection parameters per link, for the latency report. */
    val linkParams = ConcurrentHashMap<String, String>()
    @Volatile var psm = 0; private set
    @Volatile var running = false; private set
    @Volatile var advertising = false; private set

    val enabled get() = adapter?.isEnabled == true

    @Synchronized fun start() {
        if (running || !enabled) return
        running = true
        try {
            startL2cap()
            startGatt()
            startAdvertising()
            Log.i(TAG, "peripheral up, psm=$psm")
        } catch (t: Throwable) {
            Log.e(TAG, "peripheral start failed", t)
            stop()
        }
    }

    @Synchronized fun stop() {
        running = false
        advertising = false
        runCatching { advSet?.let { adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(advCallback) } }
        advSet = null
        runCatching { l2cap?.close() }
        l2cap = null
        gattPipes.values.forEach { it.close() }
        gattPipes.clear()
        runCatching { server?.close() }
        server = null
    }

    private fun startL2cap() {
        val ss = adapter!!.listenUsingInsecureL2capChannel()   // insecure on purpose: see LinkSecurity
        l2cap = ss
        psm = ss.psm
        Thread({
            while (running) {
                try {
                    val sock = ss.accept()
                    Log.i(TAG, "l2cap accepted from ${sock.remoteDevice.address}")
                    onTransport(L2capTransport(sock))
                } catch (e: IOException) {
                    if (running) {
                        // The listener died while we still run (Bluetooth hiccup): open a new one, or every later
                        // link would wait for the L2CAP timeout and fall back to GATT.
                        Log.w(TAG, "l2cap accept failed: ${e.message}; reopening the listener")
                        LinkLog.add("L2CAP listener failed; reopening")
                        Thread.sleep(1000)
                        if (running) runCatching { startL2cap() }.onFailure { LinkLog.add("L2CAP reopen failed: ${it.message}") }
                    }
                    break
                }
            }
        }, "l2cap-accept").start()
    }

    private fun startGatt() {
        val s = manager.openGattServer(ctx, gattCallback) ?: throw IOException("no GATT server")
        server = s
        // No characteristic or descriptor asks for link encryption: the link is secured by Tether's own pairing
        // (P-256 + AES-GCM), never by a Bluetooth bond. An encrypted permission made the Android phone check the LE key of
        // whatever bond it has with the iPhone; the bond made for Bluetooth tethering left that key recorded with
        // size 0, and every read failed "insufficient encryption key size" (2026-11-22). See LinkSecurity.
        val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(BluetoothGattCharacteristic(PSM_CHAR,
            BluetoothGattCharacteristic.PROPERTY_READ, LinkSecurity.CHAR_READ))
        service.addCharacteristic(BluetoothGattCharacteristic(RX_CHAR,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_WRITE,
            LinkSecurity.CHAR_WRITE))
        val tx = BluetoothGattCharacteristic(TX_CHAR, BluetoothGattCharacteristic.PROPERTY_NOTIFY, LinkSecurity.CHAR_READ)
        tx.addDescriptor(BluetoothGattDescriptor(CCCD, LinkSecurity.DESC_READ_WRITE))
        service.addCharacteristic(tx)
        txChar = tx
        val wake = BluetoothGattCharacteristic(WAKE_CHAR, BluetoothGattCharacteristic.PROPERTY_NOTIFY, LinkSecurity.CHAR_READ)
        wake.addDescriptor(BluetoothGattDescriptor(CCCD, LinkSecurity.DESC_READ_WRITE))
        service.addCharacteristic(wake)
        wakeChar = wake
        s.addService(service)
        // The mask must match Android's own values (it is unit-tested without the framework).
        val androidMask = BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED or BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED_MITM or
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED or BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM or
            BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED or BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED_MITM
        check(androidMask == LinkSecurity.SECURE_MASK && BluetoothGattCharacteristic.PERMISSION_READ == LinkSecurity.PERMISSION_READ &&
            BluetoothGattCharacteristic.PERMISSION_WRITE == LinkSecurity.PERMISSION_WRITE) { "LinkSecurity constants differ from Android's" }
        securityCheck = LinkSecurity.check(service.characteristics.map { c -> c.uuid.toString() to (listOf(c.permissions) + c.descriptors.map { it.permissions }) })
        if (securityCheck != "ok") { Log.e(TAG, "GATT security check: $securityCheck"); LinkLog.add("GATT security check failed: $securityCheck") }
    }

    /** "ok" when nothing in the service asks for link encryption (shown by the state hook). */
    @Volatile var securityCheck = "not started"; private set

    private fun startAdvertising() {
        val adv = adapter!!.bluetoothLeAdvertiser ?: throw IOException("no advertiser")
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(true)
            .setScannable(true)
            // ~100 ms while nobody is linked (quick reconnects), ~250 ms while linked: less radio time taken
            // from the connection events (call audio) and less battery.
            .setInterval(if (linked) AdvertisingSetParameters.INTERVAL_MEDIUM else AdvertisingSetParameters.INTERVAL_LOW)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE))
            .setIncludeDeviceName(false)
            .build()
        adv.startAdvertisingSet(params, data, AdvertiseData.Builder().build(), null, null, advCallback)
    }

    private val advCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
            advSet = set
            advertising = status == ADVERTISE_SUCCESS
            Log.i(TAG, "advertising started status=$status txPower=$txPower")
            if (status != ADVERTISE_SUCCESS) LinkLog.add("advertising failed to start: status=$status")
        }

        override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
            advertising = false
            if (!stoppingOurselves) LinkLog.add("advertising stopped by the system")
            stoppingOurselves = false
        }
    }

    /**
     * The iPhone can only find the Android phone again while it advertises. Samsung's stack can stop an advertising set
     * (seen after Bluetooth reconfigurations); nothing restarted it. Called every 10 s by the hub.
     */
    @Volatile private var linked = false
    @Volatile private var stoppingOurselves = false

    /** A client linked / the last one left: restart advertising at the matching interval. */
    @Synchronized fun setLinked(on: Boolean) {
        if (linked == on || !running) { linked = on; return }
        linked = on
        stoppingOurselves = advSet != null
        runCatching { advSet?.let { adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(advCallback) } }
        advSet = null
        advertising = false
        runCatching { startAdvertising() }.onFailure { LinkLog.add("advertising restart failed: ${it.message}") }
    }

    @Synchronized fun ensureAdvertising() {
        if (!running || !enabled || advertising) return
        LinkLog.add("advertising was off: restarting it")
        runCatching { advSet?.let { adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(advCallback) } }
        advSet = null
        runCatching { startAdvertising() }.onFailure { LinkLog.add("advertising restart failed: ${it.message}") }
    }

    /** Sends a 1-byte notification on the wake characteristic (wakes the iPhone app from suspension). */
    fun wake(address: String) {
        val s = server ?: return
        val c = wakeChar ?: return
        if (address !in wakeSubscribers) return
        val d = devices[address] ?: return
        runCatching { s.notifyCharacteristicChanged(d, c, false, byteArrayOf(1)) }
    }

    /**
     * Connection priority. The GATT server API cannot request it, so we open a GATT *client* on the same
     * ACL link (to the iPhone's own GATT server) and use requestConnectionPriority, which applies to the link.
     * HIGH during calls (≈15 ms interval if iOS accepts), BALANCED otherwise.
     */
    fun tune(address: String, high: Boolean) {
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return
        if (!devices.containsKey(address)) return   // that client is gone: never start a link to it ourselves
        val d = devices[address] ?: runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return
        val prio = if (high) BluetoothGatt.CONNECTION_PRIORITY_HIGH else BluetoothGatt.CONNECTION_PRIORITY_BALANCED
        wantedPriority[address] = prio
        val existing = tuners[address]
        if (existing != null) {
            runCatching { existing.requestConnectionPriority(prio) }
            return
        }
        runCatching {
            tuners[address] = d.connectGatt(ctx, false, object : android.bluetooth.BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                        // The priority wanted now (a call may have ended while this client was connecting).
                        val p = wantedPriority[address] ?: prio
                        g.requestConnectionPriority(p)
                        Log.i(TAG, "link priority ${if (p == BluetoothGatt.CONNECTION_PRIORITY_HIGH) "high" else "balanced"} requested")
                    } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                        // A dead tuning client must not be reused for the next call.
                        if (tuners[address] === g) tuners.remove(address)
                        runCatching { g.close() }
                    }
                }
            }, BluetoothDevice.TRANSPORT_LE)
        }
    }

    private val wantedPriority = ConcurrentHashMap<String, Int>()

    fun untune(address: String) {
        wantedPriority.remove(address)
        tuners.remove(address)?.let { runCatching { it.disconnect(); it.close() } }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.i(TAG, "gatt ${device.address} state=$newState status=$status bond=${device.bondState}")
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                devices[device.address] = device
                // 2M PHY roughly halves air time per packet (lower latency, more headroom for audio).
                runCatching { server?.setPreferredPhy(device, BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED) }
            }
            if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                gattPipes.remove(device.address)?.close()
                mtus.remove(device.address)
                wakeSubscribers.remove(device.address)
                devices.remove(device.address)
                tuners.remove(device.address)?.let { runCatching { it.close() } }
            }
        }

        /**
         * Hidden in the SDK stubs (so no `override`), but ART dispatches it by signature: the real
         * connection interval (×1.25 ms), peripheral latency and supervision timeout of each link.
         */
        @Suppress("unused")
        fun onConnectionUpdated(device: BluetoothDevice, interval: Int, latency: Int, timeout: Int, status: Int) {
            linkParams[device.address] = "interval %.2f ms, latency %d, timeout %d ms".format(interval * 1.25, latency, timeout * 10)
            Log.i(TAG, "link ${device.address}: ${linkParams[device.address]}")
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            mtus[device.address] = mtu
            gattPipes[device.address]?.mtu = mtu
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int,
                                                 characteristic: BluetoothGattCharacteristic) {
            val s = server ?: return
            if (characteristic.uuid == PSM_CHAR) {
                val v = byteArrayOf((psm and 0xff).toByte(), (psm shr 8).toByte())
                s.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, v.copyOfRange(minOf(offset, 2), 2))
            } else {
                s.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, 0, null)
            }
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
                                              preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            val s = server ?: return
            if (descriptor.uuid == CCCD && descriptor.characteristic.uuid == WAKE_CHAR) {
                val on = value != null && value.isNotEmpty() && value[0].toInt() and 1 == 1
                if (on) wakeSubscribers.add(device.address) else wakeSubscribers.remove(device.address)
                devices[device.address] = device
                Log.i(TAG, "wake notifications ${if (on) "on" else "off"} for ${device.address}")
            } else if (descriptor.uuid == CCCD && descriptor.characteristic.uuid == TX_CHAR) {
                val on = value != null && value.isNotEmpty() && value[0].toInt() and 1 == 1
                if (on && !gattPipes.containsKey(device.address)) {
                    val t = GattTransport(s, device, txChar!!)
                    t.mtu = mtus[device.address] ?: 23
                    gattPipes[device.address] = t
                    Log.i(TAG, "gatt fallback pipe opened for ${device.address}")
                    onTransport(t)
                } else if (!on) {
                    gattPipes.remove(device.address)?.close()
                }
            }
            if (responseNeeded) s.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
                                                  preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            if (characteristic.uuid == RX_CHAR && value != null) gattPipes[device.address]?.received(value)
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            gattPipes[device.address]?.notificationSent()
        }
    }
}
