package com.goway.gb28181.net

import com.goway.gb28181.SipEndpoint

// 信令传输抽象层（UDP/TCP/TLS）。
interface SipTransport {
    fun start()
    fun send(remote: SipEndpoint, content: String)
    fun stop()
}
