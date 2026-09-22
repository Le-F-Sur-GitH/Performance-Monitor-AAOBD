package com.lef.pmaaobd.stats

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.sin

/**
 * OBDLink CX client: Bluetooth LE, service FFF0, FFF1 = notifications, FFF2 = write.
 * Bonding is mandatory and only accepted during the first 5 minutes after the CX is powered.
 * Speaks the ELM327/STN command set.
 */
@SuppressLint("MissingPermission")
class ObdLinkCx private constructor(private val ctx: Context) : ObdSource {

    enum class State(@StringRes val label: Int) {
        STOPPED(R.string.obd_state_stopped),
        NO_PERMISSION(R.string.obd_state_no_permission),
        BT_OFF(R.string.obd_state_bt_off),
        NO_DEVICE(R.string.obd_state_no_device),
        CONNECTING(R.string.obd_state_connecting),
        BONDING(R.string.obd_state_bonding),
        INIT(R.string.obd_state_init),
        READY_NO_ECU(R.string.obd_state_ready_no_ecu),
        READY(R.string.obd_state_ready),
        RETRY(R.string.obd_state_retry),
    }

    data class Found(val name: String, val address: String, val bonded: Boolean)

    companion object {
        private const val PREFS = "obdlink"
        private const val KEY_MAC = "mac"
        private const val KEY_NAME = "name"
        private const val RETRY_MS = 5000L
        val SERVICE: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        val RX: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        val TX: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        @Volatile
        private var instance: ObdLinkCx? = null

        fun get(context: Context): ObdLinkCx =
            instance ?: synchronized(this) {
                instance ?: ObdLinkCx(context.applicationContext).also { instance = it }
            }
    }

    private val main = Handler(Looper.getMainLooper())
    private val btManager = ctx.getSystemService(BluetoothManager::class.java)
    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var gatt: BluetoothGatt? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var mtu = 23
    @Volatile private var running = false

    // ---------- State + log (shown on the adapter screen) ----------

    private val stateBacking = MutableStateFlow(State.STOPPED)
    val stateFlow: StateFlow<State> = stateBacking.asStateFlow()
    val state: State get() = stateBacking.value
    private val logLines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    fun logText(): String = synchronized(logLines) { logLines.joinToString("\n") }

    private fun log(msg: String) {
        Timber.i("OBD: $msg")
        synchronized(logLines) {
            logLines.addLast("${timeFmt.format(Date())} $msg")
            while (logLines.size > 200) logLines.removeFirst()
        }
        notifyUi()
    }

    private fun setState(s: State) {
        if (stateBacking.value != s) {
            stateBacking.value = s
            log("State: ${s.name}")
        }
    }

    private fun notifyUi() = main.post { listeners.forEach { it() } }

    val deviceName: String? get() = prefs.getString(KEY_NAME, null)
    val deviceAddress: String? get() = prefs.getString(KEY_MAC, null)

    // ---------- Ready / subscribers ----------

    @Volatile var isReady = false
        private set
    @Volatile private var ecuOk = false
    @Volatile private var testMode = false
    private val readyListeners = CopyOnWriteArrayList<(ObdSource) -> Unit>()

    fun onReady(func: (ObdSource) -> Unit) {
        if (isReady) func(this) else readyListeners.add(func)
    }

    // ---------- Lifecycle ----------

    fun start() {
        if (running) return
        running = true
        log("Starting")
        main.post { connect() }
    }

    fun stop() = stop(true)

    private fun stop(clearListeners: Boolean) {
        running = false
        isReady = false
        pollThread?.interrupt()
        main.removeCallbacksAndMessages(null)
        stopScan()
        gatt?.close()
        gatt = null
        if (clearListeners) readyListeners.clear()
        setState(State.STOPPED)
    }

    fun restart() {
        stop(false)
        start()
    }

    fun selectDevice(f: Found) {
        prefs.edit().putString(KEY_MAC, f.address).putString(KEY_NAME, f.name).apply()
        log("Adapter selected: ${f.name} ${f.address}")
        restart()
    }

    fun forgetDevice() {
        val mac = deviceAddress
        prefs.edit().remove(KEY_MAC).remove(KEY_NAME).apply()
        if (mac != null) {
            try {
                btManager?.adapter?.getRemoteDevice(mac)?.let {
                    it.javaClass.getMethod("removeBond").invoke(it)
                }
            } catch (e: Exception) {
                Timber.w(e)
            }
        }
        log("Adapter forgotten")
        restart()
    }

