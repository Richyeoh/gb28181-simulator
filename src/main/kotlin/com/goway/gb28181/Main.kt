package com.goway.gb28181

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

// 统一启动入口：支持 all/fault/server/client 模式与同一套交互命令体验。
private data class InteractiveClientCtx(
    val clientId: String,
    val deviceId: String,
    val localSipPort: Int,
    val client: Gb28181ClientSimulator
)

fun main(args: Array<String>) {
    val mode = args.firstOrNull()?.lowercase() ?: "all"
    val versionArg = args.getOrNull(1)?.lowercase() ?: "2016"
    val protocolArg = args.getOrNull(2)?.lowercase() ?: "udp"
    val vendorArg = args.getOrNull(3)?.lowercase() ?: "hikvision"
    val strategyFile = args.getOrNull(4)
    val version = if (versionArg.contains("2022")) GbVersion.V2022 else GbVersion.V2016
    val protocol = when (protocolArg) {
        "tcp" -> SipTransportProtocol.TCP
        "tls" -> SipTransportProtocol.TLS
        else -> SipTransportProtocol.UDP
    }
    val vendor = when (vendorArg) {
        "dahua" -> VendorProfile.DAHUA
        "uniview", "ys", "yushi" -> VendorProfile.UNIVIEW
        else -> VendorProfile.HIKVISION
    }
    val strategy = loadVendorStrategy(vendor, version, strategyFile)
    Logger.init(mode, vendor, version, protocol)

    val domain = "3402000000"
    val deviceId = "34020000001320000001"
    val serverId = "34020000002000000001"
    val password = "12345678"

    val server = Gb28181ServerSimulator(
        ServerConfig(
            serverId = serverId,
            domainId = domain,
            passwordLookup = mapOf(deviceId to password),
            sip = SipEndpoint("127.0.0.1", 5060),
            transport = protocol,
            vendor = vendor,
            strategy = strategy,
            version = version
        )
    )

    val client = Gb28181ClientSimulator(
        DeviceConfig(
            deviceId = deviceId,
            domainId = domain,
            password = password,
            localSip = SipEndpoint("127.0.0.1", 5061),
            serverSip = SipEndpoint("127.0.0.1", 5060),
            transport = protocol,
            vendor = vendor,
            strategy = strategy,
            version = version
        )
    )
    val interactiveClients = linkedMapOf<String, InteractiveClientCtx>()
    val clientSeq = AtomicInteger(1)
    val clientIdOfDefault = "client-${clientSeq.getAndIncrement()}"
    interactiveClients[clientIdOfDefault] = InteractiveClientCtx(
        clientId = clientIdOfDefault,
        deviceId = deviceId,
        localSipPort = 5061,
        client = client
    )
    val createInteractiveClient: (String, Int, String) -> InteractiveClientCtx = { newDeviceId, localPort, pwd ->
        val newClientId = "client-${clientSeq.getAndIncrement()}"
        val newClient = Gb28181ClientSimulator(
            DeviceConfig(
                deviceId = newDeviceId,
                domainId = domain,
                password = pwd,
                localSip = SipEndpoint("127.0.0.1", localPort),
                serverSip = SipEndpoint("127.0.0.1", 5060),
                transport = protocol,
                vendor = vendor,
                strategy = strategy,
                version = version
            )
        )
        InteractiveClientCtx(
            clientId = newClientId,
            deviceId = newDeviceId,
            localSipPort = localPort,
            client = newClient
        )
    }

    when (mode) {
        "server" -> {
            runServerConsole(server, deviceId, "${deviceId}01", startWithHelp = false)
        }
        "client" -> {
            runClientConsole(client, startWithHelp = false)
        }
        "fault" -> {
            runInteractiveConsole(
                server = server,
                clients = interactiveClients,
                defaultClientId = clientIdOfDefault,
                defaultDeviceId = deviceId,
                defaultChannelId = "${deviceId}01",
                createInteractiveClient = createInteractiveClient,
                startWithHelp = false
            )
        }
        else -> {
            runInteractiveConsole(
                server = server,
                clients = interactiveClients,
                defaultClientId = clientIdOfDefault,
                defaultDeviceId = deviceId,
                defaultChannelId = "${deviceId}01",
                createInteractiveClient = createInteractiveClient,
                startWithHelp = false
            )
        }
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        interactiveClients.values.forEach { it.client.stop() }
        server.stop()
    })

    Logger.log("simulator_running mode=$mode protocol=$protocol vendor=$vendor version=$version")
    if (mode == "server" || mode == "client") {
        CountDownLatch(1).await()
    }
}

