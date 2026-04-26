package com.goway.gb28181

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// 统一日志组件：同时写控制台与文件，并兼容交互模式提示符输出。
object Logger {
    private val fileRef = AtomicReference<File?>(null)
    private val tsFmt = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val lineFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    @Volatile private var consoleOutputEnabled: Boolean = true
    @Volatile private var activePrompt: String? = null
    private val promptScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "logger-prompt-scheduler").apply { isDaemon = true }
    }
    @Volatile private var promptFuture: ScheduledFuture<*>? = null

    fun init(mode: String, vendor: VendorProfile, version: GbVersion, protocol: SipTransportProtocol) {
        val dir = File("logs")
        if (!dir.exists()) dir.mkdirs()
        val file = File(
            dir,
            "logs-${vendor.name.lowercase()}-${version.name.lowercase()}-${protocol.name.lowercase()}-$mode-${LocalDateTime.now().format(tsFmt)}.log"
        )
        fileRef.set(file)
        log("session_start mode=$mode vendor=$vendor version=$version protocol=$protocol")
    }

    fun log(message: String) {
        val role = detectRole(message)
        val line = "${LocalDateTime.now().format(lineFmt)} | [$role] $message"
        if (consoleOutputEnabled) {
            synchronized(this) {
                val prompt = activePrompt
                if (prompt.isNullOrBlank()) {
                    println(line)
                } else {
                    // 日志单独占行输出，提示符延迟恢复，减少与连续日志交错。
                    print("\r")
                    println(line)
                    promptFuture?.cancel(false)
                    promptFuture = promptScheduler.schedule({
                        synchronized(this) {
                            val latestPrompt = activePrompt
                            if (!latestPrompt.isNullOrBlank()) {
                                print(latestPrompt)
                            }
                        }
                    }, 1000, TimeUnit.MILLISECONDS)
                }
            }
        }
        val file = fileRef.get() ?: return
        synchronized(this) {
            file.appendText(line + System.lineSeparator())
        }
    }

    fun setConsoleOutput(enabled: Boolean) {
        consoleOutputEnabled = enabled
    }

    fun setActivePrompt(prompt: String?) {
        activePrompt = prompt
    }

    // 在命令循环手动打印提示符前调用，避免与延迟补提示符叠加。
    fun cancelPendingPromptEcho() {
        synchronized(this) {
            promptFuture?.cancel(false)
            promptFuture = null
        }
    }

    private fun detectRole(message: String): String {
        val m = message.lowercase()
        if (
            m.startsWith("client_") ||
            m.startsWith("register_client_") ||
            m.startsWith("unregister_client_") ||
            m.startsWith("manual_keepalive_") ||
            m.startsWith("ps_rtp_") ||
            m.startsWith("notify_request_") ||
            m.startsWith("invite_simulate_") ||
            m.startsWith("invite_completed_timeout_terminated") ||
            m.startsWith("control_request_detail") ||
            m.startsWith("media_stream_")
        ) return "CLIENT"

        if (
            m.startsWith("server_") ||
            m.startsWith("device_state_") ||
            m.startsWith("register_state_") ||
            m.startsWith("register_request_") ||
            m.startsWith("unregister_request_") ||
            m.startsWith("query_") ||
            m.startsWith("demo_flow_") ||
            m.startsWith("subscribe_") ||
            m.startsWith("channel_set_") ||
            m.startsWith("auth_digest_") ||
            m.startsWith("request_validate_") ||
            m.startsWith("heartbeat_timeout_offline") ||
            m.startsWith("invite_") ||
            m.startsWith("bye_") ||
            m.startsWith("cancel_request_") ||
            m.startsWith("prack_request_") ||
            m.startsWith("control_request_")
        ) return "SERVER"

        return "MIXED"
    }
}
