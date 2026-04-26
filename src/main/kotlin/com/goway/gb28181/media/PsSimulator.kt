package com.goway.gb28181.media

import java.nio.ByteBuffer

// 生成可识别的模拟 PS 帧，用于信令与媒体链路联调验证。
object PsSimulator {
    /**
     * 构造小体积模拟 PS 负载（带固定前缀）。
     * 仅用于协议流程验证，不代表真实视频编码码流。
     */
    fun buildPsFrame(frameIndex: Int): ByteArray {
        val marker = byteArrayOf(0x00, 0x00, 0x01, 0xBA.toByte()) // PS 打包头起始码
        val body = ByteBuffer.allocate(20)
            .putInt(frameIndex)
            .putLong(System.currentTimeMillis())
            .putLong(0x1122334455667788L)
            .array()
        return marker + body
    }
}