private fun runInteractiveConsole(
    server: Gb28181ServerSimulator,
    clients: MutableMap<String, InteractiveClientCtx>,
    defaultClientId: String,
    defaultDeviceId: String,
    defaultChannelId: String,
    createInteractiveClient: (String, Int, String) -> InteractiveClientCtx,
    startWithHelp: Boolean
) {
    fun printHelp() {
        println(
            """
            Interactive commands:
              [system]
              help
              demo
              status
              exit

              [client]
              client new [deviceId] [localSipPort] [password]
              client list
              client use [clientId]
              client register [clientId]
              client keepalive [clientId]
              client keepalive loop [clientId] [sec]
              client keepalive stop [clientId]
              client unregister [clientId]
              client fault [clientId]

              [catalog]
              channel list [deviceId|clientId]
              channel set [deviceId|clientId] [count]

              [query]
              q-info [deviceId|clientId]
              q-status [deviceId|clientId]
              q-record [deviceId|clientId]
              q-alarm [deviceId|clientId]
              q-cfg-down [deviceId|clientId]
              q-cfg-up [deviceId|clientId]

              [control]
              c-ptz [deviceId|clientId] [channelId] [hex]
              c-zoom-in [deviceId|clientId] [channelId]
              c-zoom-out [deviceId|clientId] [channelId]
              c-home-on [deviceId|clientId] [channelId]
              c-home-off [deviceId|clientId] [channelId]
              c-reboot [deviceId|clientId]

              [subscribe]
              s-alarm [deviceId|clientId] [expires]
              s-mobile [deviceId|clientId] [expires]
              s-catalog [deviceId|clientId] [expires]

              [media]
              bye [deviceId|clientId] [channelId]
              bye-all [deviceId|clientId] [channelId]
              i-talk start [deviceId|clientId] [channelId] [rtpPort]
              i-talk stop [deviceId|clientId] [channelId]
              i-play start [deviceId|clientId] [channelId] [rtpPort]
              i-play stop [deviceId|clientId] [channelId]
              i-playback start [deviceId|clientId] [channelId] [rtpPort]
              i-playback stop [deviceId|clientId] [channelId]
              i-download start [deviceId|clientId] [channelId] [rtpPort]
              i-download stop [deviceId|clientId] [channelId]
            """.trimIndent()
        )
    }

    if (startWithHelp) printHelp()
    server.start()
    clients[defaultClientId]?.client?.start(autoRegister = false, autoBackgroundTasks = false)
    var activeClientId = defaultClientId
    while (true) {
        Logger.setActivePrompt("gb28181> ")
        Logger.cancelPendingPromptEcho()
        print("gb28181> ")
        val raw = readlnOrNull() ?: continue
        val line = raw.trim()
        val parts = line.split(Regex("\\s+")).filter { it.isNotBlank() }
        val cmd = parts.firstOrNull()?.lowercase() ?: ""
        val subCmd = parts.getOrNull(1)?.lowercase() ?: ""
        val arg = { idx: Int, fallback: String -> parts.getOrNull(idx) ?: fallback }
        val argInt = { idx: Int, fallback: Int -> parts.getOrNull(idx)?.toIntOrNull() ?: fallback }
        val activeDeviceId = clients[activeClientId]?.deviceId ?: defaultDeviceId
        val resolveDeviceId: (String?) -> String? = { idOrClient ->
            when {
                idOrClient == null -> activeDeviceId
                clients.containsKey(idOrClient) -> clients[idOrClient]?.deviceId
                idOrClient.startsWith("client-") -> null
                else -> idOrClient
            }
        }
        val deviceIdArg = parts.getOrNull(1)
        val resolvedDeviceId = resolveDeviceId(deviceIdArg)
        val resolvedDefaultChannelId = resolvedDeviceId?.let { "${it}01" }
        val printClientNotFound = { token: String? -> println("Client not found: ${token ?: "null"}") }
        when (cmd) {
            "", "help", "h", "?" -> printHelp()
            "demo" -> {
                val demoDeviceId = activeDeviceId
                val demoChannelId = "${demoDeviceId}01"
                val started = server.runDemoFlow(demoDeviceId, demoChannelId)
                if (started)
                    println("demo started: deviceId=$demoDeviceId")
                else
                    println("demo not started: device not registered or demo already running")
            }
            "client" -> {
                when (subCmd) {
                    "new" -> {
                        val newDeviceId = parts.getOrNull(2) ?: "340200000013200000${(clients.size + 1).toString().padStart(2, '0')}"
                        val localPort = parts.getOrNull(3)?.toIntOrNull() ?: (5061 + clients.size)
                        val pwd = parts.getOrNull(4) ?: "12345678"
                        val newCtx = createInteractiveClient(newDeviceId, localPort, pwd)
                        newCtx.client.start(autoRegister = false, autoBackgroundTasks = false)
                        clients[newCtx.clientId] = newCtx
                        println("new-client created: clientId=${newCtx.clientId} deviceId=${newCtx.deviceId} localSipPort=${newCtx.localSipPort}")
                    }
                    "list" -> {
                        clients.values.forEach {
                            val registered = Regex("""registered=(true|false)""")
                                .find(it.client.statusSummary())
                                ?.groupValues
                                ?.get(1)
                                ?: "unknown"
                            val marker = if (it.clientId == activeClientId) "*" else " "
                            println("$marker ${it.clientId} -> registered=$registered deviceId=${it.deviceId} localSipPort=${it.localSipPort}")
                        }
                    }
                    "use" -> {
                        val targetClientId = parts.getOrNull(2) ?: activeClientId
                        if (!clients.containsKey(targetClientId)) {
                            println("Client not found: $targetClientId")
                        } else {
                            activeClientId = targetClientId
                            println("active-client switched: $activeClientId")
                        }
                    }
                    "register" -> {
                        val targetClientId = parts.getOrNull(2) ?: activeClientId
                        val targetClient = clients[targetClientId]
                        if (targetClient == null) println("Client not found: $targetClientId")
                        else targetClient.client.triggerRegister()
                    }
                    "keepalive" -> {
                        val keepaliveAction = parts.getOrNull(2)?.lowercase()
                        when (keepaliveAction) {
                            "loop" -> {
                                val firstArg = parts.getOrNull(3)
                                val firstAsInt = firstArg?.toIntOrNull()
                                val loopClientId = if (firstAsInt != null) activeClientId else (firstArg ?: activeClientId)
                                val loopClientCtx = clients[loopClientId]
                                if (loopClientCtx == null) {
                                    println("Client not found: $loopClientId")
                                } else {
                                    val sec = if (firstAsInt != null) firstAsInt else (parts.getOrNull(4)?.toIntOrNull() ?: 20)
                                    loopClientCtx.client.startManualKeepaliveLoop(sec)
                                }
                            }
                            "stop" -> {
                                val targetClientId = parts.getOrNull(3) ?: activeClientId
                                val targetClient = clients[targetClientId]
                                if (targetClient == null) println("Client not found: $targetClientId")
                                else targetClient.client.stopManualKeepaliveLoop()
                            }
                            else -> {
                                val targetClientId = parts.getOrNull(2) ?: activeClientId
                                val targetClient = clients[targetClientId]
                                if (targetClient == null) println("Client not found: $targetClientId")
                                else targetClient.client.triggerKeepalive()
                            }
                        }
                    }
                    "unregister" -> {
                        val targetClientId = parts.getOrNull(2) ?: activeClientId
                        val targetClient = clients[targetClientId]
                        if (targetClient == null) println("Client not found: $targetClientId")
                        else targetClient.client.triggerDeregister()
                    }
                    "fault" -> {
                        val targetClientId = parts.getOrNull(2) ?: activeClientId
                        val targetClient = clients[targetClientId]
                        if (targetClient == null) println("Client not found: $targetClientId")
                        else targetClient.client.runFaultInjectionFlow()
                    }
                    else -> println("Unknown client subcommand: $subCmd")
                }
            }
            "status" -> {
                println(server.statusSummary())
                clients.values.forEach {
                    println("${it.clientId} deviceId=${it.deviceId} localSipPort=${it.localSipPort} ${it.client.statusSummary()}")
                }
            }
            "channel" -> {
                val isSet = subCmd == "set"
                val isList = subCmd == "list"
                if (isSet) {
                    val targetDeviceId = resolveDeviceId(parts.getOrNull(2))
                    if (targetDeviceId == null) {
                        printClientNotFound(parts.getOrNull(2))
                    } else {
                        val count = parts.getOrNull(3)?.toIntOrNull() ?: 1
                        println(server.setChannelCount(targetDeviceId, count).joinToString(", "))
                    }
                } else if (isList) {
                    val targetDeviceId = resolveDeviceId(parts.getOrNull(2))
                    if (targetDeviceId == null) {
                        printClientNotFound(parts.getOrNull(2))
                    } else {
                        println(server.listChannels(targetDeviceId).joinToString(", "))
                    }
                } else {
                    println("Usage: channel list [deviceId|clientId] | channel set [deviceId|clientId] [count]")
                }
            }
            "q-info" -> if (resolvedDeviceId == null) printClientNotFound(deviceIdArg) else server.queryDeviceInfo(resolvedDeviceId)
            "q-status" -> if (resolvedDeviceId == null) printClientNotFound(deviceIdArg) else server.queryDeviceStatus(resolvedDeviceId)
            "q-record" -> if (resolvedDeviceId == null) printClientNotFound(deviceIdArg) else server.queryRecordInfo(resolvedDeviceId)
            "q-alarm" -> if (resolvedDeviceId == null) printClientNotFound(deviceIdArg) else server.queryAlarm(resolvedDeviceId)
            "q-cfg-down" -> if (resolvedDeviceId == null) printClientNotFound(deviceIdArg) else server.queryConfigDownload(resolvedDeviceId)
            "q-cfg-up" -> if (resolvedDeviceId == null) printClientNotFound(deviceIdArg) else server.queryConfigUpload(resolvedDeviceId)
            "c-ptz" -> {
                if (resolvedDeviceId == null || resolvedDefaultChannelId == null) {
                    printClientNotFound(deviceIdArg)
                } else {
                    server.controlPtz(resolvedDeviceId, arg(2, resolvedDefaultChannelId), arg(3, "A50F010000"))
                }
            }
            "c-zoom-in" -> {
                if (resolvedDeviceId == null || resolvedDefaultChannelId == null) {
                    printClientNotFound(deviceIdArg)
                } else {
                    server.controlZoomFocus(resolvedDeviceId, arg(2, resolvedDefaultChannelId), zoomIn = true)
                }
            }
            "c-zoom-out" -> {
                if (resolvedDeviceId == null || resolvedDefaultChannelId == null) {
                    printClientNotFound(deviceIdArg)
                } else {
                    server.controlZoomFocus(resolvedDeviceId, arg(2, resolvedDefaultChannelId), zoomIn = false)
                }
            }
            "c-home-on" -> {
                if (resolvedDeviceId == null || resolvedDefaultChannelId == null) {
                    printClientNotFound(deviceIdArg)
                } else {
                    server.controlHomePosition(resolvedDeviceId, arg(2, resolvedDefaultChannelId), enabled = true)
                }
            }
            "c-home-off" -> {
                if (resolvedDeviceId == null || resolvedDefaultChannelId == null) {
                    printClientNotFound(deviceIdArg)
                } else {
                    server.controlHomePosition(resolvedDeviceId, arg(2, resolvedDefaultChannelId), enabled = false)
                }
            }
            "c-reboot" -> if (resolvedDeviceId == null) printClientNotFound(deviceIdArg) else server.controlDevice(resolvedDeviceId, "TeleBoot")
            "s-alarm" -> if (resolvedDeviceId == null) printClientNotFound(deviceIdArg) else server.subscribeAlarm(resolvedDeviceId, argInt(2, 3600))
            "s-mobile" -> if (resolvedDeviceId == null) printClientNotFound(deviceIdArg) else server.subscribeMobilePosition(resolvedDeviceId, argInt(2, 3600))
            "s-catalog" -> if (resolvedDeviceId == null) printClientNotFound(deviceIdArg) else server.subscribeCatalog(resolvedDeviceId, argInt(2, 3600))
            "bye" -> {
                val deviceId = resolveDeviceId(parts.getOrNull(1))
                if (deviceId == null) {
                    printClientNotFound(parts.getOrNull(1))
                } else {
                    val channel = parts.getOrNull(2) ?: "${deviceId}01"
                    server.sendBye(deviceId, channel)
                }
            }
            "bye-all" -> {
                val deviceId = resolveDeviceId(parts.getOrNull(1))
                if (deviceId == null) {
                    printClientNotFound(parts.getOrNull(1))
                } else {
                    val channel = parts.getOrNull(2) ?: "${deviceId}01"
                    server.sendByeAll(deviceId, channel)
                }
            }
            "i-play" -> {
                val action = subCmd
                val deviceId = resolveDeviceId(parts.getOrNull(2))
                if (deviceId == null) {
                    printClientNotFound(parts.getOrNull(2))
                } else {
                    val defaultChannel = "${deviceId}01"
                    val channel = parts.getOrNull(3) ?: defaultChannel
                    when (action) {
                        "stop" -> server.sendBye(deviceId, channel)
                        "start" -> {
                            val rtpPort = parts.getOrNull(4)?.toIntOrNull() ?: 15060
                            server.invitePlay(deviceId, channel, rtpPort)
                        }
                        else -> println("Usage: i-play start|stop [deviceId|clientId] [channelId] [rtpPort]")
                    }
                }
            }
            "i-playback" -> {
                val action = subCmd
                val deviceId = resolveDeviceId(parts.getOrNull(2))
                if (deviceId == null) {
                    printClientNotFound(parts.getOrNull(2))
                } else {
                    val defaultChannel = "${deviceId}01"
                    val channel = parts.getOrNull(3) ?: defaultChannel
                    when (action) {
                        "stop" -> server.sendBye(deviceId, channel)
                        "start" -> {
                            val rtpPort = parts.getOrNull(4)?.toIntOrNull() ?: 15062
                            server.invitePlayback(deviceId, channel, rtpPort)
                        }
                        else -> println("Usage: i-playback start|stop [deviceId|clientId] [channelId] [rtpPort]")
                    }
                }
            }
            "i-download" -> {
                val action = subCmd
                val deviceId = resolveDeviceId(parts.getOrNull(2))
                if (deviceId == null) {
                    printClientNotFound(parts.getOrNull(2))
                } else {
                    val defaultChannel = "${deviceId}01"
                    val channel = parts.getOrNull(3) ?: defaultChannel
                    when (action) {
                        "stop" -> server.sendBye(deviceId, channel)
                        "start" -> {
                            val rtpPort = parts.getOrNull(4)?.toIntOrNull() ?: 15064
                            server.inviteDownload(deviceId, channel, rtpPort)
                        }
                        else -> println("Usage: i-download start|stop [deviceId|clientId] [channelId] [rtpPort]")
                    }
                }
            }
            "i-talk" -> {
                val action = subCmd
                val deviceId = resolveDeviceId(parts.getOrNull(2))
                if (deviceId == null) {
                    printClientNotFound(parts.getOrNull(2))
                } else {
                    val defaultChannel = "${deviceId}01"
                    val channel = parts.getOrNull(3) ?: defaultChannel
                    when (action) {
                        "stop" -> server.sendBye(deviceId, channel)
                        "start" -> {
                            val rtpPort = parts.getOrNull(4)?.toIntOrNull() ?: 15066
                            server.inviteTalkback(deviceId, channel, rtpPort)
                        }
                        else -> println("Usage: i-talk start|stop [deviceId|clientId] [channelId] [rtpPort]")
                    }
                }
            }
            "exit", "quit", "q" -> {
                clients.values.forEach { it.client.stop() }
                server.stop()
                exitProcess(0)
            }
            else -> println("Unknown command: $cmd (type 'help')")
        }
    }
}

