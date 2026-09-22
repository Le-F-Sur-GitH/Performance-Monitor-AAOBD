package com.lef.pmaaobd.stats.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.IOException
import java.util.UUID

/**
 * Bluetooth classic serial link (SPP / RFCOMM), used by OBDLink MX+/LX, vLinker MC+,
 * Vgate iCar Pro BT, Konnwei and most ELM327 clones.
 * Unpaired adapters trigger the system pairing dialog (PIN usually 1234 or 0000).
 */
@SuppressLint("MissingPermission")
class ClassicTransport(
    private val ctx: Context,
    private val device: BluetoothDevice,
    private val listener: ObdTransport.Listener,
) : ObdTransport {

    override val kind = ObdTransport.Kind.CLASSIC

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var closed = false

    override fun open() {
        Thread({ run() }, "obd-spp").start()
    }

    private fun connectSocket(): BluetoothSocket? {
        ctx.getSystemService(BluetoothManager::class.java)?.adapter?.cancelDiscovery()
        val standard = try {
            device.createRfcommSocketToServiceRecord(SPP)
        } catch (e: IOException) {
            null
        }
        try {
            standard?.connect()
            if (standard != null) return standard
        } catch (e: IOException) {
            listener.onLog("SPP connection failed (${e.message}), trying channel 1")
            try { standard?.close() } catch (_: IOException) { }
        }
        if (closed) return null
        // Fallback required by many clones with a broken SDP record.
        return try {
            val s = device.javaClass
                .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                .invoke(device, 1) as BluetoothSocket
            s.connect()
            s
        } catch (e: Exception) {
            listener.onLog("Channel 1 connection failed (${e.cause?.message ?: e.message})")
            null
        }
    }

    private fun run() {
        val s = connectSocket()
        if (s == null) {
            if (!closed) listener.onDisconnected("Bluetooth connection failed")
            return
        }
        if (closed) {
            try { s.close() } catch (_: IOException) { }
            return
        }
        socket = s
        listener.onLog("Bluetooth link established")
        listener.onConnected()
        val buffer = ByteArray(1024)
        try {
            val input = s.inputStream
            while (!closed) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) listener.onData(buffer.copyOf(n))
            }
        } catch (e: IOException) {
            // link lost
        }
        if (!closed) listener.onDisconnected("Bluetooth link lost")
    }

    override fun write(data: ByteArray): Boolean {
        val out = socket?.outputStream ?: return false
        return try {
            out.write(data)
            out.flush()
            true
        } catch (e: IOException) {
            false
        }
    }

    override fun close() {
        closed = true
        try { socket?.close() } catch (_: IOException) { }
        socket = null
    }

    companion object {
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    }
}
