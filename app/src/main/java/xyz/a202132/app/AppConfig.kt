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
    // API URLs
    const val SUBSCRIPTION_URL = "https://your-server.com/api/nodes"
    const val UPDATE_URL = "https://your-server.com/api/update"
    const val NOTICE_URL = "https://your-server.com/api/notice"
    const val WEBSITE_URL = "https://your-website.com"
    
    // Contact
    const val FEEDBACK_EMAIL = "support@your-domain.com"
    const val FEEDBACK_URL = "https://github.com/your-username/your-repo/issues"  // 反馈链接，留空则不跳转
    const val GITHUB_URL = "https://github.com/your-username/your-repo"  // 项目源码地址，留空则隐藏关于页相关按钮
    
    // Latency Test
    // 常用http://cp.cloudflare.com/generate_204或https://www.google.com/generate_204
    const val TCPING_TEST_URL = "https://www.google.com/generate_204"
    const val TCPING_TEST_TIMEOUT = 3000L
    
    const val URL_TEST_URL = "https://www.google.com/generate_204"
    const val URL_TEST_TIMEOUT = 5000L
    
    // Concurrency
    const val TCPING_CONCURRENCY = 16
    const val URL_TEST_CONCURRENCY = 10
    const val AUTO_TEST_UNLOCK_CONCURRENCY = 3 // 流媒体解锁测试并发建议2~3
    
    // VPN
    const val VPN_MTU = 9000
    const val VPN_DNS_PRIMARY = "8.8.8.8"
    const val VPN_DNS_SECONDARY = "8.8.4.4"
    const val VPN_DNS_CHINA = "223.5.5.5"
    
    // Notification
    const val NOTIFICATION_CHANNEL_ID = "vpn_service"
    const val NOTIFICATION_ID = 1

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
