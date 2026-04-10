package com.rokidapks

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    companion object {
        private const val PHASE_DONE = "\u25CF"
        private const val PHASE_CURRENT = "\u25D0"
        private const val PHASE_PENDING = "\u25CB"
    }

    private enum class TransportMode {
        CXR,
        SPP_SLOW,
        WIFI_LAN,
    }

    private lateinit var transportSpinner: Spinner
    private lateinit var modeHintText: TextView
    private lateinit var serialInput: EditText
    private lateinit var filePathText: TextView
    private lateinit var deviceText: TextView
    private lateinit var deviceSpinner: Spinner
    private lateinit var scanDevicesButton: Button
    private lateinit var uploadButton: Button
    private lateinit var logText: TextView
    private lateinit var logScrollView: ScrollView
    private lateinit var viewStatusDot: View
    private lateinit var tvHeaderStatus: TextView
    private lateinit var tvStatusState: TextView
    private lateinit var tvStatusBt: TextView
    private lateinit var tvStatusDevices: TextView
    private lateinit var rokidSession: RokidInstallerSession
    private lateinit var sppSlowSession: SppSlowUploadSession
    private lateinit var wifiLanSession: WifiLanUploadSession

    private lateinit var phaseViews: List<TextView>
    private var phaseLabels = listOf("WAIT", "LINK", "LAN", "INST", "OK")
    private var currentMode = TransportMode.WIFI_LAN

    private var selectedApkUri: Uri? = null
    private var discoveredDevices: List<BluetoothDevice> = emptyList()
    private var pendingAction: (() -> Unit)? = null
    private var isBusy = false
    private var currentPhase = -1
    private var dotPulseAnimator: ObjectAnimator? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val requiredPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                if (currentMode == TransportMode.CXR) {
                    add(Manifest.permission.BLUETOOTH_SCAN)
                }
            }
            if (currentMode == TransportMode.CXR) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.NEARBY_WIFI_DEVICES)
                }
            }
        }.toTypedArray()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val allGranted = requiredPermissions.all { permission -> grants[permission] == true || hasPermission(permission) }
            if (allGranted) {
                consumePendingAction()
            } else {
                appendLog(getString(R.string.permission_needed))
                Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show()
            }
        }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (isBluetoothEnabled()) {
                consumePendingAction()
            } else {
                appendLog(getString(R.string.enable_bluetooth))
            }
        }

    private val apkPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        selectedApkUri = uri
        val name = resolveDisplayName(uri)
        filePathText.text = name
        filePathText.setTextColor(ContextCompat.getColor(this, R.color.phosphor_primary))
        appendLog("APK selected: $name")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        transportSpinner = findViewById(R.id.transportSpinner)
        modeHintText = findViewById(R.id.modeHintText)
        serialInput = findViewById(R.id.serialInput)
        filePathText = findViewById(R.id.filePathText)
        deviceText = findViewById(R.id.deviceText)
        deviceSpinner = findViewById(R.id.deviceSpinner)
        scanDevicesButton = findViewById(R.id.scanDevicesButton)
        uploadButton = findViewById(R.id.uploadButton)
        logText = findViewById(R.id.logText)
        logScrollView = findViewById(R.id.logScrollView)
        viewStatusDot = findViewById(R.id.viewStatusDot)
        tvHeaderStatus = findViewById(R.id.tvHeaderStatus)
        tvStatusState = findViewById(R.id.tvStatusState)
        tvStatusBt = findViewById(R.id.tvStatusBt)
        tvStatusDevices = findViewById(R.id.tvStatusDevices)

        phaseViews = listOf(
            findViewById(R.id.phaseBle),
            findViewById(R.id.phaseAuth),
            findViewById(R.id.phaseP2p),
            findViewById(R.id.phaseUpload),
            findViewById(R.id.phaseInstall),
        )

        dotPulseAnimator = ObjectAnimator.ofFloat(viewStatusDot, "alpha", 1f, 0.15f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
        }

        rokidSession = RokidInstallerSession(
            activity = this,
            onStatus = ::updateStatus,
            onDevicesChanged = ::updateDevices,
            onBusyChanged = ::setBusy,
        )
        sppSlowSession = SppSlowUploadSession(
            activity = this,
            onStatus = ::updateStatus,
            onBusyChanged = ::setBusy,
        )
        wifiLanSession = WifiLanUploadSession(
            activity = this,
            onStatus = ::updateStatus,
            onBusyChanged = ::setBusy,
        )

        setupTransportSpinner()

        findViewById<Button>(R.id.selectFileButton).setOnClickListener {
            apkPicker.launch("application/vnd.android.package-archive")
        }

        scanDevicesButton.setOnClickListener {
            runWithPrerequisites {
                rokidSession.startScan()
            }
        }

        uploadButton.setOnClickListener {
            runWithPrerequisites {
                val apkUri = selectedApkUri
                if (apkUri == null) {
                    Toast.makeText(this, R.string.select_apk_first, Toast.LENGTH_SHORT).show()
                    appendLog(getString(R.string.select_apk_first))
                    return@runWithPrerequisites
                }

                when (currentMode) {
                    TransportMode.SPP_SLOW -> {
                        sppSlowSession.sendApk(apkUri)
                    }

                    TransportMode.WIFI_LAN -> {
                        wifiLanSession.sendApk(apkUri)
                    }

                    TransportMode.CXR -> {
                        val device = currentSelectedDevice()
                        when {
                            device == null && !rokidSession.hasSavedBluetoothEndpoint() -> {
                                Toast.makeText(this, R.string.select_device, Toast.LENGTH_SHORT).show()
                                appendLog(getString(R.string.select_device))
                            }

                            else -> {
                                val serialNumber = serialInput.text?.toString()?.trim().orEmpty()
                                rokidSession.installApk(
                                    device = device,
                                    apkUri = apkUri,
                                    serialNumber = serialNumber.ifBlank { null },
                                )
                            }
                        }
                    }
                }
            }
        }

        updateDevices(emptyList())
        refreshModeUi()
        refreshStatusBar()
    }

    override fun onDestroy() {
        dotPulseAnimator?.cancel()
        rokidSession.cleanup()
        sppSlowSession.cleanup()
        wifiLanSession.cleanup()
        super.onDestroy()
    }

    private fun setupTransportSpinner() {
        val labels = listOf(
            getString(R.string.transport_mode_cxr),
            getString(R.string.transport_mode_spp_slow),
            getString(R.string.transport_mode_wifi_lan),
        )
        val adapter = ArrayAdapter(this, R.layout.spinner_item, labels)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        transportSpinner.adapter = adapter
        transportSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                currentMode = when (position) {
                    0 -> TransportMode.CXR
                    1 -> TransportMode.SPP_SLOW
                    else -> TransportMode.WIFI_LAN
                }
                refreshModeUi()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        transportSpinner.setSelection(2)
    }

    private fun refreshModeUi() {
        when (currentMode) {
            TransportMode.CXR -> {
                modeHintText.text = getString(R.string.transport_hint_cxr)
                serialInput.isEnabled = true
                serialInput.alpha = 1f
                serialInput.hint = getString(R.string.hint_serial)
                scanDevicesButton.visibility = View.VISIBLE
                phaseLabels = listOf("BLE", "AUTH", "WIFI", "UP", "OK")
                updateDevices(discoveredDevices)
            }

            TransportMode.SPP_SLOW -> {
                modeHintText.text = getString(R.string.transport_hint_spp_slow)
                serialInput.setText("")
                serialInput.isEnabled = false
                serialInput.alpha = 0.55f
                serialInput.hint = getString(R.string.hint_serial_unused_companion)
                scanDevicesButton.visibility = View.GONE
                deviceSpinner.visibility = View.GONE
                deviceText.visibility = View.VISIBLE
                deviceText.text = getString(R.string.device_hint_spp_slow)
                deviceText.setTextColor(ContextCompat.getColor(this, R.color.phosphor_text_mid))
                phaseLabels = listOf("WAIT", "LINK", "SEND", "INST", "OK")
            }

            TransportMode.WIFI_LAN -> {
                modeHintText.text = getString(R.string.transport_hint_wifi_lan)
                serialInput.setText("")
                serialInput.isEnabled = false
                serialInput.alpha = 0.55f
                serialInput.hint = getString(R.string.hint_serial_unused_companion)
                scanDevicesButton.visibility = View.GONE
                deviceSpinner.visibility = View.GONE
                deviceText.visibility = View.VISIBLE
                deviceText.text = getString(R.string.device_hint_wifi_lan)
                deviceText.setTextColor(ContextCompat.getColor(this, R.color.phosphor_text_mid))
                phaseLabels = listOf("WAIT", "LINK", "LAN", "INST", "OK")
            }
        }
        resetPhases()
        refreshStatusBar()
        updatePrimaryButtonText()
    }

    private fun updateStatus(status: String) {
        appendLog(status)
        inferPhase(status)
    }

    private fun appendLog(message: String) {
        val timestamp = timeFormat.format(Date())
        val current = logText.text.toString()
        val line = "[$timestamp] $message"
        logText.text = if (current == getString(R.string.log_waiting)) {
            line
        } else {
            "$current\n$line"
        }
        logScrollView.post { logScrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun inferPhase(status: String) {
        val lower = status.lowercase()
        val phase = when {
            lower.contains("install succeeded") || lower.contains("install success") || lower.contains("complete") -> 4
            lower.contains("install prompt") || lower.contains("installer") || lower.contains("watch the glasses") || lower.contains("launching installer") -> 3
            lower.contains("sending") || lower.contains("upload") || lower.contains("receiving apk") || lower.contains("transfer") || lower.contains("wi-fi lan is ready") || lower.contains("hotspot ready") || lower.contains("hotspot connected") || (lower.contains("joining") && lower.contains("hotspot")) -> 2
            lower.contains("connected") || lower.contains("auth") || lower.contains("link") -> 1
            lower.contains("scan") || lower.contains("ble") || lower.contains("listen") || lower.contains("waiting") || lower.contains("preparing") -> 0
            else -> return
        }
        if (phase >= currentPhase) {
            setPhase(phase)
        }
    }

    private fun setPhase(index: Int) {
        currentPhase = index
        val ghostColor = ContextCompat.getColor(this, R.color.phosphor_text_ghost)
        val primaryColor = ContextCompat.getColor(this, R.color.phosphor_primary)
        val midColor = ContextCompat.getColor(this, R.color.phosphor_mid)

        for (i in phaseViews.indices) {
            val label = phaseLabels[i]
            when {
                i < index -> {
                    phaseViews[i].text = "$PHASE_DONE $label"
                    phaseViews[i].setTextColor(primaryColor)
                    phaseViews[i].alpha = 1f
                }

                i == index -> {
                    phaseViews[i].text = "$PHASE_CURRENT $label"
                    phaseViews[i].setTextColor(midColor)
                    phaseViews[i].alpha = 1f
                }

                else -> {
                    phaseViews[i].text = "$PHASE_PENDING $label"
                    phaseViews[i].setTextColor(ghostColor)
                    phaseViews[i].alpha = 1f
                }
            }
        }
    }

    private fun resetPhases() {
        currentPhase = -1
        val ghostColor = ContextCompat.getColor(this, R.color.phosphor_text_ghost)
        for (i in phaseViews.indices) {
            phaseViews[i].text = "$PHASE_PENDING ${phaseLabels[i]}"
            phaseViews[i].setTextColor(ghostColor)
        }
    }

    private fun setBusy(busy: Boolean) {
        isBusy = busy
        scanDevicesButton.isEnabled = !busy
        uploadButton.isEnabled = !busy
        transportSpinner.isEnabled = !busy

        if (busy) {
            uploadButton.text = getString(R.string.cancel_upload)
            uploadButton.setTextColor(ContextCompat.getColor(this, R.color.phosphor_dim))
            uploadButton.setBackgroundResource(R.drawable.btn_outline_green)
            tvHeaderStatus.text = getString(R.string.busy)
            tvStatusState.text = getString(R.string.busy)
            tvStatusState.setTextColor(ContextCompat.getColor(this, R.color.phosphor_mid))
            dotPulseAnimator?.start()
        } else {
            updatePrimaryButtonText()
            tvHeaderStatus.text = getString(R.string.ready)
            tvStatusState.text = getString(R.string.ready)
            tvStatusState.setTextColor(ContextCompat.getColor(this, R.color.phosphor_primary))
            dotPulseAnimator?.cancel()
            viewStatusDot.alpha = 1f

            if (currentPhase == 4) {
                val primaryColor = ContextCompat.getColor(this, R.color.phosphor_primary)
                for (i in phaseViews.indices) {
                    phaseViews[i].text = "$PHASE_DONE ${phaseLabels[i]}"
                    phaseViews[i].setTextColor(primaryColor)
                }
            } else {
                resetPhases()
            }
        }

        refreshStatusBar()
    }

    private fun updatePrimaryButtonText() {
        if (isBusy) {
            return
        }
        val buttonLabel = when (currentMode) {
            TransportMode.CXR -> getString(R.string.upload_apk)
            TransportMode.SPP_SLOW -> getString(R.string.send_apk_spp_slow)
            TransportMode.WIFI_LAN -> getString(R.string.send_apk_wifi_lan)
        }
        uploadButton.text = buttonLabel
        uploadButton.setTextColor(resources.getColor(R.color.phosphor_bg, theme))
        uploadButton.setBackgroundResource(R.drawable.btn_primary_green)
    }

    private fun updateDevices(devices: List<BluetoothDevice>) {
        discoveredDevices = devices
        if (currentMode != TransportMode.CXR) {
            tvStatusDevices.text = when (currentMode) {
                TransportMode.SPP_SLOW -> "SPP"
                TransportMode.WIFI_LAN -> "LAN"
                TransportMode.CXR -> "${devices.size} DEV"
            }
            deviceSpinner.visibility = View.GONE
            deviceText.visibility = View.VISIBLE
            deviceText.text = when (currentMode) {
                TransportMode.SPP_SLOW -> getString(R.string.device_hint_spp_slow)
                TransportMode.WIFI_LAN -> getString(R.string.device_hint_wifi_lan)
                TransportMode.CXR -> deviceText.text
            }
            deviceText.setTextColor(ContextCompat.getColor(this, R.color.phosphor_text_mid))
            return
        }

        tvStatusDevices.text = "${devices.size} DEV"

        if (devices.isEmpty()) {
            deviceText.text = getString(R.string.no_device_found)
            deviceText.setTextColor(ContextCompat.getColor(this, R.color.phosphor_text_ghost))
            deviceText.visibility = View.VISIBLE
            deviceSpinner.visibility = View.GONE
        } else if (devices.size == 1) {
            val name = devices[0].name?.takeIf { it.isNotBlank() } ?: getString(R.string.unknown_device)
            deviceText.text = "$name (${devices[0].address})"
            deviceText.setTextColor(ContextCompat.getColor(this, R.color.phosphor_primary))
            deviceText.visibility = View.VISIBLE
            deviceSpinner.visibility = View.GONE
        } else {
            deviceText.visibility = View.GONE
            deviceSpinner.visibility = View.VISIBLE
            val labels = devices.map { device ->
                val name = device.name?.takeIf { it.isNotBlank() } ?: getString(R.string.unknown_device)
                "$name (${device.address})"
            }
            val adapter = ArrayAdapter(this, R.layout.spinner_item, labels)
            adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
            deviceSpinner.adapter = adapter
        }
    }

    private fun refreshStatusBar() {
        tvStatusBt.text = if (isBluetoothEnabled()) "BT: ON" else "BT: OFF"
        if (currentMode != TransportMode.CXR) {
            tvStatusDevices.text = if (currentMode == TransportMode.SPP_SLOW) "SPP" else "LAN"
        }
    }

    private fun runWithPrerequisites(action: () -> Unit) {
        pendingAction = action
        when {
            !hasAllPermissions() -> permissionLauncher.launch(requiredPermissions)
            !isBluetoothEnabled() -> {
                val intent = Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
                enableBluetoothLauncher.launch(intent)
            }

            else -> consumePendingAction()
        }
    }

    private fun consumePendingAction() {
        val action = pendingAction ?: return
        pendingAction = null
        action()
    }

    private fun hasAllPermissions(): Boolean = requiredPermissions.all(::hasPermission)

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun isBluetoothEnabled(): Boolean {
        val manager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager ?: return false
        return manager.adapter?.isEnabled == true
    }

    private fun currentSelectedDevice(): BluetoothDevice? {
        if (discoveredDevices.isEmpty()) return null
        if (discoveredDevices.size == 1) return discoveredDevices[0]
        return discoveredDevices.getOrNull(deviceSpinner.selectedItemPosition)
    }

    private fun resolveDisplayName(uri: Uri): String {
        val cursor: Cursor? = contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )
        cursor.use {
            if (it != null && it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    return it.getString(index)
                }
            }
        }
        return uri.lastPathSegment ?: getString(R.string.apk_selected)
    }
}
