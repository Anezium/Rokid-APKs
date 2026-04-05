package io.github.miniontoby.rokidapkuploader.glasses

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import io.github.miniontoby.rokidapkuploader.glasses.spp.SppTransferConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class GlassesBluetoothClient(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val bluetoothAdapter by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private var connectJob: Job? = null
    private var currentSocket: BluetoothSocket? = null

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> {
        if (!hasConnectPermission()) {
            return emptyList()
        }
        return bluetoothAdapter?.bondedDevices?.sortedBy { it.name ?: it.address } ?: emptyList()
    }

    @SuppressLint("MissingPermission")
    fun connect(
        device: BluetoothDevice,
        onConnected: (BluetoothSocket) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        connectJob?.cancel()
        connectJob = scope.launch(Dispatchers.IO) {
            val adapter = bluetoothAdapter
            if (adapter == null) {
                withContext(Dispatchers.Main) {
                    onFailure("Bluetooth adapter unavailable on the glasses.")
                }
                return@launch
            }

            val strategies = listOf<(BluetoothDevice) -> BluetoothSocket>(
                { it.createInsecureRfcommSocketToServiceRecord(SppTransferConstants.APP_UUID) },
                { it.createRfcommSocketToServiceRecord(SppTransferConstants.APP_UUID) },
                {
                    val method = it.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    method.invoke(it, 1) as BluetoothSocket
                },
            )

            var lastError: Exception? = null
            for (strategy in strategies) {
                try {
                    runCatching { adapter.cancelDiscovery() }
                    cleanupSocket()
                    val socket = strategy(device)
                    socket.connect()
                    currentSocket = socket
                    withContext(Dispatchers.Main) {
                        onConnected(socket)
                    }
                    return@launch
                } catch (error: Exception) {
                    lastError = error as? Exception ?: IOException(error.message, error)
                    cleanupSocket()
                }
            }

            withContext(Dispatchers.Main) {
                onFailure(lastError?.message ?: "SPP connection failed.")
            }
        }
    }

    fun cleanup() {
        connectJob?.cancel()
        connectJob = null
        cleanupSocket()
    }

    private fun cleanupSocket() {
        runCatching { currentSocket?.close() }
        currentSocket = null
    }

    private fun hasConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
    }
}
