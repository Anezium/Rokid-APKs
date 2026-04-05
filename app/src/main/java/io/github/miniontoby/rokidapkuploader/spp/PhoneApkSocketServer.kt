package io.github.miniontoby.rokidapkuploader.spp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException

class PhoneApkSocketServer(
    private val apkFile: File,
) {
    private var serverSocket: ServerSocket? = null

    suspend fun start(): Int = withContext(Dispatchers.IO) {
        val server = ServerSocket(0).apply {
            reuseAddress = true
            soTimeout = 120_000
        }
        serverSocket = server
        server.localPort
    }

    suspend fun awaitTransfer(
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): TransferStatistics = withContext(Dispatchers.IO) {
        val server = serverSocket ?: throw IOException("Wi-Fi server is not started.")
        val totalBytes = apkFile.length()
        val startedAt = System.currentTimeMillis()
        try {
            server.accept().use { client ->
                client.tcpNoDelay = true
                client.sendBufferSize = SppTransferConstants.CHUNK_SIZE
                apkFile.inputStream().buffered(SppTransferConstants.CHUNK_SIZE).use { input ->
                    client.outputStream.buffered(SppTransferConstants.CHUNK_SIZE).use { output ->
                        val buffer = ByteArray(SppTransferConstants.CHUNK_SIZE)
                        var sentBytes = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) {
                                break
                            }
                            output.write(buffer, 0, read)
                            sentBytes += read
                            onProgress(sentBytes, totalBytes)
                        }
                        output.flush()
                        return@withContext TransferStatistics(
                            totalBytes = totalBytes,
                            totalChunks = SppPacketUtils.getChunkCount(totalBytes),
                            elapsedTimeMs = System.currentTimeMillis() - startedAt,
                            retryCount = 0,
                        )
                    }
                }
            }
        } catch (_: SocketTimeoutException) {
            throw IOException("The glasses never connected to the Wi-Fi Direct socket.")
        } catch (error: SocketException) {
            if (server.isClosed || error.message?.contains("closed", ignoreCase = true) == true) {
                throw CancellationException("The Wi-Fi Direct socket server was closed.")
            }
            throw error
        }
    }

    fun close() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }
}
