package com.lef.pmaaobd.prefs

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.lef.pmaaobd.stats.ElmAdapter
import com.lef.pmaaobd.stats.R
import com.lef.pmaaobd.stats.transport.ObdTransport

/** Pick / pair an OBD adapter, show its state, run a quick test and a raw ELM327 console. */
class ObdAdapterActivity : AppCompatActivity() {

    private lateinit var obd: ElmAdapter
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var deviceText: TextView
    private lateinit var pickerCard: MaterialCardView
    private lateinit var scanProgress: LinearProgressIndicator
    private lateinit var noDeviceText: TextView
    private lateinit var deviceList: LinearLayout
    private lateinit var showAllSwitch: MaterialSwitch
    private lateinit var commandInput: TextInputEditText
    private lateinit var logText: TextView
    private val consoleLines = ArrayDeque<String>()
    private val refresh: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_obd_adapter)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        obd = ElmAdapter.get(this)

        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        deviceText = findViewById(R.id.deviceText)
        pickerCard = findViewById(R.id.pickerCard)
        scanProgress = findViewById(R.id.scanProgress)
        noDeviceText = findViewById(R.id.noDeviceText)
        deviceList = findViewById(R.id.deviceList)
        showAllSwitch = findViewById(R.id.showAllSwitch)
        showAllSwitch.setOnCheckedChangeListener { _, _ -> search() }
        commandInput = findViewById(R.id.commandInput)
        logText = findViewById(R.id.logText)

        findViewById<MaterialButton>(R.id.btnSearch).setOnClickListener { search() }
        findViewById<MaterialButton>(R.id.btnReconnect).setOnClickListener { obd.restart() }
        findViewById<MaterialButton>(R.id.btnForget).setOnClickListener {
            obd.forgetDevice()
            deviceList.removeAllViews()
            pickerCard.visibility = View.GONE
        }
        findViewById<MaterialButton>(R.id.btnTest).setOnClickListener {
            runCommands(listOf("ATRV", "ATDP", "0100", "010C", "010D", "0105"))
        }
        findViewById<MaterialButton>(R.id.btnSend).setOnClickListener { sendTypedCommand() }
        commandInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTypedCommand(); true
            } else false
        }
        findViewById<MaterialButton>(R.id.btnCopyLog).setOnClickListener { copyLog() }

        askPermissions()
        obd.start()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onResume() {
        super.onResume()
        obd.addListener(refresh)
        render()
    }

    override fun onPause() {
        obd.removeListener(refresh)
        super.onPause()
    }

    private fun render() {
        val state = obd.state
        statusText.setText(state.label)
        statusDot.background.mutate().setTint(ContextCompat.getColor(this, colorFor(state)))
        val kind = getString(
            if (obd.deviceKind == ObdTransport.Kind.BLE) R.string.obd_kind_ble else R.string.obd_kind_classic
        )
        deviceText.text = obd.deviceName?.let { "$it  ·  ${obd.deviceAddress}  ·  $kind" }
            ?: getString(R.string.obd_device_none)

        val adapterLog = obd.logText().lines().filter { it.isNotBlank() }.reversed()
        logText.text = (consoleLines + adapterLog).joinToString("\n")
    }

    private fun colorFor(state: ElmAdapter.State): Int = when (state) {
        ElmAdapter.State.READY -> R.color.status_ok
        ElmAdapter.State.READY_NO_ECU,
        ElmAdapter.State.CONNECTING,
        ElmAdapter.State.BONDING,
        ElmAdapter.State.INIT,
        ElmAdapter.State.RETRY -> R.color.status_warn
        ElmAdapter.State.NO_PERMISSION,
        ElmAdapter.State.BT_OFF -> R.color.status_error
        ElmAdapter.State.STOPPED,
        ElmAdapter.State.NO_DEVICE -> R.color.status_idle
    }

    private fun search() {
        deviceList.removeAllViews()
        pickerCard.visibility = View.VISIBLE
        noDeviceText.visibility = View.GONE
        scanProgress.visibility = View.VISIBLE
        obd.scanForPicker(
            showAll = showAllSwitch.isChecked,
            onFound = { found -> deviceList.addView(deviceButton(found)) },
            onDone = {
                scanProgress.visibility = View.GONE
                noDeviceText.visibility = if (deviceList.childCount == 0) View.VISIBLE else View.GONE
            },
        )
    }

    private fun deviceButton(found: ElmAdapter.Found) =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            isAllCaps = false
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            val kind = getString(
                if (found.kind == ObdTransport.Kind.BLE) R.string.obd_kind_ble else R.string.obd_kind_classic
            )
            val paired = if (found.bonded) "  ·  ${getString(R.string.obd_already_paired)}" else ""
            text = "${found.name}\n${found.address}  ·  $kind$paired"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setOnClickListener {
                obd.selectDevice(found)
                deviceList.removeAllViews()
                pickerCard.visibility = View.GONE
            }
        }

    private fun sendTypedCommand() {
        val cmd = commandInput.text?.toString()?.trim()?.uppercase().orEmpty()
        if (cmd.isEmpty()) return
        commandInput.text = null
        runCommands(listOf(cmd))
    }

    private fun runCommands(commands: List<String>) {
        Thread {
            val out = commands.map { c ->
                "> $c  ${obd.rawCommand(c).trim().replace("\r", " | ").replace("\n", " | ")}"
            }
            runOnUiThread {
                out.forEach { consoleLines.addFirst(it) }
                while (consoleLines.size > 50) consoleLines.removeLast()
                render()
            }
        }.start()
    }

    private fun copyLog() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("OBD log", logText.text))
        Toast.makeText(this, R.string.logs_copied, Toast.LENGTH_SHORT).show()
    }

    private fun askPermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_BT)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (rc == REQUEST_BT) obd.restart()
    }

    companion object {
        private const val REQUEST_BT = 42
    }
}
