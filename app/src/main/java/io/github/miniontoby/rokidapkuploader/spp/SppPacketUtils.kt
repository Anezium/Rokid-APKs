package com.rokidapks.spp

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32

data class StartPacketData(
    val totalSize: Int,
    val totalChunks: Int,
    val md5: ByteArray,
)

data class DataPacketData(
    val chunkIndex: Int,
    val payload: ByteArray,
    val isValid: Boolean,
)

data class AckPacketData(
    val chunkIndex: Int,
    val status: Byte,
)

data class TransferStatistics(
    val totalBytes: Long,
    val totalChunks: Int,
    val elapsedTimeMs: Long,
    val retryCount: Int,
)

object SppPacketUtils {
    fun createStartPacket(totalSize: Int, totalChunks: Int, md5: ByteArray): ByteArray {
        require(md5.size == SppTransferConstants.MD5_SIZE)
        return ByteBuffer.allocate(SppTransferConstants.START_PACKET_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .put(SppTransferConstants.PACKET_TYPE_START)
            .putInt(totalSize)
            .putInt(totalChunks)
            .put(md5)
            .array()
    }

    fun createDataPacket(chunkIndex: Int, payload: ByteArray): ByteArray {
        val crc = crc32(payload)
        return ByteBuffer.allocate(SppTransferConstants.DATA_HEADER_SIZE + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(SppTransferConstants.PACKET_TYPE_DATA)
            .putShort(payload.size.toShort())
            .putInt(chunkIndex)
            .putInt(crc)
            .put(payload)
            .array()
    }

    fun createEndPacket(status: Byte): ByteArray = byteArrayOf(
        SppTransferConstants.PACKET_TYPE_END,
        status,
    )

    fun createResultPacket(status: Byte): ByteArray = byteArrayOf(
        SppTransferConstants.PACKET_TYPE_RESULT,
        status,
    )

    fun createAckPacket(chunkIndex: Int, status: Byte): ByteArray {
        return ByteBuffer.allocate(SppTransferConstants.ACK_PACKET_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .put(SppTransferConstants.PACKET_TYPE_ACK)
            .putInt(chunkIndex)
            .put(status)
            .array()
    }

    fun createRetryPacket(chunkIndex: Int): ByteArray {
        return ByteBuffer.allocate(SppTransferConstants.RETRY_PACKET_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .put(SppTransferConstants.PACKET_TYPE_RETRY)
            .putInt(chunkIndex)
            .array()
    }

    fun parseStartPacket(packet: ByteArray): StartPacketData {
        require(packet.size == SppTransferConstants.START_PACKET_SIZE)
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        require(buffer.get() == SppTransferConstants.PACKET_TYPE_START)
        val totalSize = buffer.int
        val totalChunks = buffer.int
        val md5 = ByteArray(SppTransferConstants.MD5_SIZE)
        buffer.get(md5)
        return StartPacketData(totalSize, totalChunks, md5)
    }

    fun parseDataPayloadLength(header: ByteArray): Int {
        require(header.size == SppTransferConstants.DATA_HEADER_SIZE)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        require(buffer.get() == SppTransferConstants.PACKET_TYPE_DATA)
        return buffer.short.toInt() and 0xFFFF
    }

    fun parseDataPacket(packet: ByteArray): DataPacketData {
        require(packet.size >= SppTransferConstants.DATA_HEADER_SIZE)
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        require(buffer.get() == SppTransferConstants.PACKET_TYPE_DATA)
        val payloadLength = buffer.short.toInt() and 0xFFFF
        val chunkIndex = buffer.int
        val expectedCrc = buffer.int
        val payload = ByteArray(payloadLength)
        buffer.get(payload)
        return DataPacketData(
            chunkIndex = chunkIndex,
            payload = payload,
            isValid = crc32(payload) == expectedCrc,
        )
    }

    fun parseEndPacket(packet: ByteArray): Byte {
        require(packet.size == SppTransferConstants.END_PACKET_SIZE)
        require(packet[0] == SppTransferConstants.PACKET_TYPE_END)
        return packet[1]
    }

    fun parseResultPacket(packet: ByteArray): Byte {
        require(packet.size == SppTransferConstants.RESULT_PACKET_SIZE)
        require(packet[0] == SppTransferConstants.PACKET_TYPE_RESULT)
        return packet[1]
    }

    fun parseAckPacket(packet: ByteArray): AckPacketData {
        require(packet.size == SppTransferConstants.ACK_PACKET_SIZE)
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        require(buffer.get() == SppTransferConstants.PACKET_TYPE_ACK)
        return AckPacketData(
            chunkIndex = buffer.int,
            status = buffer.get(),
        )
    }

    fun parseRetryPacket(packet: ByteArray): Int {
        require(packet.size == SppTransferConstants.RETRY_PACKET_SIZE)
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        require(buffer.get() == SppTransferConstants.PACKET_TYPE_RETRY)
        return buffer.int
    }

    fun calculateMd5(file: File): ByteArray {
        val digest = MessageDigest.getInstance("MD5")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    fun verifyMd5(file: File, expected: ByteArray): Boolean = calculateMd5(file).contentEquals(expected)

    fun getChunkCount(totalBytes: Long): Int {
        return ((totalBytes + SppTransferConstants.CHUNK_SIZE - 1) / SppTransferConstants.CHUNK_SIZE).toInt()
    }

    private fun crc32(payload: ByteArray): Int {
        val crc = CRC32()
        crc.update(payload)
        return crc.value.toInt()
    }
}
