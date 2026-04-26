package com.goway.gb28181

import com.goway.gb28181.net.SipTransport
import com.goway.gb28181.net.SipTransportFactory
import com.goway.gb28181.sip.SipMessage
import com.goway.gb28181.sip.SipUtil
import com.goway.gb28181.sip.decodeSipMessage
import com.goway.gb28181.sip.encode
import com.goway.gb28181.sip.parseDigestHeader
import com.goway.gb28181.sip.splitSipHeaderValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// 平台侧模拟器：负责注册鉴权、查询控制订阅、INVITE 对话与事务处理。
class Gb28181ServerSimulator(
    private val cfg: ServerConfig
) {
    private enum class InviteTxState {
        CALLING,
        PROCEEDING,
        COMPLETED,
        ESTABLISHED,
        TERMINATED
    }

    private data class CachedResponse(
        val payload: String,
        val createdAt: Long
    )

    private data class InviteDialog(
        val deviceId: String,
        val remote: SipEndpoint,
        val from: String,
        val to: String,
        val channelId: String,
        val callId: String,
        val inviteCseq: Int,
        val inviteBranch: String,
        val routeSet: List<String>,
        val remoteTargetUri: String,
        val scene: String,
        var retries: Int = 0,
        var cancelSent: Boolean = false,
        var txState: InviteTxState = InviteTxState.CALLING,
        var waitingFinalResponse: Boolean = true,
        var established: Boolean = false
    )

    private data class Subscription(
        val remote: SipEndpoint,
        val toHeader: String,
        val uri: String,
        val event: String,
        val expiresSec: Int,
        val routeSet: List<String>
    )

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val seq = AtomicInteger(1)
    private val nonceMap = ConcurrentHashMap<String, String>()
    private val lastCseqByDevice = ConcurrentHashMap<String, Int>()
    private val deviceAddressMap = ConcurrentHashMap<String, SipEndpoint>()
    private val lastHeartbeatAt = ConcurrentHashMap<String, Long>()
    private val subscriptions = ConcurrentHashMap<String, Subscription>()
    private val inviteDialogs = ConcurrentHashMap<String, InviteDialog>()
    private val pendingIncomingInvites = ConcurrentHashMap<String, SipMessage.Request>()
    private val reliableProvisionalAcked = ConcurrentHashMap<String, MutableSet<Int>>()
    private val serverTransactionCache = ConcurrentHashMap<String, CachedResponse>()
    private val deviceChannels = ConcurrentHashMap<String, MutableList<String>>()
    @Volatile private var demoFlowJob: Job? = null
    private val transport: SipTransport = SipTransportFactory.create(cfg.transport, cfg.sip, ::onPacket)
    private val transportToken = cfg.transport.name
    private val gbVerHeaderValue = if (cfg.version == GbVersion.V2022) "3.0" else "2.0"

    fun start() {
        transport.start()
        startHeartbeatMonitor()
        Logger.log("server_start_done at=${cfg.sip.host}:${cfg.sip.port} protocol=${cfg.transport} version=${cfg.version} vendor=${cfg.vendor}")
    }

    fun stop() = transport.stop()

    fun statusSummary(): String {
        val establishedDialogs = inviteDialogs.values.count { it.established && it.txState == InviteTxState.ESTABLISHED }
        val pendingDialogs = inviteDialogs.size - establishedDialogs
        val registeredIds = deviceAddressMap.keys.sorted()
        return buildString {
            append("server\n")
            append("serverId=${cfg.serverId}\n")
            append("registeredDevices=${deviceAddressMap.size}\n")
            append("activeSubscriptions=${subscriptions.size}\n")
            append("inviteDialogs=${inviteDialogs.size}\n")
            append("inviteEstablished=$establishedDialogs\n")
            append("invitePending=$pendingDialogs\n")
            append("cachedTransactions=${serverTransactionCache.size}\n")
            append("deviceIds=${if (registeredIds.isEmpty()) "-" else registeredIds.joinToString(",")}\n")
        }
    }

    fun listChannels(deviceId: String): List<String> {
        return deviceChannels.computeIfAbsent(deviceId) { mutableListOf("${deviceId}01") }.toList()
    }

    fun setChannelCount(deviceId: String, count: Int): List<String> {
        val finalCount = count.coerceIn(1, 999)
        val channels = (1..finalCount).map { idx -> deviceId + idx.toString().padStart(2, '0') }.toMutableList()
        deviceChannels[deviceId] = channels
        Logger.log("channel_set_done deviceId=$deviceId count=$finalCount")
        return channels
    }

    private fun onPacket(content: String, remote: SipEndpoint) {
        val msg = runCatching { decodeSipMessage(content) }.getOrNull() ?: return
        when (msg) {
            is SipMessage.Request -> {
                val txKey = buildServerTransactionKey(msg)
                if (!msg.method.equals("ACK", true) && txKey != null) {
                    val cached = serverTransactionCache[txKey]
                    if (cached != null && System.currentTimeMillis() - cached.createdAt < 64_000) {
                        transport.send(remote, cached.payload)
                        Logger.log("sip_tx_retransmit_hit method=${msg.method} txKey=$txKey")
                        return
                    }
                }
                handleRequest(msg, remote)
            }
            is SipMessage.Response -> handleResponse(msg, remote)
        }
    }

    private fun handleResponse(resp: SipMessage.Response, remote: SipEndpoint) {
        val cseq = resp.headers["CSeq"].orEmpty()
        if (resp.statusCode in 100..199 && cseq.contains("INVITE", ignoreCase = true)) {
            val callId = resp.headers["Call-ID"]
            if (callId != null) {
                inviteDialogs[callId]?.let { dialog ->
                    if (dialog.txState == InviteTxState.CALLING || dialog.txState == InviteTxState.PROCEEDING) {
                        dialog.txState = InviteTxState.PROCEEDING
                        maybeSendPrack(dialog, resp, remote)
                    }
                }
            }
            Logger.log("invite_response_provisional_recv status=${resp.statusCode}")
            return
        }
        if (resp.statusCode == 200 && cseq.contains("INVITE", ignoreCase = true)) {
            val callId = resp.headers["Call-ID"] ?: return
            val dialog = inviteDialogs[callId] ?: return
            dialog.waitingFinalResponse = false
            dialog.established = true
            val rr = extractHeaderValues(resp, "Record-Route")
            val remoteTargetUri = extractContactUri(resp) ?: dialog.remoteTargetUri
            val finalDialog = if (rr.isNotEmpty()) {
                dialog.copy(
                    routeSet = rr.reversed(),
                    remoteTargetUri = remoteTargetUri,
                    established = true,
                    txState = InviteTxState.ESTABLISHED,
                    waitingFinalResponse = false
                )
            } else {
                dialog.copy(
                    remoteTargetUri = remoteTargetUri,
                    established = true,
                    txState = InviteTxState.ESTABLISHED,
                    waitingFinalResponse = false
                )
            }
            inviteDialogs[callId] = finalDialog
            sendAck(finalDialog)
            return
        }
        if (resp.statusCode >= 300 && cseq.contains("INVITE", ignoreCase = true)) {
            val callId = resp.headers["Call-ID"] ?: return
            val dialog = inviteDialogs[callId] ?: return
            val ackDialog = dialog.copy(to = resp.headers["To"] ?: dialog.to)
            sendAck(ackDialog)
            dialog.txState = InviteTxState.COMPLETED
            dialog.waitingFinalResponse = false
            val shouldRetry = dialog.retries < 1 && !dialog.cancelSent && resp.statusCode !in listOf(481, 487)
            if (shouldRetry) {
                dialog.retries += 1
                Logger.log("invite_response_failure_retry status=${resp.statusCode} scene=${dialog.scene}")
                inviteDialogs.remove(callId)
                sendInviteInternal(dialog.deviceId, dialog.remote, dialog.channelId, 15068, dialog.scene)
            } else {
                dialog.txState = InviteTxState.TERMINATED
                inviteDialogs.remove(callId)
            }
            return
        }
        if (resp.statusCode == 200 && cseq.contains("PRACK", ignoreCase = true)) {
            Logger.log("prack_response_ok_recv")
            return
        }
        if (resp.statusCode >= 300 && cseq.contains("PRACK", ignoreCase = true)) {
            Logger.log("prack_response_fail_recv status=${resp.statusCode}")
            return
        }
        if (resp.statusCode == 200 && cseq.contains("BYE", ignoreCase = true)) {
            resp.headers["Call-ID"]?.let { inviteDialogs.remove(it) }
            Logger.log("bye_response_ok_recv from=${remote.host}:${remote.port}")
            return
        }
        if (resp.statusCode == 481 && cseq.contains("BYE", ignoreCase = true)) {
            resp.headers["Call-ID"]?.let { inviteDialogs.remove(it) }
            Logger.log("bye_response_481_cleanup")
        }
    }

    private fun handleRequest(req: SipMessage.Request, remote: SipEndpoint) {
        val validationStatus = validateRequest(req)
        if (validationStatus != null) {
            replyWithStatus(req, remote, validationStatus.first, validationStatus.second)
            return
        }
        when (req.method.uppercase()) {
            "REGISTER" -> handleRegister(req, remote)
            "MESSAGE" -> handleMessage(req, remote)
            "SUBSCRIBE" -> handleSubscribe(req, remote)
            "OPTIONS" -> replyOptions(req, remote)
            "ACK" -> Logger.log("ack_request_recv from=${remote.host}:${remote.port}")
            "BYE" -> {
                val callId = req.headers["Call-ID"]
                if (callId != null && inviteDialogs.containsKey(callId)) {
                    replySimpleOk(req, remote)
                    inviteDialogs.remove(callId)
                } else {
                    replyWithStatus(req, remote, 481, "Call/Transaction Does Not Exist")
                }
            }
            "INVITE" -> {
                val callId = req.headers["Call-ID"] ?: "unknown"
                pendingIncomingInvites[callId] = req
                replySimpleOk(req, remote)
                pendingIncomingInvites.remove(callId)
            }
            "CANCEL" -> handleCancel(req, remote)
            else -> replySimpleOk(req, remote)
        }
    }

    private fun handleCancel(req: SipMessage.Request, remote: SipEndpoint) {
        val callId = req.headers["Call-ID"] ?: return
        val invite = pendingIncomingInvites.remove(callId)
        if (invite == null) {
            replyWithStatus(req, remote, 481, "Call/Transaction Does Not Exist")
            return
        }
        replySimpleOk(req, remote)
        replyWithStatus(invite, remote, 487, "Request Terminated")
        Logger.log("cancel_request_handled_487 callId=$callId")
    }

    private fun handleRegister(req: SipMessage.Request, remote: SipEndpoint) {
        val from = req.headers["From"].orEmpty()
        val deviceId = Regex("""sip:(\d+)@""").find(from)?.groupValues?.get(1) ?: "unknown"
        val authHeader = req.headers["Authorization"]
        val expires = parseRegisterExpires(req)
        if (authHeader.isNullOrBlank()) {
            sendDigestChallenge(req, remote, deviceId, stale = false)
            return
        }

        val digest = parseDigestHeader(authHeader)
        val username = digest["username"].orEmpty()
        val uri = digest["uri"].orEmpty()
        val nonce = digest["nonce"].orEmpty()
        val response = digest["response"].orEmpty()
        val issuedNonce = nonceMap[username]
        if (issuedNonce != null && issuedNonce != nonce) {
            sendDigestChallenge(req, remote, username, stale = true)
            return
        }
        // 模拟模式下允许未知用户名复用默认密码，
        // 以便动态新增客户端时无需同步修改服务端静态配置。
        val password = cfg.passwordLookup[username] ?: cfg.passwordLookup.values.firstOrNull()
        val expected = password?.let {
            SipUtil.digestResponse(
                username = username,
                realm = cfg.domainId,
                password = it,
                nonce = nonce,
                method = "REGISTER",
                uri = uri
            )
        }

        if (expected != null && expected.equals(response, ignoreCase = true)) {
            if (expires == 0) {
                val existed = deviceAddressMap.containsKey(username)
                deviceAddressMap.remove(username)
                lastHeartbeatAt.remove(username)
                if (existed) {
                    Logger.log("register_state_removed id=$username")
                } else {
                    Logger.log("register_state_remove_skip_not_found id=$username")
                }
            } else {
                val previous = deviceAddressMap[username]
                val resolved = resolveRegisterEndpoint(req, remote)
                deviceAddressMap[username] = resolved
                lastHeartbeatAt[username] = System.currentTimeMillis()
                if (previous == null) {
                    Logger.log("register_state_added id=$username endpoint=${resolved.host}:${resolved.port} expires=$expires")
                } else {
                    Logger.log(
                        "register_state_refresh id=$username " +
                            "from=${previous.host}:${previous.port} to=${resolved.host}:${resolved.port} expires=$expires"
                    )
                }
            }
            replyRegisterOk(req, remote, expires)
        } else {
            replyWithStatus(req, remote, 403, "Forbidden")
        }
    }

    private fun handleMessage(req: SipMessage.Request, remote: SipEndpoint) {
        if (!req.body.trimStart().startsWith("<") || !req.body.contains("</")) {
            replyWithStatus(req, remote, 400, "Bad XML Body")
            return
        }
        if (req.body.contains("<Response>")) {
            val cmdType = Regex("""<CmdType>(.*?)</CmdType>""").find(req.body)?.groupValues?.get(1).orEmpty()
            val sn = Regex("""<SN>(.*?)</SN>""").find(req.body)?.groupValues?.get(1).orEmpty()
            replySimpleOk(req, remote)
            Logger.log("query_response_recv cmdType=$cmdType sn=$sn from=${remote.host}:${remote.port}")
            return
        }
        val cmdType = Regex("""<CmdType>(.*?)</CmdType>""").find(req.body)?.groupValues?.get(1).orEmpty()
        if (cmdType.isBlank()) {
            replyWithStatus(req, remote, 400, "CmdType Missing")
            return
        }
        if (cmdType.equals("Keepalive", ignoreCase = true)) {
            val dev = Regex("""<DeviceID>(.*?)</DeviceID>""").find(req.body)?.groupValues?.get(1)
            if (!dev.isNullOrBlank()) {
                lastHeartbeatAt[dev] = System.currentTimeMillis()
            }
            replySimpleOk(req, remote)
            return
        }

        when (cmdType) {
            "Catalog", "DeviceInfo", "DeviceStatus", "RecordInfo", "Alarm", "ConfigDownload", "ConfigUpload" -> {
                val sn = Regex("""<SN>(.*?)</SN>""").find(req.body)?.groupValues?.get(1) ?: "1"
                val dev = Regex("""<DeviceID>(.*?)</DeviceID>""").find(req.body)?.groupValues?.get(1) ?: "unknown"
                if (cmdType == "Catalog") {
                    deviceChannels.computeIfAbsent(dev) { mutableListOf("${dev}01") }
                }
                replySimpleOk(req, remote)
                sendManscdpResponse(req, remote, cmdType, sn, dev)
            }
            "DeviceControl", "PTZ" -> {
                replySimpleOk(req, remote)
                Logger.log("control_request_recv cmdType=$cmdType")
            }

            else -> replySimpleOk(req, remote)
        }
    }

    private fun handleSubscribe(req: SipMessage.Request, remote: SipEndpoint) {
        replySimpleOk(req, remote)
        val event = req.headers["Event"] ?: "presence"
        val expires = req.headers["Expires"]?.toIntOrNull() ?: 3600
        val key = req.headers["Call-ID"] ?: SipUtil.callId()
        if (expires <= 0) {
            subscriptions.remove(key)?.let { old ->
                Logger.log("subscribe_state_terminated event=${old.event} callId=$key")
                sendNotify(old, "terminated", 0)
            }
            return
        }
        subscriptions[key] = Subscription(
            remote = remote,
            toHeader = req.headers["From"].orEmpty(),
            uri = req.uri,
            event = event,
            expiresSec = expires,
            routeSet = extractHeaderValues(req, "Record-Route") + extractHeaderValues(req, "Route")
        )
        Logger.log("subscribe_request_accepted event=$event expires=$expires")
        sendNotify(subscriptions[key]!!, "active", 0)
    }

    private fun sendManscdpResponse(
        req: SipMessage.Request,
        remote: SipEndpoint,
        cmdType: String,
        sn: String,
        dev: String
    ) {
        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"GB2312\"?>\r\n")
            append("<Response>\r\n")
            append("<CmdType>$cmdType</CmdType>\r\n")
            append("<SN>$sn</SN>\r\n")
            append("<DeviceID>$dev</DeviceID>\r\n")
            when (cmdType) {
                "Catalog" -> {
                    val channels = deviceChannels.computeIfAbsent(dev) { mutableListOf("${dev}01") }
                    append("<SumNum>${channels.size}</SumNum>\r\n")
                    append("<DeviceList Num=\"${channels.size}\">\r\n")
                    channels.forEachIndexed { index, channelId ->
                        append("<Item>\r\n")
                        append("<DeviceID>$channelId</DeviceID>\r\n")
                        append("<Name>SimCamera-${(index + 1).toString().padStart(2, '0')}</Name>\r\n")
                        append("<Manufacturer>${vendorManufacturer(cfg.strategy)}</Manufacturer>\r\n")
                        append("<Model>${vendorModel(cfg.strategy, "IPC")}</Model>\r\n")
                        append("<Status>ON</Status>\r\n")
                        append("<${cfg.strategy.catalogExtraFieldName}>${cfg.strategy.catalogExtraFieldValue}</${cfg.strategy.catalogExtraFieldName}>\r\n")
                        if (cfg.version == GbVersion.V2022) {
                            append("<SafetyWay>0</SafetyWay>\r\n")
                            append("<RegisterWay>1</RegisterWay>\r\n")
                        }
                        append("</Item>\r\n")
                    }
                    append("</DeviceList>\r\n")
                }

                "DeviceInfo" -> {
                    append("<DeviceName>SimNVR</DeviceName>\r\n")
                    append("<Manufacturer>${vendorManufacturer(cfg.strategy)}</Manufacturer>\r\n")
                    append("<Model>${vendorModel(cfg.strategy, "NVR")}</Model>\r\n")
                    append("<Firmware>1.0.0</Firmware>\r\n")
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
                    append("<DeviceID>${dev}01</DeviceID>\r\n")
                    append("<Name>record-001</Name>\r\n")
                    append("<StartTime>2026-04-25T00:00:00</StartTime>\r\n")
                    append("<EndTime>2026-04-25T00:10:00</EndTime>\r\n")
                    append("<Secrecy>0</Secrecy>\r\n")
                    append("<Type>all</Type>\r\n")
                    append("<DownloadSpeed>${vendorPlaybackSpeed(cfg.strategy)}</DownloadSpeed>\r\n")
                    append("</Item>\r\n")
                    append("</RecordList>\r\n")
                }

                "Alarm" -> {
                    append("<AlarmPriority>1</AlarmPriority>\r\n")
                    append("<AlarmMethod>4</AlarmMethod>\r\n")
                    append("<AlarmDescription>simulated alarm response</AlarmDescription>\r\n")
                }
                "ConfigDownload" -> {
                    append("<Result>OK</Result>\r\n")
                    append("<BasicParam>\r\n")
                    append("<Name>SimPlatformConfig</Name>\r\n")
                    append("<Expiration>3600</Expiration>\r\n")
                    append("</BasicParam>\r\n")
                }
                "ConfigUpload" -> {
                    append("<Result>OK</Result>\r\n")
                    append("<Info>Config upload accepted by platform simulator</Info>\r\n")
                }
            }
            append("</Response>")
        }

        val msg = SipMessage.Request(
            method = "MESSAGE",
            uri = req.headers["From"]?.substringAfter("<")?.substringBefore(">") ?: "sip:$dev@${cfg.domainId}",
            headers = linkedMapOf(
                "Via" to "SIP/2.0/$transportToken ${cfg.sip.host}:${cfg.sip.port};branch=${SipUtil.branch()}",
                "From" to "<sip:${cfg.serverId}@${cfg.domainId}>;tag=${SipUtil.tag()}",
                "To" to req.headers["From"].orEmpty(),
                "Call-ID" to SipUtil.callId(),
                "CSeq" to "20 MESSAGE",
                "Content-Type" to "Application/MANSCDP+xml",
                "Max-Forwards" to "70",
                "X-GB-Ver" to gbVerHeaderValue
            ),
            body = xml
        )
        transport.send(remote, msg.encode())
    }

    private fun sendNotify(sub: Subscription, state: String, retryAfter: Int) {
        val routing = buildRoutingTarget(sub.routeSet, sub.toHeader.substringAfter("<").substringBefore(">").ifBlank { sub.uri })
        val body = buildString {
            append("<?xml version=\"1.0\" encoding=\"GB2312\"?>\r\n")
            append("<Notify>\r\n")
            append(
                "<CmdType>${
                    when {
                        sub.event.equals("MobilePosition", true) -> "MobilePosition"
                        sub.event.equals("Catalog", true) -> "Catalog"
                        else -> "Alarm"
                    }
                }</CmdType>\r\n"
            )
            append("<SN>${System.currentTimeMillis() % 100000}</SN>\r\n")
            append("<DeviceID>${cfg.serverId}</DeviceID>\r\n")
            if (sub.event.equals("MobilePosition", true)) {
                append("<Time>2026-04-26T01:30:00</Time>\r\n")
                append("<Longitude>116.397128</Longitude>\r\n")
                append("<Latitude>39.916527</Latitude>\r\n")
                append("<Speed>5.0</Speed>\r\n")
                append("<Direction>45.0</Direction>\r\n")
                append("<Altitude>45.2</Altitude>\r\n")
            } else if (sub.event.equals("Catalog", true)) {
                append("<SumNum>1</SumNum>\r\n")
                append("<DeviceList Num=\"1\">\r\n")
                append("<Item><DeviceID>34020000001320000099</DeviceID><Name>NewCam</Name><Status>ON</Status></Item>\r\n")
                append("</DeviceList>\r\n")
            } else {
                append("<AlarmPriority>1</AlarmPriority>\r\n")
                append("<AlarmMethod>4</AlarmMethod>\r\n")
                append("<AlarmDescription>notify event ${sub.event}</AlarmDescription>\r\n")
            }
            append("</Notify>")
        }
        val req = SipMessage.Request(
            method = "NOTIFY",
            uri = routing.requestUri,
            headers = linkedMapOf(
                "Via" to "SIP/2.0/$transportToken ${cfg.sip.host}:${cfg.sip.port};branch=${SipUtil.branch()}",
                "From" to "<sip:${cfg.serverId}@${cfg.domainId}>;tag=${SipUtil.tag()}",
                "To" to sub.toHeader,
                "Call-ID" to SipUtil.callId(),
                "CSeq" to SipUtil.cseq(seq.getAndIncrement(), "NOTIFY"),
                "Event" to sub.event,
                "Subscription-State" to "$state;expires=${sub.expiresSec};retry-after=$retryAfter",
                "Content-Type" to "Application/MANSCDP+xml",
                "Max-Forwards" to "70",
                "X-GB-Ver" to gbVerHeaderValue
            ),
            headerLines = linkedMapOf<String, MutableList<String>>().apply {
                if (routing.routeHeaders.isNotEmpty()) this["Route"] = routing.routeHeaders.toMutableList()
            },
            body = body
        )
        if (routing.routeHeaders.isNotEmpty()) {
            req.headers["Route"] = routing.routeHeaders.joinToString(", ")
        }
        transport.send(sub.remote, req.encode())
    }

    fun subscribeAlarm(deviceId: String, expiresSec: Int = 3600) {
        val finalExpires = if (expiresSec == 3600) vendorDefaultSubscribeExpiresSec(cfg.strategy) else expiresSec
        subscribeEvent(deviceId, "Alarm", finalExpires)
    }

    fun subscribeMobilePosition(deviceId: String, expiresSec: Int = 3600) {
        val finalExpires = if (expiresSec == 3600) vendorDefaultSubscribeExpiresSec(cfg.strategy) else expiresSec
        subscribeEvent(deviceId, "MobilePosition", finalExpires)
    }

    fun subscribeCatalog(deviceId: String, expiresSec: Int = 3600) {
        val finalExpires = if (expiresSec == 3600) vendorDefaultSubscribeExpiresSec(cfg.strategy) else expiresSec
        subscribeEvent(deviceId, "Catalog", finalExpires)
    }

    fun controlPtz(deviceId: String, channelId: String, value: String = "A50F010000") {
        sendControl(deviceId, channelId, "PTZ", value)
    }

    fun controlDevice(deviceId: String, command: String = "TeleBoot") {
        sendControl(deviceId, deviceId, "DeviceControl", command)
    }

    fun controlHomePosition(deviceId: String, channelId: String, enabled: Boolean = true) {
        val value = if (enabled) "<HomePosition><Enabled>1</Enabled><ResetTime>30</ResetTime></HomePosition>"
        else "<HomePosition><Enabled>0</Enabled></HomePosition>"
        sendControlRaw(deviceId, channelId, "DeviceControl", value)
    }

    fun controlZoomFocus(deviceId: String, channelId: String, zoomIn: Boolean = true) {
        val ptz = vendorPtzCommand(cfg.strategy, zoomIn)
        sendControl(deviceId, channelId, "PTZ", ptz)
    }

    private fun subscribeEvent(deviceId: String, event: String, expiresSec: Int) {
        val remote = deviceAddressMap[deviceId] ?: run {
            Logger.log("device_state_not_registered deviceId=$deviceId action=subscribe event=$event")
            return
        }
        val req = SipMessage.Request(
            method = "SUBSCRIBE",
            uri = "sip:$deviceId@${cfg.domainId}",
            headers = linkedMapOf(
                "Via" to "SIP/2.0/$transportToken ${cfg.sip.host}:${cfg.sip.port};branch=${SipUtil.branch()}",
                "From" to "<sip:${cfg.serverId}@${cfg.domainId}>;tag=${SipUtil.tag()}",
                "To" to "<sip:$deviceId@${cfg.domainId}>",
                "Call-ID" to SipUtil.callId(),
                "CSeq" to SipUtil.cseq(seq.getAndIncrement(), "SUBSCRIBE"),
                "Event" to event,
                "Expires" to expiresSec.toString(),
                "Max-Forwards" to "70",
                "X-GB-Ver" to gbVerHeaderValue
            )
        )
        Logger.log("subscribe_request_sent event=$event deviceId=$deviceId expires=$expiresSec endpoint=${remote.host}:${remote.port}")
        transport.send(remote, req.encode())
    }

    private fun sendControl(deviceId: String, targetId: String, cmdType: String, content: String) {
        val remote = deviceAddressMap[deviceId] ?: run {
            Logger.log("device_state_not_registered deviceId=$deviceId action=control cmdType=$cmdType")
            return
        }
        val body = buildString {
            append("<?xml version=\"1.0\" encoding=\"GB2312\"?>\r\n")
            append("<Control>\r\n")
            append("<CmdType>$cmdType</CmdType>\r\n")
            append("<SN>${System.currentTimeMillis() % 100000}</SN>\r\n")
            append("<DeviceID>$targetId</DeviceID>\r\n")
            if (cmdType == "PTZ") append("<PTZCmd>$content</PTZCmd>\r\n") else append("<$content/>\r\n")
            append("</Control>")
        }
        sendControlBody(remote, targetId, cmdType, body)
    }

    private fun sendControlRaw(deviceId: String, targetId: String, cmdType: String, rawXml: String) {
        val remote = deviceAddressMap[deviceId] ?: run {
            Logger.log("device_state_not_registered deviceId=$deviceId action=control_raw cmdType=$cmdType")
            return
        }
        val body = buildString {
            append("<?xml version=\"1.0\" encoding=\"GB2312\"?>\r\n")
            append("<Control>\r\n")
            append("<CmdType>$cmdType</CmdType>\r\n")
            append("<SN>${System.currentTimeMillis() % 100000}</SN>\r\n")
            append("<DeviceID>$targetId</DeviceID>\r\n")
            append(rawXml)
            append("\r\n</Control>")
        }
        sendControlBody(remote, targetId, cmdType, body)
    }

    private fun sendControlBody(remote: SipEndpoint, targetId: String, cmdType: String, body: String) {
        val req = SipMessage.Request(
            method = "MESSAGE",
            uri = "sip:$targetId@${cfg.domainId}",
            headers = linkedMapOf(
                "Via" to "SIP/2.0/$transportToken ${cfg.sip.host}:${cfg.sip.port};branch=${SipUtil.branch()}",
                "From" to "<sip:${cfg.serverId}@${cfg.domainId}>;tag=${SipUtil.tag()}",
                "To" to "<sip:$targetId@${cfg.domainId}>",
                "Call-ID" to SipUtil.callId(),
                "CSeq" to SipUtil.cseq(seq.getAndIncrement(), "MESSAGE"),
                "Content-Type" to "Application/MANSCDP+xml",
                "Max-Forwards" to "70",
                "X-GB-Ver" to gbVerHeaderValue
            ),
            body = body
        )
        Logger.log("control_request_sent cmdType=$cmdType targetId=$targetId endpoint=${remote.host}:${remote.port}")
        transport.send(remote, req.encode())
    }

    fun queryDeviceInfo(deviceId: String) = sendQuery(deviceId, "DeviceInfo")
    fun queryDeviceStatus(deviceId: String) = sendQuery(deviceId, "DeviceStatus")
    fun queryRecordInfo(deviceId: String) = sendQuery(deviceId, "RecordInfo")
    fun queryAlarm(deviceId: String) = sendQuery(deviceId, "Alarm")
    fun queryConfigDownload(deviceId: String) = sendQuery(deviceId, "ConfigDownload")
    fun queryConfigUpload(deviceId: String) = sendQuery(deviceId, "ConfigUpload")

    private fun sendQuery(deviceId: String, cmdType: String) {
        val remote = deviceAddressMap[deviceId] ?: run {
            Logger.log("query_request_skip_unregistered deviceId=$deviceId cmdType=$cmdType")
            return
        }
        val body = buildString {
            append("<?xml version=\"1.0\" encoding=\"GB2312\"?>\r\n")
            append("<Query>\r\n")
            append("<CmdType>$cmdType</CmdType>\r\n")
            append("<SN>${System.currentTimeMillis() % 100000}</SN>\r\n")
            append("<DeviceID>$deviceId</DeviceID>\r\n")
            append("</Query>")
        }
        val req = SipMessage.Request(
            method = "MESSAGE",
            uri = "sip:$deviceId@${cfg.domainId}",
            headers = linkedMapOf(
                "Via" to "SIP/2.0/$transportToken ${cfg.sip.host}:${cfg.sip.port};branch=${SipUtil.branch()}",
                "From" to "<sip:${cfg.serverId}@${cfg.domainId}>;tag=${SipUtil.tag()}",
                "To" to "<sip:$deviceId@${cfg.domainId}>",
                "Call-ID" to SipUtil.callId(),
                "CSeq" to SipUtil.cseq(seq.getAndIncrement(), "MESSAGE"),
                "Content-Type" to "Application/MANSCDP+xml",
                "Max-Forwards" to "70",
                "X-GB-Ver" to gbVerHeaderValue
            ),
            body = body
        )
        Logger.log("query_request_sent deviceId=$deviceId cmdType=$cmdType target=${remote.host}:${remote.port}")
        transport.send(remote, req.encode())
    }

    fun runDemoFlow(deviceId: String, channelId: String): Boolean {
        if (demoFlowJob?.isActive == true) {
            Logger.log("demo_flow_skip_running deviceId=$deviceId channelId=$channelId")
            return false
        }
        if (!deviceAddressMap.containsKey(deviceId)) {
            Logger.log("demo_flow_skip_unregistered deviceId=$deviceId")
            return false
        }
        demoFlowJob = scope.launch {
            fun step(index: Int, action: String) {
                Logger.log("demo_flow_step_$index $action deviceId=$deviceId channelId=$channelId")
            }
            delay(500)
            step(1, "q-info")
            queryDeviceInfo(deviceId)
            delay(400)
            step(2, "q-status")
            queryDeviceStatus(deviceId)
            delay(400)
            step(3, "q-record")
            queryRecordInfo(deviceId)
            delay(400)
            step(4, "q-alarm")
            queryAlarm(deviceId)
            delay(400)
            step(5, "q-cfg-down")
            queryConfigDownload(deviceId)
            delay(400)
            step(6, "q-cfg-up")
            queryConfigUpload(deviceId)
            delay(400)
            step(7, "c-ptz")
            controlPtz(deviceId, channelId)
            delay(400)
            step(8, "c-zoom-in")
            controlZoomFocus(deviceId, channelId, zoomIn = true)
            delay(400)
            step(9, "c-home-on")
            controlHomePosition(deviceId, channelId, enabled = true)
            delay(400)
            step(10, "c-reboot")
            controlDevice(deviceId, "TeleBoot")
            delay(400)
            step(11, "i-play-start")
            invitePlay(deviceId = deviceId, channelId = channelId)
            delay(500)
            step(12, "i-playback-start")
            invitePlayback(deviceId = deviceId, channelId = channelId)
            delay(500)
            step(13, "i-download-start")
            inviteDownload(deviceId = deviceId, channelId = channelId)
            delay(500)
            step(14, "i-talk-start")
            inviteTalkback(deviceId = deviceId, channelId = channelId)
            delay(500)
            step(15, "s-alarm")
            subscribeAlarm(deviceId)
            delay(500)
            step(16, "s-mobile")
            subscribeMobilePosition(deviceId)
            delay(500)
            step(17, "s-catalog")
            subscribeCatalog(deviceId)
            delay(1200)
            step(18, "i-stop")
            sendByeAll(deviceId, channelId)
        }.also { job ->
            job.invokeOnCompletion {
                demoFlowJob = null
            }
        }
        return true
    }

    fun invitePlay(deviceId: String, channelId: String, remoteRtpPort: Int = 15060) {
        sendInvite(deviceId, channelId, remoteRtpPort, "Play")
    }

    fun invitePlayback(deviceId: String, channelId: String, remoteRtpPort: Int = 15062) {
        sendInvite(deviceId, channelId, remoteRtpPort, "Playback")
    }

    fun inviteDownload(deviceId: String, channelId: String, remoteRtpPort: Int = 15064) {
        sendInvite(deviceId, channelId, remoteRtpPort, "Download")
    }

    fun inviteTalkback(deviceId: String, channelId: String, remoteRtpPort: Int = 15066) {
        sendInvite(deviceId, channelId, remoteRtpPort, "Talkback")
    }

    fun sendBye(deviceId: String, channelId: String) {
        val remote = deviceAddressMap[deviceId] ?: run {
            Logger.log("device_state_not_registered deviceId=$deviceId action=bye")
            return
        }
        val dialog = inviteDialogs.values
            .filter { it.deviceId == deviceId && it.channelId == channelId && it.established }
            .maxByOrNull { it.callId }
        val callId = dialog?.callId ?: run {
            Logger.log("bye_request_skip_no_dialog deviceId=$deviceId channelId=$channelId")
            return
        }
        sendByeByDialog(remote, dialog, callId)
    }

    fun sendByeAll(deviceId: String, channelId: String) {
        val remote = deviceAddressMap[deviceId] ?: run {
            Logger.log("device_state_not_registered deviceId=$deviceId action=bye_all")
            return
        }
        val dialogs = inviteDialogs.values
            .filter { it.deviceId == deviceId && it.channelId == channelId && it.established }
            .sortedBy { it.callId }
        if (dialogs.isEmpty()) {
            Logger.log("bye_request_skip_no_dialog deviceId=$deviceId channelId=$channelId")
            return
        }
        dialogs.forEach { dialog ->
            sendByeByDialog(remote, dialog, dialog.callId)
        }
    }

    private fun sendByeByDialog(remote: SipEndpoint, dialog: InviteDialog, callId: String) {
        val req = SipMessage.Request(
            method = "BYE",
            uri = buildRoutingTarget(dialog.routeSet, dialog.remoteTargetUri).requestUri,
            headers = linkedMapOf(
                "Via" to "SIP/2.0/$transportToken ${cfg.sip.host}:${cfg.sip.port};branch=${SipUtil.branch()}",
                "From" to dialog.from,
                "To" to dialog.to,
                "Call-ID" to callId,
                "CSeq" to SipUtil.cseq(seq.getAndIncrement(), "BYE"),
                "Max-Forwards" to "70",
                "X-GB-Ver" to gbVerHeaderValue
            ),
            headerLines = linkedMapOf<String, MutableList<String>>()
        )
        val routing = buildRoutingTarget(dialog.routeSet, dialog.remoteTargetUri)
        if (routing.routeHeaders.isNotEmpty()) {
            req.headerLines["Route"] = routing.routeHeaders.toMutableList()
            req.headers["Route"] = routing.routeHeaders.joinToString(", ")
        }
        Logger.log(
            "bye_request_sent deviceId=${dialog.deviceId} channelId=${dialog.channelId} callId=$callId " +
                "endpoint=${remote.host}:${remote.port}"
        )
        transport.send(remote, req.encode())
    }

    private fun sendInvite(deviceId: String, channelId: String, remoteRtpPort: Int, scene: String) {
        val remote = deviceAddressMap[deviceId] ?: run {
            Logger.log("device_state_not_registered deviceId=$deviceId action=invite scene=$scene")
            return
        }
        sendInviteInternal(deviceId, remote, channelId, remoteRtpPort, scene)
    }

    private fun sendInviteInternal(deviceId: String, remote: SipEndpoint, channelId: String, remoteRtpPort: Int, scene: String) {
        val sdp = buildString {
            append("v=0\r\n")
            append("o=${cfg.serverId} 0 0 IN IP4 ${cfg.sip.host}\r\n")
            append("s=$scene\r\n")
            append("c=IN IP4 ${cfg.sip.host}\r\n")
            append("t=0 0\r\n")
            append("m=video $remoteRtpPort RTP/AVP 96 98\r\n")
            append(if (scene == "Talkback") "a=sendrecv\r\n" else "a=recvonly\r\n")
            append("a=rtpmap:96 PS/90000\r\n")
            append("${cfg.strategy.sdpExtraLine}\r\n")
            if (scene == "Playback" || scene == "Download") {
                append("u=3402000000:0\r\n")
                append("a=downloadspeed:${vendorPlaybackSpeed(cfg.strategy)}\r\n")
                append("a=range:npt=0-\r\n")
            }
            if (cfg.version == GbVersion.V2022) {
                append("y=0100000001\r\n")
                append("f=v/2/4///a/1/8/1\r\n")
                append("a=setup:active\r\n")
                append("a=connection:new\r\n")
            } else {
                append("y=0100000001\r\n")
                append("a=recvonly\r\n")
            }
        }

        val callId = SipUtil.callId()
        val inviteCseq = seq.getAndIncrement()
        val req = SipMessage.Request(
            method = "INVITE",
            uri = "sip:$channelId@${cfg.domainId}",
            headers = linkedMapOf(
                "Via" to "SIP/2.0/$transportToken ${cfg.sip.host}:${cfg.sip.port};branch=${SipUtil.branch()}",
                "From" to "<sip:${cfg.serverId}@${cfg.domainId}>;tag=${SipUtil.tag()}",
                "To" to "<sip:$channelId@${cfg.domainId}>",
                "Call-ID" to callId,
                "CSeq" to SipUtil.cseq(inviteCseq, "INVITE"),
                "Content-Type" to "application/sdp",
                "Max-Forwards" to "70",
                "X-GB-Ver" to gbVerHeaderValue,
                "Subject" to "$channelId:${cfg.serverId},${cfg.serverId}:0"
            ),
            body = sdp
        )
        inviteDialogs[callId] = InviteDialog(
            deviceId = deviceId,
            remote = remote,
            from = req.headers["From"].orEmpty(),
            to = req.headers["To"].orEmpty(),
            channelId = channelId,
            callId = callId,
            inviteCseq = inviteCseq,
            inviteBranch = req.headers["Via"]?.substringAfter("branch=", "")?.substringBefore(";")?.ifBlank { SipUtil.branch() } ?: SipUtil.branch(),
            routeSet = emptyList(),
            remoteTargetUri = req.uri,
            scene = scene
        )
        transport.send(remote, req.encode())
        Logger.log("invite_request_sent scene=$scene channelId=$channelId")
        scheduleInviteTimeout(callId)
    }

    private fun scheduleInviteTimeout(callId: String) {
        scope.launch {
            delay(1500)
            val dialog = inviteDialogs[callId] ?: return@launch
            if (dialog.txState == InviteTxState.ESTABLISHED && dialog.established) {
                // 已建立对话需保留，以支持后续 BYE/刷新等处理。
                return@launch
            }
            val pendingFinal = dialog.txState == InviteTxState.CALLING || dialog.txState == InviteTxState.PROCEEDING
            if (pendingFinal && dialog.waitingFinalResponse && dialog.retries < 1) {
                if (dialog.txState == InviteTxState.PROCEEDING) {
                    dialog.cancelSent = true
                    sendCancel(dialog)
                }
                dialog.retries += 1
                Logger.log("invite_request_timeout_retry scene=${dialog.scene}")
                dialog.txState = InviteTxState.TERMINATED
                inviteDialogs.remove(callId)
                sendInviteInternal(dialog.deviceId, dialog.remote, dialog.channelId, 15069, dialog.scene)
            } else {
                if (!dialog.established) dialog.txState = InviteTxState.TERMINATED
                if (!dialog.established) {
                    inviteDialogs.remove(callId)
                }
            }
        }
    }

    private fun sendAck(dialog: InviteDialog) {
        val req = SipMessage.Request(
            method = "ACK",
            uri = buildRoutingTarget(dialog.routeSet, dialog.remoteTargetUri).requestUri,
            headers = linkedMapOf(
                "Via" to "SIP/2.0/$transportToken ${cfg.sip.host}:${cfg.sip.port};branch=${SipUtil.branch()}",
                "From" to dialog.from,
                "To" to dialog.to,
                "Call-ID" to dialog.callId,
                "CSeq" to "${dialog.inviteCseq} ACK",
                "Max-Forwards" to "70",
                "X-GB-Ver" to gbVerHeaderValue
            ),
            headerLines = linkedMapOf<String, MutableList<String>>()
        )
        val routing = buildRoutingTarget(dialog.routeSet, dialog.remoteTargetUri)
        if (routing.routeHeaders.isNotEmpty()) {
            req.headerLines["Route"] = routing.routeHeaders.toMutableList()
            req.headers["Route"] = routing.routeHeaders.joinToString(", ")
        }
        transport.send(dialog.remote, req.encode())
    }

    private fun sendCancel(dialog: InviteDialog) {
        val routing = buildRoutingTarget(dialog.routeSet, dialog.remoteTargetUri)
        val req = SipMessage.Request(
            method = "CANCEL",
            uri = routing.requestUri,
            headers = linkedMapOf(
                // CANCEL 必须复用原 INVITE 的 branch 与数值 CSeq。
                "Via" to "SIP/2.0/$transportToken ${cfg.sip.host}:${cfg.sip.port};branch=${dialog.inviteBranch}",
                "From" to dialog.from,
                "To" to dialog.to,
                "Call-ID" to dialog.callId,
                "CSeq" to "${dialog.inviteCseq} CANCEL",
                "Max-Forwards" to "70",
                "X-GB-Ver" to gbVerHeaderValue
            ),
            headerLines = linkedMapOf<String, MutableList<String>>().apply {
                if (routing.routeHeaders.isNotEmpty()) this["Route"] = routing.routeHeaders.toMutableList()
            }
        )
        if (routing.routeHeaders.isNotEmpty()) {
            req.headers["Route"] = routing.routeHeaders.joinToString(", ")
        }
        transport.send(dialog.remote, req.encode())
        Logger.log("cancel_request_sent callId=${dialog.callId} scene=${dialog.scene}")
    }

    private fun maybeSendPrack(dialog: InviteDialog, resp: SipMessage.Response, remote: SipEndpoint) {
        val require = resp.headers["Require"].orEmpty()
        if (!require.contains("100rel", ignoreCase = true)) return
        val rseq = resp.headers["RSeq"]?.trim()?.toIntOrNull() ?: return
        val sentSet = reliableProvisionalAcked.getOrPut(dialog.callId) { mutableSetOf() }
        if (sentSet.contains(rseq)) return

        val rr = extractHeaderValues(resp, "Record-Route")
        val routeSet = if (rr.isNotEmpty()) rr.reversed() else dialog.routeSet
        val remoteTarget = extractContactUri(resp) ?: dialog.remoteTargetUri
        val routing = buildRoutingTarget(routeSet, remoteTarget)
        val toHeader = resp.headers["To"] ?: dialog.to

        val req = SipMessage.Request(
            method = "PRACK",
            uri = routing.requestUri,
            headers = linkedMapOf(
                "Via" to "SIP/2.0/$transportToken ${cfg.sip.host}:${cfg.sip.port};branch=${SipUtil.branch()}",
                "From" to dialog.from,
                "To" to toHeader,
                "Call-ID" to dialog.callId,
                "CSeq" to SipUtil.cseq(seq.getAndIncrement(), "PRACK"),
                "RAck" to "$rseq ${dialog.inviteCseq} INVITE",
                "Max-Forwards" to "70",
                "X-GB-Ver" to gbVerHeaderValue
            ),
            headerLines = linkedMapOf<String, MutableList<String>>().apply {
                if (routing.routeHeaders.isNotEmpty()) this["Route"] = routing.routeHeaders.toMutableList()
            }
        )
        if (routing.routeHeaders.isNotEmpty()) {
            req.headers["Route"] = routing.routeHeaders.joinToString(", ")
        }
        transport.send(remote, req.encode())
        sentSet += rseq
        Logger.log("prack_request_sent callId=${dialog.callId} rseq=$rseq")
    }

    private fun replySimpleOk(req: SipMessage.Request, remote: SipEndpoint) {
        val resp = SipMessage.Response(
            statusCode = 200,
            reason = "OK",
            headers = buildCommonResponseHeaders(req),
            headerLines = buildCommonResponseHeaderLines(req)
        )
        sendAndCacheResponse(req, remote, resp)
    }

    private fun replyRegisterOk(req: SipMessage.Request, remote: SipEndpoint, expires: Int) {
        val finalExpires = expires.coerceAtLeast(0)
        val contact = req.headers["Contact"].orEmpty()
        val contactWithExpires = when {
            contact.isBlank() -> ""
            Regex(""";\s*expires=""", RegexOption.IGNORE_CASE).containsMatchIn(contact) ->
                Regex(""";\s*expires=\d+""", RegexOption.IGNORE_CASE).replace(contact, ";expires=$finalExpires")
            else -> "$contact;expires=$finalExpires"
        }
        val headers = buildCommonResponseHeaders(req).apply {
            this["Expires"] = finalExpires.toString()
            if (contactWithExpires.isNotBlank()) this["Contact"] = contactWithExpires
        }
        val headerLines = buildCommonResponseHeaderLines(req).apply {
            this["Expires"] = mutableListOf(finalExpires.toString())
            if (contactWithExpires.isNotBlank()) this["Contact"] = mutableListOf(contactWithExpires)
        }
        val resp = SipMessage.Response(
            statusCode = 200,
            reason = "OK",
            headers = headers,
            headerLines = headerLines
        )
        sendAndCacheResponse(req, remote, resp)
    }

    private fun parseRegisterExpires(req: SipMessage.Request): Int {
        val expiresHeader = req.headers["Expires"]?.trim()?.toIntOrNull()
        if (expiresHeader != null) return expiresHeader
        val contact = req.headers["Contact"].orEmpty()
        return Regex(""";\s*expires=(\d+)""", RegexOption.IGNORE_CASE)
            .find(contact)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 3600
    }

    private fun replyOptions(req: SipMessage.Request, remote: SipEndpoint) {
        val headers = buildCommonResponseHeaders(req).apply {
            this["Allow"] = "REGISTER, MESSAGE, SUBSCRIBE, NOTIFY, INVITE, ACK, BYE, CANCEL, OPTIONS, PRACK"
            this["Supported"] = "replaces, timer"
        }
        val headerLines = buildCommonResponseHeaderLines(req).apply {
            this["Allow"] = mutableListOf("REGISTER, MESSAGE, SUBSCRIBE, NOTIFY, INVITE, ACK, BYE, CANCEL, OPTIONS, PRACK")
            this["Supported"] = mutableListOf("replaces, timer")
        }
        val resp = SipMessage.Response(
            statusCode = 200,
            reason = "OK",
            headers = headers,
            headerLines = headerLines
        )
        sendAndCacheResponse(req, remote, resp)
    }

    private fun replyWithStatus(req: SipMessage.Request, remote: SipEndpoint, code: Int, reason: String) {
        Logger.log("request_validate_rejected method=${req.method} code=$code reason=$reason")
        val resp = SipMessage.Response(
            statusCode = code,
            reason = reason,
            headers = buildCommonResponseHeaders(req),
            headerLines = buildCommonResponseHeaderLines(req)
        )
        sendAndCacheResponse(req, remote, resp)
    }

    private fun buildCommonResponseHeaders(req: SipMessage.Request): MutableMap<String, String> = linkedMapOf(
        "Via" to extractViaValues(req).joinToString(", "),
        "From" to req.headers["From"].orEmpty(),
        "To" to req.headers["To"].orEmpty(),
        "Call-ID" to req.headers["Call-ID"].orEmpty(),
        "CSeq" to req.headers["CSeq"].orEmpty(),
        "X-GB-Ver" to gbVerHeaderValue
    )

    private fun buildCommonResponseHeaderLines(req: SipMessage.Request): MutableMap<String, MutableList<String>> {
        val lines = linkedMapOf<String, MutableList<String>>()
        val viaValues = extractViaValues(req)
        if (viaValues.isNotEmpty()) lines["Via"] = viaValues.toMutableList()
        lines["From"] = mutableListOf(req.headers["From"].orEmpty())
        lines["To"] = mutableListOf(req.headers["To"].orEmpty())
        lines["Call-ID"] = mutableListOf(req.headers["Call-ID"].orEmpty())
        lines["CSeq"] = mutableListOf(req.headers["CSeq"].orEmpty())
        lines["X-GB-Ver"] = mutableListOf(gbVerHeaderValue)
        return lines
    }

    private fun sendDigestChallenge(req: SipMessage.Request, remote: SipEndpoint, deviceId: String, stale: Boolean) {
        val nonce = UUID.randomUUID().toString().replace("-", "")
        nonceMap[deviceId] = nonce
        val stalePart = if (stale) ", stale=\"true\"" else ""
        if (stale) {
            Logger.log("auth_digest_challenge deviceId=$deviceId stale=true")
        }
        val resp = SipMessage.Response(
            statusCode = 401,
            reason = "Unauthorized",
            headers = buildCommonResponseHeaders(req).apply {
                this["WWW-Authenticate"] = "Digest realm=\"${cfg.domainId}\", nonce=\"$nonce\"$stalePart"
            },
            headerLines = buildCommonResponseHeaderLines(req).apply {
                this["WWW-Authenticate"] = mutableListOf("Digest realm=\"${cfg.domainId}\", nonce=\"$nonce\"$stalePart")
            }
        )
        sendAndCacheResponse(req, remote, resp)
    }

    private fun sendAndCacheResponse(req: SipMessage.Request, remote: SipEndpoint, resp: SipMessage.Response) {
        val payload = resp.encode()
        transport.send(remote, payload)
        val txKey = buildServerTransactionKey(req) ?: return
        serverTransactionCache[txKey] = CachedResponse(payload, System.currentTimeMillis())
        if (serverTransactionCache.size > 1024) {
            val expiredBefore = System.currentTimeMillis() - 64_000
            serverTransactionCache.entries.removeIf { it.value.createdAt < expiredBefore }
        }
    }

    private fun buildServerTransactionKey(req: SipMessage.Request): String? {
        val callId = req.headers["Call-ID"] ?: return null
        val cseq = req.headers["CSeq"] ?: return null
        val branch = extractViaValues(req).firstOrNull()
            ?.let { Regex("""branch=([^;,\s]+)""").find(it)?.groupValues?.get(1) }
            ?: return null
        return "${req.method.uppercase()}|$callId|$cseq|$branch"
    }

    private fun validateRequest(req: SipMessage.Request): Pair<Int, String>? {
        val via = extractViaValues(req).firstOrNull().orEmpty()
        if (!via.startsWith("SIP/2.0/") || !via.contains("branch=")) return 400 to "Invalid Via/branch"
        val from = req.headers["From"].orEmpty()
        val deviceId = Regex("""sip:(\d+)@""").find(from)?.groupValues?.get(1)
        val cseqHeader = req.headers["CSeq"].orEmpty()
        val cseqNum = cseqHeader.substringBefore(" ").toIntOrNull()
        if (cseqNum == null) return 400 to "Invalid CSeq"
        if (deviceId != null) {
            val last = lastCseqByDevice[deviceId]
            val isRegister = req.method.equals("REGISTER", ignoreCase = true)
            // 设备重启后 REGISTER 的 CSeq 可能回到较小值，这里放宽校验以支持重连场景。
            if (!isRegister && last != null && cseqNum < last) return 500 to "CSeq Out Of Order"
            lastCseqByDevice[deviceId] = cseqNum
        }
        if ((req.method.equals("ACK", true) || req.method.equals("BYE", true))
            && (req.headers["Call-ID"].isNullOrBlank() || req.headers["To"].isNullOrBlank())
        ) return 481 to "Dialog Headers Missing"
        return null
    }

    private fun resolveRegisterEndpoint(req: SipMessage.Request, remote: SipEndpoint): SipEndpoint {
        val via = extractViaValues(req).firstOrNull().orEmpty()
        val rport = Regex("""rport[=\s]*(\d+)""").find(via)?.groupValues?.get(1)?.toIntOrNull()
        if (rport != null) return SipEndpoint(remote.host, rport)

        val contact = req.headers["Contact"].orEmpty()
        val contactMatch = Regex("""sip:[^@]+@([0-9A-Za-z\.\-]+):(\d+)""").find(contact)
        if (contactMatch != null) {
            val host = contactMatch.groupValues[1]
            val port = contactMatch.groupValues[2].toIntOrNull()
            if (port != null) return SipEndpoint(host, port)
        }
        return remote
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

    private fun extractHeaderValues(resp: SipMessage.Response, name: String): List<String> {
        val lines = resp.headerLines[name]
        if (!lines.isNullOrEmpty()) return lines
        val merged = resp.headers[name].orEmpty()
        if (merged.isBlank()) return emptyList()
        return splitSipHeaderValues(merged)
    }

    private fun extractContactUri(resp: SipMessage.Response): String? {
        val contact = extractHeaderValues(resp, "Contact").firstOrNull() ?: return null
        val bracketUri = contact.substringAfter("<", "").substringBefore(">", "")
        if (bracketUri.startsWith("sip:", ignoreCase = true)) return bracketUri
        val plain = contact.substringBefore(";").trim()
        return plain.takeIf { it.startsWith("sip:", ignoreCase = true) }
    }

    private data class RoutingTarget(
        val requestUri: String,
        val routeHeaders: List<String>
    )

    /**
     * RFC3261 路由规则：
     * - 松路由（首个 route 带 lr）：request-uri 使用 remote target，Route 使用 route set
     * - 严路由（无 lr）：request-uri 使用首个 route URI，Route 使用剩余 route + remote target
     */
    private fun buildRoutingTarget(routeSet: List<String>, remoteTargetUri: String): RoutingTarget {
        if (routeSet.isEmpty()) return RoutingTarget(remoteTargetUri, emptyList())
        val first = routeSet.first()
        val hasLr = first.contains(";lr", ignoreCase = true) || first.contains(";lr>", ignoreCase = true)
        return if (hasLr) {
            RoutingTarget(remoteTargetUri, routeSet)
        } else {
            val firstUri = extractUriFromRoute(first) ?: remoteTargetUri
            val routes = routeSet.drop(1).toMutableList().apply {
                add("<$remoteTargetUri>")
            }
            RoutingTarget(firstUri, routes)
        }
    }

    private fun extractUriFromRoute(route: String): String? {
        val bracket = route.substringAfter("<", "").substringBefore(">", "")
        if (bracket.startsWith("sip:", ignoreCase = true)) return bracket
        val plain = route.substringBefore(";").trim()
        return plain.takeIf { it.startsWith("sip:", ignoreCase = true) }
    }

    private fun startHeartbeatMonitor() {
        scope.launch {
            while (true) {
                delay(15_000)
                val now = System.currentTimeMillis()
                val timeoutMs = 90_000L
                val stale = lastHeartbeatAt.filterValues { now - it > timeoutMs }.keys
                stale.forEach { dev ->
                    lastHeartbeatAt.remove(dev)
                    deviceAddressMap.remove(dev)
                    Logger.log("heartbeat_timeout_offline id=$dev")
                }
            }
        }
    }
}
