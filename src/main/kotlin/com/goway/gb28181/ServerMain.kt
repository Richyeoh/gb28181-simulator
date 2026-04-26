package com.goway.gb28181

import kotlin.system.exitProcess

// 服务端独立启动入口，便于外接设备/客户端进行单端联调。
fun main(args: Array<String>) {
    val versionArg = args.firstOrNull()?.lowercase() ?: "2016"
    val protocolArg = args.getOrNull(1)?.lowercase() ?: "udp"
    val vendorArg = args.getOrNull(2)?.lowercase() ?: "hikvision"
    val strategyFile = args.getOrNull(3)
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
    Logger.init("server", vendor, version, protocol)

    val domain = "3402000000"
    val deviceId = "34020000001320000001"
    val serverId = "34020000002000000001"
    val password = "12345678"
    val channelId = "${deviceId}01"

    val server = Gb28181ServerSimulator(
        ServerConfig(
            serverId = serverId,
            domainId = domain,
            passwordLookup = mapOf(deviceId to password),
            sip = SipEndpoint("0.0.0.0", 5060),
            transport = protocol,
            vendor = vendor,
            strategy = strategy,
            version = version
        )
    )
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    Logger.log("simulator_running mode=server protocol=$protocol vendor=$vendor version=$version")

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
            "", "help", "h", "?" -> printServerHelp()
            "status" -> println(server.statusSummary())
            "demo" -> {
                val started = server.runDemoFlow(deviceId, channelId)
                if (started)
                    println("demo started: deviceId=$deviceId")
                else
                    println("demo not started: device not registered or demo already running")
            }
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
            "exit", "quit", "q" -> {
                server.stop()
                exitProcess(0)
            }
            else -> println("Unknown command: $cmd (type 'help')")
        }
    }
}

private fun printServerHelp() {
    println(
        """
        Server commands:
          [system]
          help
          status
          demo
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
          i-talk start [deviceId] [channelId] [rtpPort]
          i-talk stop [deviceId] [channelId]
          i-play start [deviceId] [channelId] [rtpPort]
          i-play stop [deviceId] [channelId]
          i-playback start [deviceId] [channelId] [rtpPort]
          i-playback stop [deviceId] [channelId]
          i-download start [deviceId] [channelId] [rtpPort]
          i-download stop [deviceId] [channelId]
          
          [misc]
          bye [deviceId] [channelId]
          bye-all [deviceId] [channelId]
        """.trimIndent()
    )
}
