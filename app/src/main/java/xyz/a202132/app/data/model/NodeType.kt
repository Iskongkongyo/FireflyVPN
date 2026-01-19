package xyz.a202132.app.data.model

/**
 * 节点协议类型
 */
enum class NodeType(val protocol: String) {
    VLESS("vless"),
    VMESS("vmess"),
    TROJAN("trojan"),
    HYSTERIA2("hysteria2"),
    SHADOWSOCKS("ss"),
    SOCKS("socks"),
    UNKNOWN("unknown");
    
    companion object {
        fun fromLink(link: String): NodeType {
            return when {
                link.startsWith("vless://") -> VLESS
                link.startsWith("vmess://") -> VMESS
                link.startsWith("trojan://") -> TROJAN
                link.startsWith("hysteria2://") || link.startsWith("hy2://") -> HYSTERIA2
                link.startsWith("ss://") -> SHADOWSOCKS
                link.startsWith("socks://") -> SOCKS
                else -> UNKNOWN
            }
        }
    }
}
