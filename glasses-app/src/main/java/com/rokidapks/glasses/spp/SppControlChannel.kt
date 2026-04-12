package com.rokidapks.glasses.spp

import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

data class CompanionTransferOffer(
    val transportMode: String,
    val hostIp: String? = null,
    val port: Int? = null,
    val apkSize: Long,
    val md5Hex: String,
    val fileName: String,
)

class SppControlChannel(
    socket: BluetoothSocket,
) {
    private val input = DataInputStream(socket.inputStream)
    private val output = DataOutputStream(socket.outputStream)

    suspend fun awaitOffer(): CompanionTransferOffer = withContext(Dispatchers.IO) {
        val payload = readJson()
        if (payload.optString("type") != "offer") {
            throw IOException("Unexpected control message from the phone.")
        }
        CompanionTransferOffer(
            transportMode = payload.optString("transportMode", "wifi_lan"),
            hostIp = payload.optString("hostIp").ifBlank { null },
            port = payload.optInt("port").takeIf { it > 0 },
            apkSize = payload.getLong("apkSize"),
            md5Hex = payload.getString("md5"),
            fileName = payload.optString("fileName", "transfer.apk"),
        )
    }

    suspend fun sendResult(success: Boolean, message: String) = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("type", "result")
            .put("success", success)
            .put("message", message)
        writeJson(payload)
    }

    private fun writeJson(payload: JSONObject) {
        val body = payload.toString().toByteArray(Charsets.UTF_8)
        output.writeInt(body.size)
        output.write(body)
        output.flush()
    }

    private fun readJson(): JSONObject {
        val length = input.readInt()
        if (length <= 0) {
            throw IOException("SPP control payload is empty.")
        }
        val body = ByteArray(length)
        input.readFully(body)
        return JSONObject(String(body, Charsets.UTF_8))
    }
}
