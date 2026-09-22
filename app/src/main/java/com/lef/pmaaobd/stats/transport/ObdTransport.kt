package com.lef.pmaaobd.stats.transport

/** Byte pipe to an ELM327-compatible adapter (Bluetooth LE or Bluetooth classic). */
interface ObdTransport {

    enum class Kind { BLE, CLASSIC }

    /** Callbacks may arrive on any thread. */
    interface Listener {
        fun onConnected()
        fun onData(bytes: ByteArray)
        fun onDisconnected(reason: String)
        fun onBonding()
        fun onLog(message: String)
    }

    val kind: Kind

    /** Starts connecting; returns immediately. */
    fun open()

    /** Blocking write. Returns false if the data could not be sent. */
    fun write(data: ByteArray): Boolean

    fun close()
}
