package com.lef.pmaaobd.stats.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_INDICATE
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Bluetooth LE serial link. Finds the UART service automatically (see [BleUart]),
 * handles MTU, bonding when the adapter requires it (OBDLink CX) and write acknowledgements.
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val ctx: Context,
    private val device: BluetoothDevice,
    private val listener: ObdTransport.Listener,
) : ObdTransport {

    override val kind = ObdTransport.Kind.BLE

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private var notifyAttempts = 0
    private var servicesRequested = false
    @Volatile private var closed = false
    @Volatile private var connected = false
    private val writeDone = Semaphore(0)
    @Volatile private var writeStatus = BluetoothGatt.GATT_SUCCESS

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            if (d?.address != device.address || closed) return
            when (i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                BluetoothDevice.BOND_BONDING -> listener.onBonding()
                BluetoothDevice.BOND_BONDED -> {
                    listener.onLog("Bonding OK")
                    val g = gatt
                    if (g != null && !connected) main.postDelayed({ subscribe(g) }, 800)
                }
                BluetoothDevice.BOND_NONE -> listener.onLog(
                    "Bonding refused or expired. OBDLink CX: unplug and replug it, bonding is only allowed 5 min after power-up"
                )
            }
        }
    }

    override fun open() {
        ContextCompat.registerReceiver(
            ctx, bondReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        mtu = 23
        gatt = device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    override fun close() {
        if (closed) return
        closed = true
        main.removeCallbacksAndMessages(null)
        try {
            ctx.unregisterReceiver(bondReceiver)
        } catch (e: IllegalArgumentException) {
            // not registered
        }
        gatt?.close()
        gatt = null
        tx = null
        writeDone.release()
    }

    override fun write(data: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = tx ?: return false
        val type = if (c.properties and PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        val chunk = (mtu - 3).coerceAtLeast(20)
        var off = 0
        while (off < data.size) {
            val part = data.copyOfRange(off, minOf(off + chunk, data.size))
            writeDone.drainPermits()
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, part, type) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.value = part
                c.writeType = type
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
            if (!ok) return false
            if (!writeDone.tryAcquire(2, TimeUnit.SECONDS) || writeStatus != BluetoothGatt.GATT_SUCCESS) {
                listener.onLog("Write not acknowledged (status $writeStatus)")
                return false
            }
            off += part.size
        }
        return !closed
    }

    private fun discover(g: BluetoothGatt) {
        if (servicesRequested || closed) return
        servicesRequested = true
        g.discoverServices()
    }

    /** Known layout first, then any service offering a notify and a write characteristic. */
    private fun findUart(g: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        for (l in BleUart.known) {
            val s = g.getService(l.service) ?: continue
            val n = s.getCharacteristic(l.notify) ?: continue
            val w = s.getCharacteristic(l.write) ?: continue
            return n to w
        }
        for (s in g.services) {
            if (s.uuid in BleUart.ignoredServices) continue
            val n = s.characteristics.firstOrNull { it.properties and (PROPERTY_NOTIFY or PROPERTY_INDICATE) != 0 }
            val w = s.characteristics.firstOrNull { it.properties and (PROPERTY_WRITE or PROPERTY_WRITE_NO_RESPONSE) != 0 }
            if (n != null && w != null) return n to w
        }
        return null
    }

    private var rx: BluetoothGattCharacteristic? = null

    private fun subscribe(g: BluetoothGatt) {
        val r = rx ?: return
        g.setCharacteristicNotification(r, true)
        val d = r.getDescriptor(BleUart.CCCD) ?: run {
            // Some clones notify without a CCCD descriptor.
            listener.onLog("No CCCD descriptor, assuming notifications are on")
            connected = true
            listener.onConnected()
            return
        }
        val v = if (r.properties and PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, v) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            d.value = v
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
        if (!ok) listener.onLog("Subscription not sent (bonding in progress?), will retry")
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (closed) return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                listener.onLog("BLE link established")
                notifyAttempts = 0
                main.post {
                    if (!g.requestMtu(512)) discover(g)
                }
                // Some adapters never answer the MTU request.
                main.postDelayed({ discover(g) }, 3000)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                listener.onDisconnected("BLE disconnected (status $status)")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            listener.onLog("MTU = $mtu")
            main.post { discover(g) }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val uart = findUart(g)
            if (uart == null) {
                listener.onLog("No serial service found: is this an OBD adapter?")
                g.disconnect()
                return
            }
            rx = uart.first
            tx = uart.second
            listener.onLog("Serial service ${uart.first.service.uuid.toString().substring(4, 8)} found")
            if (g.device.bondState == BluetoothDevice.BOND_BONDING) listener.onBonding()
            main.post { subscribe(g) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid != BleUart.CCCD || connected) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                listener.onLog("Notifications enabled")
                connected = true
                listener.onConnected()
                return
            }
            notifyAttempts++
            listener.onLog("Subscription refused (status $status), attempt $notifyAttempts")
            if (notifyAttempts == 1 && g.device.bondState != BluetoothDevice.BOND_BONDED) {
                listener.onBonding()
                g.device.createBond()
            }
            if (notifyAttempts < 4) {
                main.postDelayed({ subscribe(g) }, 3000)
            } else {
                g.disconnect()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeStatus = status
            writeDone.release()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            listener.onData(value)
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            listener.onData(c.value)
        }
    }
}
