package com.goway.gb28181

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File

// 模拟器的集中配置模型，包含协议/版本/厂商策略等参数。
enum class GbVersion {
    V2016,
    V2022
}

enum class SipTransportProtocol {
    UDP,
    TCP,
    TLS
}

enum class VendorProfile {
    HIKVISION,
    DAHUA,
    UNIVIEW
}

data class SipEndpoint(
    val host: String,
    val port: Int
)

data class DeviceConfig(
    val deviceId: String,
    val domainId: String,
    val password: String,
    val localSip: SipEndpoint,
    val serverSip: SipEndpoint,
    val transport: SipTransportProtocol = SipTransportProtocol.UDP,
    val vendor: VendorProfile = VendorProfile.HIKVISION,
    val strategy: VendorStrategy = defaultVendorStrategy(VendorProfile.HIKVISION, GbVersion.V2016),
    val version: GbVersion = GbVersion.V2016
) {
    // 按项目域标识规则拼装 SIP URI。
    fun sipUri(target: String): String = "sip:$target@$domainId"
    // 根据厂商策略生成 User-Agent，用于模拟设备指纹差异。
    fun userAgent(): String = when (vendor) {
        VendorProfile.HIKVISION -> "Hikvision-GB28181/1.0"
        VendorProfile.DAHUA -> "Dahua-GB28181/1.0"
        VendorProfile.UNIVIEW -> "Uniview-GB28181/1.0"
    }
}

data class ServerConfig(
    val serverId: String,
    val domainId: String,
    val passwordLookup: Map<String, String>,
    val sip: SipEndpoint,
    val transport: SipTransportProtocol = SipTransportProtocol.UDP,
    val vendor: VendorProfile = VendorProfile.HIKVISION,
    val strategy: VendorStrategy = defaultVendorStrategy(VendorProfile.HIKVISION, GbVersion.V2016),
    val version: GbVersion = GbVersion.V2016
)

data class VendorStrategy(
    val manufacturer: String,
    val nvrModel: String,
    val ipcModel: String,
    val registerContactSuffix: String,
    val ptzZoomInCmd: String,
    val ptzZoomOutCmd: String,
    val heartbeatIntervalSec: Int,
    val registerRefreshSec: Int,
    val subscribeExpiresSec: Int,
    val playbackSpeed: Int,
    val sdpExtraLine: String,
    val catalogExtraFieldName: String,
    val catalogExtraFieldValue: String
)

data class VendorStrategyOverrides(
    val manufacturer: String? = null,
    val nvrModel: String? = null,
    val ipcModel: String? = null,
    val registerContactSuffix: String? = null,
    val ptzZoomInCmd: String? = null,
    val ptzZoomOutCmd: String? = null,
    val heartbeatIntervalSec: Int? = null,
    val registerRefreshSec: Int? = null,
    val subscribeExpiresSec: Int? = null,
    val playbackSpeed: Int? = null,
    val sdpExtraLine: String? = null,
    val catalogExtraFieldName: String? = null,
    val catalogExtraFieldValue: String? = null
)