    // ---------- ObdSource ----------

    override val isConnectedToECU: Boolean get() = isReady && ecuOk

    override fun setDebugTestMode(activateTestMode: Boolean): Boolean {
        testMode = activateTestMode
        return true
    }

    override fun listAllPIDs(): Array<String> = ObdPids.all.map { it.id }.toTypedArray()

    override fun getPIDInformation(pidIDs: Array<String>): Array<String> =
        pidIDs.map { ObdPidLabels.info(ctx, it) }.toTypedArray()

    override fun getPIDValuesAsDouble(pidsToRetrieve: Array<String>): DoubleArray {
        if (!isReady) {
            return if (testMode) pidsToRetrieve.map { fake(ObdPids.normalize(it)) }.toDoubleArray() else DoubleArray(0)
        }
        val now = System.currentTimeMillis()
        val out = DoubleArray(pidsToRetrieve.size)
        for ((i, raw) in pidsToRetrieve.withIndex()) {
            val id = ObdPids.normalize(raw)
            ObdPids.sources(id).forEach { wanted[it] = now }
            out[i] = value(id) ?: return DoubleArray(0)
        }
        return out
    }

    /** Last value read (adapter test screen). */
    fun lastValue(id: String): Double? = value(id)

    // ---------- Polling loop ----------

    private val wanted = ConcurrentHashMap<String, Long>()
    private val values = ConcurrentHashMap<String, Double>()
    private val lastRead = ConcurrentHashMap<String, Long>()
    @Volatile private var multiOk = true
    @Volatile private var pollThread: Thread? = null

    private fun value(id: String): Double? = when (id) {
        ObdPids.BOOST -> values["010B"]?.let { (it - (values["0133"] ?: 101.3)) / 100.0 }
        else -> values[id]
    }

    private fun startPolling() {
        multiOk = true
        pollThread?.interrupt()
        pollThread = Thread({
            while (isReady && running) {
                try {
                    pollOnce()
                } catch (e: InterruptedException) {
                    return@Thread
                } catch (e: Exception) {
                    Timber.e(e, "OBD read error")
                }
            }
        }, "obdlink-poll").also { it.start() }
    }

    private fun pollOnce() {
        val now = System.currentTimeMillis()
        wanted.entries.removeIf { now - it.value > 3000 }

        if (wanted.containsKey(ObdPids.ADAPTER_VOLTAGE) &&
            now - (lastRead[ObdPids.ADAPTER_VOLTAGE] ?: 0L) > 2000
        ) {
            send("ATRV")?.replace("V", "")?.trim()?.toDoubleOrNull()?.let {
                values[ObdPids.ADAPTER_VOLTAGE] = it
            }
            lastRead[ObdPids.ADAPTER_VOLTAGE] = now
        }

        val due = wanted.keys
            .filter { it.startsWith("01") }
            .filter { now - (lastRead[it] ?: 0L) >= ObdPids.intervalMs(it) }
            .sortedBy { lastRead[it] ?: 0L }
            .take(if (multiOk) 6 else 1)
        if (due.isEmpty()) {
            Thread.sleep(20)
            return
        }
        due.forEach { lastRead[it] = now }

        val cmd = "01" + due.joinToString("") { it.substring(2) } + if (due.size == 1) "1" else ""
        val parsed = parse(send(cmd), due)
        if (parsed.isEmpty() && due.size > 1) {
            val single = parse(send(due[0] + "1"), due.take(1))
            if (single.isNotEmpty()) {
                log("Grouped requests not supported, switching to single PID requests")
                multiOk = false
                due.forEach { lastRead.remove(it) }
            } else {
                if (ecuOk) {
                    ecuOk = false
                    setState(State.READY_NO_ECU)
                }
                Thread.sleep(500)
            }
            return
        }
        if (parsed.isNotEmpty() && !ecuOk) {
            ecuOk = true
            setState(State.READY)
        }
        for ((id, bytes) in parsed) {
            try {
                values[id] = ObdPids.byId[id]!!.calc(bytes)
            } catch (e: IndexOutOfBoundsException) {
                Timber.w("Incomplete data for $id")
            }
        }
    }

    private fun parse(resp: String?, requested: List<String>) = ObdParser.parse(resp, requested)

    private fun fake(id: String): Double {
        val p = ObdPids.byId[id] ?: return 0.0
        val t = System.currentTimeMillis() / 1000.0
        return p.min + (p.max - p.min) * (0.5 + 0.5 * sin(t + id.hashCode()))
    }

