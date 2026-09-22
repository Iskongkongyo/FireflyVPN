package xyz.a202132.app

import org.json.JSONArray

/**
 * 网络工具数据类
 */
data class NetworkTool(
    val name: String,
    val url: String,
    val icon: String
)

/**
 * 测速大小选项
 */
data class SpeedTestSize(
    val label: String,
    val bytes: Long
)

data class UnlockPrioritySite(
    val id: String,
    val label: String,
    val keywords: List<String>
)

/**
 * 应用配置常量
 */
object AppConfig {
    const val STARTUP_SPLASH_DURATION_SECONDS = 10 // 启动图倒计时时长，设为0则不启用

    // Firefly Edge API（保持生产域名不变，仅使用新版 /api/v2 路由）
    const val API_BASE_URL = "https://填写域名"
    const val BOOTSTRAP_URL = "$API_BASE_URL/api/v2/bootstrap"
    const val SUBSCRIPTION_URL = "$API_BASE_URL/api/v2/subscriptions"
    const val USAGE_REPORT_URL = "$API_BASE_URL/api/v2/usage/report"

    // API timeout (milliseconds) for retry-controlled requests
    const val NODE_REQUEST_TIMEOUT_MS = 25000L // 节点请求超时（毫秒）
    
    // Latency Test
    // 常用http://cp.cloudflare.com/generate_204或https://www.google.com/generate_204
    const val TCPING_TEST_TIMEOUT = 3000L // TCPing 超时（毫秒）
    
    const val URL_TEST_URL = "https://www.google.com/generate_204" // URL Test测试URL
    val VPN_HEALTH_CHECK_URLS = listOf(
        URL_TEST_URL,
        "https://cp.cloudflare.com/generate_204"
    )
    const val URL_TEST_TIMEOUT = 3000L // URL Test 超时（毫秒）
    const val URL_TEST_RETRY_COUNT = 1 // URL Test 自动重试次数，仅对503/504或异常生效
    
    // Concurrency
    const val TCPING_CONCURRENCY = 16 // TCPing并发数
    const val URL_TEST_CONCURRENCY = 10 // URL Test并发数
    const val AUTO_TEST_UNLOCK_CONCURRENCY = 3 // 主流站解锁测试并发数
    
    // VPN
    const val VPN_MTU = 9000
    const val LAN_PROXY_DEFAULT_PORT = 10809
    const val LAN_PROXY_MIN_PORT = 1024
    const val LAN_PROXY_MAX_PORT = 65535
    const val VPN_DNS_PRIMARY = "8.8.8.8"
    const val VPN_DNS_SECONDARY = "8.8.4.4"
    const val VPN_DNS_CHINA = "223.5.5.5" // 国内 DNS（智能分流模式使用）
    const val VPN_HEALTH_CHECK_INTERVAL_MS = 6_000L
    const val VPN_HEALTH_CHECK_RETRY_DELAY_MS = 1_000L
    const val VPN_HEALTH_CHECK_FAILURE_THRESHOLD = 3
    const val VPN_HEALTH_CHECK_TIMEOUT_MS = 4_000L
    const val VPN_RECOVERY_COOLDOWN_MS = 5 * 60_000L
    const val VPN_CONNECTIVITY_RECOVERY_DEFAULT_ENABLED = true
    const val HYSTERIA2_DEFAULT_BANDWIDTH_MBPS = 50
    const val HYSTERIA2_MIN_BANDWIDTH_MBPS = 20
    const val HYSTERIA2_MAX_BANDWIDTH_MBPS = 10_000
    const val HYSTERIA2_ADAPTIVE_TEST_BYTES = 25_000_000L
    const val HYSTERIA2_ADAPTIVE_SAFETY_RATIO = 0.8f
    val HTTP_USER_AGENT: String
        get() = "FireflyVPN/${xyz.a202132.app.BuildConfig.VERSION_NAME}"


    // Notification
    const val NOTIFICATION_CHANNEL_ID = "vpn_service"
    const val NOTIFICATION_ID = 1

    // 节点IP信息
    const val NODE_IP_INFO_URL = "https://my.ippure.com/v1/info"
    const val NODE_IP_INFO_TIMEOUT_MS = 12000L // 单次节点IP信息查询超时（毫秒）
    const val NODE_IP_INFO_RETRY_COUNT = 1 // 节点IP信息自动重试次数，仅对超时、网络异常或服务端临时错误生效

    // Network Toolbox - 网络工具箱
    const val NETWORK_TOOLS_JSON = """
[
  {"name": "出口检测", "url": "https://ippure.com/IP-Outbound-Detect.html", "icon": "outbound"},
  {"name": "IP信息查询", "url": "https://ippure.com", "icon": "ip"},
  {"name": "WebRTC泄漏", "url": "https://ippure.com/Browser-WebRTC-Leak-Detect.html", "icon": "webrtc"},
  {"name": "DNS泄漏", "url": "https://ippure.com/DNS-Leak-Detect.html", "icon": "dns"},
  {"name": "IP检测", "url": "https://ipcheck.ing/#/", "icon": "check"},
  {"name": "高精度IP查询", "url": "https://ping0.cc/", "icon": "precision"},
  {"name": "IP定位", "url": "https://iplark.com/", "icon": "location"},
  {"name": "伪装度查询", "url": "https://whoer.net/zh", "icon": "disguise"},
  {"name": "BGP查询", "url": "https://bgp.tools/", "icon": "bgp"},
  {"name": "速度测试", "url": "https://www.speedtest.net/", "icon": "speed"}
]
"""

