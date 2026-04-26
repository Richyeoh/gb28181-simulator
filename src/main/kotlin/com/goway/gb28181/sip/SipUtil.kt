package com.goway.gb28181.sip

import java.security.MessageDigest
import java.util.UUID

// SIP 工具集合：标识生成与 Digest 鉴权计算。
object SipUtil {
    fun branch(): String = "z9hG4bK-${UUID.randomUUID().toString().replace("-", "")}"
    fun tag(): String = UUID.randomUUID().toString().substring(0, 8)
    fun callId(): String = "${UUID.randomUUID()}@simulator"
    fun cseq(seq: Int, method: String): String = "$seq ${method.uppercase()}"

    fun md5(text: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun digestResponse(
        username: String,
        realm: String,
        password: String,
        nonce: String,
        method: String,
        uri: String
    ): String {
        val ha1 = md5("$username:$realm:$password")
        val ha2 = md5("${method.uppercase()}:$uri")
        return md5("$ha1:$nonce:$ha2")
    }
}

// 轻量级 Digest 头解析，满足模拟器互通需求。
fun parseDigestHeader(value: String): Map<String, String> {
    val result = mutableMapOf<String, String>()
    val content = value.removePrefix("Digest").trim()
    content.split(",").forEach { segment ->
        val idx = segment.indexOf('=')
        if (idx > 0) {
            val key = segment.substring(0, idx).trim()
            val rawValue = segment.substring(idx + 1).trim()
            result[key] = rawValue.removePrefix("\"").removeSuffix("\"")
        }
    }
    return result
}
