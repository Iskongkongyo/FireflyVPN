package xyz.a202132.app.data.model

/**
 * 节点协议类型
 */
enum class NodeType(val protocol: String) {
    VLESS("vless"),
    VMESS("vmess"),
    TROJAN("trojan"),
    HYSTERIA2("hysteria2"),
    ANYTLS("anytls"),
    TUIC("tuic"),
    NAIVE("naive"),
    WIREGUARD("wireguard"),
    SHADOWSOCKS("ss"),
    SOCKS("socks"),
    HTTP("http"),
    UNKNOWN("unknown");
    
    companion object {
        fun fromLink(link: String): NodeType {
            val lowerLink = link.lowercase()
            return when {
                lowerLink.startsWith("vless://") -> VLESS
                lowerLink.startsWith("vmess://") -> VMESS
                lowerLink.startsWith("trojan://") -> TROJAN
                lowerLink.startsWith("hysteria2://") || lowerLink.startsWith("hy2://") -> HYSTERIA2
                lowerLink.startsWith("anytls://") -> ANYTLS
                lowerLink.startsWith("tuic://") -> TUIC
                lowerLink.startsWith("naive://") || lowerLink.startsWith("naive+https://") -> NAIVE
                lowerLink.startsWith("wireguard://") -> WIREGUARD
                lowerLink.startsWith("ss://") -> SHADOWSOCKS
                lowerLink.startsWith("socks://") || lowerLink.startsWith("socks5://") || lowerLink.startsWith("socks4://") -> SOCKS
                lowerLink.startsWith("http://") || lowerLink.startsWith("https://") -> HTTP
                else -> UNKNOWN
            }
        }
    }
}
