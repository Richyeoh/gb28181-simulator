package com.goway.gb28181.sip

// SIP 消息模型与编解码辅助，供平台端和设备端共用。
sealed class SipMessage {
    data class Request(
        val method: String,
        val uri: String,
        val version: String = "SIP/2.0",
        val headers: MutableMap<String, String> = linkedMapOf(),
        val headerLines: MutableMap<String, MutableList<String>> = linkedMapOf(),
        val body: String = ""
    ) : SipMessage()

    data class Response(
        val version: String = "SIP/2.0",
        val statusCode: Int,
        val reason: String,
        val headers: MutableMap<String, String> = linkedMapOf(),
        val headerLines: MutableMap<String, MutableList<String>> = linkedMapOf(),
        val body: String = ""
    ) : SipMessage()
}

fun SipMessage.encode(): String {
    val builder = StringBuilder()
    when (this) {
        is SipMessage.Request -> {
            builder.append("${method.uppercase()} $uri $version\r\n")
            val lines = if (headerLines.isNotEmpty()) {
                headerLines.toMutableMap()
            } else {
                headers.entries.associate { it.key to mutableListOf(it.value) }.toMutableMap()
            }
            lines["Content-Length"] = mutableListOf(body.toByteArray().size.toString())
            lines.forEach { (k, values) -> values.forEach { v -> builder.append("$k: $v\r\n") } }
            builder.append("\r\n")
            builder.append(body)
        }

        is SipMessage.Response -> {
            builder.append("$version $statusCode $reason\r\n")
            val lines = if (headerLines.isNotEmpty()) {
                headerLines.toMutableMap()
            } else {
                headers.entries.associate { it.key to mutableListOf(it.value) }.toMutableMap()
            }
            lines["Content-Length"] = mutableListOf(body.toByteArray().size.toString())
            lines.forEach { (k, values) -> values.forEach { v -> builder.append("$k: $v\r\n") } }
            builder.append("\r\n")
            builder.append(body)
        }
    }
    return builder.toString()
}

// 解析时同时保留合并头与逐行头，便于重传与路由集合处理保持原貌。
fun decodeSipMessage(raw: String): SipMessage {
    val sep = raw.indexOf("\r\n\r\n")
    if (sep < 0) error("Invalid SIP message: header terminator not found")
    val head = raw.substring(0, sep)
    val lines = head.split("\r\n")
    val firstLine = lines.firstOrNull() ?: error("Empty SIP message")
    val headers = linkedMapOf<String, String>()
    val headerLines = linkedMapOf<String, MutableList<String>>()
    var currentHeader: String? = null
    lines.drop(1).forEach { line ->
        if ((line.startsWith(" ") || line.startsWith("\t")) && currentHeader != null) {
            val list = headerLines[currentHeader] ?: mutableListOf()
            if (list.isNotEmpty()) {
                list[list.lastIndex] = list.last() + " " + line.trim()
                headerLines[currentHeader!!] = list
                headers[currentHeader!!] = list.joinToString(", ")
            }
            return@forEach
        }
        val idx = line.indexOf(':')
        if (idx > 0) {
            val key = normalizeHeaderName(line.substring(0, idx).trim())
            val value = line.substring(idx + 1).trim()
            val list = headerLines.getOrPut(key) { mutableListOf() }
            list += value
            headers[key] = list.joinToString(", ")
            currentHeader = key
        }
    }
    val contentLength = headers["Content-Length"]?.toIntOrNull() ?: 0
    if (contentLength < 0) error("Invalid SIP message: negative Content-Length")
    val bodyStart = sep + 4
    val available = raw.length - bodyStart
    if (available < contentLength) {
        error("Invalid SIP message: body shorter than Content-Length")
    }
    val body = raw.substring(bodyStart, bodyStart + contentLength)

    return if (firstLine.uppercase().startsWith("SIP/2.0")) {
        val tokens = firstLine.split(" ", limit = 3)
        SipMessage.Response(
            version = tokens[0],
            statusCode = tokens[1].toInt(),
            reason = tokens.getOrElse(2) { "" },
            headers = headers,
            headerLines = headerLines,
            body = body
        )
    } else {
        val tokens = firstLine.split(" ", limit = 3)
        SipMessage.Request(
            method = tokens[0],
            uri = tokens[1],
            version = tokens.getOrElse(2) { "SIP/2.0" },
            headers = headers,
            headerLines = headerLines,
            body = body
        )
    }
}

// 按逗号拆分 SIP 头多值时，正确处理引号与 <sip:...> 段落。
fun splitSipHeaderValues(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    val parts = mutableListOf<String>()
    val token = StringBuilder()
    var inQuotes = false
    var angleDepth = 0
    raw.forEach { ch ->
        when (ch) {
            '"' -> {
                inQuotes = !inQuotes
                token.append(ch)
            }
            '<' -> {
                angleDepth += 1
                token.append(ch)
            }
            '>' -> {
                if (angleDepth > 0) angleDepth -= 1
                token.append(ch)
            }
            ',' -> {
                if (!inQuotes && angleDepth == 0) {
                    val v = token.toString().trim()
                    if (v.isNotEmpty()) parts += v
                    token.clear()
                } else {
                    token.append(ch)
                }
            }
            else -> token.append(ch)
        }
    }
    val tail = token.toString().trim()
    if (tail.isNotEmpty()) parts += tail
    return parts
}

private fun normalizeHeaderName(name: String): String = when (name.lowercase()) {
    "v" -> "Via"
    "via" -> "Via"
    "f" -> "From"
    "from" -> "From"
    "t" -> "To"
    "to" -> "To"
    "i" -> "Call-ID"
    "call-id" -> "Call-ID"
    "m" -> "Contact"
    "cseq" -> "CSeq"
    "l" -> "Content-Length"
    "contact" -> "Contact"
    "c" -> "Content-Type"
    "content-type" -> "Content-Type"
    "content-length" -> "Content-Length"
    "k" -> "Supported"
    "s" -> "Subject"
    "o" -> "Event"
    "r" -> "Refer-To"
    "max-forwards" -> "Max-Forwards"
    "allow" -> "Allow"
    "supported" -> "Supported"
    "require" -> "Require"
    "rack" -> "RAck"
    "rseq" -> "RSeq"
    "www-authenticate" -> "WWW-Authenticate"
    "authorization" -> "Authorization"
    "route" -> "Route"
    "record-route" -> "Record-Route"
    "user-agent" -> "User-Agent"
    "subject" -> "Subject"
    "event" -> "Event"
    "subscription-state" -> "Subscription-State"
    else -> name
}
