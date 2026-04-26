package com.goway.gb28181.net

import com.goway.gb28181.SipEndpoint
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

// 无连接数据报传输，用于经典 SIP over UDP 模拟。
class UdpSipTransport(
    private val bind: SipEndpoint,
    private val onReceive: (String, SipEndpoint) -> Unit
) : SipTransport {
    private val socket = DatagramSocket(bind.port, InetAddress.getByName(bind.host))
    private val running = AtomicBoolean(false)

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        thread(name = "udp-sip-${bind.port}", isDaemon = true) {
            val buf = ByteArray(64 * 1024)
            while (running.get()) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length)
                    val remote = SipEndpoint(packet.address.hostAddress, packet.port)
                    onReceive(text, remote)
                } catch (_: Exception) {
                    if (!running.get()) break
                }
            }
        }
    }

    override fun send(remote: SipEndpoint, content: String) {
        val bytes = content.toByteArray()
        val packet = DatagramPacket(
            bytes,
            bytes.size,
            InetAddress.getByName(remote.host),
            remote.port
        )
        socket.send(packet)
    }

    override fun stop() {
        running.set(false)
        socket.close()
    }
}