private fun runServerConsole(
    server: Gb28181ServerSimulator,
    deviceId: String,
    channelId: String,
    startWithHelp: Boolean
) {
    fun printHelp() {
        println(
            """
            Server commands:
              [system]
              help
              demo
              status
              exit

              [catalog]
              channel list [deviceId]
              channel set [deviceId] [count]

              [query]
              q-info [deviceId]
              q-status [deviceId]
              q-record [deviceId]
              q-alarm [deviceId]
              q-cfg-down [deviceId]
              q-cfg-up [deviceId]

              [control]
              c-ptz [deviceId] [channelId] [hex]
              c-zoom-in [deviceId] [channelId]
              c-zoom-out [deviceId] [channelId]
              c-home-on [deviceId] [channelId]
              c-home-off [deviceId] [channelId]
              c-reboot [deviceId]

              [subscribe]
              s-alarm [deviceId] [expires]
              s-mobile [deviceId] [expires]
              s-catalog [deviceId] [expires]

              [media]
              bye [deviceId] [channelId]
              bye-all [deviceId] [channelId]
              i-talk start [deviceId] [channelId] [rtpPort]
              i-talk stop [deviceId] [channelId]
              i-play start [deviceId] [channelId] [rtpPort]
              i-play stop [deviceId] [channelId]
              i-playback start [deviceId] [channelId] [rtpPort]
              i-playback stop [deviceId] [channelId]
              i-download start [deviceId] [channelId] [rtpPort]
              i-download stop [deviceId] [channelId]
            """.trimIndent()
        )
    }
    if (startWithHelp) printHelp()
    server.start()
    while (true) {
        Logger.setActivePrompt("gb28181-server> ")
        Logger.cancelPendingPromptEcho()
        print("gb28181-server> ")
        val raw = readlnOrNull() ?: continue
        val line = raw.trim()
        val parts = line.split(Regex("\\s+")).filter { it.isNotBlank() }
        val cmd = parts.firstOrNull()?.lowercase() ?: ""
        val subCmd = parts.getOrNull(1)?.lowercase() ?: ""
        val arg = { idx: Int, fallback: String -> parts.getOrNull(idx) ?: fallback }
        val argInt = { idx: Int, fallback: Int -> parts.getOrNull(idx)?.toIntOrNull() ?: fallback }
        when (cmd) {
            "", "help", "h", "?" -> printHelp()
            "demo" -> {
                val started = server.runDemoFlow(deviceId, channelId)
                if (started) println("demo started: deviceId=$deviceId")
                else println("demo not started: device not registered or demo already running")
            }
            "status" -> println(server.statusSummary())
            "channel" -> {
                when (subCmd) {
                    "list" -> println(server.listChannels(arg(2, deviceId)).joinToString(", "))
                    "set" -> println(server.setChannelCount(arg(2, deviceId), argInt(3, 1)).joinToString(", "))
                    else -> println("Usage: channel list [deviceId] | channel set [deviceId] [count]")
                }
            }
            "q-info" -> server.queryDeviceInfo(arg(1, deviceId))
            "q-status" -> server.queryDeviceStatus(arg(1, deviceId))
            "q-record" -> server.queryRecordInfo(arg(1, deviceId))
            "q-alarm" -> server.queryAlarm(arg(1, deviceId))
            "q-cfg-down" -> server.queryConfigDownload(arg(1, deviceId))
            "q-cfg-up" -> server.queryConfigUpload(arg(1, deviceId))
            "c-ptz" -> server.controlPtz(arg(1, deviceId), arg(2, channelId), arg(3, "A50F010000"))
            "c-zoom-in" -> server.controlZoomFocus(arg(1, deviceId), arg(2, channelId), zoomIn = true)
            "c-zoom-out" -> server.controlZoomFocus(arg(1, deviceId), arg(2, channelId), zoomIn = false)
            "c-home-on" -> server.controlHomePosition(arg(1, deviceId), arg(2, channelId), enabled = true)
            "c-home-off" -> server.controlHomePosition(arg(1, deviceId), arg(2, channelId), enabled = false)
            "c-reboot" -> server.controlDevice(arg(1, deviceId), "TeleBoot")
            "s-alarm" -> server.subscribeAlarm(arg(1, deviceId), argInt(2, 3600))
            "s-mobile" -> server.subscribeMobilePosition(arg(1, deviceId), argInt(2, 3600))
            "s-catalog" -> server.subscribeCatalog(arg(1, deviceId), argInt(2, 3600))
            "bye" -> server.sendBye(arg(1, deviceId), arg(2, channelId))
            "bye-all" -> server.sendByeAll(arg(1, deviceId), arg(2, channelId))
            "i-talk" -> {
                when (subCmd) {
                    "start" -> server.inviteTalkback(arg(2, deviceId), arg(3, channelId), argInt(4, 15066))
                    "stop" -> server.sendBye(arg(2, deviceId), arg(3, channelId))
                    else -> println("Usage: i-talk start|stop [deviceId] [channelId] [rtpPort]")
                }
            }
            "i-play" -> {
                when (subCmd) {
                    "start" -> server.invitePlay(arg(2, deviceId), arg(3, channelId), argInt(4, 15060))
                    "stop" -> server.sendBye(arg(2, deviceId), arg(3, channelId))
                    else -> println("Usage: i-play start|stop [deviceId] [channelId] [rtpPort]")
                }
            }
            "i-playback" -> {
                when (subCmd) {
                    "start" -> server.invitePlayback(arg(2, deviceId), arg(3, channelId), argInt(4, 15062))
                    "stop" -> server.sendBye(arg(2, deviceId), arg(3, channelId))
                    else -> println("Usage: i-playback start|stop [deviceId] [channelId] [rtpPort]")
                }
            }
            "i-download" -> {
                when (subCmd) {
                    "start" -> server.inviteDownload(arg(2, deviceId), arg(3, channelId), argInt(4, 15064))
                    "stop" -> server.sendBye(arg(2, deviceId), arg(3, channelId))
                    else -> println("Usage: i-download start|stop [deviceId] [channelId] [rtpPort]")
                }
            }
            "exit", "quit", "q" -> exitProcess(0)
            else -> println("Unknown command: $cmd (type 'help')")
        }
    }
}

