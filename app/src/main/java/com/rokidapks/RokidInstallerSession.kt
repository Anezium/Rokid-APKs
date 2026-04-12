package com.rokidapks

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.provider.OpenableColumns
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.rokid.cxr.client.controllers.CxrController
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.ApkStatusCallback
import com.rokid.cxr.client.extend.callbacks.BluetoothClientsInfoCallback
import com.rokid.cxr.client.extend.callbacks.BluetoothStatusCallback
import com.rokid.cxr.client.extend.callbacks.GlassInfoResultCallback
import com.rokid.cxr.client.extend.callbacks.WifiHotStatusCallback
import com.rokid.cxr.client.utils.ValueUtil
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class RokidInstallerSession(
    private val activity: AppCompatActivity,
    private val onStatus: (String) -> Unit,
    private val onDevicesChanged: (List<BluetoothDevice>) -> Unit,
    private val onBusyChanged: (Boolean) -> Unit,
) {
    private val tag = "RokidInstallerSession"

    private data class BluetoothAuth(
        val encryptedContent: ByteArray,
        val clientSecretToken: String,
    )

    private data class SavedBluetoothEndpoint(
        val socketUuid: String,
        val macAddress: String,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager =
        activity.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val preferences =
        activity.getSharedPreferences("rokid_connection_cache", Context.MODE_PRIVATE)
    private val discoveredDevices = linkedMapOf<String, BluetoothDevice>()

    private var activeUpload = false
    private var hotspotConnector: RokidHotspotConnector? = null
    private var installTimeout: Runnable? = null
    private var bluetoothConnectTimeout: Runnable? = null
    private var bluetoothControlProbeTimeout: Runnable? = null
    private var wifiHotspotTimeout: Runnable? = null
    private var pendingAuth: BluetoothAuth? = null
    private var currentApkStatusCallback: ApkStatusCallback? = null
    private var pendingApkFile: File? = null
    private var reconnectStarted = false
    private var wifiBootstrapStarted = false
    private var hotspotConnectStarted = false
    private var transferStarted = false
    private var hotspotSsid: String? = null
    private var hotspotPassword: String? = null
    private var hotspotIpAddress: String? = null
    private var hotspotSecurityType: Int? = null
    private var pendingBluetoothActivationMac: String? = null
    private var directReconnectUsingCache = false

    private val wifiCleanupDelayMs = 6_000L
    private val bluetoothClientName = buildBluetoothClientName()

    private val discoveryBluetoothCallback = object : BluetoothStatusCallback {
        override fun onConnectionInfo(
            socketUuid: String?,
            macAddress: String?,
            rokidAccount: String?,
            glassesType: Int,
        ) {
            handleBluetoothConnectionInfo(socketUuid, macAddress)
        }

        override fun onConnected() {
            handleBluetoothReady()
        }

        override fun onInActiveConnected(clientMac: String?, clientName: String?) {
            if (!activeUpload) {
                return
            }
            postStatus("The glasses already know this phone. Activating the saved Bluetooth client...")
            activateInactiveBluetoothLink(clientMac, clientName)
        }

        override fun onDisconnected() {
            if (activeUpload && !wifiBootstrapStarted) {
                postStatus("Bluetooth discovery disconnected.")
            }
        }

        override fun onFailed(errorCode: ValueUtil.CxrBluetoothErrorCode?) {
            fail("Bluetooth discovery failed: $errorCode")
        }
    }

    private val sessionBluetoothCallback = object : BluetoothStatusCallback {
        override fun onConnectionInfo(
            socketUuid: String?,
            macAddress: String?,
            rokidAccount: String?,
            glassesType: Int,
        ) = Unit

        override fun onConnected() {
            handleBluetoothReady()
        }

        override fun onInActiveConnected(clientMac: String?, clientName: String?) {
            if (!activeUpload) {
                return
            }
            activateInactiveBluetoothLink(clientMac, clientName)
        }

        override fun onDisconnected() {
            if (activeUpload) {
                fail("Bluetooth disconnected from the glasses.")
            }
        }

        override fun onFailed(errorCode: ValueUtil.CxrBluetoothErrorCode?) {
            fail("Bluetooth connection failed: $errorCode")
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val key = device.address ?: return
            discoveredDevices[key] = device
            emitDevices()
        }

        override fun onScanFailed(errorCode: Int) {
            postStatus("Bluetooth scan failed: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            postStatus("Bluetooth is unavailable on this phone.")
            return
        }

        stopScan()
        discoveredDevices.clear()
        emitDevices()

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            postStatus("Bluetooth scanner unavailable.")
            return
        }

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid.fromString(RokidSdkAuth.bleServiceUuid))
                .build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(filters, settings, scanCallback)
        postStatus("Scanning for Rokid glasses...")
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        runCatching {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }
    }

    @SuppressLint("MissingPermission")
    fun installApk(device: BluetoothDevice?, apkUri: Uri, serialNumber: String?) {
        if (activeUpload) {
            return
        }

        val apkFile = copyApkToCache(apkUri)
        if (apkFile == null) {
            postStatus("Unable to read the APK from the phone.")
            return
        }

        val auth = resolveBluetoothAuth(serialNumber)
        if (auth == null) {
            pendingApkFile = apkFile
            deletePendingApkFile()
            postStatus(buildMissingAuthMessage(serialNumber))
            return
        }

        pendingApkFile = apkFile
        pendingAuth = auth
        reconnectStarted = false
        wifiBootstrapStarted = false
        hotspotConnectStarted = false
        transferStarted = false
        hotspotSsid = null
        hotspotPassword = null
        hotspotIpAddress = null
        hotspotSecurityType = null
        pendingBluetoothActivationMac = null
        directReconnectUsingCache = false
        activeUpload = true
        onBusyChanged(true)
        stopScan()
        clearInstallTimeout()
        clearBluetoothConnectTimeout()
        clearWifiHotspotTimeout()
        cleanupHotspot()
        currentApkStatusCallback = buildApkStatusCallback()

        val initStatus = if (serialNumber.isNullOrBlank()) {
            "Initializing Bluetooth session with bundled auth..."
        } else {
            "Initializing Bluetooth session with serial auth..."
        }

        if (device == null) {
            val savedEndpoint = loadSavedBluetoothEndpoint()
            if (savedEndpoint == null) {
                fail("No saved Rokid Bluetooth connection was found. Scan the glasses once to cache it.")
                return
            }
            directReconnectUsingCache = true
            postStatus("Using the saved Rokid Bluetooth endpoint...")
            connectBluetooth(savedEndpoint.socketUuid, savedEndpoint.macAddress)
            return
        }

        postStatus(initStatus)
        CxrApi.getInstance().initBluetooth(activity, device, discoveryBluetoothCallback)
    }

    fun hasSavedBluetoothEndpoint(): Boolean = loadSavedBluetoothEndpoint() != null

    fun cleanup() {
        activeUpload = false
        clearInstallTimeout()
        clearBluetoothConnectTimeout()
        clearBluetoothControlProbe()
        clearWifiHotspotTimeout()
        stopScan()
        cleanupHotspot()
        deletePendingApkFile()
        pendingAuth = null
        currentApkStatusCallback = null
        reconnectStarted = false
        wifiBootstrapStarted = false
        hotspotConnectStarted = false
        transferStarted = false
        hotspotSsid = null
        hotspotPassword = null
        hotspotIpAddress = null
        hotspotSecurityType = null
        pendingBluetoothActivationMac = null
        directReconnectUsingCache = false
        runCatching { CxrApi.getInstance().stopUploadApk() }
        runCatching { CxrApi.getInstance().deinitWifiHot() }
        runCatching { CxrApi.getInstance().deinitBluetooth() }
    }

    private fun handleBluetoothConnectionInfo(socketUuid: String?, macAddress: String?) {
        if (socketUuid.isNullOrBlank() || macAddress.isNullOrBlank()) {
            fail("Glasses socket information is missing.")
            return
        }

        saveBluetoothEndpoint(socketUuid, macAddress)
        postStatus(activity.getString(R.string.bluetooth_pair_prompt))
        mainHandler.post {
            if (!activeUpload || reconnectStarted || wifiBootstrapStarted) {
                return@post
            }
            if (CxrApi.getInstance().isBluetoothConnected) {
                handleBluetoothReady()
            } else {
                connectBluetooth(socketUuid, macAddress)
            }
        }
    }

    private fun connectBluetooth(socketUuid: String, macAddress: String) {
        val auth = pendingAuth ?: run {
            fail("Bluetooth auth data unavailable.")
            return
        }

        reconnectStarted = true
        scheduleBluetoothConnectTimeout()
        postStatus("Connecting to the glasses... ${activity.getString(R.string.bluetooth_pair_prompt)}")
        CxrApi.getInstance().connectBluetooth(
            activity,
            socketUuid,
            macAddress,
            bluetoothClientName,
            sessionBluetoothCallback,
            auth.encryptedContent,
            auth.clientSecretToken,
        )
    }

    private fun activateInactiveBluetoothLink(clientMac: String?, clientName: String?) {
        val resolvedClientMac = clientMac.toMeaningfulSdkValue()
        val resolvedClientName = clientName.toMeaningfulSdkValue()

        if (resolvedClientMac != null) {
            requestBluetoothActivation(
                clientMac = resolvedClientMac,
                clientName = resolvedClientName,
            )
            return
        }

        if (forceSdkBluetoothConnectedState()) {
            postStatus("Older Rokid firmware left the SDK in inactive mode. Forcing the control channel as connected...")
            handleBluetoothReady()
            return
        }

        postStatus("Bluetooth link is inactive. Looking up the saved Android client in the Rokid SDK...")
        CxrApi.getInstance().getBluetoothClientList(object : BluetoothClientsInfoCallback {
            override fun onBtClientsInfo(clients: List<ValueUtil.BtClientInfo>) {
                if (!activeUpload) {
                    return
                }

                val preferredNames = buildList {
                    resolvedClientName?.let(::add)
                    add(bluetoothClientName)
                }.map { it.lowercase() }

                val candidate = clients.firstOrNull { client ->
                    val mac = client.mac.toMeaningfulSdkValue()
                    val name = client.customInfo.toMeaningfulSdkValue()?.lowercase()
                    mac != null && name != null && preferredNames.contains(name)
                } ?: clients.firstOrNull { client ->
                    client.mac.toMeaningfulSdkValue() != null
                }

                val candidateMac = candidate?.mac.toMeaningfulSdkValue()
                if (candidateMac == null) {
                    fail("The Rokid SDK did not return an activatable Bluetooth client for this phone.")
                    return
                }

                requestBluetoothActivation(
                    clientMac = candidateMac,
                    clientName = candidate?.customInfo.toMeaningfulSdkValue(),
                )
            }
        })
    }

    private fun requestBluetoothActivation(clientMac: String, clientName: String?) {
        if (clientMac == pendingBluetoothActivationMac) {
            postStatus("Still waiting for the Rokid SDK to activate the Bluetooth link...")
            return
        }

        pendingBluetoothActivationMac = clientMac
        val label = clientName?.takeIf { it.isNotBlank() } ?: bluetoothClientName
        postStatus("Bluetooth link is inactive. Activating phone client $label ($clientMac)...")
        CxrApi.getInstance().activeBluetoothConnect(clientMac)
    }

    private fun handleBluetoothReady() {
        if (!activeUpload) {
            return
        }
        clearBluetoothConnectTimeout()
        clearBluetoothControlProbe()
        if (wifiBootstrapStarted) {
            return
        }
        directReconnectUsingCache = false
        wifiBootstrapStarted = true
        postStatus("Bluetooth connected. Preparing the Rokid hotspot...")
        bootstrapWifiHotspot()
    }

    private fun bootstrapWifiHotspot() {
        cleanupHotspot()
        clearWifiHotspotTimeout()
        runCatching { CxrApi.getInstance().deinitWifiHot() }
        postStatus("Resetting the Rokid hotspot state...")
        mainHandler.postDelayed({
            if (!activeUpload) {
                return@postDelayed
            }
            requestWifiHotspot()
        }, wifiCleanupDelayMs)
    }

    private fun requestWifiHotspot() {
        postStatus("Requesting Rokid hotspot details...")
        scheduleWifiHotspotTimeout()

        val status = CxrApi.getInstance().initWifiHot(object : WifiHotStatusCallback {
            override fun onWifiHotAvailable(
                ssid: String?,
                password: String?,
                ip: String?,
                securityType: Int,
            ) {
                if (!activeUpload) {
                    return
                }

                val resolvedSsid = ssid?.trim().orEmpty()
                val resolvedIp = ip?.trim().orEmpty()
                if (resolvedSsid.isBlank() || resolvedIp.isBlank()) {
                    fail("The glasses did not return valid hotspot details.")
                    return
                }

                hotspotSsid = resolvedSsid
                hotspotPassword = password?.trim().orEmpty()
                hotspotIpAddress = resolvedIp
                hotspotSecurityType = securityType
                clearWifiHotspotTimeout()
                postStatus("Rokid hotspot ready. Joining $resolvedSsid...")
                connectHotspot()
            }
        })

        when (status) {
            ValueUtil.CxrStatus.REQUEST_FAILED ->
                fail("The glasses rejected the hotspot start request.")
            ValueUtil.CxrStatus.REQUEST_WAITING ->
                fail("The glasses are busy. Retry in a few seconds.")
            else -> Unit
        }
    }

    private fun connectHotspot() {
        if (!activeUpload || hotspotConnectStarted || transferStarted) {
            return
        }

        val ssid = hotspotSsid
        val ipAddress = hotspotIpAddress
        val securityType = hotspotSecurityType
        if (ssid.isNullOrBlank() || ipAddress.isNullOrBlank() || securityType == null) {
            fail("The Rokid hotspot details are incomplete.")
            return
        }

        hotspotConnectStarted = true
        cleanupHotspot()
        hotspotConnector = RokidHotspotConnector(
            context = activity,
            ssid = ssid,
            password = hotspotPassword.orEmpty(),
            ipAddress = ipAddress,
            securityType = securityType,
            onStatus = ::postStatus,
            onConnected = ::startSdkUpload,
            onFailure = { reason -> fail("Hotspot failed: $reason") },
        ).also { connector ->
            connector.start()
        }
    }

    private fun startSdkUpload(ipAddress: String) {
        if (transferStarted) {
            return
        }

        val apkFile = pendingApkFile
        if (!activeUpload || apkFile == null) {
            fail("No APK selected.")
            return
        }

        transferStarted = true
        postStatus("Uploading APK to $ipAddress...")
        val callback = currentApkStatusCallback ?: buildApkStatusCallback().also {
            currentApkStatusCallback = it
        }
        val started = CxrApi.getInstance().startUploadApk(apkFile.absolutePath, ipAddress, callback)
        if (!started) {
            transferStarted = false
            fail("The Rokid SDK refused to start the APK upload.")
        }
    }

    private fun buildApkStatusCallback(): ApkStatusCallback {
        return object : ApkStatusCallback {
            override fun onUploadApkSucceed() {
                postStatus("APK uploaded. Waiting for install callback...")
                cleanupHotspot()
                runCatching { CxrApi.getInstance().deinitWifiHot() }
                scheduleInstallTimeout()
            }

            override fun onUploadApkFailed() {
                fail("APK upload failed.")
            }

            override fun onInstallApkSucceed() {
                if (!activeUpload) {
                    return
                }
                clearInstallTimeout()
                postStatus("APK installed successfully on the glasses.")
                finishFlow()
            }

            override fun onInstallApkFailed() {
                if (!activeUpload) {
                    return
                }
                fail("Glasses reported an install failure.")
            }

            override fun onUninstallApkSucceed() = Unit

            override fun onUninstallApkFailed() = Unit

            override fun onOpenAppSucceed() = Unit

            override fun onOpenAppFailed() = Unit

            override fun onStopAppResult(success: Boolean) = Unit

            override fun onGlassAppResume(packageName: String?) = Unit

            override fun onQueryAppResult(packageName: String?, installed: Boolean) = Unit
        }
    }

    private fun scheduleInstallTimeout() {
        clearInstallTimeout()
        installTimeout = Runnable {
            fail("Upload finished but the install callback never arrived.")
        }.also { runnable ->
            mainHandler.postDelayed(runnable, 90_000L)
        }
    }

    private fun scheduleBluetoothConnectTimeout() {
        clearBluetoothConnectTimeout()
        bluetoothConnectTimeout = Runnable {
            fail(activity.getString(R.string.bluetooth_connect_timeout))
        }.also { runnable ->
            mainHandler.postDelayed(runnable, 30_000L)
        }
    }

    private fun scheduleBluetoothControlProbe(delayMs: Long = 2_000L) {
        clearBluetoothControlProbe()
        bluetoothControlProbeTimeout = Runnable {
            probeBluetoothControlChannel()
        }.also { runnable ->
            mainHandler.postDelayed(runnable, delayMs)
        }
    }

    private fun scheduleWifiHotspotTimeout() {
        clearWifiHotspotTimeout()
        wifiHotspotTimeout = Runnable {
            fail("Timed out while waiting for the Rokid hotspot details.")
        }.also { runnable ->
            mainHandler.postDelayed(runnable, 25_000L)
        }
    }

    private fun clearInstallTimeout() {
        installTimeout?.let(mainHandler::removeCallbacks)
        installTimeout = null
    }

    private fun clearBluetoothConnectTimeout() {
        bluetoothConnectTimeout?.let(mainHandler::removeCallbacks)
        bluetoothConnectTimeout = null
    }

    private fun clearBluetoothControlProbe() {
        bluetoothControlProbeTimeout?.let(mainHandler::removeCallbacks)
        bluetoothControlProbeTimeout = null
    }

    private fun clearWifiHotspotTimeout() {
        wifiHotspotTimeout?.let(mainHandler::removeCallbacks)
        wifiHotspotTimeout = null
    }

    private fun finishFlow() {
        activeUpload = false
        cleanupHotspot()
        clearInstallTimeout()
        clearBluetoothConnectTimeout()
        clearBluetoothControlProbe()
        clearWifiHotspotTimeout()
        deletePendingApkFile()
        pendingAuth = null
        currentApkStatusCallback = null
        reconnectStarted = false
        wifiBootstrapStarted = false
        hotspotConnectStarted = false
        transferStarted = false
        hotspotSsid = null
        hotspotPassword = null
        hotspotIpAddress = null
        hotspotSecurityType = null
        pendingBluetoothActivationMac = null
        directReconnectUsingCache = false
        runCatching { CxrApi.getInstance().deinitWifiHot() }
        runCatching { CxrApi.getInstance().deinitBluetooth() }
        onBusyChanged(false)
    }

    private fun fail(message: String) {
        val finalMessage = finalizeFailureMessage(message)
        if (!activeUpload) {
            postStatus(finalMessage)
            return
        }
        activeUpload = false
        cleanupHotspot()
        clearInstallTimeout()
        clearBluetoothConnectTimeout()
        clearBluetoothControlProbe()
        clearWifiHotspotTimeout()
        deletePendingApkFile()
        pendingAuth = null
        currentApkStatusCallback = null
        reconnectStarted = false
        wifiBootstrapStarted = false
        hotspotConnectStarted = false
        transferStarted = false
        hotspotSsid = null
        hotspotPassword = null
        hotspotIpAddress = null
        hotspotSecurityType = null
        pendingBluetoothActivationMac = null
        directReconnectUsingCache = false
        runCatching { CxrApi.getInstance().stopUploadApk() }
        runCatching { CxrApi.getInstance().deinitWifiHot() }
        runCatching { CxrApi.getInstance().deinitBluetooth() }
        onBusyChanged(false)
        postStatus(finalMessage)
    }

    private fun finalizeFailureMessage(message: String): String {
        if (!directReconnectUsingCache || wifiBootstrapStarted) {
            return message
        }
        clearSavedBluetoothEndpoint()
        return "$message Saved Bluetooth endpoint cleared; scan the glasses once to refresh it."
    }

    private fun cleanupHotspot() {
        hotspotConnector?.cleanup()
        hotspotConnector = null
    }

    private fun forceSdkBluetoothConnectedState(): Boolean {
        return runCatching {
            val controller = CxrController.getInstance()
            val bluetoothControllerField = controller.javaClass.getDeclaredField("d").apply {
                isAccessible = true
            }
            val bluetoothController = bluetoothControllerField.get(controller) ?: return false

            bluetoothController.javaClass.getDeclaredField("n").apply {
                isAccessible = true
                setBoolean(bluetoothController, true)
            }
            bluetoothController.javaClass.getDeclaredField("d").apply {
                isAccessible = true
                set(bluetoothController, ValueUtil.CxrStatus.BLUETOOTH_AVAILABLE)
            }
            CxrApi::class.java.getDeclaredField("E").apply {
                isAccessible = true
                setBoolean(CxrApi.getInstance(), true)
            }
            true
        }.getOrDefault(false)
    }

    private fun probeBluetoothControlChannel() {
        if (!activeUpload || wifiBootstrapStarted) {
            return
        }
        if (CxrApi.getInstance().isBluetoothConnected) {
            handleBluetoothReady()
            return
        }

        postStatus("Bluetooth activation is still pending. Probing the Rokid control channel...")
        val requestStatus = CxrApi.getInstance().getGlassInfo(object : GlassInfoResultCallback {
            override fun onGlassInfoResult(
                status: ValueUtil.CxrStatus?,
                glassInfo: com.rokid.cxr.client.extend.infos.GlassInfo?,
            ) {
                if (!activeUpload || wifiBootstrapStarted) {
                    return
                }
                if (status == ValueUtil.CxrStatus.RESPONSE_SUCCEED) {
                    postStatus("The Rokid control channel is responding. Continuing with hotspot setup...")
                    handleBluetoothReady()
                    return
                }

                postStatus("Bluetooth control probe returned $status. Waiting for the SDK to finish activating...")
                scheduleBluetoothControlProbe(delayMs = 3_000L)
            }
        })

        if (requestStatus != ValueUtil.CxrStatus.REQUEST_SUCCEED) {
            postStatus("Bluetooth control probe could not start ($requestStatus). Retrying shortly...")
            scheduleBluetoothControlProbe(delayMs = 3_000L)
        }
    }

    private fun emitDevices() {
        onDevicesChanged(discoveredDevices.values.toList())
    }

    private fun postStatus(status: String) {
        Log.i(tag, status)
        mainHandler.post {
            onStatus(status)
        }
    }

    private fun buildBluetoothClientName(): String {
        val rawName = listOf(Build.MANUFACTURER, Build.MODEL)
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .joinToString(" ")
            .ifBlank { Build.DEVICE?.trim().orEmpty() }
            .ifBlank { "android-phone" }
        return rawName.replace(Regex("[^A-Za-z0-9._ -]"), "_")
    }

    private fun saveBluetoothEndpoint(socketUuid: String, macAddress: String) {
        preferences.edit()
            .putString("socket_uuid", socketUuid)
            .putString("mac_address", macAddress)
            .apply()
    }

    private fun loadSavedBluetoothEndpoint(): SavedBluetoothEndpoint? {
        val socketUuid = preferences.getString("socket_uuid", null)?.trim().orEmpty()
        val macAddress = preferences.getString("mac_address", null)?.trim().orEmpty()
        if (socketUuid.isEmpty() || macAddress.isEmpty()) {
            return null
        }
        return SavedBluetoothEndpoint(
            socketUuid = socketUuid,
            macAddress = macAddress,
        )
    }

    private fun clearSavedBluetoothEndpoint() {
        preferences.edit()
            .remove("socket_uuid")
            .remove("mac_address")
            .apply()
    }

    private fun String?.toMeaningfulSdkValue(): String? {
        val value = this?.trim().orEmpty()
        if (value.isEmpty()) {
            return null
        }
        if (value.equals("unknown", ignoreCase = true)) {
            return null
        }
        return value
    }

    private fun readAuthFile(): ByteArray? {
        val blobName = RokidSdkAuth.authBlobName.takeIf { it.isNotBlank() } ?: return null
        val resourceId = activity.resources.getIdentifier(blobName, "raw", activity.packageName)
        if (resourceId == 0) {
            Log.w(tag, "Rokid auth blob '$blobName' was not found in res/raw.")
            return null
        }

        return activity.resources.openRawResource(resourceId).use { input ->
            input.readBytes()
        }
    }

    private fun resolveBluetoothAuth(serialNumber: String?): BluetoothAuth? {
        val clientSecretToken = RokidSdkAuth.clientSecretToken ?: return null
        if (!serialNumber.isNullOrBlank()) {
            return BluetoothAuth(
                encryptedContent = encryptSerialNumber(
                    serialNumber = serialNumber,
                    clientSecretToken = clientSecretToken,
                ),
                clientSecretToken = clientSecretToken,
            )
        }

        val authBlob = readAuthFile() ?: return null
        return BluetoothAuth(
            encryptedContent = authBlob,
            clientSecretToken = clientSecretToken,
        )
    }

    private fun buildMissingAuthMessage(serialNumber: String?): String {
        if (RokidSdkAuth.clientSecretToken == null) {
            return "Missing Rokid credentials. Add rokid.clientSecret to local.properties before using the upload flow."
        }

        if (!serialNumber.isNullOrBlank()) {
            return "The Rokid client secret is loaded, but the serial auth payload could not be generated."
        }

        val blobName = RokidSdkAuth.authBlobName.takeIf { it.isNotBlank() }
        return if (blobName == null) {
            "No Rokid auth blob is configured. Add rokid.authBlobName to local.properties or enter the glasses serial number in the app."
        } else {
            "Rokid auth blob '$blobName' was not found in app/src/main/res/raw/. Add the .lc file there or enter the glasses serial number in the app."
        }
    }

    private fun encryptSerialNumber(serialNumber: String, clientSecretToken: String): ByteArray {
        val secretBytes = clientSecretToken.toByteArray()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val secretKeySpec = SecretKeySpec(secretBytes, "AES")
        val ivParameterSpec = IvParameterSpec(secretBytes, 0, 16)
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivParameterSpec)
        return cipher.doFinal(serialNumber.toByteArray())
    }

    private fun copyApkToCache(uri: Uri): File? {
        val fileName = queryFileName(uri) ?: "upload.apk"
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(activity.cacheDir, safeName)
        val input = activity.contentResolver.openInputStream(uri) ?: return null
        return try {
            input.use { stream ->
                target.outputStream().use { output ->
                    stream.copyTo(output)
                }
            }
            target
        } catch (_: Exception) {
            null
        }
    }

    private fun queryFileName(uri: Uri): String? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        activity.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    return cursor.getString(index)
                }
            }
        }
        return uri.lastPathSegment
    }

    private fun deletePendingApkFile() {
        pendingApkFile?.delete()
        pendingApkFile = null
    }
}
