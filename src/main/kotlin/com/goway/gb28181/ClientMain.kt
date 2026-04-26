package com.goway.gb28181

import kotlin.system.exitProcess

// 客户端独立启动入口，便于设备侧行为与故障路径验证。
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
    Logger.init("client", vendor, version, protocol)

    val domain = "3402000000"
    val deviceId = "34020000001320000001"
    val password = "12345678"

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
    Runtime.getRuntime().addShutdownHook(Thread { client.stop() })
    Logger.log("simulator_running mode=client protocol=$protocol vendor=$vendor version=$version")

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
            "", "help", "h", "?" -> printClientHelp()
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
            "exit", "quit", "q" -> {
                client.stop()
                exitProcess(0)
            }
            else -> println("Unknown command: $cmd (type 'help')")
        }
    }
}

private fun printClientHelp() {
    println(
        """
        Client commands:
          [system]
          help
          status
          exit

          [client]
          client register
          client keepalive
          client keepalive loop [sec]
          client keepalive stop
          client unregister
          client fault
        """.trimIndent()
    )
}
