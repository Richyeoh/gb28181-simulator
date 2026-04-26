package com.goway.gb28181.net

import com.goway.gb28181.SipEndpoint
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

// TLS 传输层：行为与 TCP 类似，同时支持模拟模式与严格证书模式。
class TlsSipTransport(
    private val bind: SipEndpoint,
    private val onReceive: (String, SipEndpoint) -> Unit
) : SipTransport {
    private val insecureMode = System.getenv("SIP_TLS_INSECURE")?.equals("true", ignoreCase = true) ?: true
    private val running = AtomicBoolean(false)
    private val sslContext = createSslContext()
    private val server = sslContext.serverSocketFactory.createServerSocket() as SSLServerSocket
    private val connections = ConcurrentHashMap<String, SSLSocket>()

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        server.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")
        server.reuseAddress = true
        server.bind(InetSocketAddress(bind.host, bind.port))
        thread(name = "tls-sip-accept-${bind.port}", isDaemon = true) {
            while (running.get()) {
                try {
                    val socket = server.accept() as SSLSocket
                    socket.useClientMode = false
                    socket.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")
                    socket.startHandshake()
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

    private fun createOutboundConnection(remote: SipEndpoint): SSLSocket {
        val socket = sslContext.socketFactory.createSocket() as SSLSocket
        socket.useClientMode = true
        socket.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")
        socket.connect(InetSocketAddress(remote.host, remote.port), 3000)
        socket.startHandshake()
        val key = "${remote.host}:${remote.port}"
        connections[key] = socket
        startReadLoop(socket, remote)
        return socket
    }

    private fun startReadLoop(socket: SSLSocket, remote: SipEndpoint) {
        thread(name = "tls-sip-read-${remote.port}", isDaemon = true) {
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
        thread(name = "tls-sip-keepalive-${bind.port}", isDaemon = true) {
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

    private fun createSslContext(): SSLContext {
        return if (insecureMode) {
            createInsecureSslContext()
        } else {
            createStrictSslContext()
        }
    }

    private fun createInsecureSslContext(): SSLContext {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, trustAll, SecureRandom())
        return ctx
    }

    private fun createStrictSslContext(): SSLContext {
        val keyStorePath = System.getenv("SIP_TLS_KEYSTORE_PATH")
            ?: error("SIP_TLS_KEYSTORE_PATH is required when SIP_TLS_INSECURE=false")
        val keyStorePassword = System.getenv("SIP_TLS_KEYSTORE_PASSWORD")
            ?: error("SIP_TLS_KEYSTORE_PASSWORD is required when SIP_TLS_INSECURE=false")
        val trustStorePath = System.getenv("SIP_TLS_TRUSTSTORE_PATH")
        val trustStorePassword = System.getenv("SIP_TLS_TRUSTSTORE_PASSWORD") ?: ""

        val ks = KeyStore.getInstance("JKS")
        FileInputStream(keyStorePath).use { ks.load(it, keyStorePassword.toCharArray()) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, keyStorePassword.toCharArray())

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        if (!trustStorePath.isNullOrBlank()) {
            val ts = KeyStore.getInstance("JKS")
            FileInputStream(trustStorePath).use { ts.load(it, trustStorePassword.toCharArray()) }
            tmf.init(ts)
        } else {
            tmf.init(null as KeyStore?)
        }

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, tmf.trustManagers, SecureRandom())
        return ctx
    }
}
