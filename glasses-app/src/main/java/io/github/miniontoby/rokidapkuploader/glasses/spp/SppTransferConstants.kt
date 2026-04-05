package io.github.miniontoby.rokidapkuploader.glasses.spp

import java.util.UUID

object SppTransferConstants {
    const val SERVICE_NAME = "RokidApksSpp"
    val APP_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")

    const val PACKET_TYPE_START: Byte = 0x01
    const val PACKET_TYPE_DATA: Byte = 0x02
    const val PACKET_TYPE_END: Byte = 0x03
    const val PACKET_TYPE_ACK: Byte = 0x04
    const val PACKET_TYPE_RETRY: Byte = 0x05
    const val PACKET_TYPE_RESULT: Byte = 0x06

    const val CHUNK_SIZE = 60 * 1024
    const val START_PACKET_SIZE = 25
    const val DATA_HEADER_SIZE = 11
    const val END_PACKET_SIZE = 2
    const val ACK_PACKET_SIZE = 6
    const val RETRY_PACKET_SIZE = 5
    const val RESULT_PACKET_SIZE = 2
    const val MD5_SIZE = 16

    const val STATUS_SUCCESS: Byte = 0x00
    const val STATUS_CRC_ERROR: Byte = 0x01
    const val STATUS_MD5_ERROR: Byte = 0x02
    const val STATUS_TIMEOUT: Byte = 0x03
    const val STATUS_ERROR: Byte = 0x7F
}
