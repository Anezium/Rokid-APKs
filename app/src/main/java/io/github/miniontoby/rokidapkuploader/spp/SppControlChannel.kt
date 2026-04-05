package io.github.miniontoby.rokidapkuploader.spp

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

data class CompanionTransferResult(
    val success: Boolean,
    val message: String,
)

class SppControlChannel(
    socket: BluetoothSocket,
) {
    private val input = DataInputStream(socket.inputStream)
    private val output = DataOutputStream(socket.outputStream)

    suspend fun sendOffer(offer: CompanionTransferOffer) = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("type", "offer")
            .put("transportMode", offer.transportMode)
            .put("hostIp", offer.hostIp)
            .put("port", offer.port)
            .put("apkSize", offer.apkSize)
            .put("md5", offer.md5Hex)
            .put("fileName", offer.fileName)
        writeJson(payload)
    }

    suspend fun awaitResult(): CompanionTransferResult = withContext(Dispatchers.IO) {
        val payload = readJson()
        if (payload.optString("type") != "result") {
            throw IOException("Unexpected control message from the glasses.")
        }
        CompanionTransferResult(
            success = payload.optBoolean("success"),
            message = payload.optString("message", "No result message."),
        )
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
