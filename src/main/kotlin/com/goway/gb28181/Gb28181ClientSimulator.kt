package com.goway.gb28181

import com.goway.gb28181.media.SimulatedPsRtpStreamer
import com.goway.gb28181.net.SipTransport
import com.goway.gb28181.net.SipTransportFactory
import com.goway.gb28181.sip.SipMessage
import com.goway.gb28181.sip.SipUtil
import com.goway.gb28181.sip.decodeSipMessage
import com.goway.gb28181.sip.encode
import com.goway.gb28181.sip.splitSipHeaderValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicInteger

// 设备侧模拟器：负责注册心跳、请求应答与模拟 PS/RTP 推流行为。
class Gb28181ClientSimulator(
    private val cfg: DeviceConfig
) {
    private enum class IncomingInviteState {
        PROCEEDING,
        COMPLETED,
        ESTABLISHED,
        TERMINATED
    }

    private data class IncomingInviteTx(
        val request: SipMessage.Request,
        var state: IncomingInviteState = IncomingInviteState.PROCEEDING
    )

    private data class CachedResponse(
        val payload: String,
        val createdAt: Long
    )

    private val seq = AtomicInteger(1)
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val transport: SipTransport = SipTransportFactory.create(cfg.transport, cfg.localSip, ::onPacket)
    private val transportToken = cfg.transport.name
    private val gbVerHeaderValue = if (cfg.version == GbVersion.V2022) "3.0" else "2.0"
    private val clientTransactionCache = linkedMapOf<String, CachedResponse>()
    private val registerExpiresByCseq = linkedMapOf<Int, Int>()

    private var authNonce: String? = null
    private var registered = false
    private var lastInviteCallId: String? = null
    private val activeStreamers = linkedMapOf<String, SimulatedPsRtpStreamer>()
    private var registerExpiresSec: Int = 3600
    private var manualKeepaliveLoopJob: Job? = null
    private var manualKeepaliveIntervalSec: Int? = null
    private val incomingInviteTxByCallId = linkedMapOf<String, IncomingInviteTx>()
    private val heartbeatIntervalMs = vendorHeartbeatIntervalSec(cfg.strategy) * 1000L
    private val registerRefreshMs = vendorRegisterRefreshSec(cfg.strategy) * 1000L
    private val inviteFailureOnceByScene = linkedMapOf(
        "Playback" to 404,
        "Download" to 488
    )
    private var dropTalkbackOnceForTimeout = true

    fun start(autoRegister: Boolean = true, autoBackgroundTasks: Boolean = true) {
        transport.start()
        if (autoRegister) {
            sendRegister()
        }
        if (autoBackgroundTasks) {
            scope.launch {
                while (true) {
                    delay(heartbeatIntervalMs)
                    if (registered) sendKeepalive()
                }
            }
            scope.launch {
                while (true) {
                    delay(registerRefreshMs)
                    if (registered) sendRegister(withAuth = true, expires = registerExpiresSec)
                }
            }
        }
        Logger.log("client_start_done at=${cfg.localSip.host}:${cfg.localSip.port} protocol=${cfg.transport} version=${cfg.version} vendor=${cfg.vendor}")
    }

    fun stop() {
        stopManualKeepaliveLoop()
        if (registered) sendRegister(withAuth = true, expires = 0)
        activeStreamers.values.forEach { it.stop() }
        activeStreamers.clear()
        Thread.sleep(150)
        transport.stop()
    }

    fun statusSummary(): String {
        val establishedIncoming = incomingInviteTxByCallId.values.count { it.state == IncomingInviteState.ESTABLISHED }
        val completedIncoming = incomingInviteTxByCallId.values.count { it.state == IncomingInviteState.COMPLETED }
        val proceedingIncoming = incomingInviteTxByCallId.values.count { it.state == IncomingInviteState.PROCEEDING }
        val packetTotal = activeStreamers.values.sumOf { it.statsSnapshot().packets }
        val byteTotal = activeStreamers.values.sumOf { it.statsSnapshot().bytes }
        return buildString {
            append("client ")
            append("deviceId=${cfg.deviceId} ")
            append("registered=$registered ")
            append("activeStreamers=${activeStreamers.size} ")
            append("streamPackets=$packetTotal ")
            append("streamBytes=$byteTotal ")
            append("keepaliveLoop=${manualKeepaliveIntervalSec ?: 0}s ")
            append("incomingInviteTx=${incomingInviteTxByCallId.size} ")
            append("inviteEstablished=$establishedIncoming ")
            append("inviteProceeding=$proceedingIncoming ")
            append("inviteCompleted=$completedIncoming ")
            append("cachedTransactions=${clientTransactionCache.size}")
        }
    }

    fun runFaultInjectionFlow() {
        scope.launch {
            while (!registered) {
                delay(300)
            }
            delay(700)
            sendMalformedXmlMessage()
            delay(500)
            sendOutOfOrderCseqMessage()
            delay(500)
            sendRegisterWithStaleNonce()
        }
    }

    // 手动注册固定使用正常过期时间，避免在执行过 unregister 后沿用 Expires: 0。
    fun triggerRegister() = sendRegister(expires = 3600)
    fun triggerKeepalive() = sendKeepalive()
    fun triggerDeregister() = sendRegister(withAuth = true, expires = 0)
    fun startManualKeepaliveLoop(intervalSec: Int) {
        val finalSec = intervalSec.coerceAtLeast(1)
        stopManualKeepaliveLoop()
        manualKeepaliveIntervalSec = finalSec
        manualKeepaliveLoopJob = scope.launch {
            while (isActive) {
                delay(finalSec * 1000L)
                if (registered) {
                    sendKeepalive()
                }
            }
        }
        Logger.log("manual_keepalive_loop_start intervalSec=$finalSec")
    }

    fun stopManualKeepaliveLoop() {
        manualKeepaliveLoopJob?.cancel()
        manualKeepaliveLoopJob = null
        if (manualKeepaliveIntervalSec != null) {
            Logger.log("manual_keepalive_loop_stop")
        }
        manualKeepaliveIntervalSec = null
    }

    private fun onPacket(content: String, remote: SipEndpoint) {
        val msg = runCatching { decodeSipMessage(content) }.getOrNull() ?: return
        when (msg) {
            is SipMessage.Response -> handleResponse(msg)
            is SipMessage.Request -> {
                val txKey = buildClientTransactionKey(msg)
                if (!msg.method.equals("ACK", true) && txKey != null) {
                    val cached = clientTransactionCache[txKey]
                    if (cached != null && System.currentTimeMillis() - cached.createdAt < 64_000) {
                        transport.send(remote, cached.payload)
                        Logger.log("sip_tx_retransmit_hit method=${msg.method} txKey=$txKey")
                        return
                    }
                }
                handleRequest(msg, remote)
            }
        }
    }

    private fun handleResponse(resp: SipMessage.Response) {
        val cseq = resp.headers["CSeq"].orEmpty()
        if (resp.statusCode == 401 && cseq.contains("REGISTER", ignoreCase = true)) {
            val auth = resp.headers["WWW-Authenticate"].orEmpty()
            val nonce = Regex("""nonce="([^"]+)"""").find(auth)?.groupValues?.get(1)
            authNonce = nonce
            sendRegister(withAuth = true)
            return
        }

        if (resp.statusCode == 200 && cseq.contains("REGISTER", ignoreCase = true)) {
            val cseqNum = cseq.substringBefore(" ").trim().toIntOrNull()
            val expires = cseqNum?.let { registerExpiresByCseq.remove(it) } ?: registerExpiresSec
            registered = expires > 0
            if (registered) {
                Logger.log("register_client_ok")
            } else {
                Logger.log("unregister_client_ok")
            }
        }
    }

    private fun handleRequest(req: SipMessage.Request, remote: SipEndpoint) {
        when (req.method.uppercase()) {
            "INVITE" -> {
                lastInviteCallId = req.headers["Call-ID"]
                val callId = req.headers["Call-ID"] ?: "unknown"
                incomingInviteTxByCallId[callId] = IncomingInviteTx(req, IncomingInviteState.PROCEEDING)
                val scene = Regex("""\bs=([^\r\n]+)""").find(req.body)?.groupValues?.get(1) ?: "Play"
                if (scene == "Talkback" && dropTalkbackOnceForTimeout) {
                    dropTalkbackOnceForTimeout = false
                    Logger.log("invite_simulate_timeout scene=Talkback")
                    return
                }
                val fail = inviteFailureOnceByScene.remove(scene)
                if (fail != null) {
                    replyError(req, remote, fail, if (fail == 404) "Not Found" else "Not Acceptable Here")
                    incomingInviteTxByCallId[callId]?.state = IncomingInviteState.COMPLETED
                    scheduleIncomingInviteCompletedTimeout(callId)
                } else {
                    reply200(req, remote, buildInviteAnswerSdp(scene))
                    val mediaTarget = parseInviteMediaTarget(req.body)
                    if (mediaTarget != null) {
                        activeStreamers.remove(callId)?.stop()
                        val streamer = SimulatedPsRtpStreamer(mediaTarget)
                        activeStreamers[callId] = streamer
                        streamer.start()
                        Logger.log("media_stream_bind callId=$callId target=${mediaTarget.host}:${mediaTarget.port}")
                    }
                    incomingInviteTxByCallId[callId]?.state = IncomingInviteState.ESTABLISHED
                }
            }

            "CANCEL" -> {
                val cancelCallId = req.headers["Call-ID"] ?: ""
                val tx = incomingInviteTxByCallId[cancelCallId]
                if (tx != null && tx.state == IncomingInviteState.PROCEEDING) {
                    reply200(req, remote)
                    replyError(tx.request, remote, 487, "Request Terminated")
                    tx.state = IncomingInviteState.COMPLETED
                    scheduleIncomingInviteCompletedTimeout(cancelCallId)
                    Logger.log("cancel_request_handled_487 callId=$cancelCallId")
                } else {
                    replyError(req, remote, 481, "Call/Transaction Does Not Exist")
                }
            }

            "ACK" -> {
                val ackCallId = req.headers["Call-ID"] ?: "unknown"
                incomingInviteTxByCallId[ackCallId]?.let { tx ->
                    if (tx.state == IncomingInviteState.COMPLETED) {
                        tx.state = IncomingInviteState.TERMINATED
                        incomingInviteTxByCallId.remove(ackCallId)
                    }
                }
                Logger.log("ack_request_recv callId=$ackCallId")
            }

            "MESSAGE" -> {
                val cmdType = Regex("""<CmdType>(.*?)</CmdType>""").find(req.body)?.groupValues?.get(1).orEmpty()
                reply200(req, remote)
                when (cmdType) {
                    "Catalog" -> sendCatalogResult(req, remote)
                    "DeviceInfo", "DeviceStatus", "RecordInfo", "Alarm", "ConfigDownload", "ConfigUpload" -> sendQueryResponse(req, remote, cmdType)
                    "DeviceControl", "PTZ" -> {
                        logControlDetails(req.body)
                        sendControlResponse(req, remote, cmdType)
                    }
                }
            }

            "SUBSCRIBE" -> {
                reply200(req, remote)
                sendNotifyForSubscription(req, remote)
            }

            "OPTIONS" -> replyOptions(req, remote)
            "PRACK" -> {
                val callId = req.headers["Call-ID"] ?: ""
                val tx = incomingInviteTxByCallId[callId]
                if (tx == null || tx.state == IncomingInviteState.TERMINATED) {
                    replyError(req, remote, 481, "Call/Transaction Does Not Exist")
                } else {
                    reply200(req, remote)
                    Logger.log("prack_request_recv rack=${req.headers["RAck"].orEmpty()}")
                }
            }

            "NOTIFY" -> {
                reply200(req, remote)
                val event = req.headers["Event"] ?: "unknown"
                val subState = req.headers["Subscription-State"].orEmpty()
                Logger.log("notify_request_recv event=$event state=$subState")
            }

            "BYE" -> {
                val callId = req.headers["Call-ID"] ?: ""
                activeStreamers.remove(callId)?.stop()
                incomingInviteTxByCallId[callId]?.state = IncomingInviteState.TERMINATED
                incomingInviteTxByCallId.remove(callId)
                reply200(req, remote)
            }
            else -> reply200(req, remote)
        }
    }

    private fun sendRegister(withAuth: Boolean = false, expires: Int = registerExpiresSec) {
        registerExpiresSec = expires.coerceAtLeast(0)
        val cseqNum = seq.getAndIncrement()
        val uri = cfg.sipUri(cfg.serverSip.host)
        val headers = linkedMapOf(
            "Via" to "SIP/2.0/$transportToken ${cfg.localSip.host}:${cfg.localSip.port};rport=${cfg.localSip.port};branch=${SipUtil.branch()}",
            "From" to "<sip:${cfg.deviceId}@${cfg.domainId}>;tag=${SipUtil.tag()}",
            "To" to "<sip:${cfg.deviceId}@${cfg.domainId}>",
            "Call-ID" to SipUtil.callId(),
            "CSeq" to SipUtil.cseq(cseqNum, "REGISTER"),
            "Contact" to "<sip:${cfg.deviceId}@${cfg.localSip.host}:${cfg.localSip.port}>${vendorRegisterContactSuffix(cfg.strategy)}",
            "Expires" to expires.toString(),
            "Max-Forwards" to "70",
            "User-Agent" to cfg.userAgent(),
            "X-GB-Ver" to gbVerHeaderValue
        )
        registerExpiresByCseq[cseqNum] = expires
        if (registerExpiresByCseq.size > 256) {
            val first = registerExpiresByCseq.entries.firstOrNull()?.key
            if (first != null) registerExpiresByCseq.remove(first)
        }

        if (withAuth) {
            val nonce = authNonce ?: ""
            val response = SipUtil.digestResponse(
                username = cfg.deviceId,
                realm = cfg.domainId,
                password = cfg.password,
                nonce = nonce,
                method = "REGISTER",
                uri = uri
            )
            headers["Authorization"] =
                "Digest username=\"${cfg.deviceId}\", realm=\"${cfg.domainId}\", nonce=\"$nonce\", uri=\"$uri\", response=\"$response\""
        }

        val req = SipMessage.Request(
            method = "REGISTER",
            uri = uri,
            headers = headers
        )
        transport.send(cfg.serverSip, req.encode())
    }

    private fun sendKeepalive() {
        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"GB2312\"?>\r\n")
            append("<Notify>\r\n")
            append("<CmdType>Keepalive</CmdType>\r\n")
            append("<SN>${System.currentTimeMillis() % 100000}</SN>\r\n")
            append("<DeviceID>${cfg.deviceId}</DeviceID>\r\n")
            append("<Status>OK</Status>\r\n")
            if (cfg.version == GbVersion.V2022) {
                append("<Info><Code>0</Code><Reason>normal</Reason></Info>\r\n")
            }
            append("</Notify>")
        }

        val req = SipMessage.Request(
            method = "MESSAGE",
            uri = cfg.sipUri(cfg.serverSip.host),
            headers = defaultHeaders("MESSAGE").apply {
                put("Content-Type", "Application/MANSCDP+xml")
            },
            body = xml
        )
        transport.send(cfg.serverSip, req.encode())
    }

    private fun sendMalformedXmlMessage() {
        val req = SipMessage.Request(
            method = "MESSAGE",
            uri = cfg.sipUri(cfg.serverSip.host),
            headers = defaultHeaders("MESSAGE").apply {
                put("Content-Type", "Application/MANSCDP+xml")
            },
            body = "MALFORMED_XML_BODY"
        )
        transport.send(cfg.serverSip, req.encode())
    }

    private fun sendOutOfOrderCseqMessage() {
        val headers = defaultHeaders("MESSAGE").apply {
            put("CSeq", "1 MESSAGE")
            put("Content-Type", "Application/MANSCDP+xml")
        }
        val body = """
            <?xml version="1.0" encoding="GB2312"?>
            <Query>
            <CmdType>DeviceInfo</CmdType>
            <SN>1</SN>
            <DeviceID>${cfg.deviceId}</DeviceID>
            </Query>
        """.trimIndent().replace("\n", "\r\n")
        val req = SipMessage.Request(
            method = "MESSAGE",
            uri = cfg.sipUri(cfg.serverSip.host),
            headers = headers,
            body = body
        )
        transport.send(cfg.serverSip, req.encode())
    }

    private fun sendRegisterWithStaleNonce() {
        val staleNonce = "stale-${System.currentTimeMillis()}"
        val uri = cfg.sipUri(cfg.serverSip.host)
        val staleCseq = seq.getAndIncrement()
        val response = SipUtil.digestResponse(
            username = cfg.deviceId,
            realm = cfg.domainId,
            password = cfg.password,
            nonce = staleNonce,
            method = "REGISTER",
            uri = uri
        )
        val req = SipMessage.Request(
            method = "REGISTER",
            uri = uri,
            headers = linkedMapOf(
                "Via" to "SIP/2.0/$transportToken ${cfg.localSip.host}:${cfg.localSip.port};rport=${cfg.localSip.port};branch=${SipUtil.branch()}",
                "From" to "<sip:${cfg.deviceId}@${cfg.domainId}>;tag=${SipUtil.tag()}",
                "To" to "<sip:${cfg.deviceId}@${cfg.domainId}>",
                "Call-ID" to SipUtil.callId(),
                "CSeq" to "$staleCseq REGISTER",
                "Contact" to "<sip:${cfg.deviceId}@${cfg.localSip.host}:${cfg.localSip.port}>",
                "Expires" to "3600",
                "X-GB-Ver" to gbVerHeaderValue,
                "Authorization" to "Digest username=\"${cfg.deviceId}\", realm=\"${cfg.domainId}\", nonce=\"$staleNonce\", uri=\"$uri\", response=\"$response\""
            )
        )
        transport.send(cfg.serverSip, req.encode())
    }

    private fun sendCatalogResult(origin: SipMessage.Request, remote: SipEndpoint) {
        val sn = Regex("""<SN>(.*?)</SN>""").find(origin.body)?.groupValues?.get(1) ?: "1"
        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"GB2312\"?>\r\n")
            append("<Response>\r\n")
            append("<CmdType>Catalog</CmdType>\r\n")
            append("<SN>$sn</SN>\r\n")
            append("<DeviceID>${cfg.deviceId}</DeviceID>\r\n")
            append("<SumNum>1</SumNum>\r\n")
            append("<DeviceList Num=\"1\">\r\n")
            append("<Item>\r\n")
            append("<DeviceID>${cfg.deviceId}01</DeviceID>\r\n")
            append("<Name>Cam-01</Name>\r\n")
            append("<Manufacturer>${vendorManufacturer(cfg.strategy)}</Manufacturer>\r\n")
            append("<Model>${vendorModel(cfg.strategy, "IPC")}</Model>\r\n")
            append("<Status>ON</Status>\r\n")
            append("<${cfg.strategy.catalogExtraFieldName}>${cfg.strategy.catalogExtraFieldValue}</${cfg.strategy.catalogExtraFieldName}>\r\n")
            append("</Item>\r\n")
            append("</DeviceList>\r\n")
            append("</Response>")
        }
        val req = SipMessage.Request(
            method = "MESSAGE",
            uri = cfg.sipUri(cfg.serverSip.host),
            headers = defaultHeaders("MESSAGE").apply {
                put("To", origin.headers["From"].orEmpty())
                put("Content-Type", "Application/MANSCDP+xml")
            },
            body = xml
        )
        transport.send(remote, req.encode())
    }

    private fun sendQueryResponse(origin: SipMessage.Request, remote: SipEndpoint, cmdType: String) {
        val sn = Regex("""<SN>(.*?)</SN>""").find(origin.body)?.groupValues?.get(1) ?: "1"
        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"GB2312\"?>\r\n")
            append("<Response>\r\n")
            append("<CmdType>$cmdType</CmdType>\r\n")
            append("<SN>$sn</SN>\r\n")
            append("<DeviceID>${cfg.deviceId}</DeviceID>\r\n")
            when (cmdType) {
                "DeviceInfo" -> {
                    append("<DeviceName>SimIPC</DeviceName>\r\n")
                    append("<Manufacturer>${vendorManufacturer(cfg.strategy)}</Manufacturer>\r\n")
                    append("<Model>${vendorModel(cfg.strategy, "IPC")}</Model>\r\n")
                    append("<Firmware>2.0.0</Firmware>\r\n")
                }

                "DeviceStatus" -> {
                    append("<Result>OK</Result>\r\n")
                    append("<Online>ONLINE</Online>\r\n")
                    append("<Status>OK</Status>\r\n")
                    append("<Encode>ON</Encode>\r\n")
                    append("<Record>ON</Record>\r\n")
                    if (cfg.vendor == VendorProfile.DAHUA) append("<DeviceTime>2026-04-26T02:20:00</DeviceTime>\r\n")
                }

                "RecordInfo" -> {
                    append("<Name>PlaybackList</Name>\r\n")
                    append("<SumNum>1</SumNum>\r\n")
                    append("<RecordList Num=\"1\">\r\n")
                    append("<Item>\r\n")
                    append("<DeviceID>${cfg.deviceId}01</DeviceID>\r\n")
                    append("<Name>rec-0001</Name>\r\n")
                    append("<StartTime>2026-04-25T00:00:00</StartTime>\r\n")
                    append("<EndTime>2026-04-25T00:20:00</EndTime>\r\n")
                    append("<Secrecy>0</Secrecy>\r\n")
                    append("<Type>all</Type>\r\n")
                    append("<DownloadSpeed>${vendorPlaybackSpeed(cfg.strategy)}</DownloadSpeed>\r\n")
                    append("</Item>\r\n")
                    append("</RecordList>\r\n")
                }

                "Alarm" -> {
                    append("<AlarmPriority>1</AlarmPriority>\r\n")
                    append("<AlarmMethod>4</AlarmMethod>\r\n")
                    append("<AlarmDescription>simulated device alarm list</AlarmDescription>\r\n")
                }
                "ConfigDownload" -> {
                    append("<Result>OK</Result>\r\n")
                    append("<Info>Device returns config items for platform download</Info>\r\n")
                }
                "ConfigUpload" -> {
                    append("<Result>OK</Result>\r\n")
                    append("<Info>Device config upload accepted</Info>\r\n")
                }
            }
            append("</Response>")
        }
        val req = SipMessage.Request(
            method = "MESSAGE",
            uri = cfg.sipUri(cfg.serverSip.host),
            headers = defaultHeaders("MESSAGE").apply {
                put("To", origin.headers["From"].orEmpty())
                put("Content-Type", "Application/MANSCDP+xml")
            },
            body = xml
        )
        transport.send(remote, req.encode())
    }

    private fun sendNotifyForSubscription(origin: SipMessage.Request, remote: SipEndpoint) {
        val event = origin.headers["Event"] ?: "Alarm"
        val expires = origin.headers["Expires"] ?: "3600"
        val routeSet = extractHeaderValues(origin, "Record-Route") + extractHeaderValues(origin, "Route")
        val toTarget = origin.headers["From"]?.substringAfter("<")?.substringBefore(">") ?: cfg.sipUri(cfg.serverSip.host)
        val routing = buildRoutingTarget(routeSet, toTarget)
        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"GB2312\"?>\r\n")
            append("<Notify>\r\n")
            append("<CmdType>${if (event.equals("MobilePosition", true)) "MobilePosition" else "Alarm"}</CmdType>\r\n")
            append("<SN>${System.currentTimeMillis() % 100000}</SN>\r\n")
            append("<DeviceID>${cfg.deviceId}</DeviceID>\r\n")
            if (event.equals("MobilePosition", true)) {
                append("<Time>2026-04-26T01:30:00</Time>\r\n")
                append("<Longitude>121.473701</Longitude>\r\n")
                append("<Latitude>31.230416</Latitude>\r\n")
                append("<Speed>12.3</Speed>\r\n")
                append("<Direction>76.0</Direction>\r\n")
                append("<Altitude>21.0</Altitude>\r\n")
            } else {
                append("<AlarmPriority>1</AlarmPriority>\r\n")
                append("<AlarmMethod>4</AlarmMethod>\r\n")
                append("<AlarmDescription>subscription notify event</AlarmDescription>\r\n")
            }
            append("</Notify>")
        }
        val req = SipMessage.Request(
            method = "NOTIFY",
            uri = routing.requestUri,
            headers = defaultHeaders("NOTIFY").apply {
                put("To", origin.headers["From"].orEmpty())
                put("Event", event)
                put("Subscription-State", "active;expires=$expires")
                put("Content-Type", "Application/MANSCDP+xml")
            },
            headerLines = linkedMapOf<String, MutableList<String>>().apply {
                if (routing.routeHeaders.isNotEmpty()) this["Route"] = routing.routeHeaders.toMutableList()
            },
            body = xml
        )
        if (routing.routeHeaders.isNotEmpty()) {
            req.headers["Route"] = routing.routeHeaders.joinToString(", ")
        }
        transport.send(remote, req.encode())
    }

    private fun sendControlResponse(origin: SipMessage.Request, remote: SipEndpoint, cmdType: String) {
        val sn = Regex("""<SN>(.*?)</SN>""").find(origin.body)?.groupValues?.get(1) ?: "1"
        val resultXml = buildString {
            append("<?xml version=\"1.0\" encoding=\"GB2312\"?>\r\n")
            append("<Response>\r\n")
            append("<CmdType>$cmdType</CmdType>\r\n")
            append("<SN>$sn</SN>\r\n")
            append("<DeviceID>${cfg.deviceId}</DeviceID>\r\n")
            append("<Result>OK</Result>\r\n")
            append("</Response>")
        }
        val req = SipMessage.Request(
            method = "MESSAGE",
            uri = cfg.sipUri(cfg.serverSip.host),
            headers = defaultHeaders("MESSAGE").apply {
                put("To", origin.headers["From"].orEmpty())
                put("Content-Type", "Application/MANSCDP+xml")
            },
            body = resultXml
        )
        transport.send(remote, req.encode())
    }

    private fun buildInviteAnswerSdp(scene: String): String = buildString {
        append("v=0\r\n")
        append("o=${cfg.deviceId} 0 0 IN IP4 ${cfg.localSip.host}\r\n")
        append("s=$scene\r\n")
        append("c=IN IP4 ${cfg.localSip.host}\r\n")
        append("t=0 0\r\n")
        append("m=video 30000 RTP/AVP 96\r\n")
        append(if (scene == "Talkback") "a=sendrecv\r\n" else "a=sendonly\r\n")
        append("a=rtpmap:96 PS/90000\r\n")
        append("${cfg.strategy.sdpExtraLine}\r\n")
        if (scene == "Playback" || scene == "Download") {
            append("a=range:npt=0-\r\n")
        }
        if (cfg.version == GbVersion.V2022) {
            append("f=v/2/4///a/1/8/1\r\n")
            append("a=setup:passive\r\n")
            append("a=connection:new\r\n")
        }
    }

    private fun logControlDetails(xml: String) {
        val hasHome = xml.contains("<HomePosition>")
        val hasPtz = xml.contains("<PTZCmd>")
        if (hasHome || hasPtz) {
            Logger.log("control_request_detail homePosition=$hasHome ptz=$hasPtz")
        }
    }

    private fun defaultHeaders(method: String): LinkedHashMap<String, String> = linkedMapOf(
        "Via" to "SIP/2.0/$transportToken ${cfg.localSip.host}:${cfg.localSip.port};rport=${cfg.localSip.port};branch=${SipUtil.branch()}",
        "From" to "<sip:${cfg.deviceId}@${cfg.domainId}>;tag=${SipUtil.tag()}",
        "To" to "<sip:${cfg.serverSip.host}@${cfg.domainId}>",
        "Call-ID" to SipUtil.callId(),
        "CSeq" to SipUtil.cseq(seq.getAndIncrement(), method),
        "Max-Forwards" to "70",
        "X-GB-Ver" to gbVerHeaderValue
    )

    private fun reply200(req: SipMessage.Request, remote: SipEndpoint, body: String = "") {
        val viaValues = extractViaValues(req)
        val headers = linkedMapOf(
            "Via" to viaValues.joinToString(", "),
            "From" to req.headers["From"].orEmpty(),
            "To" to req.headers["To"].orEmpty(),
            "Call-ID" to req.headers["Call-ID"].orEmpty(),
            "CSeq" to req.headers["CSeq"].orEmpty(),
            "X-GB-Ver" to gbVerHeaderValue
        )
        val headerLines = linkedMapOf<String, MutableList<String>>()
        if (viaValues.isNotEmpty()) headerLines["Via"] = viaValues.toMutableList()
        headerLines["From"] = mutableListOf(req.headers["From"].orEmpty())
        headerLines["To"] = mutableListOf(req.headers["To"].orEmpty())
        headerLines["Call-ID"] = mutableListOf(req.headers["Call-ID"].orEmpty())
        headerLines["CSeq"] = mutableListOf(req.headers["CSeq"].orEmpty())
        headerLines["X-GB-Ver"] = mutableListOf(gbVerHeaderValue)
        if (body.isNotBlank()) headers["Content-Type"] = "application/sdp"
        if (body.isNotBlank()) headerLines["Content-Type"] = mutableListOf("application/sdp")
        val resp = SipMessage.Response(
            statusCode = 200,
            reason = "OK",
            headers = headers,
            headerLines = headerLines,
            body = body
        )
        sendAndCacheResponse(req, remote, resp)
    }

    private fun replyError(req: SipMessage.Request, remote: SipEndpoint, code: Int, reason: String) {
        val viaValues = extractViaValues(req)
        val headers = linkedMapOf(
            "Via" to viaValues.joinToString(", "),
            "From" to req.headers["From"].orEmpty(),
            "To" to req.headers["To"].orEmpty(),
            "Call-ID" to req.headers["Call-ID"].orEmpty(),
            "CSeq" to req.headers["CSeq"].orEmpty(),
            "X-GB-Ver" to gbVerHeaderValue
        )
        val headerLines = linkedMapOf<String, MutableList<String>>()
        if (viaValues.isNotEmpty()) headerLines["Via"] = viaValues.toMutableList()
        headerLines["From"] = mutableListOf(req.headers["From"].orEmpty())
        headerLines["To"] = mutableListOf(req.headers["To"].orEmpty())
        headerLines["Call-ID"] = mutableListOf(req.headers["Call-ID"].orEmpty())
        headerLines["CSeq"] = mutableListOf(req.headers["CSeq"].orEmpty())
        headerLines["X-GB-Ver"] = mutableListOf(gbVerHeaderValue)
        val resp = SipMessage.Response(
            statusCode = code,
            reason = reason,
            headers = headers,
            headerLines = headerLines
        )
        sendAndCacheResponse(req, remote, resp)
    }

    private fun replyOptions(req: SipMessage.Request, remote: SipEndpoint) {
        val viaValues = extractViaValues(req)
        val headers = linkedMapOf(
            "Via" to viaValues.joinToString(", "),
            "From" to req.headers["From"].orEmpty(),
            "To" to req.headers["To"].orEmpty(),
            "Call-ID" to req.headers["Call-ID"].orEmpty(),
            "CSeq" to req.headers["CSeq"].orEmpty(),
            "X-GB-Ver" to gbVerHeaderValue,
            "Allow" to "REGISTER, MESSAGE, SUBSCRIBE, NOTIFY, INVITE, ACK, BYE, CANCEL, OPTIONS, PRACK",
            "Supported" to "replaces, timer"
        )
        val headerLines = linkedMapOf<String, MutableList<String>>()
        if (viaValues.isNotEmpty()) headerLines["Via"] = viaValues.toMutableList()
        headerLines["From"] = mutableListOf(req.headers["From"].orEmpty())
        headerLines["To"] = mutableListOf(req.headers["To"].orEmpty())
        headerLines["Call-ID"] = mutableListOf(req.headers["Call-ID"].orEmpty())
        headerLines["CSeq"] = mutableListOf(req.headers["CSeq"].orEmpty())
        headerLines["X-GB-Ver"] = mutableListOf(gbVerHeaderValue)
        headerLines["Allow"] = mutableListOf("REGISTER, MESSAGE, SUBSCRIBE, NOTIFY, INVITE, ACK, BYE, CANCEL, OPTIONS, PRACK")
        headerLines["Supported"] = mutableListOf("replaces, timer")
        val resp = SipMessage.Response(
            statusCode = 200,
            reason = "OK",
            headers = headers,
            headerLines = headerLines
        )
        sendAndCacheResponse(req, remote, resp)
    }

    private fun sendAndCacheResponse(req: SipMessage.Request, remote: SipEndpoint, resp: SipMessage.Response) {
        val payload = resp.encode()
        transport.send(remote, payload)
        val txKey = buildClientTransactionKey(req) ?: return
        clientTransactionCache[txKey] = CachedResponse(payload, System.currentTimeMillis())
        if (clientTransactionCache.size > 512) {
            val first = clientTransactionCache.entries.firstOrNull()?.key
            if (first != null) clientTransactionCache.remove(first)
        }
    }

    private fun buildClientTransactionKey(req: SipMessage.Request): String? {
        val callId = req.headers["Call-ID"] ?: return null
        val cseq = req.headers["CSeq"] ?: return null
        val branch = extractViaValues(req).firstOrNull()
            ?.let { Regex("""branch=([^;,\s]+)""").find(it)?.groupValues?.get(1) }
            ?: return null
        return "${req.method.uppercase()}|$callId|$cseq|$branch"
    }

    private fun extractViaValues(req: SipMessage.Request): List<String> {
        val viaLines = req.headerLines["Via"]
        if (!viaLines.isNullOrEmpty()) return viaLines
        val merged = req.headers["Via"].orEmpty()
        if (merged.isBlank()) return emptyList()
        return splitSipHeaderValues(merged)
    }

    private fun extractHeaderValues(req: SipMessage.Request, name: String): List<String> {
        val lines = req.headerLines[name]
        if (!lines.isNullOrEmpty()) return lines
        val merged = req.headers[name].orEmpty()
        if (merged.isBlank()) return emptyList()
        return splitSipHeaderValues(merged)
    }

    private fun parseInviteMediaTarget(sdp: String): SipEndpoint? {
        val ip = Regex("""(?m)^c=IN IP4 ([0-9\.]+)$""").find(sdp)?.groupValues?.get(1) ?: return null
        val port = Regex("""(?m)^m=video (\d+) RTP/AVP""").find(sdp)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return SipEndpoint(ip, port)
    }

    private fun scheduleIncomingInviteCompletedTimeout(callId: String) {
        scope.launch {
            delay(4_000)
            val tx = incomingInviteTxByCallId[callId] ?: return@launch
            if (tx.state == IncomingInviteState.COMPLETED) {
                tx.state = IncomingInviteState.TERMINATED
                incomingInviteTxByCallId.remove(callId)
                Logger.log("invite_completed_timeout_terminated callId=$callId")
            }
        }
    }

    private data class RoutingTarget(
        val requestUri: String,
        val routeHeaders: List<String>
    )

    private fun buildRoutingTarget(routeSet: List<String>, remoteTargetUri: String): RoutingTarget {
        if (routeSet.isEmpty()) return RoutingTarget(remoteTargetUri, emptyList())
        val first = routeSet.first()
        val hasLr = first.contains(";lr", ignoreCase = true) || first.contains(";lr>", ignoreCase = true)
        return if (hasLr) {
            RoutingTarget(remoteTargetUri, routeSet)
        } else {
            val firstUri = extractUriFromRoute(first) ?: remoteTargetUri
            val routes = routeSet.drop(1).toMutableList().apply { add("<$remoteTargetUri>") }
            RoutingTarget(firstUri, routes)
        }
    }

    private fun extractUriFromRoute(route: String): String? {
        val bracket = route.substringAfter("<", "").substringBefore(">", "")
        if (bracket.startsWith("sip:", ignoreCase = true)) return bracket
        val plain = route.substringBefore(";").trim()
        return plain.takeIf { it.startsWith("sip:", ignoreCase = true) }
    }
}
