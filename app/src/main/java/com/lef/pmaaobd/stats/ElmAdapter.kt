package com.lef.pmaaobd.stats

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
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
import com.lef.pmaaobd.stats.transport.AdapterNames
import com.lef.pmaaobd.stats.transport.BleTransport
import com.lef.pmaaobd.stats.transport.BleUart
import com.lef.pmaaobd.stats.transport.ClassicTransport
import com.lef.pmaaobd.stats.transport.ObdTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.sin

/**
 * ELM327-compatible OBD adapter over Bluetooth LE or Bluetooth classic.
 * Tested with the OBDLink CX; designed for OBDLink MX+/LX, Vgate vLinker and iCar,
 * Veepeak, Konnwei and generic ELM327 v1.5 clones.
 */
@SuppressLint("MissingPermission")
class ElmAdapter private constructor(private val ctx: Context) : ObdSource {

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

    data class Found(
        val name: String,
        val address: String,
        val bonded: Boolean,
        val kind: ObdTransport.Kind,
    )

    companion object {
        private const val PREFS = "obdlink"
        private const val KEY_MAC = "mac"
        private const val KEY_NAME = "name"
        private const val KEY_KIND = "kind"
        private const val RETRY_MS = 5000L
        private const val SCAN_MS = 15_000L

        @Volatile
        private var instance: ElmAdapter? = null

        fun get(context: Context): ElmAdapter =
            instance ?: synchronized(this) {
                instance ?: ElmAdapter(context.applicationContext).also { instance = it }
            }
    }

    private val main = Handler(Looper.getMainLooper())
    private val btManager = ctx.getSystemService(BluetoothManager::class.java)
    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    @Volatile private var transport: ObdTransport? = null
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

