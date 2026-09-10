package xyz.a202132.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * Firefly Edge API 的统一 JSON 响应。
 */
data class ApiEnvelope<T>(
    @SerializedName("ok")
    val ok: Boolean = false,
    @SerializedName("data")
    val data: T? = null,
    @SerializedName("error")
    val error: String? = null,
    @SerializedName("requestId")
    val requestId: String? = null
)

/** `/api/v2/bootstrap` 下发的客户端公开配置。 */
data class ClientCatalogInfo(
    @SerializedName("crypto")
    val crypto: ClientCryptoInfo? = null,

    @SerializedName("notice")
    val notice: NoticeInfo? = null,

    @SerializedName("appUpdate")
    val appUpdate: UpdateInfo? = null,

    @SerializedName("settings")
    val settings: RemoteAppSettings? = null
)

data class ClientCryptoInfo(
    @SerializedName("version")
    val version: Int = 0,
    @SerializedName("algorithm")
    val algorithm: String = ""
)

data class RemoteAppSettings(
    @SerializedName("websiteUrl")
    val websiteUrl: String = "",

    @SerializedName("feedbackEmail")
    val feedbackEmail: String = "",

    @SerializedName("feedbackUrl")
    val feedbackUrl: String = "",

    @SerializedName("githubUrl")
    val githubUrl: String = ""
)

/**
 * 版本更新响应
 */
data class UpdateInfo(
    @SerializedName(value = "versionName", alternate = ["version"])
    val version: String,
    
    @SerializedName("versionCode")
    val versionCode: Int,
    
    @SerializedName("downloadUrl")
    val downloadUrl: String,
    
    @SerializedName("changelog") val changelog: String,
    @SerializedName("force") val force: Boolean = false
){
    val isForce: Int get() = if (force) 1 else 0
}

/**
 * 通知公告响应
 */
data class NoticeInfo(
    @SerializedName(value = "enabled", alternate = ["hasNotice"])
    val hasNotice: Boolean = true,
    
    @SerializedName("title")
    val title: String = "",
    
    @SerializedName("content")
    val content: String = "",
    
    @SerializedName(value = "id", alternate = ["noticeId"])
    val noticeId: String = "",
    
    @SerializedName("showOnce")
    val showOnce: Boolean = true
)

/**
 * 节点出口 IP 信息
 */
data class NodeIpInfo(
    @SerializedName("ip")
    val ip: String = "",
    @SerializedName("asn")
    val asn: Int? = null,
    @SerializedName("asOrganization")
    val asOrganization: String? = null,
    @SerializedName("country")
    val country: String? = null,
    @SerializedName("countryCode")
    val countryCode: String? = null,
    @SerializedName("region")
    val region: String? = null,
    @SerializedName("regionCode")
    val regionCode: String? = null,
    @SerializedName("city")
    val city: String? = null,
    @SerializedName("timezone")
    val timezone: String? = null,
    @SerializedName("longitude")
    val longitude: String? = null,
    @SerializedName("latitude")
    val latitude: String? = null,
    @SerializedName("postalCode")
    val postalCode: String? = null,
    @SerializedName("fraudScore")
    val fraudScore: Int? = null,
    @SerializedName("isResidential")
    val isResidential: Boolean? = null,
    @SerializedName("isBroadcast")
    val isBroadcast: Boolean? = null,
    @SerializedName("userAgent")
    val userAgent: String? = null
)

/**
 * VPN连接状态
 */
enum class VpnState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING
}

/**
 * 代理模式
 */
enum class ProxyMode {
    GLOBAL,     // 全局代理
    SMART       // 智能分流（国内直连，国外代理）
}
