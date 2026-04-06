package com.rokidapks.glasses.spp

import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.File
import java.io.IOException

class SppApkReceiver(
    private val socket: BluetoothSocket,
    private val targetFile: File,
    private val onProgress: (receivedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
) {
    companion object {
        private const val TAG = "RokidApksSpp"
    }

    suspend fun receiveApk(): Result<TransferStatistics> = withContext(Dispatchers.IO) {
        var resultSent = false
        runCatching {
            if (!socket.isConnected) {
                throw IOException("SPP socket is not connected.")
            }

            val input = socket.inputStream.buffered(SppTransferConstants.CHUNK_SIZE)
            val output = socket.outputStream
            val startedAt = System.currentTimeMillis()

            val startPacket = readFully(input, SppTransferConstants.START_PACKET_SIZE)
            val startData = SppPacketUtils.parseStartPacket(startPacket)
            Log.d(
                TAG,
                "Starting fast SPP receive: bytes=${startData.totalSize} chunks=${startData.totalChunks}",
            )

            targetFile.parentFile?.mkdirs()
            if (targetFile.exists()) {
                targetFile.delete()
            }

            val buffer = ByteArray(SppTransferConstants.CHUNK_SIZE)
            var receivedBytes = 0L
            var reportChunk = 0
            var remaining = startData.totalSize.toLong()
            targetFile.outputStream().buffered(SppTransferConstants.CHUNK_SIZE).use { fileOutput ->
                while (remaining > 0L) {
                    val nextRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = input.read(buffer, 0, nextRead)
                    if (read < 0) {
                        throw EOFException(
                            "Bluetooth stream ended before the full APK was received.",
                        )
                    }
                    fileOutput.write(buffer, 0, read)
                    receivedBytes += read
                    remaining -= read
                    val currentChunk = (receivedBytes / SppTransferConstants.CHUNK_SIZE).toInt()
                    if (receivedBytes == startData.totalSize.toLong() || currentChunk != reportChunk) {
                        reportChunk = currentChunk
                        if (
                            receivedBytes == read.toLong() ||
                            receivedBytes == startData.totalSize.toLong() ||
                            currentChunk % 8 == 0
                        ) {
                            Log.d(
                                TAG,
                                "Received $receivedBytes/${startData.totalSize} bytes over SPP.",
                            )
                        }
                    }
                    onProgress(receivedBytes, startData.totalSize.toLong())
                }
                fileOutput.flush()
            }

            val endStatus = SppPacketUtils.parseEndPacket(
                readFully(input, SppTransferConstants.END_PACKET_SIZE),
            )
            if (endStatus != SppTransferConstants.STATUS_SUCCESS) {
                output.write(SppPacketUtils.createResultPacket(endStatus))
                output.flush()
                resultSent = true
                throw IOException("Sender reported end status $endStatus.")
            }

            if (!SppPacketUtils.verifyMd5(targetFile, startData.md5)) {
                output.write(SppPacketUtils.createResultPacket(SppTransferConstants.STATUS_MD5_ERROR))
                output.flush()
                resultSent = true
                throw IOException("APK checksum mismatch after SPP transfer.")
            }

            output.write(SppPacketUtils.createResultPacket(SppTransferConstants.STATUS_SUCCESS))
            output.flush()
            resultSent = true
            Log.d(TAG, "Fast SPP receive complete and checksum verified.")

            TransferStatistics(
                totalBytes = startData.totalSize.toLong(),
                totalChunks = startData.totalChunks,
                elapsedTimeMs = System.currentTimeMillis() - startedAt,
                retryCount = 0,
            )
        }.onFailure {
            Log.e(TAG, "SPP receive failed.", it)
            if (!resultSent) {
                runCatching {
                    socket.outputStream.write(
                        SppPacketUtils.createResultPacket(SppTransferConstants.STATUS_ERROR),
                    )
                    socket.outputStream.flush()
                }
            }
            if (targetFile.exists()) {
                targetFile.delete()
            }
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