// 内置厂商默认策略，可通过 JSON 对部分字段进行覆盖。
fun defaultVendorStrategy(vendor: VendorProfile, version: GbVersion): VendorStrategy = when (vendor) {
    VendorProfile.HIKVISION -> VendorStrategy(
        manufacturer = "Hikvision",
        nvrModel = "DS-96${if (version == GbVersion.V2022) "22" else "16"}N",
        ipcModel = "DS-2CD",
        registerContactSuffix = ";expires=3600",
        ptzZoomInCmd = "A50F010100",
        ptzZoomOutCmd = "A50F010200",
        heartbeatIntervalSec = 20,
        registerRefreshSec = 50,
        subscribeExpiresSec = 3600,
        playbackSpeed = 2,
        sdpExtraLine = "a=framerate:25",
        catalogExtraFieldName = "Parental",
        catalogExtraFieldValue = "0"
    )

    VendorProfile.DAHUA -> VendorStrategy(
        manufacturer = "Dahua",
        nvrModel = "DHI-NVR${if (version == GbVersion.V2022) "7" else "5"}",
        ipcModel = "DH-IPC",
        registerContactSuffix = ";ob",
        ptzZoomInCmd = "A50F110100",
        ptzZoomOutCmd = "A50F110200",
        heartbeatIntervalSec = 25,
        registerRefreshSec = 55,
        subscribeExpiresSec = 1800,
        playbackSpeed = 4,
        sdpExtraLine = "a=recvonly",
        catalogExtraFieldName = "CivilCode",
        catalogExtraFieldValue = "340200"
    )

    VendorProfile.UNIVIEW -> VendorStrategy(
        manufacturer = "Uniview",
        nvrModel = "UN-NVR${if (version == GbVersion.V2022) "8" else "6"}",
        ipcModel = "UN-IPC",
        registerContactSuffix = ";q=1.0",
        ptzZoomInCmd = "A50F210100",
        ptzZoomOutCmd = "A50F210200",
        heartbeatIntervalSec = 30,
        registerRefreshSec = 60,
        subscribeExpiresSec = 3000,
        playbackSpeed = 1,
        sdpExtraLine = "a=quality:5",
        catalogExtraFieldName = "Address",
        catalogExtraFieldValue = "Uniview-Street"
    )
}

fun loadVendorStrategy(vendor: VendorProfile, version: GbVersion, strategyFile: String?): VendorStrategy {
    val base = defaultVendorStrategy(vendor, version)
    if (strategyFile.isNullOrBlank()) return base
    val f = File(strategyFile)
    if (!f.exists() || !f.isFile) return base
    val mapper = jacksonObjectMapper()
    val ov = runCatching { mapper.readValue<VendorStrategyOverrides>(f) }.getOrNull() ?: return base
    return base.copy(
        manufacturer = ov.manufacturer ?: base.manufacturer,
        nvrModel = ov.nvrModel ?: base.nvrModel,
        ipcModel = ov.ipcModel ?: base.ipcModel,
        registerContactSuffix = ov.registerContactSuffix ?: base.registerContactSuffix,
        ptzZoomInCmd = ov.ptzZoomInCmd ?: base.ptzZoomInCmd,
        ptzZoomOutCmd = ov.ptzZoomOutCmd ?: base.ptzZoomOutCmd,
        heartbeatIntervalSec = ov.heartbeatIntervalSec ?: base.heartbeatIntervalSec,
        registerRefreshSec = ov.registerRefreshSec ?: base.registerRefreshSec,
        subscribeExpiresSec = ov.subscribeExpiresSec ?: base.subscribeExpiresSec,
        playbackSpeed = ov.playbackSpeed ?: base.playbackSpeed,
        sdpExtraLine = ov.sdpExtraLine ?: base.sdpExtraLine,
        catalogExtraFieldName = ov.catalogExtraFieldName ?: base.catalogExtraFieldName,
        catalogExtraFieldValue = ov.catalogExtraFieldValue ?: base.catalogExtraFieldValue
    )
}

fun vendorManufacturer(strategy: VendorStrategy): String = strategy.manufacturer
fun vendorModel(strategy: VendorStrategy, role: String): String = if (role == "NVR") strategy.nvrModel else strategy.ipcModel
fun vendorRegisterContactSuffix(strategy: VendorStrategy): String = strategy.registerContactSuffix
fun vendorPtzCommand(strategy: VendorStrategy, zoomIn: Boolean): String = if (zoomIn) strategy.ptzZoomInCmd else strategy.ptzZoomOutCmd
fun vendorHeartbeatIntervalSec(strategy: VendorStrategy): Int = strategy.heartbeatIntervalSec
fun vendorRegisterRefreshSec(strategy: VendorStrategy): Int = strategy.registerRefreshSec
fun vendorDefaultSubscribeExpiresSec(strategy: VendorStrategy): Int = strategy.subscribeExpiresSec
fun vendorPlaybackSpeed(strategy: VendorStrategy): Int = strategy.playbackSpeed
