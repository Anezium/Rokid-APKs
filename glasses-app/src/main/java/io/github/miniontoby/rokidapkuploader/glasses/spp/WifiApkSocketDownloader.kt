package com.rokidapks.glasses.spp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

class WifiApkSocketDownloader {
    suspend fun downloadApk(
        hostIp: String,
        port: Int,
        targetFile: File,
        totalBytes: Long,
        expectedMd5Hex: String,
        onProgress: (receivedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<TransferStatistics> = withContext(Dispatchers.IO) {
        runCatching {
            targetFile.parentFile?.mkdirs()
            if (targetFile.exists()) {
                targetFile.delete()
            }

            val startedAt = System.currentTimeMillis()
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(hostIp, port), 15_000)
                socket.receiveBufferSize = SppTransferConstants.CHUNK_SIZE
                socket.inputStream.buffered(SppTransferConstants.CHUNK_SIZE).use { input ->
                    targetFile.outputStream().buffered(SppTransferConstants.CHUNK_SIZE).use { output ->
                        val buffer = ByteArray(SppTransferConstants.CHUNK_SIZE)
                        var receivedBytes = 0L
                        while (receivedBytes < totalBytes) {
                            val nextRead = minOf(buffer.size.toLong(), totalBytes - receivedBytes).toInt()
                            val read = input.read(buffer, 0, nextRead)
                            if (read < 0) {
                                throw EOFException("Wi-Fi transfer ended before the APK was fully downloaded.")
                            }
                            output.write(buffer, 0, read)
                            receivedBytes += read
                            onProgress(receivedBytes, totalBytes)
                        }
                        output.flush()
                    }
                }
            }

            val actualMd5Hex = SppPacketUtils.calculateMd5(targetFile).toHexString()
            if (!actualMd5Hex.equals(expectedMd5Hex, ignoreCase = true)) {
                throw IllegalStateException("APK checksum mismatch after the Wi-Fi transfer.")
            }

            TransferStatistics(
                totalBytes = totalBytes,
                totalChunks = SppPacketUtils.getChunkCount(totalBytes),
                elapsedTimeMs = System.currentTimeMillis() - startedAt,
                retryCount = 0,
            )
        }.onFailure {
            if (targetFile.exists()) {
                targetFile.delete()
            }
        }
    }

    private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte)
    }
}
