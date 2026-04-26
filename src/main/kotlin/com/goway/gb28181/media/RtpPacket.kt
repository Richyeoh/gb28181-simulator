package com.goway.gb28181.media

import kotlin.random.Random

// 精简 RTP 包模型，供 PS 模拟推流发送使用。
data class RtpPacket(
    val payloadType: Int = 96,
    val marker: Boolean = false,
    val sequenceNumber: Int,
    val timestamp: Long,
    val ssrc: Long,
    val payload: ByteArray
) {
    fun encode(): ByteArray {
        val header = ByteArray(12)
        header[0] = 0x80.toByte() // RTP 版本号 V=2
        header[1] = ((if (marker) 0x80 else 0x00) or (payloadType and 0x7F)).toByte()
        header[2] = (sequenceNumber shr 8).toByte()
        header[3] = sequenceNumber.toByte()
        header[4] = (timestamp shr 24).toByte()
        header[5] = (timestamp shr 16).toByte()
        header[6] = (timestamp shr 8).toByte()
        header[7] = timestamp.toByte()
        header[8] = (ssrc shr 24).toByte()
        header[9] = (ssrc shr 16).toByte()
        header[10] = (ssrc shr 8).toByte()
        header[11] = ssrc.toByte()
        return header + payload
    }
}

fun randomSsrc(): Long = Random.nextLong(1, Int.MAX_VALUE.toLong())
