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
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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

    // ── views ───────────────────────────────────────────────────────────

    private lateinit var deviceNameText: TextView
    private lateinit var deviceAddressText: TextView
    private lateinit var pageIndicator: TextView
    private lateinit var statusText: TextView
    private lateinit var progressText: TextView
    private lateinit var transferProgress: ProgressBar
    private lateinit var hintText: TextView
    private lateinit var logText: TextView

    // ── gesture handling ────────────────────────────────────────────────

    private lateinit var gestureDetector: GestureDetector

    // ── state ───────────────────────────────────────────────────────────

    private enum class UiState {
        DEVICE_SELECT, CONNECTING, WAITING, TRANSFERRING, INSTALLING, RESULT
    }

    private var uiState = UiState.DEVICE_SELECT
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var bluetoothClient: GlassesBluetoothClient
    private var pairedDevices: List<BluetoothDevice> = emptyList()
    private var selectedDeviceIndex = 0
    private var autoConnectAttempted = false
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val logLines = mutableListOf<String>()

    companion object {
        private const val SWIPE_MIN_DISTANCE_DP = 40f
        private const val SWIPE_DOMINANCE = 1.3f
        private const val MAX_LOG_LINES = 3
    }

    // ── permissions ─────────────────────────────────────────────────────

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
                setStatus("Bluetooth permission required")
                appendLog("Missing Bluetooth permission.")
            }
        }

    private val installStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra(PackageInstallHelper.EXTRA_MESSAGE) ?: return
            transitionTo(UiState.RESULT)
            setStatus(message)
            appendLog(message)
        }
    }

    // ── lifecycle ───────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        setContentView(R.layout.activity_main)

        deviceNameText = findViewById(R.id.deviceNameText)
        deviceAddressText = findViewById(R.id.deviceAddressText)
        pageIndicator = findViewById(R.id.pageIndicator)
        statusText = findViewById(R.id.statusText)
        progressText = findViewById(R.id.progressText)
        transferProgress = findViewById(R.id.transferProgress)
        hintText = findViewById(R.id.hintText)
        logText = findViewById(R.id.logText)

        bluetoothClient = GlassesBluetoothClient(this, scope)
        setupGestures()
        transitionTo(UiState.DEVICE_SELECT)
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
        hideSystemUi()
        if (PackageInstallHelper.resumePendingInstallIfPossible(this, ::appendLog)) {
            setStatus("Install prompt opening...")
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

    // ── gesture & key input ─────────────────────────────────────────────

    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                onTapAction()
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                val minDist = SWIPE_MIN_DISTANCE_DP * resources.displayMetrics.density
                if (Math.abs(dx) >= minDist && Math.abs(dx) > Math.abs(dy) * SWIPE_DOMINANCE) {
                    if (dx > 0) onSwipeRight() else onSwipeLeft()
                    return true
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                onSwipeLeft(); true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                onSwipeRight(); true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            -> {
                onTapAction(); true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun onSwipeLeft() {
        if (uiState == UiState.DEVICE_SELECT && pairedDevices.size > 1) {
            shiftSelection(-1)
        }
    }

    private fun onSwipeRight() {
        if (uiState == UiState.DEVICE_SELECT && pairedDevices.size > 1) {
            shiftSelection(1)
        }
    }

    private fun onTapAction() {
        when (uiState) {
            UiState.DEVICE_SELECT -> {
                ensurePermissionsThenStart(manual = true)
            }
            UiState.RESULT -> {
                transitionTo(UiState.DEVICE_SELECT)
                refreshPairedDevices()
            }
            else -> { /* ignore during transfer */ }
        }
    }

    // ── UI state machine ────────────────────────────────────────────────

    private fun transitionTo(state: UiState) {
        uiState = state
        runOnUiThread { updateUiForState() }
    }

    private fun updateUiForState() {
        when (uiState) {
            UiState.DEVICE_SELECT -> {
                deviceNameText.visibility = View.VISIBLE
                deviceAddressText.visibility = View.VISIBLE
                pageIndicator.visibility = if (pairedDevices.size > 1) View.VISIBLE else View.GONE
                progressText.visibility = View.GONE
                transferProgress.visibility = View.GONE
                hintText.visibility = View.VISIBLE
                hintText.text = when {
                    pairedDevices.size > 1 -> getString(R.string.hint_swipe_tap)
                    pairedDevices.isNotEmpty() -> getString(R.string.hint_tap_connect)
                    else -> getString(R.string.hint_no_device)
                }
                setStatus(getString(R.string.status_idle))
                updateSelectedDeviceUi()
            }

            UiState.CONNECTING, UiState.WAITING -> {
                deviceNameText.visibility = View.VISIBLE
                deviceAddressText.visibility = View.VISIBLE
                pageIndicator.visibility = View.GONE
                progressText.visibility = View.GONE
                transferProgress.visibility = View.GONE
                hintText.visibility = View.GONE
            }

            UiState.TRANSFERRING -> {
                deviceNameText.visibility = View.GONE
                deviceAddressText.visibility = View.GONE
                pageIndicator.visibility = View.GONE
                progressText.visibility = View.VISIBLE
                transferProgress.visibility = View.VISIBLE
                hintText.visibility = View.GONE
            }

            UiState.INSTALLING -> {
                deviceNameText.visibility = View.GONE
                deviceAddressText.visibility = View.GONE
                pageIndicator.visibility = View.GONE
                progressText.visibility = View.GONE
                transferProgress.visibility = View.GONE
                hintText.visibility = View.GONE
            }

            UiState.RESULT -> {
                deviceNameText.visibility = View.GONE
                deviceAddressText.visibility = View.GONE
                pageIndicator.visibility = View.GONE
                progressText.visibility = View.GONE
                transferProgress.visibility = View.GONE
                hintText.visibility = View.VISIBLE
                hintText.text = getString(R.string.hint_tap_continue)
            }
        }
    }

    // ── system UI ───────────────────────────────────────────────────────

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ── permissions & init ──────────────────────────────────────────────

    private fun ensurePermissionsThenStart(manual: Boolean) {
        if (!hasAllPermissions()) {
            permissionsLauncher.launch(requiredPermissions)
            return
        }
        refreshPairedDevices()
        if (manual) {
            currentSelectedDevice()?.let { device ->
                connectToPhone(device, initiatedAutomatically = false)
            }
        } else {
            maybeAutoConnect()
        }
    }

    // ── device selection ────────────────────────────────────────────────

    private fun refreshPairedDevices() {
        val selectedAddress = currentSelectedDevice()?.address
        pairedDevices = bluetoothClient.getPairedDevices()
        if (pairedDevices.isEmpty()) {
            selectedDeviceIndex = 0
            updateSelectedDeviceUi()
            setStatus("Pair the phone in Bluetooth settings")
            appendLog("No paired phone found.")
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
        if (autoConnectAttempted || pairedDevices.isEmpty()) return
        val preferred = resolvePreferredDevice()
        if (preferred != null) {
            autoConnectAttempted = true
            connectToPhone(preferred, initiatedAutomatically = true)
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
        if (pairedDevices.isEmpty()) return null
        return pairedDevices.getOrNull(selectedDeviceIndex)
    }

    private fun shiftSelection(direction: Int) {
        if (pairedDevices.size <= 1) return
        selectedDeviceIndex = (selectedDeviceIndex + direction).mod(pairedDevices.size)
        updateSelectedDeviceUi()
    }

    private fun updateSelectedDeviceUi() {
        val device = currentSelectedDevice()
        if (device == null) {
            deviceNameText.text = getString(R.string.no_paired_phone)
            deviceAddressText.text = ""
            pageIndicator.visibility = View.GONE
            return
        }

        val name = device.name?.takeIf { it.isNotBlank() } ?: "Unknown"
        deviceNameText.text = name
        deviceAddressText.text = device.address

        if (pairedDevices.size > 1) {
            pageIndicator.visibility = View.VISIBLE
            pageIndicator.text = buildString {
                for (i in pairedDevices.indices) {
                    if (i > 0) append("  ")
                    append(if (i == selectedDeviceIndex) "●" else "○")
                }
            }
        } else {
            pageIndicator.visibility = View.GONE
        }
    }

    // ── connection & transfer ───────────────────────────────────────────

    private fun connectToPhone(
        device: BluetoothDevice,
        initiatedAutomatically: Boolean = false,
    ) {
        val name = device.name?.takeIf { it.isNotBlank() } ?: device.address
        PendingInstallStore.saveLastDeviceAddress(this, device.address)
        transitionTo(UiState.CONNECTING)
        setStatus("Connecting to $name…")
        appendLog("Connecting to $name over SPP…")

        bluetoothClient.connect(
            device = device,
            onConnected = { socket ->
                transitionTo(UiState.WAITING)
                setStatus("Connected. Waiting for phone…")
                appendLog("Phone connected over SPP.")
                val targetFile = File(cacheDir, "pending-install.apk")
                scope.launch {
                    var selectedTransport: String? = null
                    var controlChannel: SppControlChannel? = null
                    runCatching {
                        val channel = SppControlChannel(socket)
                        controlChannel = channel
                        val offer = channel.awaitOffer()
                        selectedTransport = offer.transportMode
                        appendLog("Phone mode: ${offer.transportMode}")
                        when (offer.transportMode) {
                            "spp_slow" -> {
                                runOnUiThread {
                                    transitionTo(UiState.TRANSFERRING)
                                    setStatus("Receiving via SPP")
                                }
                                val stats =
                                    SppApkReceiver(socket, targetFile) { receivedBytes, totalBytes ->
                                        val percent =
                                            ((receivedBytes * 100L) / totalBytes).toInt()
                                        runOnUiThread {
                                            progressText.text = "$percent%"
                                            transferProgress.progress = percent
                                        }
                                    }.receiveApk().getOrThrow()
                                appendLog(
                                    "APK received: ${stats.totalBytes}B in ${stats.elapsedTimeMs}ms",
                                )
                            }

                            "wifi_lan" -> {
                                val hostIp = offer.hostIp
                                    ?: throw IllegalStateException("Phone did not provide Wi-Fi address.")
                                val port = offer.port
                                    ?: throw IllegalStateException("Phone did not provide Wi-Fi port.")
                                runOnUiThread {
                                    transitionTo(UiState.TRANSFERRING)
                                    setStatus("Receiving via Wi-Fi")
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
                                        progressText.text = "$percent%"
                                        transferProgress.progress = percent
                                    }
                                }.getOrThrow()
                                channel.sendResult(
                                    success = true,
                                    message = "Wi-Fi LAN transfer complete.",
                                )
                                appendLog(
                                    "APK received: ${stats.totalBytes}B in ${stats.elapsedTimeMs}ms",
                                )
                            }

                            else -> {
                                throw IllegalStateException("Unsupported mode: ${offer.transportMode}")
                            }
                        }

                        runOnUiThread {
                            transitionTo(UiState.INSTALLING)
                            setStatus("APK received. Installing…")
                        }
                        val started = PackageInstallHelper.requestInstall(
                            this@MainActivity,
                            targetFile,
                        ) { message ->
                            appendLog(message)
                            setStatus(message)
                        }
                        if (!started) {
                            transitionTo(UiState.RESULT)
                        }
                    }.onFailure { error ->
                        val message = formatConnectionMessage(error.message)
                        if (selectedTransport == null) {
                            handlePreTransferFailure(message, initiatedAutomatically)
                        } else {
                            appendLog("Failed: $message")
                            runOnUiThread {
                                transitionTo(UiState.RESULT)
                                setStatus("Failed: $message")
                            }
                        }
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
                handlePreTransferFailure(
                    formatConnectionMessage(message),
                    initiatedAutomatically,
                )
            },
        )
    }

    private fun handlePreTransferFailure(
        message: String,
        initiatedAutomatically: Boolean,
    ) {
        val status = if (initiatedAutomatically) {
            "Phone not ready. Start the transfer on the phone, then tap to connect."
        } else {
            message
        }
        val logMessage = if (initiatedAutomatically) {
            "Auto-connect skipped: $message"
        } else {
            message
        }
        runOnUiThread {
            transitionTo(UiState.DEVICE_SELECT)
            setStatus(status)
            appendLog(logMessage)
        }
    }

    private fun formatConnectionMessage(rawMessage: String?): String {
        val message = rawMessage?.trim().orEmpty()
        val lowered = message.lowercase(Locale.getDefault())
        return when {
            lowered.contains("read failed") ||
                lowered.contains("socket might closed") ||
                lowered.contains("ret: -1") ||
                lowered.contains("connection refused") ||
                lowered.contains("service discovery failed") ||
                lowered.contains("control channel closed") -> {
                "The phone companion is not listening yet. Start the transfer on the phone, then tap again."
            }

            message.isBlank() -> "Connection failed. Start the transfer on the phone, then tap again."
            else -> message
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun hasAllPermissions(): Boolean = requiredPermissions.all(::hasPermission)

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            permission,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun setStatus(message: String) {
        runOnUiThread { statusText.text = message }
    }

    private fun appendLog(message: String) {
        runOnUiThread {
            val timestamp = timeFormat.format(Date())
            logLines.add("[$timestamp] $message")
            while (logLines.size > MAX_LOG_LINES) {
                logLines.removeAt(0)
            }
            logText.text = logLines.joinToString("\n")
        }
    }
}
