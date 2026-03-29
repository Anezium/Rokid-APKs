package io.github.miniontoby.rokidapkuploader

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
    private lateinit var session: RokidInstallerSession

    private lateinit var phaseViews: List<TextView>
    private val phaseLabels = listOf("BLE", "AUTH", "P2P", "UP", "OK")

    private var selectedApkUri: Uri? = null
    private var discoveredDevices: List<BluetoothDevice> = emptyList()
    private var pendingAction: (() -> Unit)? = null
    private var isBusy = false
    private var currentPhase = -1
    private var dotPulseAnimator: ObjectAnimator? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val requiredPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }.toTypedArray()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val allGranted = requiredPermissions.all { permission -> grants[permission] == true }
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

        session = RokidInstallerSession(
            activity = this,
            onStatus = ::updateStatus,
            onDevicesChanged = ::updateDevices,
            onBusyChanged = ::setBusy
        )

        findViewById<Button>(R.id.selectFileButton).setOnClickListener {
            apkPicker.launch("application/vnd.android.package-archive")
        }

        scanDevicesButton.setOnClickListener {
            runWithPrerequisites {
                session.startScan()
            }
        }

        uploadButton.setOnClickListener {
            runWithPrerequisites {
                val apkUri = selectedApkUri
                val device = currentSelectedDevice()
                when {
                    apkUri == null -> {
                        Toast.makeText(this, R.string.select_apk_first, Toast.LENGTH_SHORT).show()
                        appendLog(getString(R.string.select_apk_first))
                    }

                    device == null && !session.hasSavedBluetoothEndpoint() -> {
                        Toast.makeText(this, R.string.select_device, Toast.LENGTH_SHORT).show()
                        appendLog(getString(R.string.select_device))
                    }

                    else -> {
                        val serialNumber = serialInput.text?.toString()?.trim().orEmpty()
                        session.installApk(
                            device = device,
                            apkUri = apkUri,
                            serialNumber = serialNumber.ifBlank { null }
                        )
                    }
                }
            }
        }

        updateDevices(emptyList())
        refreshStatusBar()
    }

    override fun onDestroy() {
        dotPulseAnimator?.cancel()
        session.cleanup()
        super.onDestroy()
    }

    // ── Status & logging ──

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
            lower.contains("install") && (lower.contains("success") || lower.contains("complete")) -> 4
            lower.contains("upload") || lower.contains("uploading") -> 3
            lower.contains("wi-fi") || lower.contains("wifi") || lower.contains("p2p") || lower.contains("peer") -> 2
            lower.contains("bluetooth") || lower.contains("pair") || lower.contains("auth") || lower.contains("connect") -> 1
            lower.contains("scan") || lower.contains("ble") || lower.contains("found") -> 0
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
                    phaseViews[i].text = "● $label"
                    phaseViews[i].setTextColor(primaryColor)
                    phaseViews[i].alpha = 1f
                }
                i == index -> {
                    phaseViews[i].text = "◐ $label"
                    phaseViews[i].setTextColor(midColor)
                    phaseViews[i].alpha = 1f
                }
                else -> {
                    phaseViews[i].text = "○ $label"
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
            phaseViews[i].text = "○ ${phaseLabels[i]}"
            phaseViews[i].setTextColor(ghostColor)
        }
    }

    // ── Busy state ──

    private fun setBusy(busy: Boolean) {
        isBusy = busy
        scanDevicesButton.isEnabled = !busy
        uploadButton.isEnabled = !busy

        if (busy) {
            uploadButton.text = getString(R.string.cancel_upload)
            uploadButton.setTextColor(ContextCompat.getColor(this, R.color.phosphor_dim))
            uploadButton.setBackgroundResource(R.drawable.btn_outline_green)
            tvHeaderStatus.text = getString(R.string.busy)
            tvStatusState.text = getString(R.string.busy)
            tvStatusState.setTextColor(ContextCompat.getColor(this, R.color.phosphor_mid))
            dotPulseAnimator?.start()
        } else {
            uploadButton.text = getString(R.string.upload_apk)
            uploadButton.setTextColor(resources.getColor(R.color.phosphor_bg, theme))
            uploadButton.setBackgroundResource(R.drawable.btn_primary_green)
            tvHeaderStatus.text = getString(R.string.ready)
            tvStatusState.text = getString(R.string.ready)
            tvStatusState.setTextColor(ContextCompat.getColor(this, R.color.phosphor_primary))
            dotPulseAnimator?.cancel()
            viewStatusDot.alpha = 1f

            // Mark all completed phases as done if we finished successfully
            if (currentPhase == 4) {
                val primaryColor = ContextCompat.getColor(this, R.color.phosphor_primary)
                for (i in phaseViews.indices) {
                    phaseViews[i].text = "● ${phaseLabels[i]}"
                    phaseViews[i].setTextColor(primaryColor)
                }
            } else {
                resetPhases()
            }
        }

        refreshStatusBar()
    }

    // ── Devices ──

    private fun updateDevices(devices: List<BluetoothDevice>) {
        discoveredDevices = devices
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

    // ── Status bar ──

    private fun refreshStatusBar() {
        tvStatusBt.text = if (isBluetoothEnabled()) "BT: ON" else "BT: OFF"
    }

    // ── Prerequisites ──

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

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isBluetoothEnabled(): Boolean {
        val manager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager ?: return false
        return manager.adapter?.isEnabled == true
    }

    // ── Helpers ──

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
            null
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
