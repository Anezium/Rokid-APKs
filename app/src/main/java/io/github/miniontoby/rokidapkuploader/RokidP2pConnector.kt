package io.github.miniontoby.rokidapkuploader

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.MacAddress
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

class RokidP2pConnector(
    context: Context,
    private val targetDeviceName: String?,
    private val targetDeviceMac: String?,
    private val onStatus: (String) -> Unit,
    private val onConnected: (String) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private val tag = "RokidP2pConnector"
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false
    private var finished = false
    private var isP2pEnabled = false
    private var isDiscovering = false
    private var isConnecting = false
    private var targetDeviceFound = false
    private var discoveryRetryCount = 0
    private var connectionRetryCount = 0
    private var unmatchedPeerPasses = 0
    private var pendingStartDiscovery = false
    private var lastThisDeviceChangedAt = 0L
    private var lastConnectionProgressStatusAt = 0L
    private var peerPollScheduled = false
    private var connectionInfoPoll: Runnable? = null
    private var connectionAttemptTimeout: Runnable? = null

    private val timeoutRunnable = Runnable {
        fail("Wi-Fi Direct timeout")
    }

    private val peerPollRunnable = object : Runnable {
        override fun run() {
            peerPollScheduled = false
            if (finished || !isDiscovering || targetDeviceFound) {
                return
            }
            requestPeers()
            schedulePeerPoll()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    isP2pEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    if (!isP2pEnabled) {
                        pendingStartDiscovery = false
                        isDiscovering = false
                        targetDeviceFound = false
                        if (!finished) {
                            onStatus("Wi-Fi Direct not ready on the phone yet.")
                        }
                        return
                    }

                    if (pendingStartDiscovery && !finished) {
                        pendingStartDiscovery = false
                        mainHandler.postDelayed({ startDiscovery(isRetry = false) }, 700L)
                    }
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (isDiscovering && !targetDeviceFound) {
                        requestPeers()
                    }
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(
                            WifiP2pManager.EXTRA_NETWORK_INFO,
                            NetworkInfo::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                    }

                    if (networkInfo?.isConnected == true) {
                        postConnectionProgressStatus("Wi-Fi Direct link reported by Android. Fetching IP...")
                        requestConnectionInfo()
                    } else if (isConnecting && !finished) {
                        // Android often emits a transient disconnected state while the
                        // Wi-Fi Direct group is still being negotiated. Canceling the
                        // session here aborts the very handshake we just started.
                        postConnectionProgressStatus("Wi-Fi Direct negotiation still in progress...")
                    }
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val now = System.currentTimeMillis()
                    if (!isDiscovering || targetDeviceFound || finished) {
                        return
                    }
                    if (now - lastThisDeviceChangedAt < 2_000L) {
                        return
                    }
                    lastThisDeviceChangedAt = now
                    resetAndRestartDiscovery()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (finished) {
            return
        }

        manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (manager == null) {
            fail("WifiP2pManager unavailable")
            return
        }

        channel = manager?.initialize(appContext, Looper.getMainLooper(), null)
        if (channel == null) {
            fail("Wi-Fi Direct channel unavailable")
            return
        }

        registerReceiver()
        mainHandler.removeCallbacks(timeoutRunnable)
        mainHandler.postDelayed(timeoutRunnable, 45_000L)

        onStatus("Preparing Wi-Fi Direct...")
        if (isP2pEnabled) {
            startDiscovery(isRetry = false)
        } else {
            pendingStartDiscovery = true
            // The SDK usually enables P2P just before advertising the glasses peer.
            // If the enable broadcast is missed because the receiver started slightly late,
            // force a retry path that skips the optimistic "enabled" gate.
            mainHandler.postDelayed({
                if (pendingStartDiscovery && !finished) {
                    startDiscovery(isRetry = true)
                }
            }, 1_500L)
        }
    }

    @SuppressLint("MissingPermission")
    fun cleanup() {
        mainHandler.removeCallbacks(timeoutRunnable)
        clearPeerPoll()
        clearConnectionInfoPoll()
        clearConnectionAttemptTimeout()
        pendingStartDiscovery = false
        isDiscovering = false
        isConnecting = false

        val localManager = manager
        val localChannel = channel
        if (localManager != null && localChannel != null) {
            runCatching { localManager.stopPeerDiscovery(localChannel, null) }
            runCatching { localManager.cancelConnect(localChannel, null) }
            runCatching { localManager.removeGroup(localChannel, null) }
        }

        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery(isRetry: Boolean) {
        if (finished) {
            return
        }

        val localManager = manager ?: return fail("Wi-Fi Direct manager lost")
        val localChannel = channel ?: return fail("Wi-Fi Direct channel lost")

        if (!isP2pEnabled && !isRetry) {
            pendingStartDiscovery = true
            onStatus("Waiting for Wi-Fi Direct to become ready...")
            return
        }

        isDiscovering = true
        targetDeviceFound = false
        if (!isRetry) {
            discoveryRetryCount = 0
            unmatchedPeerPasses = 0
        }

        onStatus("Scanning Wi-Fi Direct peers...")
        localManager.discoverPeers(localChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                discoveryRetryCount = 0
                requestPeers()
                schedulePeerPoll()
            }

            override fun onFailure(reason: Int) {
                discoveryRetryCount += 1
                if (discoveryRetryCount <= 10) {
                    onStatus("Retrying Wi-Fi Direct discovery... ($discoveryRetryCount/10)")
                    mainHandler.postDelayed({ resetAndRestartDiscovery() }, 1_000L)
                } else {
                    fail("Wi-Fi Direct discovery failed: $reason")
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun requestPeers() {
        val localManager = manager ?: return
        val localChannel = channel ?: return
        localManager.requestPeers(localChannel) { peers ->
            handlePeers(peers)
        }
    }

    @SuppressLint("MissingPermission")
    private fun handlePeers(peers: WifiP2pDeviceList) {
        if (finished) {
            return
        }

        val devices = peers.deviceList.toList()
        if (devices.isEmpty()) {
            unmatchedPeerPasses += 1
            onStatus("Target glasses not visible yet. Waiting...")
            return
        }

        val exactMatch = devices.firstOrNull { device ->
            val macMatches = !targetDeviceMac.isNullOrBlank() &&
                device.deviceAddress.equals(targetDeviceMac, ignoreCase = true)
            val nameMatches = !targetDeviceName.isNullOrBlank() &&
                device.deviceName.equals(targetDeviceName, ignoreCase = true)
            macMatches || nameMatches
        }

        val fuzzyMatch = exactMatch ?: devices.firstOrNull { device ->
            val targetName = targetDeviceName?.trim().orEmpty()
            targetName.isNotBlank() && (
                device.deviceName.contains(targetName, ignoreCase = true) ||
                    targetName.contains(device.deviceName, ignoreCase = true)
                )
        }

        val selectedDevice = exactMatch ?: fuzzyMatch
        if (selectedDevice == null) {
            unmatchedPeerPasses += 1
            val peerSummary = devices.joinToString {
                "${it.deviceName} (${it.deviceAddress}, status=${it.status})"
            }
            onStatus(
                "Target glasses not visible yet. Waiting for " +
                    "${targetDeviceName ?: targetDeviceMac ?: "the Rokid peer"}... Seen: $peerSummary",
            )
            return
        }

        targetDeviceFound = true
        clearPeerPoll()
        unmatchedPeerPasses = 0
        val exact = exactMatch != null
        val prefix = if (exact) "Matching" else "Using fuzzy match for"
        onStatus(
            "$prefix Wi-Fi Direct peer: ${selectedDevice.deviceName} " +
                "(status=${selectedDevice.status}, mac=${selectedDevice.deviceAddress})",
        )
        connectToDevice(selectedDevice)
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: WifiP2pDevice) {
        if (finished || isConnecting) {
            return
        }

        val localManager = manager ?: return fail("Wi-Fi Direct manager lost")
        val localChannel = channel ?: return fail("Wi-Fi Direct channel lost")

        isConnecting = true
        val config = buildConnectConfig(device)

        onStatus("Connecting to ${device.deviceName} via Wi-Fi Direct...")
        localManager.connect(localChannel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                connectionRetryCount = 0
                runCatching { localManager.stopPeerDiscovery(localChannel, null) }
                postConnectionProgressStatus("Wi-Fi Direct connect request accepted. Waiting for group formation...")
                scheduleConnectionAttemptTimeout()
                scheduleConnectionInfoPoll(immediate = false)
            }

            override fun onFailure(reason: Int) {
                isConnecting = false
                clearConnectionInfoPoll()
                clearConnectionAttemptTimeout()
                connectionRetryCount += 1
                if (connectionRetryCount <= 10) {
                    onStatus("Retrying Wi-Fi Direct connection... ($connectionRetryCount/10)")
                    mainHandler.postDelayed({ connectToDevice(device) }, 1_000L)
                } else {
                    fail("Wi-Fi Direct connection failed: $reason")
                }
            }
        })
    }

    private fun buildConnectConfig(device: WifiP2pDevice): WifiP2pConfig {
        return runCatching {
            WifiP2pConfig.Builder()
                .setDeviceAddress(MacAddress.fromString(device.deviceAddress))
                .enablePersistentMode(false)
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                .build()
                .apply {
                    setGroupOwnerVersion(WifiP2pConfig.P2P_VERSION_1)
                }
        }.getOrElse {
            WifiP2pConfig().apply {
                deviceAddress = device.deviceAddress
                wps.setup = WpsInfo.PBC
                groupOwnerIntent = 15
            }
        }
    }

    private fun requestConnectionInfo() {
        val localManager = manager ?: return fail("Wi-Fi Direct manager lost")
        val localChannel = channel ?: return fail("Wi-Fi Direct channel lost")
        localManager.requestConnectionInfo(localChannel) { info ->
            handleConnectionInfo(info)
        }
    }

    private fun handleConnectionInfo(info: WifiP2pInfo?) {
        if (finished) {
            return
        }

        if (info == null || !info.groupFormed) {
            if (isConnecting) {
                scheduleConnectionInfoPoll(immediate = false)
            }
            return
        }

        val address = if (info.isGroupOwner) {
            getLocalIpAddress()
        } else {
            info.groupOwnerAddress?.hostAddress
        }

        if (address.isNullOrBlank()) {
            scheduleConnectionInfoPoll(immediate = false, delayMs = 500L)
            return
        }

        finished = true
        isConnecting = false
        clearConnectionInfoPoll()
        clearConnectionAttemptTimeout()
        mainHandler.removeCallbacks(timeoutRunnable)
        val role = if (info.isGroupOwner) "phone hosts group" else "glasses host group"
        onStatus("Wi-Fi Direct connected: $address ($role)")
        onConnected(address)
    }

    @SuppressLint("MissingPermission")
    private fun resetAndRestartDiscovery() {
        if (finished) {
            return
        }

        val localManager = manager
        val localChannel = channel
        if (localManager == null || localChannel == null) {
            fail("Wi-Fi Direct reset failed.")
            return
        }

        isConnecting = false
        targetDeviceFound = false
        clearPeerPoll()
        clearConnectionInfoPoll()
        clearConnectionAttemptTimeout()
        runCatching { localManager.stopPeerDiscovery(localChannel, null) }
        runCatching { localManager.cancelConnect(localChannel, null) }
        localManager.removeGroup(localChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                mainHandler.postDelayed({ startDiscovery(isRetry = true) }, 500L)
            }

            override fun onFailure(reason: Int) {
                mainHandler.postDelayed({ startDiscovery(isRetry = true) }, 500L)
            }
        })
    }

    private fun getLocalIpAddress(): String? {
        data class CandidateAddress(
            val interfaceName: String,
            val hostAddress: String,
            val score: Int,
        )

        return runCatching {
            val candidates = mutableListOf<CandidateAddress>()
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) {
                    continue
                }

                val interfaceName = networkInterface.name.orEmpty()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address.isLoopbackAddress || address !is Inet4Address) {
                        continue
                    }

                    val hostAddress = address.hostAddress ?: continue
                    val score = scoreLocalAddress(interfaceName, hostAddress)
                    candidates += CandidateAddress(
                        interfaceName = interfaceName,
                        hostAddress = hostAddress,
                        score = score,
                    )
                }
            }

            val selected = candidates.maxByOrNull { it.score }
            Log.i(
                tag,
                "Local P2P address candidates: " +
                    candidates.joinToString { "${it.interfaceName}=${it.hostAddress}[${it.score}]" },
            )
            selected?.hostAddress
        }.onFailure { error ->
            Log.w(tag, "Unable to resolve the local Wi-Fi Direct address", error)
        }.getOrNull()
    }

    private fun scoreLocalAddress(interfaceName: String, hostAddress: String): Int {
        var score = 0
        val loweredName = interfaceName.lowercase()
        when {
            loweredName.contains("p2p") -> score += 100
            loweredName.contains("wifi") -> score += 30
            loweredName.startsWith("wlan") -> score += 20
            loweredName.startsWith("rmnet") -> score -= 50
            loweredName.startsWith("ccmni") -> score -= 50
        }

        when {
            hostAddress.startsWith("192.168.49.") -> score += 100
            hostAddress.startsWith("192.168.") -> score += 30
            hostAddress.startsWith("10.") -> score += 15
            hostAddress.startsWith("172.") -> score += 10
        }

        return score
    }

    private fun schedulePeerPoll() {
        if (peerPollScheduled || finished || !isDiscovering || targetDeviceFound) {
            return
        }
        peerPollScheduled = true
        mainHandler.postDelayed(peerPollRunnable, 1_500L)
    }

    private fun scheduleConnectionInfoPoll(immediate: Boolean, delayMs: Long = 1_000L) {
        if (finished || !isConnecting) {
            return
        }
        clearConnectionInfoPoll()
        connectionInfoPoll = Runnable {
            connectionInfoPoll = null
            if (!finished && isConnecting) {
                requestConnectionInfo()
            }
        }.also { runnable ->
            mainHandler.postDelayed(runnable, if (immediate) 0L else delayMs)
        }
    }

    private fun clearConnectionInfoPoll() {
        connectionInfoPoll?.let(mainHandler::removeCallbacks)
        connectionInfoPoll = null
    }

    private fun scheduleConnectionAttemptTimeout() {
        clearConnectionAttemptTimeout()
        connectionAttemptTimeout = Runnable {
            if (!finished && isConnecting) {
                onStatus("Wi-Fi Direct handshake timed out. Restarting discovery...")
                isConnecting = false
                resetAndRestartDiscovery()
            }
        }.also { runnable ->
            mainHandler.postDelayed(runnable, 12_000L)
        }
    }

    private fun clearConnectionAttemptTimeout() {
        connectionAttemptTimeout?.let(mainHandler::removeCallbacks)
        connectionAttemptTimeout = null
    }

    private fun postConnectionProgressStatus(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastConnectionProgressStatusAt < 1_500L) {
            return
        }
        lastConnectionProgressStatusAt = now
        onStatus(message)
    }

    private fun clearPeerPoll() {
        peerPollScheduled = false
        mainHandler.removeCallbacks(peerPollRunnable)
    }

    private fun registerReceiver() {
        if (receiverRegistered) {
            return
        }

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    private fun fail(reason: String) {
        if (finished) {
            return
        }
        Log.w(tag, "Wi-Fi Direct connector failed: $reason")
        finished = true
        clearPeerPoll()
        cleanup()
        onFailure(reason)
    }
}