private fun runClientConsole(
    client: Gb28181ClientSimulator,
    startWithHelp: Boolean
) {
    fun printHelp() {
        println(
            """
            Client commands:
              help
              status
              exit
              client register
              client keepalive
              client keepalive loop [sec]
              client keepalive stop
              client unregister
              client fault
            """.trimIndent()
        )
    }
    if (startWithHelp) printHelp()
    client.start(autoRegister = false, autoBackgroundTasks = false)
    while (true) {
        Logger.setActivePrompt("gb28181-client> ")
        Logger.cancelPendingPromptEcho()
        print("gb28181-client> ")
        val raw = readlnOrNull() ?: continue
        val parts = raw.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val cmd = parts.firstOrNull()?.lowercase() ?: ""
        val subCmd = parts.getOrNull(1)?.lowercase() ?: ""
        val argInt = { idx: Int, fallback: Int -> parts.getOrNull(idx)?.toIntOrNull() ?: fallback }
        when (cmd) {
            "", "help", "h", "?" -> printHelp()
            "client" -> {
                when (subCmd) {
                    "register" -> client.triggerRegister()
                    "keepalive" -> {
                        when (parts.getOrNull(2)?.lowercase()) {
                            "loop" -> client.startManualKeepaliveLoop(argInt(3, 20))
                            "stop" -> client.stopManualKeepaliveLoop()
                            else -> client.triggerKeepalive()
                        }
                    }
                    "unregister" -> client.triggerDeregister()
                    "fault" -> client.runFaultInjectionFlow()
                    else -> println("Unknown client subcommand: $subCmd")
                }
            }
            "status" -> println(client.statusSummary())
            "exit", "quit", "q" -> exitProcess(0)
            else -> println("Unknown command: $cmd (type 'help')")
        }
    }
}
