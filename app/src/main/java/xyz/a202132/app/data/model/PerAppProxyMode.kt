package xyz.a202132.app.data.model

/**
 * 分应用代理模式
 */
enum class PerAppProxyMode {
    /**
     * 代理模式 (白名单)
     * 只有选中的应用走 VPN，其他应用直连
     */
    WHITELIST,
    
    /**
     * 绕过模式 (黑名单)
     * 所有应用走 VPN，选中的应用绕过 VPN
     */
    BLACKLIST
}
