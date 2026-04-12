package com.rokidapks

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

class RokidHotspotConnector(
    context: Context,
    private val ssid: String,
    private val password: String,
    private val ipAddress: String,
    private val securityType: Int,
    private val onStatus: (String) -> Unit,
    private val onConnected: (String) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private val tag = "RokidHotspotConnector"
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var activeNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var observeNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var finished = false
    private var connected = false

    private val timeoutRunnable = Runnable {
        fail("Hotspot connection timeout")
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (finished) {
            return
        }

        val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
            ?: return fail("ConnectivityManager unavailable")
        val wifiManager = appContext.getSystemService(WifiManager::class.java)
            ?: return fail("WifiManager unavailable")

        clearCallbacks(connectivityManager)
        mainHandler.removeCallbacks(timeoutRunnable)
        mainHandler.postDelayed(timeoutRunnable, 25_000L)

        onStatus("Preparing the Rokid hotspot connection...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            requestHotspotNetwork(connectivityManager)
        } else {
            connectLegacyWifi(wifiManager, connectivityManager)
        }
    }

    fun cleanup() {
        finished = true
        mainHandler.removeCallbacks(timeoutRunnable)
        val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
        clearCallbacks(connectivityManager)
    }

    @SuppressLint("MissingPermission")
    private fun requestHotspotNetwork(connectivityManager: ConnectivityManager) {
        val specifier = buildWifiSpecifier() ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

        onStatus("Android is joining the Rokid hotspot. Accept the system prompt if it appears.")
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                reportConnected(connectivityManager, network)
            }

            override fun onUnavailable() {
                fail("Android could not join the Rokid hotspot.")
            }

            override fun onLost(network: Network) {
                if (!finished && connected) {
                    fail("The Rokid hotspot connection was lost.")
                }
            }
        }

        activeNetworkCallback = callback
        connectivityManager.requestNetwork(request, callback)
    }

    @SuppressLint("MissingPermission")
    private fun connectLegacyWifi(
        wifiManager: WifiManager,
        connectivityManager: ConnectivityManager,
    ) {
        if (!wifiManager.isWifiEnabled) {
            val enabled = runCatching {
                wifiManager.isWifiEnabled = true
                true
            }.getOrDefault(false)
            if (!enabled && !wifiManager.isWifiEnabled) {
                fail("Wi-Fi is disabled on the phone.")
                return
            }
        }

        val config = buildWifiConfig() ?: return
        val networkId = findExistingNetworkId(wifiManager, config.SSID) ?: wifiManager.addNetwork(config)
        if (networkId == -1) {
            fail("Android could not save the Rokid hotspot network.")
            return
        }

        onStatus("Joining the Rokid hotspot with legacy Wi-Fi APIs...")
        val enabled = wifiManager.enableNetwork(networkId, true)
        val reconnected = wifiManager.reconnect()
        if (!enabled || !reconnected) {
            fail("Android refused the Rokid hotspot connection.")
            return
        }

        observeLegacyWifi(wifiManager, connectivityManager)
    }

    private fun buildWifiSpecifier(): WifiNetworkSpecifier? {
        val builder = WifiNetworkSpecifier.Builder().setSsid(ssid)
        if (password.isBlank() || securityType == 0) {
            return builder.build()
        }

        return try {
            when (securityType) {
                2 -> builder.setWpa3Passphrase(password).build()
                else -> builder.setWpa2Passphrase(password).build()
            }
        } catch (error: IllegalArgumentException) {
            Log.w(tag, "Invalid hotspot credentials", error)
            fail("The hotspot credentials returned by the glasses are invalid.")
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun buildWifiConfig(): WifiConfiguration? {
        val config = WifiConfiguration()
        config.SSID = "\"$ssid\""

        return try {
            when {
                password.isBlank() || securityType == 0 -> {
                    config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    config
                }

                securityType == 2 -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        config.setSecurityParams(WifiConfiguration.SECURITY_TYPE_SAE)
                        config.preSharedKey = "\"$password\""
                        config.hiddenSSID = true
                        config
                    } else {
                        config.preSharedKey = "\"$password\""
                        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                        config
                    }
                }

                else -> {
                    config.preSharedKey = "\"$password\""
                    config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                    config
                }
            }
        } catch (error: IllegalArgumentException) {
            Log.w(tag, "Invalid hotspot credentials", error)
            fail("The hotspot credentials returned by the glasses are invalid.")
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun observeLegacyWifi(
        wifiManager: WifiManager,
        connectivityManager: ConnectivityManager,
    ) {
        val targetSsid = normalizeSsid(ssid)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                val wifiInfo = networkCapabilities.transportInfo as? WifiInfo
                val currentSsid = normalizeSsid(wifiInfo?.ssid)
                if (currentSsid == targetSsid) {
                    reportConnected(connectivityManager, network)
                }
            }

            override fun onLost(network: Network) {
                if (!finished && connected) {
                    fail("The Rokid hotspot connection was lost.")
                }
            }
        }

        observeNetworkCallback = callback
        connectivityManager.registerNetworkCallback(request, callback)

        val currentSsid = normalizeSsid(wifiManager.connectionInfo?.ssid)
        if (currentSsid == targetSsid) {
            val network = connectivityManager.activeNetwork
            if (network != null) {
                reportConnected(connectivityManager, network)
            }
        }
    }

    private fun reportConnected(
        connectivityManager: ConnectivityManager,
        network: Network,
    ) {
        if (finished || connected) {
            return
        }

        connected = true
        mainHandler.removeCallbacks(timeoutRunnable)
        runCatching { connectivityManager.bindProcessToNetwork(network) }
        Log.i(tag, "Hotspot connected: $ssid")
        onStatus("Rokid hotspot connected.")
        mainHandler.post {
            if (!finished) {
                onConnected(ipAddress)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun findExistingNetworkId(
        wifiManager: WifiManager,
        quotedSsid: String?,
    ): Int? {
        if (quotedSsid.isNullOrBlank()) {
            return null
        }
        return runCatching {
            wifiManager.configuredNetworks
                ?.firstOrNull { normalizeSsid(it.SSID) == normalizeSsid(quotedSsid) }
                ?.networkId
        }.getOrNull()
    }

    private fun clearCallbacks(connectivityManager: ConnectivityManager?) {
        activeNetworkCallback?.let { callback ->
            connectivityManager?.let { manager ->
                runCatching { manager.unregisterNetworkCallback(callback) }
            }
        }
        activeNetworkCallback = null

        observeNetworkCallback?.let { callback ->
            connectivityManager?.let { manager ->
                runCatching { manager.unregisterNetworkCallback(callback) }
            }
        }
        observeNetworkCallback = null

        connectivityManager?.let { manager ->
            runCatching { manager.bindProcessToNetwork(null) }
        }
    }

    private fun fail(reason: String) {
        if (finished) {
            return
        }

        finished = true
        mainHandler.removeCallbacks(timeoutRunnable)
        val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
        clearCallbacks(connectivityManager)
        Log.w(tag, "Hotspot connector failed: $reason")
        mainHandler.post {
            onFailure(reason)
        }
    }

    private fun normalizeSsid(value: String?): String {
        if (value.isNullOrBlank()) {
            return ""
        }
        return value.trim().trim('"')
    }
}
