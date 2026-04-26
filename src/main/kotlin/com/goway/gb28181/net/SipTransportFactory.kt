package com.goway.gb28181.net

import com.goway.gb28181.SipEndpoint
import com.goway.gb28181.SipTransportProtocol

// 工厂统一管理传输实现选择，业务层无需感知具体协议细节。
object SipTransportFactory {
    fun create(
        protocol: SipTransportProtocol,
        bind: SipEndpoint,
        onReceive: (String, SipEndpoint) -> Unit
    ): SipTransport {
        return when (protocol) {
            SipTransportProtocol.UDP -> UdpSipTransport(bind, onReceive)
            SipTransportProtocol.TCP -> TcpSipTransport(bind, onReceive)
            SipTransportProtocol.TLS -> TlsSipTransport(bind, onReceive)
        }
    }
}
