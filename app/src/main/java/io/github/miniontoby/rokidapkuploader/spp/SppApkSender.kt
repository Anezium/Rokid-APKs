package io.github.miniontoby.rokidapkuploader.spp

import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.EOFException
import java.io.File
import java.io.IOException

class SppApkSender(
    private val socket: BluetoothSocket,
    private val onProgress: (sentBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
) {
    companion object {
        private const val TAG = "RokidApksSpp"
    }

    suspend fun sendApk(apkFile: File): Result<TransferStatistics> = withContext(Dispatchers.IO) {
        runCatching {
            if (!socket.isConnected) {
                throw IOException("SPP socket is not connected.")
            }

            val totalBytes = apkFile.length()
            if (totalBytes <= 0L) {
                throw IOException("Selected APK is empty.")
            }

            val totalChunks = SppPacketUtils.getChunkCount(totalBytes)
            val md5 = SppPacketUtils.calculateMd5(apkFile)
            var sentBytes = 0L
            val startedAt = System.currentTimeMillis()
            val output = socket.outputStream.buffered(SppTransferConstants.CHUNK_SIZE)

            Log.d(TAG, "Starting fast SPP send: bytes=$totalBytes chunks=$totalChunks")
            output.write(SppPacketUtils.createStartPacket(totalBytes.toInt(), totalChunks, md5))
            output.flush()

            apkFile.inputStream().buffered(SppTransferConstants.CHUNK_SIZE).use { input ->
                val buffer = ByteArray(SppTransferConstants.CHUNK_SIZE)
                var reportChunk = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    sentBytes += read
                    val currentChunk = (sentBytes / SppTransferConstants.CHUNK_SIZE).toInt()
                    if (sentBytes == totalBytes || currentChunk != reportChunk) {
                        reportChunk = currentChunk
                        if (
                            sentBytes == read.toLong() ||
                            sentBytes == totalBytes ||
                            currentChunk % 8 == 0
                        ) {
                            Log.d(TAG, "Streamed $sentBytes/$totalBytes bytes over SPP.")
                        }
                    }
                    onProgress(sentBytes, totalBytes)
                }
            }

            output.write(SppPacketUtils.createEndPacket(SppTransferConstants.STATUS_SUCCESS))
            output.flush()

            when (waitForTransferResult()) {
                SppTransferConstants.STATUS_SUCCESS -> {
                    Log.d(TAG, "SPP send complete and verified by the glasses.")
                }

                SppTransferConstants.STATUS_MD5_ERROR -> {
                    throw IOException("The glasses reported an APK checksum mismatch.")
                }

                else -> {
                    throw IOException("The glasses rejected the APK after transfer.")
                }
            }

            TransferStatistics(
                totalBytes = totalBytes,
                totalChunks = totalChunks,
                elapsedTimeMs = System.currentTimeMillis() - startedAt,
                retryCount = 0,
            )
        }
    }

    private suspend fun waitForTransferResult(): Byte = withContext(Dispatchers.IO) {
        try {
            withTimeout(SppTransferConstants.RESULT_TIMEOUT_MS) {
                SppPacketUtils.parseResultPacket(
                    readFully(socket.inputStream, SppTransferConstants.RESULT_PACKET_SIZE),
                )
            }
        } catch (error: TimeoutCancellationException) {
            Log.e(TAG, "Timed out waiting for the final transfer result.", error)
            throw IOException("Timed out waiting for the glasses transfer result.", error)
        }
    }

    private fun readFully(input: java.io.InputStream, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read < 0) {
                throw EOFException("Bluetooth stream ended while waiting for $length bytes.")
            }
            offset += read
        }
        return buffer
    }
}