    // ---------- I/O ----------

    private val ioLock = ReentrantLock()
    private val rxBuffer = StringBuilder()
    private val responses = LinkedBlockingQueue<String>()
    private val writeDone = Semaphore(0)
    @Volatile private var writeStatus = BluetoothGatt.GATT_SUCCESS

    /** Raw command (test console). Must not be called on the UI thread. */
    fun rawCommand(cmd: String, timeoutMs: Long = 3000): String {
        if (gatt == null || txChar == null) return ctx.getString(R.string.obd_console_not_connected)
        return sendRaw(cmd, timeoutMs) ?: ctx.getString(R.string.obd_console_no_response)
    }

    private fun send(cmd: String, timeoutMs: Long = 1500): String? {
        return ObdParser.clean(sendRaw(cmd, timeoutMs), cmd)
    }

    /** Writes the command (split by MTU, waiting for each ack), then waits for the '>' prompt. */
    private fun sendRaw(cmd: String, timeoutMs: Long): String? = ioLock.withLock {
        val g = gatt ?: return null
        val c = txChar ?: return null
        synchronized(rxBuffer) { rxBuffer.setLength(0) }
        responses.clear()
        val data = "$cmd\r".toByteArray(Charsets.US_ASCII)
        val chunk = (mtu - 3).coerceAtLeast(20)
        var off = 0
        while (off < data.size) {
            val part = data.copyOfRange(off, minOf(off + chunk, data.size))
            writeDone.drainPermits()
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, part, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                        BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.value = part
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
            if (!ok) {
                Timber.w("Write rejected: $cmd")
                return null
            }
            if (!writeDone.tryAcquire(2, TimeUnit.SECONDS) || writeStatus != BluetoothGatt.GATT_SUCCESS) {
                log("Write not acknowledged ($cmd, status $writeStatus)")
                return null
            }
            off += part.size
        }
        responses.poll(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun onRx(bytes: ByteArray) {
        synchronized(rxBuffer) {
            rxBuffer.append(String(bytes, Charsets.US_ASCII))
            var idx = rxBuffer.indexOf(">")
            while (idx >= 0) {
                responses.offer(rxBuffer.substring(0, idx))
                rxBuffer.delete(0, idx + 1)
                idx = rxBuffer.indexOf(">")
            }
        }
    }

    private fun initElm() {
        setState(State.INIT)
        Thread {
            val id = sendRaw("ATZ", 4000)
            log("ATZ -> ${id?.trim()?.replace("\r", " ") ?: "no answer"}")
            Thread.sleep(300)
            for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0")) send(c)
            send("ATRV")?.let { log("Battery voltage: $it") }
            val r = send("0100", 10_000)
            ecuOk = r?.contains("4100") == true
            log(if (ecuOk) "ECU answered (0100 OK)" else "ECU did not answer 0100")
            isReady = true
            setState(if (ecuOk) State.READY else State.READY_NO_ECU)
            startPolling()
            val cbs = readyListeners.toList()
            readyListeners.clear()
            cbs.forEach { it(this) }
        }.start()
    }

    // ---------- Device discovery ----------

    private var scanning = false
    private var pickerCb: ((Found) -> Unit)? = null
    private var scanDoneCb: (() -> Unit)? = null
    private val seen = HashSet<String>()

    val isScanning: Boolean get() = scanning

    fun hasPermissions(): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    }

    /** Lists bonded OBDLink adapters plus those found by a 15 s scan. */
    fun scanForPicker(onFound: (Found) -> Unit, onDone: () -> Unit = {}) {
        val adapter = btManager?.adapter
        if (adapter == null || !adapter.isEnabled) { setState(State.BT_OFF); onDone(); return }
        if (!hasPermissions()) { setState(State.NO_PERMISSION); onDone(); return }
        seen.clear()
        adapter.bondedDevices?.forEach { d ->
            val n = d.name ?: return@forEach
            if (n.contains("OBD", true) && seen.add(d.address)) {
                onFound(Found(n, d.address, true))
            }
        }
        stopScan()
        pickerCb = onFound
        scanDoneCb = onDone
        scanning = true
        log("Scanning for BLE adapters...")
        adapter.bluetoothLeScanner?.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCb
        )
        main.removeCallbacks(scanTimeout)
        main.postDelayed(scanTimeout, 15_000)
    }

    private fun notifyScanDone() {
        val cb = scanDoneCb
        scanDoneCb = null
        cb?.invoke()
    }

