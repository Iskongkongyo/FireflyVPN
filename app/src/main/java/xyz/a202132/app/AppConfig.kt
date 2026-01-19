package xyz.a202132.app

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
    
    // Latency Test
    const val LATENCY_TEST_URL = "https://www.google.com/generate_204"
    const val LATENCY_TEST_TIMEOUT = 5000L // 5 seconds
    
    // VPN
    const val VPN_MTU = 9000
    const val VPN_DNS_PRIMARY = "8.8.8.8"
    const val VPN_DNS_SECONDARY = "8.8.4.4"
    const val VPN_DNS_CHINA = "223.5.5.5"
    
    // Notification
    const val NOTIFICATION_CHANNEL_ID = "vpn_service"
    const val NOTIFICATION_ID = 1
}
