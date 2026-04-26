package com.goway.gb28181.net

import com.goway.gb28181.SipEndpoint
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

// 面向连接的 SIP 传输，包含断线重连与 CRLF 保活。
class TcpSipTransport(
    private val bind: SipEndpoint,
    private val onReceive: (String, SipEndpoint) -> Unit
) : SipTransport {
    private val running = AtomicBoolean(false)
    private val server = ServerSocket()
    private val connections = ConcurrentHashMap<String, Socket>()

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        server.reuseAddress = true
        server.bind(InetSocketAddress(bind.host, bind.port))
        thread(name = "tcp-sip-accept-${bind.port}", isDaemon = true) {
            while (running.get()) {
                try {
                    val socket = server.accept()
                    socket.tcpNoDelay = true
                    val remote = SipEndpoint(socket.inetAddress.hostAddress, socket.port)
                    connections["${remote.host}:${remote.port}"] = socket
                    startReadLoop(socket, remote)
                } catch (_: Exception) {
                    if (!running.get()) break
                }
            }
        }
        startKeepaliveLoop()
    }

    override fun send(remote: SipEndpoint, content: String) {
        val key = "${remote.host}:${remote.port}"
        val bytes = content.toByteArray()
        val first = runCatching {
            val socket = connections[key] ?: createOutboundConnection(remote)
            val out = BufferedOutputStream(socket.getOutputStream())
            out.write(bytes)
            out.flush()
        }
        if (first.isFailure) {
            runCatching { connections[key]?.close() }
            connections.remove(key)
            // 长连接异常时重连一次，提升演示场景稳定性。
            val socket = createOutboundConnection(remote)
            val out = BufferedOutputStream(socket.getOutputStream())
            out.write(bytes)
            out.flush()
        }
    }

    override fun stop() {
        running.set(false)
        runCatching { server.close() }
        connections.values.forEach { runCatching { it.close() } }
        connections.clear()
    }

    private fun createOutboundConnection(remote: SipEndpoint): Socket {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(remote.host, remote.port), 3000)
        val key = "${remote.host}:${remote.port}"
        connections[key] = socket
        startReadLoop(socket, remote)
        return socket
    }

    private fun startReadLoop(socket: Socket, remote: SipEndpoint) {
        thread(name = "tcp-sip-read-${remote.port}", isDaemon = true) {
            val input = BufferedInputStream(socket.getInputStream())
            val buffer = ByteArray(4096)
            val text = StringBuilder()
            while (running.get() && !socket.isClosed) {
                val n = try {
                    input.read(buffer)
                } catch (_: Exception) {
                    -1
                }
                if (n <= 0) break
                text.append(String(buffer, 0, n))
                while (true) {
                    val message = extractOneSipMessage(text) ?: break
                    onReceive(message, remote)
                }
            }
            runCatching { socket.close() }
            connections.remove("${remote.host}:${remote.port}")
        }
    }

    private fun startKeepaliveLoop() {
        thread(name = "tcp-sip-keepalive-${bind.port}", isDaemon = true) {
            while (running.get()) {
                try {
                    Thread.sleep(15_000)
                } catch (_: Exception) {
                    break
                }
                val heartbeat = "\r\n\r\n".toByteArray()
                val dead = mutableListOf<String>()
                connections.forEach { (key, socket) ->
                    val ok = runCatching {
                        val out = BufferedOutputStream(socket.getOutputStream())
                        out.write(heartbeat)
                        out.flush()
                    }.isSuccess
                    if (!ok) dead += key
                }
                dead.forEach {
                    runCatching { connections[it]?.close() }
                    connections.remove(it)
                }
            }
        }
    }

    private fun extractOneSipMessage(text: StringBuilder): String? {
        val full = text.toString()
        val sep = full.indexOf("\r\n\r\n")
        if (sep < 0) return null
        val head = full.substring(0, sep)
        val contentLength = Regex("""(?im)^Content-Length:\s*(\d+)\s*$""")
            .find(head)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?: 0
        val total = sep + 4 + contentLength
        if (full.length < total) return null
        val one = full.substring(0, total)
        text.delete(0, total)
        return one
    }
}
