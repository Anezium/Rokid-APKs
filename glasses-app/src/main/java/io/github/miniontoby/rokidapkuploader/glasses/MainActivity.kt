package io.github.miniontoby.rokidapkuploader.glasses

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.github.miniontoby.rokidapkuploader.glasses.spp.SppApkReceiver
import io.github.miniontoby.rokidapkuploader.glasses.spp.SppControlChannel
import io.github.miniontoby.rokidapkuploader.glasses.spp.WifiApkSocketDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var selectedDeviceText: TextView
    private lateinit var previousDeviceButton: Button
    private lateinit var nextDeviceButton: Button
    private lateinit var connectButton: Button

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var bluetoothClient: GlassesBluetoothClient
    private var pairedDevices: List<BluetoothDevice> = emptyList()
    private var selectedDeviceIndex = 0
    private var autoConnectAttempted = false
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val requiredPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }.toTypedArray()

    private val permissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val granted = requiredPermissions.all { grants[it] == true || hasPermission(it) }
            if (granted) {
                refreshPairedDevices()
                maybeAutoConnect()
            } else {
                setStatus("Bluetooth permission is required on the glasses.")
                appendLog("Missing Bluetooth permission on the glasses.")
            }
        }

    private val installStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra(PackageInstallHelper.EXTRA_MESSAGE) ?: return
            setStatus(message)
            appendLog(message)
            connectButton.isEnabled = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        selectedDeviceText = findViewById(R.id.selectedDeviceText)
        previousDeviceButton = findViewById(R.id.previousDeviceButton)
        nextDeviceButton = findViewById(R.id.nextDeviceButton)
        connectButton = findViewById(R.id.connectButton)

        bluetoothClient = GlassesBluetoothClient(this, scope)

        previousDeviceButton.setOnClickListener {
            shiftSelection(-1)
        }
        nextDeviceButton.setOnClickListener {
            shiftSelection(1)
        }
        connectButton.setOnClickListener {
            ensurePermissionsThenStart(manual = true)
        }

        setStatus(getString(R.string.status_idle))
        appendLog("Companion ready.")
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(PackageInstallHelper.ACTION_INSTALL_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(installStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(installStatusReceiver, filter)
        }
        ensurePermissionsThenStart(manual = false)
    }

    override fun onResume() {
        super.onResume()
        if (PackageInstallHelper.resumePendingInstallIfPossible(this, ::appendLog)) {
            setStatus("Install prompt opening on the glasses...")
        }
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(installStatusReceiver) }
    }

    override fun onDestroy() {
        bluetoothClient.cleanup()
        scope.cancel()
        super.onDestroy()
    }

    private fun ensurePermissionsThenStart(manual: Boolean) {
        if (!hasAllPermissions()) {
            permissionsLauncher.launch(requiredPermissions)
            return
        }

        refreshPairedDevices()
        if (manual) {
            currentSelectedDevice()?.let(::connectToPhone)
        } else {
            maybeAutoConnect()
        }
    }

    private fun refreshPairedDevices() {
        val selectedAddress = currentSelectedDevice()?.address
        pairedDevices = bluetoothClient.getPairedDevices()
        if (pairedDevices.isEmpty()) {
            selectedDeviceIndex = 0
            selectedDeviceText.text = getString(R.string.no_paired_phone)
            previousDeviceButton.isEnabled = false
            nextDeviceButton.isEnabled = false
            connectButton.isEnabled = false
            setStatus("Pair the phone with the glasses in Bluetooth settings first.")
            appendLog("No paired phone found on the glasses.")
        } else {
            val restoredIndex = selectedAddress?.let { address ->
                pairedDevices.indexOfFirst { it.address == address }
            }
            if (restoredIndex != null && restoredIndex >= 0) {
                selectedDeviceIndex = restoredIndex
            } else {
                val preferredAddress = PendingInstallStore.getLastDeviceAddress(this)
                val preferredIndex = preferredAddress?.let { address ->
                    pairedDevices.indexOfFirst { it.address == address }
                } ?: -1
                selectedDeviceIndex = when {
                    preferredIndex >= 0 -> preferredIndex
                    selectedDeviceIndex in pairedDevices.indices -> selectedDeviceIndex
                    else -> 0
                }
            }
            updateSelectedDeviceUi()
        }
    }

    private fun maybeAutoConnect() {
        if (autoConnectAttempted || pairedDevices.isEmpty()) {
            return
        }

        val preferred = resolvePreferredDevice()
        if (preferred != null) {
            autoConnectAttempted = true
            connectToPhone(preferred)
        }
    }

    private fun resolvePreferredDevice(): BluetoothDevice? {
        val lastAddress = PendingInstallStore.getLastDeviceAddress(this)
        if (lastAddress != null) {
            pairedDevices.firstOrNull { it.address == lastAddress }?.let { return it }
        }
        return if (pairedDevices.size == 1) pairedDevices.first() else null
    }

    private fun currentSelectedDevice(): BluetoothDevice? {
        if (pairedDevices.isEmpty()) {
            return null
        }
        return pairedDevices.getOrNull(selectedDeviceIndex)
    }

    private fun shiftSelection(direction: Int) {
        if (pairedDevices.size <= 1) {
            return
        }
        selectedDeviceIndex = (selectedDeviceIndex + direction).mod(pairedDevices.size)
        updateSelectedDeviceUi()
    }

    private fun updateSelectedDeviceUi() {
        val device = currentSelectedDevice()
        if (device == null) {
            selectedDeviceText.text = getString(R.string.no_paired_phone)
            previousDeviceButton.isEnabled = false
            nextDeviceButton.isEnabled = false
            connectButton.isEnabled = false
            return
        }

        val name = device.name?.takeIf { it.isNotBlank() } ?: "Unknown device"
        val currentIndex = selectedDeviceIndex + 1
        selectedDeviceText.text = "$name\n${device.address}  ($currentIndex/${pairedDevices.size})"
        previousDeviceButton.isEnabled = pairedDevices.size > 1
        nextDeviceButton.isEnabled = pairedDevices.size > 1
        connectButton.isEnabled = true
    }

    private fun connectToPhone(device: BluetoothDevice) {
        val name = device.name?.takeIf { it.isNotBlank() } ?: device.address
        PendingInstallStore.saveLastDeviceAddress(this, device.address)
        connectButton.isEnabled = false
        setStatus("Connecting to $name...")
        appendLog("Connecting to $name over SPP...")

        bluetoothClient.connect(
            device = device,
            onConnected = { socket ->
                setStatus("Connected. Waiting for the phone mode...")
                appendLog("Phone connected over SPP.")
                val targetFile = File(cacheDir, "pending-install.apk")
                scope.launch {
                    var selectedTransport: String? = null
                    var controlChannel: SppControlChannel? = null
                    runCatching {
                        controlChannel = SppControlChannel(socket)
                        val offer = controlChannel.awaitOffer()
                        selectedTransport = offer.transportMode
                        appendLog("Phone requested mode: ${offer.transportMode}.")
                        when (offer.transportMode) {
                            "spp_slow" -> {
                                runOnUiThread {
                                    setStatus("Receiving APK over direct SPP...")
                                }
                                val stats = SppApkReceiver(socket, targetFile) { receivedBytes, totalBytes ->
                                    val percent = ((receivedBytes * 100L) / totalBytes).toInt()
                                    runOnUiThread {
                                        setStatus("Receiving APK over direct SPP: $percent%")
                                    }
                                }.receiveApk().getOrThrow()
                                appendLog(
                                    "APK received over direct SPP: ${stats.totalBytes} bytes in ${stats.elapsedTimeMs} ms.",
                                )
                            }

                            "wifi_lan" -> {
                                val hostIp = offer.hostIp
                                    ?: throw IllegalStateException("Phone did not provide a Wi-Fi LAN address.")
                                val port = offer.port
                                    ?: throw IllegalStateException("Phone did not provide a Wi-Fi LAN port.")
                                runOnUiThread {
                                    setStatus("Connecting to the phone over Wi-Fi LAN...")
                                }
                                val stats = WifiApkSocketDownloader().downloadApk(
                                    hostIp = hostIp,
                                    port = port,
                                    targetFile = targetFile,
                                    totalBytes = offer.apkSize,
                                    expectedMd5Hex = offer.md5Hex,
                                ) { receivedBytes, totalBytes ->
                                    val percent = ((receivedBytes * 100L) / totalBytes).toInt()
                                    runOnUiThread {
                                        setStatus("Receiving APK over Wi-Fi LAN: $percent%")
                                    }
                                }.getOrThrow()
                                controlChannel?.sendResult(
                                    success = true,
                                    message = "Wi-Fi LAN transfer complete.",
                                )
                                appendLog(
                                    "APK received over Wi-Fi LAN: ${stats.totalBytes} bytes in ${stats.elapsedTimeMs} ms.",
                                )
                            }

                            else -> {
                                throw IllegalStateException("Unsupported phone mode: ${offer.transportMode}")
                            }
                        }

                        runOnUiThread {
                            setStatus("APK received. Launching installer...")
                        }
                        val started = PackageInstallHelper.requestInstall(
                            this@MainActivity,
                            targetFile,
                        ) { message ->
                            appendLog(message)
                            setStatus(message)
                        }
                        if (!started) {
                            connectButton.isEnabled = true
                        }
                    }.onFailure { error ->
                        val message = error.message ?: "Transfer failed on the glasses."
                        appendLog("Transfer failed: $message")
                        setStatus("Transfer failed: $message")
                        connectButton.isEnabled = true
                        if (selectedTransport == "wifi_lan") {
                            runCatching {
                                controlChannel?.sendResult(
                                    success = false,
                                    message = message,
                                )
                            }
                        }
                    }.also {
                        bluetoothClient.cleanup()
                    }
                }
            },
            onFailure = { message ->
                setStatus(message)
                appendLog(message)
                connectButton.isEnabled = true
            },
        )
    }

    private fun hasAllPermissions(): Boolean = requiredPermissions.all(::hasPermission)

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun setStatus(message: String) {
        statusText.text = message
    }

    private fun appendLog(message: String) {
        val timestamp = timeFormat.format(Date())
        val current = logText.text?.toString().orEmpty()
        val line = "[$timestamp] $message"
        logText.text = if (current.isBlank() || current == getString(R.string.log_waiting)) {
            line
        } else {
            "$current\n$line"
        }
    }
}