    /** 1.x only supported the BLE OBDLink CX, so a missing value means BLE. */
    val deviceKind: ObdTransport.Kind
        get() = if (prefs.getString(KEY_KIND, null) == ObdTransport.Kind.CLASSIC.name)
            ObdTransport.Kind.CLASSIC else ObdTransport.Kind.BLE

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
        transport?.close()
        transport = null
        if (clearListeners) readyListeners.clear()
        setState(State.STOPPED)
    }

    fun restart() {
        stop(false)
        start()
    }

    fun selectDevice(f: Found) {
        prefs.edit()
            .putString(KEY_MAC, f.address)
            .putString(KEY_NAME, f.name)
            .putString(KEY_KIND, f.kind.name)
            .apply()
        log("Adapter selected: ${f.name} ${f.address} (${f.kind})")
        restart()
    }

    fun forgetDevice() {
        val mac = deviceAddress
        prefs.edit().remove(KEY_MAC).remove(KEY_NAME).remove(KEY_KIND).apply()
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

    // ---------- Polling loop ----------

    private val wanted = ConcurrentHashMap<String, Long>()
    private val values = ConcurrentHashMap<String, Double>()
    private val lastRead = ConcurrentHashMap<String, Long>()
    @Volatile private var multiOk = true
    /** ELM v1.3+ accepts a response count suffix ("010C1"), old clones answer "?". */
    @Volatile private var countSuffixOk = true
    @Volatile private var pollThread: Thread? = null

    private fun value(id: String): Double? = when (id) {
        ObdPids.BOOST -> values["010B"]?.let { (it - (values["0133"] ?: 101.3)) / 100.0 }
        else -> values[id]
    }

    private fun startPolling() {
        multiOk = true
        countSuffixOk = true
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
        }, "obd-poll").also { it.start() }
    }

    private fun single(id: String): Map<String, IntArray> {
        val first = parse(send(if (countSuffixOk) id + "1" else id), listOf(id))
        if (first.isNotEmpty() || !countSuffixOk) return first
        val plain = parse(send(id), listOf(id))
        if (plain.isNotEmpty()) {
            log("Response count suffix not supported, disabled")
            countSuffixOk = false
        }
        return plain
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

        val parsed = if (due.size == 1) {
            single(due[0])
        } else {
            parse(send("01" + due.joinToString("") { it.substring(2) }), due)
        }
        if (parsed.isEmpty()) {
            if (due.size > 1 && single(due[0]).isNotEmpty()) {
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
        if (!ecuOk) {
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

    /** Raw command (test console). Must not be called on the UI thread. */
    fun rawCommand(cmd: String, timeoutMs: Long = 3000): String {
        if (transport == null || state == State.CONNECTING || state == State.BONDING) {
            return ctx.getString(R.string.obd_console_not_connected)
        }
        return sendRaw(cmd, timeoutMs) ?: ctx.getString(R.string.obd_console_no_response)
    }

    private fun send(cmd: String, timeoutMs: Long = 2000): String? {
        return ObdParser.clean(sendRaw(cmd, timeoutMs), cmd)
    }

    /** Writes the command, then waits for the '>' prompt. */
    private fun sendRaw(cmd: String, timeoutMs: Long): String? = ioLock.withLock {
        val t = transport ?: return null
        synchronized(rxBuffer) { rxBuffer.setLength(0) }
        responses.clear()
        if (!t.write("$cmd\r".toByteArray(Charsets.US_ASCII))) {
            Timber.w("Write failed: $cmd")
            return null
        }
        responses.poll(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun onRx(bytes: ByteArray) {
        synchronized(rxBuffer) {
            // Some clones pad their answers with NUL bytes.
            rxBuffer.append(String(bytes, Charsets.US_ASCII).replace("\u0000", ""))
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
            // Wake up clones that ignore the first command after connecting.
            sendRaw("", 500)
            val id = sendRaw("ATZ", 5000)
            log("ATZ -> ${id?.trim()?.replace("\r", " ")?.replace("\n", " ") ?: "no answer"}")
            Thread.sleep(500)
            for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0")) send(c)
            sendRaw("ATI", 1000)?.trim()?.let { log("Adapter: ${it.replace("\r", " ").replace("\n", " ")}") }
            send("ATRV")?.let { log("Battery voltage: $it") }
            val r = send("0100", 10_000)
            ecuOk = r?.contains("4100") == true
            log(if (ecuOk) "ECU answered (0100 OK)" else "ECU did not answer 0100")
            if (!running) return@Thread
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
    private var showAllDevices = false
    private var pickerCb: ((Found) -> Unit)? = null
    private var scanDoneCb: (() -> Unit)? = null
    private val seen = HashSet<String>()
    private var discoveryRegistered = false

    val isScanning: Boolean get() = scanning

    fun hasPermissions(): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun emit(f: Found) {
        if (seen.add(f.address)) pickerCb?.invoke(f)
    }

    /**
     * Lists paired adapters, then scans for 15 s for Bluetooth LE and Bluetooth classic adapters.
     * [showAll] lists every named device instead of only those that look like OBD adapters.
     */
    fun scanForPicker(showAll: Boolean, onFound: (Found) -> Unit, onDone: () -> Unit = {}) {
        val adapter = btManager?.adapter
        if (adapter == null || !adapter.isEnabled) { setState(State.BT_OFF); onDone(); return }
        if (!hasPermissions()) { setState(State.NO_PERMISSION); onDone(); return }
        stopScan()
        seen.clear()
        showAllDevices = showAll
        pickerCb = onFound
        scanDoneCb = onDone
        scanning = true

        adapter.bondedDevices?.forEach { d ->
            val n = d.name ?: return@forEach
            if (showAll || AdapterNames.looksLikeObd(n)) {
                val kind = if (d.type == BluetoothDevice.DEVICE_TYPE_LE) ObdTransport.Kind.BLE else ObdTransport.Kind.CLASSIC
                emit(Found(n, d.address, true, kind))
            }
        }

        log("Scanning for adapters...")
        adapter.bluetoothLeScanner?.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCb,
        )
        ContextCompat.registerReceiver(
            ctx, discoveryReceiver,
            IntentFilter(BluetoothDevice.ACTION_FOUND),
            ContextCompat.RECEIVER_EXPORTED,
        )
        discoveryRegistered = true
        adapter.startDiscovery()
        main.removeCallbacks(scanTimeout)
        main.postDelayed(scanTimeout, SCAN_MS)
    }

    private val scanTimeout = Runnable { stopScan(); log("Scan finished") }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        pickerCb = null
        main.removeCallbacks(scanTimeout)
        val adapter = btManager?.adapter
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCb)
            adapter?.cancelDiscovery()
        } catch (e: Exception) {
            Timber.w(e)
        }
        if (discoveryRegistered) {
            discoveryRegistered = false
            try {
                ctx.unregisterReceiver(discoveryReceiver)
            } catch (e: IllegalArgumentException) {
                // already unregistered
            }
        }
        val cb = scanDoneCb
        scanDoneCb = null
        cb?.invoke()
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            val hasUart = result.scanRecord?.serviceUuids?.any { it.uuid in BleUart.knownServices } == true
            if (!showAllDevices && !hasUart && !AdapterNames.looksLikeObd(name)) return
            val f = Found(
                name, result.device.address,
                result.device.bondState == BluetoothDevice.BOND_BONDED,
                ObdTransport.Kind.BLE,
            )
            main.post { if (scanning) emit(f) }
        }

        override fun onScanFailed(errorCode: Int) {
            log("BLE scan failed (code $errorCode)")
        }
    }

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != BluetoothDevice.ACTION_FOUND || !scanning) return
            val d: BluetoothDevice = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }) ?: return
            if (d.type == BluetoothDevice.DEVICE_TYPE_LE) return // covered by the BLE scan
            val name = d.name ?: i.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: return
            if (!showAllDevices && !AdapterNames.looksLikeObd(name)) return
            emit(Found(name, d.address, d.bondState == BluetoothDevice.BOND_BONDED, ObdTransport.Kind.CLASSIC))
        }
    }

    // ---------- Connection ----------

    private fun retryLater() {
        if (!running) return
        setState(State.RETRY)
        main.postDelayed({ connect() }, RETRY_MS)
    }

    private fun connect() {
        if (!running || transport != null) return
        val adapter: BluetoothAdapter? = btManager?.adapter
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
        lateinit var t: ObdTransport
        val cb = object : ObdTransport.Listener {
            override fun onConnected() {
                if (transport === t) main.post { initElm() }
            }

            override fun onData(bytes: ByteArray) {
                if (transport === t) onRx(bytes)
            }

            override fun onDisconnected(reason: String) {
                if (transport !== t) return
                log(reason)
                main.post { if (transport === t) onLost() }
            }

            override fun onBonding() {
                if (transport === t) setState(State.BONDING)
            }

            override fun onLog(message: String) = log(message)
        }
        t = when (deviceKind) {
            ObdTransport.Kind.CLASSIC -> ClassicTransport(ctx, dev, cb)
            ObdTransport.Kind.BLE -> BleTransport(ctx, dev, cb)
        }
        transport = t
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 45_000)
        t.open()
    }

    private fun onLost() {
        main.removeCallbacks(watchdog)
        isReady = false
        ecuOk = false
        pollThread?.interrupt()
        transport?.close()
        transport = null
        retryLater()
    }

    /** Not ready 45 s after connecting: disconnect and start over. */
    private val watchdog = Runnable {
        if (!isReady && transport != null) {
            log("Initialisation timed out, reconnecting")
            onLost()
        }
    }
}
