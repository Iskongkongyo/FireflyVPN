package xyz.a202132.app.data.model

/**
 * IPv6 路由模式
 */
enum class IPv6RoutingMode {
    /**
     * 禁用 IPv6 (默认)
     * DNS策略: prefer_ipv4, 无 inet6_address
     */
    DISABLED,
    
    /**
     * 启用 IPv6 (双栈)
     * DNS策略: prefer_ipv4, 添加 inet6_address
     */
    ENABLED,
    
    /**
     * 优先 IPv6
     * DNS策略: prefer_ipv6, 添加 inet6_address
     */
    PREFER,
    
    /**
     * 仅 IPv6 (实验性)
     * DNS策略: ipv6_only, 仅 inet6_address
     */
    ONLY
}
