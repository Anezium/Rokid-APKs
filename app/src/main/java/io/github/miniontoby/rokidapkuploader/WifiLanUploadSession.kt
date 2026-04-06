package com.rokidapks

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.appcompat.app.AppCompatActivity
import com.rokidapks.spp.CompanionTransferOffer
import com.rokidapks.spp.PhoneApkSocketServer
import com.rokidapks.spp.SppControlChannel
import com.rokidapks.spp.SppPacketUtils
import com.rokidapks.spp.SppTransferConstants
import com.rokidapks.spp.TransferStatistics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface

class WifiLanUploadSession(
    private val activity: AppCompatActivity,
    private val onStatus: (String) -> Unit,
    private val onBusyChanged: (Boolean) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val bluetoothManager =
        activity.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private var sessionJob: Job? = null
    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    private var pendingApkFile: File? = null

    @SuppressLint("MissingPermission")
    fun sendApk(apkUri: Uri) {
        if (sessionJob != null) {
            return
        }

        val apkFile = copyApkToCache(apkUri)
        if (apkFile == null) {
            postStatus("Unable to read the APK from the phone.")
            return
        }

        val hostIp = resolveWifiLanIpAddress()
        if (hostIp.isNullOrBlank()) {
            apkFile.delete()
            postStatus("Wi-Fi LAN mode requires the phone to be on Wi-Fi or hotspot. No LAN address was found.")
            return
        }

        pendingApkFile = apkFile
        onBusyChanged(true)
        postStatus("Preparing the Wi-Fi LAN transfer...")

        sessionJob = scope.launch {
            var apkServer: PhoneApkSocketServer? = null
            var transferDeferred: Deferred<TransferStatistics>? = null
            try {
                val md5Hex = withContext(Dispatchers.IO) {
                    SppPacketUtils.calculateMd5(apkFile).toHexString()
                }
                apkServer = PhoneApkSocketServer(apkFile)
                val tcpPort = apkServer.start()

                postStatus("Listening for the glasses companion over Bluetooth...")
                val socket = withContext(Dispatchers.IO) { acceptConnection() }
                clientSocket = socket
                postStatus("Glasses companion connected. Handing off to Wi-Fi LAN...")

                val controlChannel = SppControlChannel(socket)
                controlChannel.sendOffer(
                    CompanionTransferOffer(
                        transportMode = "wifi_lan",
                        hostIp = hostIp,
                        port = tcpPort,
                        apkSize = apkFile.length(),
                        md5Hex = md5Hex,
                        fileName = apkFile.name,
                    ),
                )

                postStatus("Wi-Fi LAN is ready. Waiting for the glasses to pull the APK...")

                var lastStep = -1
                transferDeferred = async {
                    apkServer.awaitTransfer { sentBytes, totalBytes ->
                        val percent = ((sentBytes * 100L) / totalBytes).toInt()
                        val step = percent / 10
                        if (step != lastStep) {
                            lastStep = step
                            postStatus("Sending APK over Wi-Fi LAN: $percent%")
                        }
                    }
                }

                val result = withTimeout(90_000L) {
                    controlChannel.awaitResult()
                }

                if (!result.success) {
                    transferDeferred.cancel()
                    throw IOException(result.message)
                }

                val transferStats = transferDeferred.await()
                postStatus(
                    "Wi-Fi LAN transfer complete in ${transferStats.elapsedTimeMs} ms. Watch the glasses for the install prompt.",
                )
            } catch (error: Exception) {
                postStatus("Wi-Fi LAN transfer failed: ${error.message}")
            } finally {
                transferDeferred?.cancel()
                apkServer?.close()
                cleanupSockets()
                deletePendingApkFile()
                onBusyChanged(false)
                sessionJob = null
            }
        }
    }

    fun cleanup() {
        sessionJob?.cancel()
        sessionJob = null
        cleanupSockets()
        deletePendingApkFile()
        scope.cancel()
    }

    @SuppressLint("MissingPermission")
    private fun acceptConnection(): BluetoothSocket {
        val adapter = bluetoothManager?.adapter ?: throw IOException("Bluetooth adapter unavailable.")
        val server = try {
            adapter.listenUsingInsecureRfcommWithServiceRecord(
                SppTransferConstants.SERVICE_NAME,
                SppTransferConstants.APP_UUID,
            )
        } catch (_: Exception) {
            adapter.listenUsingRfcommWithServiceRecord(
                SppTransferConstants.SERVICE_NAME,
                SppTransferConstants.APP_UUID,
            )
        }

        serverSocket = server

        val timeoutJob = scope.launch(Dispatchers.IO) {
            delay(120_000L)
            runCatching { server.close() }
        }

        return try {
            server.accept() ?: throw IOException("The glasses companion did not connect.")
        } finally {
            timeoutJob.cancel()
            runCatching { server.close() }
            if (serverSocket === server) {
                serverSocket = null
            }
        }
    }

    private fun cleanupSockets() {
        runCatching { clientSocket?.close() }
        runCatching { serverSocket?.close() }
        clientSocket = null
        serverSocket = null
    }

    private fun postStatus(message: String) {
        activity.runOnUiThread {
            onStatus(message)
        }
    }

    private fun deletePendingApkFile() {
        pendingApkFile?.let { file ->
            if (file.exists()) {
                runCatching { file.delete() }
            }
        }
        pendingApkFile = null
    }

    private fun copyApkToCache(uri: Uri): File? {
        val fileName = queryFileName(uri) ?: "wifi-lan-upload.apk"
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(activity.cacheDir, safeName)
        val input = activity.contentResolver.openInputStream(uri) ?: return null
        return try {
            input.use { source ->
                target.outputStream().use { sink ->
                    source.copyTo(sink)
                }
            }
            target
        } catch (_: Exception) {
            runCatching { target.delete() }
            null
        }
    }

    private fun queryFileName(uri: Uri): String? {
        val cursor: Cursor? = activity.contentResolver.query(
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
        return uri.lastPathSegment
    }

    private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte)
    }

    private fun resolveWifiLanIpAddress(): String? {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            var bestAddress: String? = null
            var bestScore = Int.MIN_VALUE
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) {
                    continue
                }
                val interfaceName = networkInterface.name.orEmpty()
                if (!isWifiLikeInterface(interfaceName)) {
                    continue
                }
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address !is Inet4Address || address.isLoopbackAddress) {
                        continue
                    }
                    val hostAddress = address.hostAddress ?: continue
                    if (!isPrivateLanAddress(hostAddress)) {
                        continue
                    }
                    val score = scoreInterface(interfaceName, hostAddress)
                    if (score > bestScore) {
                        bestScore = score
                        bestAddress = hostAddress
                    }
                }
            }
            bestAddress
        }.getOrNull()
    }

    private fun isWifiLikeInterface(interfaceName: String): Boolean {
        val lowered = interfaceName.lowercase()
        return lowered == "wlan0" ||
            lowered.startsWith("wlan") ||
            lowered.startsWith("ap") ||
            lowered.contains("wifi") ||
            lowered.contains("softap") ||
            lowered.contains("swlan")
    }

    private fun isPrivateLanAddress(hostAddress: String): Boolean {
        if (hostAddress.startsWith("192.168.") || hostAddress.startsWith("10.")) {
            return true
        }
        val octets = hostAddress.split('.')
        if (octets.size < 2) {
            return false
        }
        val first = octets[0].toIntOrNull() ?: return false
        val second = octets[1].toIntOrNull() ?: return false
        return first == 172 && second in 16..31
    }

    private fun scoreInterface(interfaceName: String, hostAddress: String): Int {
        var score = 0
        val lowered = interfaceName.lowercase()
        when {
            lowered == "wlan0" -> score += 200
            lowered.startsWith("wlan") -> score += 150
            lowered.startsWith("ap") || lowered.contains("softap") -> score += 130
            lowered.contains("wifi") -> score += 120
            lowered.contains("swlan") -> score += 110
        }
        when {
            hostAddress.startsWith("192.168.") -> score += 80
            hostAddress.startsWith("10.") -> score += 60
            hostAddress.startsWith("172.") -> score += 40
        }
        return score
    }
}