    private val scanTimeout = Runnable { stopScan(); log("Scan finished") }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        pickerCb = null
        main.removeCallbacks(scanTimeout)
        try {
            btManager?.adapter?.bluetoothLeScanner?.stopScan(scanCb)
        } catch (e: Exception) {
            Timber.w(e)
        }
        notifyScanDone()
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            val hasUart = result.scanRecord?.serviceUuids?.any { it.uuid == SERVICE } == true
            if (!name.contains("OBD", true) && !hasUart) return
            if (!seen.add(result.device.address)) return
            val f = Found(name, result.device.address, result.device.bondState == BluetoothDevice.BOND_BONDED)
            main.post { pickerCb?.invoke(f) }
        }

        override fun onScanFailed(errorCode: Int) {
            log("Scan failed (code $errorCode)")
        }
    }

    // ---------- Connection ----------

    private fun retryLater() {
        if (!running) return
        setState(State.RETRY)
        main.postDelayed({ connect() }, RETRY_MS)
    }

    private fun connect() {
        if (!running || gatt != null) return
        val adapter = btManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            setState(State.BT_OFF); main.postDelayed({ connect() }, RETRY_MS); return
        }
        if (!hasPermissions()) {
            setState(State.NO_PERMISSION); main.postDelayed({ connect() }, RETRY_MS); return
        }
        val mac = deviceAddress
        if (mac == null) {
            setState(State.NO_DEVICE)
            return
        }
        val dev = adapter.getRemoteDevice(mac)
        setState(State.CONNECTING)
        mtu = 23
        gatt = dev.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    private fun onLost() {
        main.removeCallbacks(watchdog)
        isReady = false
        ecuOk = false
        pollThread?.interrupt()
        txChar = null
        gatt?.close()
        gatt = null
        retryLater()
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
            when (st) {
                BluetoothDevice.BOND_BONDING -> setState(State.BONDING)
                BluetoothDevice.BOND_BONDED -> {
                    log("Bonding OK")
                    val g = gatt
                    if (g != null && !isReady) main.postDelayed({
                        g.getService(SERVICE)?.getCharacteristic(RX)?.let { enableNotify(g, it) }
                    }, 800)
                }
                BluetoothDevice.BOND_NONE -> log("Bonding refused or expired. Unplug and replug the CX (bonding is only allowed 5 min after power-up)")
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            ctx, bondReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun enableNotify(g: BluetoothGatt, rx: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(rx, true)
        val d = rx.getDescriptor(CCCD) ?: run { log("CCCD descriptor missing"); g.disconnect(); return }
        val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, v) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            d.value = v
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
        if (!ok) log("Subscription not sent (bonding in progress?), the watchdog will retry")
    }

    private var notifyAttempts = 0

    /** Not ready 45 s after connecting: disconnect and start over. */
    private val watchdog = Runnable {
        if (!isReady && gatt != null) {
            log("Initialisation timed out, reconnecting")
            gatt?.disconnect()
            onLost()
        }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("BLE link established")
                notifyAttempts = 0
                main.removeCallbacks(watchdog)
                main.postDelayed(watchdog, 45_000)
                main.post { g.requestMtu(512) }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Disconnected (status $status)")
                main.post { onLost() }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            log("MTU = $mtu")
            main.post { g.discoverServices() }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val s = g.getService(SERVICE)
            val rx = s?.getCharacteristic(RX)
            val tx = s?.getCharacteristic(TX)
            if (rx == null || tx == null) {
                log("Service FFF0 not found: is this an OBDLink CX?")
                g.disconnect()
                return
            }
            txChar = tx
            log("OBDLink service found")
            if (g.device.bondState != BluetoothDevice.BOND_BONDED) {
                setState(State.BONDING)
            }
            main.post { enableNotify(g, rx) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid != CCCD) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("Notifications enabled")
                initElm()
                return
            }
            notifyAttempts++
            log("Subscription refused (status $status), attempt $notifyAttempts")
            if (notifyAttempts == 1 && g.device.bondState != BluetoothDevice.BOND_BONDED) {
                setState(State.BONDING)
                g.device.createBond()
            }
            if (notifyAttempts < 4) {
                main.postDelayed({
                    g.getService(SERVICE)?.getCharacteristic(RX)?.let { enableNotify(g, it) }
                }, 3000)
            } else {
                g.disconnect()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeStatus = status
            writeDone.release()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onRx(value)
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            onRx(c.value)
        }
    }
}
