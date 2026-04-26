package com.goway.gb28181.media

import com.goway.gb28181.Logger
import com.goway.gb28181.SipEndpoint
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

// 通过 RTP/UDP 发送模拟 PS 数据，并输出流运行统计。
class SimulatedPsRtpStreamer(
    private val target: SipEndpoint,
    private val frameIntervalMs: Long = 40L
) {
    data class Stats(
        val packets: Long,
        val bytes: Long,
        val uptimeMs: Long
    )

    private val running = AtomicBoolean(false)
    private val socket = DatagramSocket()
    private val ssrc = randomSsrc()
    private val startedAtMs = AtomicLong(0)
    private val packetsSent = AtomicLong(0)
    private val bytesSent = AtomicLong(0)
    private var seq = 0
    private var ts = 0L
    private var frame = 0

    fun start() {
        if (!running.compareAndSet(false, true)) return
        startedAtMs.set(System.currentTimeMillis())
        packetsSent.set(0)
        bytesSent.set(0)
        Logger.log("ps_rtp_stream_start target=${target.host}:${target.port} ssrc=$ssrc")
        thread(name = "ps-rtp-stream-${target.port}", isDaemon = true) {
            while (running.get()) {
                try {
                    sendOneFrame()
                    Thread.sleep(frameIntervalMs)
                } catch (_: Exception) {
                    if (!running.get()) break
                }
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { socket.close() }
        val s = statsSnapshot()
        Logger.log(
            "ps_rtp_stream_stop target=${target.host}:${target.port} ssrc=$ssrc " +
                "packets=${s.packets} bytes=${s.bytes} uptimeMs=${s.uptimeMs}"
        )
    }

    fun statsSnapshot(): Stats {
        val up = (System.currentTimeMillis() - startedAtMs.get()).coerceAtLeast(0)
        return Stats(
            packets = packetsSent.get(),
            bytes = bytesSent.get(),
            uptimeMs = up
        )
    }

    private fun sendOneFrame() {
        val ps = PsSimulator.buildPsFrame(frame++)
        val rtp = RtpPacket(
            payloadType = 96,
            marker = true,
            sequenceNumber = seq++ and 0xFFFF,
            timestamp = ts,
            ssrc = ssrc,
            payload = ps
        ).encode()
        ts += 3600 // 在 90kHz 时钟下约等于 40ms
        val packet = DatagramPacket(rtp, rtp.size, InetAddress.getByName(target.host), target.port)
        socket.send(packet)
        val p = packetsSent.incrementAndGet()
        bytesSent.addAndGet(rtp.size.toLong())
        if (p % 100L == 0L) {
            val s = statsSnapshot()
            Logger.log(
                "ps_rtp_stream_progress target=${target.host}:${target.port} ssrc=$ssrc " +
                    "packets=${s.packets} bytes=${s.bytes} uptimeMs=${s.uptimeMs}"
            )
        }
    }
}