    // Speed Test - Cloudflare 网速测试
    const val SPEED_TEST_DOWNLOAD_URL = "https://speed.cloudflare.com/__down"
    const val SPEED_TEST_UPLOAD_URL = "https://speed.cloudflare.com/__up"
    const val AUTO_TEST_BANDWIDTH_DOWNLOAD_TIMEOUT_MS = 25000L // 单次下载带宽测试超时（毫秒），设为0则无限制
    const val AUTO_TEST_BANDWIDTH_UPLOAD_TIMEOUT_MS = 30000L // 单次上传带宽测试超时（毫秒），设为0则无限制
    const val SPEED_TEST_JSON = """
[
  {"label": "1MB", "bytes": 1000000},
  {"label": "10MB", "bytes": 10000000},
  {"label": "25MB", "bytes": 25000000},
  {"label": "50MB", "bytes": 50000000}
]
"""

    /**
     * 解析网络工具箱 JSON 数据
     */
    fun getNetworkTools(): List<NetworkTool> {
        val tools = mutableListOf<NetworkTool>()
        try {
            val jsonArray = JSONArray(NETWORK_TOOLS_JSON)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                tools.add(
                    NetworkTool(
                        name = obj.getString("name"),
                        url = obj.getString("url"),
                        icon = obj.getString("icon")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return tools
    }

    /**
     * 解析测速大小 JSON 数据
     */
    fun getSpeedTestSizes(): List<SpeedTestSize> {
        val sizes = mutableListOf<SpeedTestSize>()
        try {
            val jsonArray = JSONArray(SPEED_TEST_JSON)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                sizes.add(
                    SpeedTestSize(
                        label = obj.getString("label"),
                        bytes = obj.getLong("bytes")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return sizes
    }

    // 主流站解锁测试涉及的网站名
    val UNLOCK_PRIORITY_PRESET_SITES: List<UnlockPrioritySite> = listOf(
        UnlockPrioritySite("apple", "Apple", listOf("Apple")),
        UnlockPrioritySite("bing_search", "BingSearch", listOf("BingSearch")),
        UnlockPrioritySite("claude", "Claude", listOf("Claude")),
        UnlockPrioritySite("dazn", "Dazn", listOf("Dazn", "DAZN")),
        UnlockPrioritySite("disney_plus", "Disney+", listOf("Disney+")),
        UnlockPrioritySite("gemini", "Gemini", listOf("Gemini")),
        UnlockPrioritySite("google_search", "GoogleSearch", listOf("GoogleSearch")),
        UnlockPrioritySite("google_play_store", "Google Play Store", listOf("Google Play Store")),
        UnlockPrioritySite("iqiyi", "IQiYi", listOf("IQiYi")),
        UnlockPrioritySite("instagram_audio", "Instagram Licensed Audio", listOf("Instagram Licensed Audio")),
        UnlockPrioritySite("kocowa", "KOCOWA", listOf("KOCOWA")),
        UnlockPrioritySite("meta_ai", "MetaAI", listOf("MetaAI")),
        UnlockPrioritySite("netflix", "Netflix", listOf("Netflix", "Netflix CDN")),
        UnlockPrioritySite("onetrust", "OneTrust", listOf("OneTrust")),
        UnlockPrioritySite("chatgpt", "ChatGPT", listOf("ChatGPT", "OpenAI")),
        UnlockPrioritySite("paramount_plus", "Paramount+", listOf("Paramount+")),
        UnlockPrioritySite("prime_video", "Amazon Prime Video", listOf("Amazon Prime Video", "Prime")),
        UnlockPrioritySite("reddit", "Reddit", listOf("Reddit")),
        UnlockPrioritySite("sony_liv", "SonyLiv", listOf("SonyLiv")),
        UnlockPrioritySite("sora", "Sora", listOf("Sora")),
        UnlockPrioritySite("spotify", "Spotify Registration", listOf("Spotify Registration", "Spotify")),
        UnlockPrioritySite("steam_store", "Steam Store", listOf("Steam Store", "Steam")),
        UnlockPrioritySite("tvbanywhere", "TVBAnywhere+", listOf("TVBAnywhere+", "TVB")),
        UnlockPrioritySite("tiktok", "TikTok", listOf("TikTok")),
        UnlockPrioritySite("viu", "Viu.com", listOf("Viu.com")),
        UnlockPrioritySite("wikipedia_edit", "Wikipedia Editability", listOf("Wikipedia Editability")),
        UnlockPrioritySite("youtube", "YouTube Region", listOf("YouTube Region", "YouTube CDN", "YouTube"))
    )
}
