package io.github.miniontoby.rokidapkuploader

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.appcompat.app.AppCompatActivity
import io.github.miniontoby.rokidapkuploader.spp.CompanionTransferOffer
import io.github.miniontoby.rokidapkuploader.spp.SppApkSender
import io.github.miniontoby.rokidapkuploader.spp.SppControlChannel
import io.github.miniontoby.rokidapkuploader.spp.SppPacketUtils
import io.github.miniontoby.rokidapkuploader.spp.SppTransferConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class SppSlowUploadSession(
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

        pendingApkFile = apkFile
        onBusyChanged(true)
        postStatus("Preparing the direct SPP upload...")

        sessionJob = scope.launch {
            try {
                val md5Hex = withContext(Dispatchers.IO) {
                    SppPacketUtils.calculateMd5(apkFile).toHexString()
                }

                postStatus("Listening for the glasses companion over Bluetooth...")
                val socket = withContext(Dispatchers.IO) { acceptConnection() }
                clientSocket = socket
                postStatus("Glasses companion connected. Starting the direct SPP upload...")

                SppControlChannel(socket).sendOffer(
                    CompanionTransferOffer(
                        transportMode = "spp_slow",
                        apkSize = apkFile.length(),
                        md5Hex = md5Hex,
                        fileName = apkFile.name,
                    ),
                )

                var lastStep = -1
                val transferStats = SppApkSender(socket) { sentBytes, totalBytes ->
                    val percent = ((sentBytes * 100L) / totalBytes).toInt()
                    val step = percent / 10
                    if (step != lastStep) {
                        lastStep = step
                        postStatus("Sending APK over direct SPP: $percent%")
                    }
                }.sendApk(apkFile).getOrThrow()

                postStatus(
                    "Direct SPP transfer complete in ${transferStats.elapsedTimeMs} ms. Watch the glasses for the install prompt.",
                )
            } catch (error: Exception) {
                postStatus("Direct SPP transfer failed: ${error.message}")
            } finally {
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
        val fileName = queryFileName(uri) ?: "spp-slow-upload.apk"
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
}
