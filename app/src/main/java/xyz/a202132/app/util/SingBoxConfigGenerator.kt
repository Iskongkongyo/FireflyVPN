package xyz.a202132.app.util

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeType
import xyz.a202132.app.data.model.ProxyMode
import xyz.a202132.app.data.model.IPv6RoutingMode
import android.net.Uri
import android.util.Base64

/**
 * sing-box 配置生成器
 */
class SingBoxConfigGenerator {
    
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    
    /**
     * 生成完整的sing-box配置
     * @param nodes 所有可用节点列表
     * @param selectedNodeId 当前选择的节点ID（如果为null则默认选择auto）
     * @param bypassLan 是否绕过局域网
     * @param ipv6Mode IPv6 路由模式
     */
    fun generateConfig(nodes: List<Node>, selectedNodeId: String?, proxyMode: ProxyMode, bypassLan: Boolean = true, ipv6Mode: IPv6RoutingMode = IPv6RoutingMode.DISABLED): String {
        // 如果没有节点，生成一个空配置防止崩溃
        if (nodes.isEmpty()) {
            return generateEmptyConfig()
        }

        val config = JsonObject().apply {
            // Extract unique domains from nodes (ignoring IPs) to prevent routing loops/DNS deadlocks
            // Extract unique domains and IPs
            val uniqueServers = nodes.map { it.server }.distinct()
            val ipRegex = Regex("^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$")
            val ipv6Regex = Regex("([0-9a-fA-F]{1,4}:){1,7}[0-9a-fA-F]{1,4}") // Simple regex for IPv6 detection
            
            val nodeIPs = uniqueServers.filter { it.matches(ipRegex) || it.contains(":") } // Treat anything with : as potential IPv6
            val nodeDomains = uniqueServers.filter { !it.matches(ipRegex) && !it.contains(":") }
                
            add("log", createLogConfig())
            add("dns", createDnsConfig(proxyMode, nodeDomains, ipv6Mode))
            add("inbounds", createInbounds(ipv6Mode))
            add("outbounds", createOutbounds(nodes, selectedNodeId))
            add("route", createRoute(proxyMode, nodeDomains, nodeIPs, bypassLan))
            add("experimental", createExperimental())
        }
        return gson.toJson(config)
    }
    
    private fun generateEmptyConfig(): String {
        val config = JsonObject().apply {
            add("log", createLogConfig())
            add("inbounds", createInbounds(IPv6RoutingMode.DISABLED))
            add("outbounds", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "direct")
                    addProperty("tag", "direct")
                })
            })
        }
        return gson.toJson(config)
    }
    
    /**
     * 生成用于延迟测试的配置
     * 仍然支持单节点测试（用于Socket测试无法连接时的回退）
     */
    fun generateTestConfig(node: Node, localPort: Int = 10808): String {
        val config = JsonObject().apply {
            add("log", JsonObject().apply {
                addProperty("level", "error")
            })
            add("inbounds", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "socks")
                    addProperty("tag", "socks-in")
                    addProperty("listen", "127.0.0.1")
                    addProperty("listen_port", localPort)
                })
            })
            add("outbounds", JsonArray().apply {
                add(createNodeOutbound(node, "proxy")) // 单节点测试直接用proxy tag
                add(JsonObject().apply {
                    addProperty("type", "direct")
                    addProperty("tag", "direct")
                })
            })
        }
        return gson.toJson(config)
    }
    
    private fun createLogConfig(): JsonObject {
        return JsonObject().apply {
            addProperty("level", "info")
            addProperty("timestamp", true)
        }
    }
    
    private fun createDnsConfig(proxyMode: ProxyMode, nodeDomains: List<String>, ipv6Mode: IPv6RoutingMode): JsonObject {
        val servers = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("tag", "google")
                addProperty("address", "tls://8.8.8.8")
                addProperty("detour", "proxy") // DNS走代理组
            })
            add(JsonObject().apply {
                addProperty("tag", "local")
                addProperty("address", "223.5.5.5")
                addProperty("detour", "direct")
            })
        }
        
        val rules = JsonArray()
        
        // 1. Resolve Proxy Server Domains via Local DNS (Critical for domain-based proxies!)
        if (nodeDomains.isNotEmpty()) {
            rules.add(JsonObject().apply {
                add("domain", JsonArray().apply { nodeDomains.forEach { add(it) } })
                addProperty("server", "local")
            })
        }
        
        // 2. Resolve Common CN Domains via Local DNS (Mirroring Route rules)
        if (proxyMode == ProxyMode.SMART) {
        // 2. Resolve Common CN Domains via Local DNS (Mirroring Route rules)
        if (proxyMode == ProxyMode.SMART) {
            rules.add(JsonObject().apply {
                add("domain_suffix", JsonArray().apply {
                     add("cn")
                     // Bilibili
                     add("bilibili.com"); add("bilivideo.com"); add("bilivideo.cn"); add("biliapi.com"); add("biliapi.net"); add("hdslb.com")
                     // Alibaba / Taobao / Alipay
                     add("alibaba.com"); add("alibabagroup.com"); add("alicdn.com"); add("alikunlun.com"); add("alipay.com"); add("alipayobjects.com")
                     add("aliyun.com"); add("aliyuncdn.com"); add("aliyuncs.com"); add("mmstat.com"); add("tanx.com"); add("taobao.com"); add("tmall.com")
                     // Tencent / WeChat
                     add("qq.com"); add("tencent.com"); add("weixin.com"); add("wechat.com"); add("qzone.com"); add("qcloud.com"); add("myqcloud.com")
                     add("gtimg.com"); add("qpic.cn"); add("qlogo.cn"); add("weixinbridge.com")
                     // ByteDance / Douyin / Toutiao
                     add("bytedance.com"); add("douyin.com"); add("douyinpic.com"); add("douyinvod.com"); add("idouyinvod.com")
                     add("snssdk.com"); add("pstatp.com"); add("ixigua.com"); add("byteimg.com"); add("toutiao.com"); add("toutiaocloud.com")
                     // NetEase
                     add("163.com"); add("126.net"); add("127.net"); add("netease.com"); add("music.126.net"); add("ydstatic.com")
                     // Baidu
                     add("baidu.com"); add("baidustatic.com"); add("bdstatic.com"); add("bdimg.com"); add("bcebos.com")
                     // XiaoMi
                     add("mi.com"); add("xiaomi.com"); add("xiaomiyoupin.com")
                     // JD
                     add("jd.com"); add("jd.hk"); add("360buy.com"); add("360buyimg.com")
                     // Others
                     add("zhihu.com"); add("zhimg.com"); add("weibo.com"); add("sina.com.cn"); add("sinajs.cn")
                     add("xiaohongshu.com"); add("xhscdn.com"); add("meituan.com"); add("dianping.com"); add("ele.me")
                     add("360.cn"); add("360.com"); add("sohu.com"); add("sogou.com"); add("xunlei.com")
                     add("csdn.net"); add("cnblogs.com")
                     add("amap.com"); add("autonavi.com"); add("gaode.com")
                     add("iqiyi.com"); add("iqiyipic.com"); add("71.am"); add("youku.com"); add("ip138.com")
                     add("bce.baidu.com") // Common issue fix
                })
                addProperty("server", "local")
            })
        }
        }

        // 3. Fallback to Google DNS (Remote)
        rules.add(JsonObject().apply {
            addProperty("server", "google")
        })
        
        // DNS strategy based on IPv6 mode
        val dnsStrategy = when (ipv6Mode) {
            IPv6RoutingMode.ONLY -> "ipv6_only"
            IPv6RoutingMode.PREFER -> "prefer_ipv6"
            // ENABLED 模式下，如果想要测试 IPv6，最好也稍微倾向 IPv6，或者使用 prefer_ipv4 (默认)
            // 但用户反馈 ipcheck 不显示 ipv6，说明默认 prefer_ipv4 导致双栈站点走 IPv4。
            // 为了更好的体验，ENABLED 可以保持 prefer_ipv4 保证速度，PREFER 用 prefer_ipv6
            else -> "prefer_ipv4"
        }
        
        return JsonObject().apply {
            add("servers", servers)
            add("rules", rules)
            addProperty("strategy", dnsStrategy)
        }
    }
    
    private fun createInbounds(ipv6Mode: IPv6RoutingMode): JsonArray {
        return JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "tun")
                addProperty("tag", "tun-in")
                addProperty("interface_name", "tun0")
                // IPv4 地址 (总是添加，否则 DNS 劫持会报错 "need one more IPv4 address")
                addProperty("inet4_address", "172.19.0.1/30")
                
                // IPv6 地址 (启用/优先/仅模式下添加)
                if (ipv6Mode != IPv6RoutingMode.DISABLED) {
                    // 使用 GUA 地址而非 ULA，防止 Android 认为无 IPv6 互联网连接
                    // 必须使用 /126 或更小掩码，因为 system stack 需要至少一个额外的 IPv6 地址用于网关/路由
                    addProperty("inet6_address", "2001:db8::1/126")
                }
                addProperty("mtu", 9000)
                addProperty("auto_route", true)
                addProperty("strict_route", true)
                
                // 显式配置路由地址 (sing-box 1.12.x 使用 route_address)
                add("route_address", JsonArray().apply {
                    // IPv4 全局路由 (仅IPv6模式下不添加)
                    if (ipv6Mode != IPv6RoutingMode.ONLY) {
                        add("0.0.0.0/0")
                    }
                    // IPv6 全局路由 (启用/优先/仅模式下添加)
                    if (ipv6Mode != IPv6RoutingMode.DISABLED) {
                        add("::/0")
                    }
                })
                
                addProperty("stack", "gvisor")
                addProperty("sniff", true)
                addProperty("sniff_override_destination", true)
            })
        }
    }

    private fun createOutbounds(nodes: List<Node>, selectedNodeId: String?): JsonArray {
        val outbounds = JsonArray()
        
        // 1. Selector Group (手动选择组)
        val selectorGroup = JsonObject().apply {
            addProperty("type", "selector")
            addProperty("tag", "proxy")
            val outboundTags = JsonArray()
            outboundTags.add("auto") // 添加自动选择组
            nodes.forEach { outboundTags.add(it.id) } // 添加所有节点ID
            add("outbounds", outboundTags)
            
            // 如果只有当前选中的节点ID，且该节点存在，则默认选中它
            // 否则默认选中 auto
            if (selectedNodeId != null && nodes.any { it.id == selectedNodeId }) {
                addProperty("default", selectedNodeId)
            } else {
                addProperty("default", "auto")
            }
        }
        outbounds.add(selectorGroup)
        
        // 2. URLTest Group (自动选择组)
        val urlTestGroup = JsonObject().apply {
            addProperty("type", "urltest")
            addProperty("tag", "auto")
            val outboundTags = JsonArray()
            nodes.forEach { outboundTags.add(it.id) }
            add("outbounds", outboundTags)
            addProperty("url", "https://www.google.com/generate_204")
            addProperty("interval", "10m") 
            addProperty("tolerance", 50)
            addProperty("interrupt_exist_connections", false)
        }
        outbounds.add(urlTestGroup)
        
        // 3. Individual Node Outbounds (具体节点)
        nodes.forEach { node ->
            outbounds.add(createNodeOutbound(node, node.id))
        }
        
        // 4. Other Outbounds (Direct, Block, DNS)
        outbounds.add(JsonObject().apply {
            addProperty("type", "direct")
            addProperty("tag", "direct")
        })
        
        outbounds.add(JsonObject().apply {
            addProperty("type", "block")
            addProperty("tag", "block")
        })
        
        outbounds.add(JsonObject().apply {
            addProperty("type", "dns")
            addProperty("tag", "dns-out")
        })
        
        return outbounds
    }
    
    private fun createNodeOutbound(node: Node, tag: String): JsonObject {
        val outbound = when (node.type) {
            NodeType.VLESS -> createVlessOutbound(node)
            NodeType.VMESS -> createVmessOutbound(node)
            NodeType.TROJAN -> createTrojanOutbound(node)
            NodeType.HYSTERIA2 -> createHysteria2Outbound(node)
            NodeType.SHADOWSOCKS -> createShadowsocksOutbound(node)
            NodeType.SOCKS -> createSocksOutbound(node)
            NodeType.HTTP -> createHttpOutbound(node)
            else -> createVlessOutbound(node)
        }
        // 覆盖 tag
        outbound.addProperty("tag", tag)
        return outbound
    }
    
    private fun createVlessOutbound(node: Node): JsonObject {
        val uri = Uri.parse(node.rawLink)
        
        return JsonObject().apply {
            addProperty("type", "vless")
            addProperty("server", node.server)
            addProperty("server_port", node.port)
            addProperty("uuid", uri.userInfo ?: "")
            addProperty("flow", uri.getQueryParameter("flow") ?: "")
            
            val security = uri.getQueryParameter("security")
            if (security == "tls" || security == "reality") {
                add("tls", JsonObject().apply {
                    addProperty("enabled", true)
                    val sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("host")
                    addProperty("server_name", sni ?: node.server)
                    if (security == "reality") {
                        add("reality", JsonObject().apply {
                            addProperty("enabled", true)
                            addProperty("public_key", uri.getQueryParameter("pbk") ?: "")
                            addProperty("short_id", uri.getQueryParameter("sid") ?: "")
                        })
                        add("utls", JsonObject().apply {
                            addProperty("enabled", true)
                            addProperty("fingerprint", uri.getQueryParameter("fp") ?: "chrome")
                        })
                    }
                    addProperty("insecure", uri.getQueryParameter("allowInsecure") == "1")
                })
            }
            
            val transport = uri.getQueryParameter("type") ?: "tcp"
            if (transport != "tcp" && transport != "none") {
                add("transport", createTransport(transport, uri))
            }
        }
    }
    
    private fun createVmessOutbound(node: Node): JsonObject {
        val base64Content = node.rawLink.removePrefix("vmess://")
        val jsonStr = String(Base64.decode(base64Content, Base64.DEFAULT), Charsets.UTF_8)
        val vmessConfig = gson.fromJson(jsonStr, JsonObject::class.java)
        
        return JsonObject().apply {
            addProperty("type", "vmess")
            addProperty("server", vmessConfig.get("add")?.asString ?: node.server)
            addProperty("server_port", vmessConfig.get("port")?.asInt ?: node.port)
            addProperty("uuid", vmessConfig.get("id")?.asString ?: "")
            addProperty("alter_id", vmessConfig.get("aid")?.asInt ?: 0)
            addProperty("security", vmessConfig.get("scy")?.asString ?: "auto")
            
            val tls = vmessConfig.get("tls")?.asString
            if (tls == "tls") {
                add("tls", JsonObject().apply {
                    addProperty("enabled", true)
                    addProperty("server_name", vmessConfig.get("sni")?.asString ?: vmessConfig.get("host")?.asString ?: node.server)
                })
            }
            
            val network = vmessConfig.get("net")?.asString ?: "tcp"
            if (network != "tcp" && network != "none") {
                add("transport", createVmessTransport(network, vmessConfig))
            }
        }
    }
    
    
    private fun createTrojanOutbound(node: Node): JsonObject {
        val uri = Uri.parse(node.rawLink)
        
        return JsonObject().apply {
            addProperty("type", "trojan")
            addProperty("server", node.server)
            addProperty("server_port", node.port)
            addProperty("password", uri.userInfo ?: "")
            
            add("tls", JsonObject().apply {
                addProperty("enabled", true)
                val sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("host")
                addProperty("server_name", sni ?: node.server)
                addProperty("insecure", uri.getQueryParameter("allowInsecure") == "1")
            })
            
            val transport = uri.getQueryParameter("type") ?: "tcp"
            if (transport != "tcp" && transport != "none") {
                add("transport", createTransport(transport, uri))
            }
        }
    }
    
    private fun createHysteria2Outbound(node: Node): JsonObject {
        val normalizedLink = node.rawLink.replace("hy2://", "hysteria2://")
        val uri = Uri.parse(normalizedLink)
        
        return JsonObject().apply {
            addProperty("type", "hysteria2")
            addProperty("server", node.server)
            addProperty("server_port", node.port)
            addProperty("password", uri.userInfo ?: "")
            
            add("tls", JsonObject().apply {
                addProperty("enabled", true)
                addProperty("server_name", uri.getQueryParameter("sni") ?: node.server)
                addProperty("insecure", uri.getQueryParameter("insecure") == "1")
            })
            
            val obfsType = uri.getQueryParameter("obfs")
            val obfsPassword = uri.getQueryParameter("obfs-password")
            
            if (!obfsType.isNullOrEmpty()) {
                add("obfs", JsonObject().apply {
                    addProperty("type", obfsType)
                    addProperty("password", obfsPassword ?: "")
                })
            }
        }
    }
    
    private fun createShadowsocksOutbound(node: Node): JsonObject {
        val linkContent = node.rawLink.removePrefix("ss://").substringBefore("#")
        val methodPassword = try {
            if (linkContent.contains("@")) {
                val base64Part = linkContent.substringBefore("@")
                String(Base64.decode(base64Part, Base64.DEFAULT), Charsets.UTF_8)
            } else {
                String(Base64.decode(linkContent, Base64.DEFAULT), Charsets.UTF_8).substringBefore("@")
            }
        } catch (e: Exception) {
            "aes-256-gcm:password"
        }
        
        val parts = methodPassword.split(":", limit = 2)
        val method = parts.getOrNull(0) ?: "aes-256-gcm"
        val password = parts.getOrNull(1) ?: ""
        
        return JsonObject().apply {
            addProperty("type", "shadowsocks")
            addProperty("server", node.server)
            addProperty("server_port", node.port)
            addProperty("method", method)
            addProperty("password", password)
        }
    }

    private fun createSocksOutbound(node: Node): JsonObject {
        val uri = Uri.parse(node.rawLink)
        val userInfo = uri.userInfo ?: ""
        val parts = userInfo.split(":")
        val username = parts.getOrNull(0) ?: ""
        val password = parts.getOrNull(1) ?: ""
        
        // 检测 SOCKS 版本 (socks4:// -> 4, socks5:// 或 socks:// -> 5)
        val version = when {
            node.rawLink.startsWith("socks4://") -> "4"
            else -> "5"
        }

        return JsonObject().apply {
            addProperty("type", "socks")
            addProperty("server", node.server)
            addProperty("server_port", node.port)
            addProperty("version", version)
            if (username.isNotEmpty()) {
                addProperty("username", username)
                addProperty("password", password)
            }
        }
    }
    
    private fun createHttpOutbound(node: Node): JsonObject {
        val uri = Uri.parse(node.rawLink)
        val userInfo = uri.userInfo ?: ""
        val parts = userInfo.split(":")
        val username = parts.getOrNull(0) ?: ""
        val password = parts.getOrNull(1) ?: ""
        val useTls = node.rawLink.startsWith("https://")

        return JsonObject().apply {
            addProperty("type", "http")
            addProperty("server", node.server)
            addProperty("server_port", node.port)
            if (username.isNotEmpty()) {
                addProperty("username", username)
                addProperty("password", password)
            }
            if (useTls) {
                add("tls", JsonObject().apply {
                    addProperty("enabled", true)
                    addProperty("server_name", node.server)
                })
            }
        }
    }
    
    private fun createTransport(type: String, uri: Uri): JsonObject {
        return JsonObject().apply {
            addProperty("type", type)
            when (type) {
                "ws" -> {
                    addProperty("path", uri.getQueryParameter("path") ?: "/")
                    uri.getQueryParameter("host")?.let {
                        add("headers", JsonObject().apply { addProperty("Host", it) })
                    }
                }
                "grpc" -> addProperty("service_name", uri.getQueryParameter("serviceName") ?: uri.getQueryParameter("path") ?: "")
                "http" -> {
                    addProperty("path", uri.getQueryParameter("path") ?: "/")
                    uri.getQueryParameter("host")?.let { add("host", JsonArray().apply { add(it) }) }
                }
            }
        }
    }
    
    private fun createVmessTransport(network: String, config: JsonObject): JsonObject {
        return JsonObject().apply {
            addProperty("type", network)
            when (network) {
                "ws" -> {
                    addProperty("path", config.get("path")?.asString ?: "/")
                    config.get("host")?.asString?.let {
                        add("headers", JsonObject().apply { addProperty("Host", it) })
                    }
                }
                "grpc" -> addProperty("service_name", config.get("path")?.asString ?: "")
                "h2" -> {
                    addProperty("path", config.get("path")?.asString ?: "/")
                    config.get("host")?.asString?.let {
                        add("host", JsonArray().apply { add(it) })
                    }
                }
            }
        }
    }
    
    private fun createRoute(proxyMode: ProxyMode, nodeDomains: List<String>, nodeIPs: List<String>, bypassLan: Boolean = true): JsonObject {
        val rules = JsonArray()
        
        // 1. Hijack DNS Traffic
        rules.add(JsonObject().apply {
            addProperty("protocol", "dns")
            addProperty("outbound", "dns-out")
        })
        
        // 2. Loopback/Private IPs -> Direct (仅当 bypassLan 开启时)
        if (bypassLan) {
            rules.add(JsonObject().apply {
                addProperty("ip_is_private", true)
                addProperty("outbound", "direct")
            })
        }

        // 3. Proxy Server Domains -> Direct (Avoid Loop)
        if (nodeDomains.isNotEmpty()) {
            rules.add(JsonObject().apply {
                add("domain", JsonArray().apply { nodeDomains.forEach { add(it) } })
                addProperty("outbound", "direct")
            })
        }
        
        if (proxyMode == ProxyMode.SMART) {
            // Hardcoded Smart Routing Rules
            // 4. Common CN Domains -> Direct
            // 4. Common CN Domains -> Direct
            rules.add(JsonObject().apply {
                add("domain_suffix", JsonArray().apply {
                     add("cn")
                     // Bilibili
                     add("bilibili.com"); add("bilivideo.com"); add("bilivideo.cn"); add("biliapi.com"); add("biliapi.net"); add("hdslb.com")
                     // Alibaba / Taobao / Alipay
                     add("alibaba.com"); add("alibabagroup.com"); add("alicdn.com"); add("alikunlun.com"); add("alipay.com"); add("alipayobjects.com")
                     add("aliyun.com"); add("aliyuncdn.com"); add("aliyuncs.com"); add("mmstat.com"); add("tanx.com"); add("taobao.com"); add("tmall.com")
                     // Tencent / WeChat
                     add("qq.com"); add("tencent.com"); add("weixin.com"); add("wechat.com"); add("qzone.com"); add("qcloud.com"); add("myqcloud.com")
                     add("gtimg.com"); add("qpic.cn"); add("qlogo.cn"); add("weixinbridge.com")
                     // ByteDance / Douyin / Toutiao
                     add("bytedance.com"); add("douyin.com"); add("douyinpic.com"); add("douyinvod.com"); add("idouyinvod.com")
                     add("snssdk.com"); add("pstatp.com"); add("ixigua.com"); add("byteimg.com"); add("toutiao.com"); add("toutiaocloud.com")
                     // NetEase
                     add("163.com"); add("126.net"); add("127.net"); add("netease.com"); add("music.126.net"); add("ydstatic.com")
                     // Baidu
                     add("baidu.com"); add("baidustatic.com"); add("bdstatic.com"); add("bdimg.com"); add("bcebos.com")
                     // XiaoMi
                     add("mi.com"); add("xiaomi.com"); add("xiaomiyoupin.com")
                     // JD
                     add("jd.com"); add("jd.hk"); add("360buy.com"); add("360buyimg.com")
                     // Others
                     add("zhihu.com"); add("zhimg.com"); add("weibo.com"); add("sina.com.cn"); add("sinajs.cn")
                     add("xiaohongshu.com"); add("xhscdn.com"); add("meituan.com"); add("dianping.com"); add("ele.me")
                     add("360.cn"); add("360.com"); add("sohu.com"); add("sogou.com"); add("xunlei.com")
                     add("csdn.net"); add("cnblogs.com"); add("oschina.net")
                     add("gitee.com")
                     add("amap.com"); add("autonavi.com"); add("gaode.com")
                     add("iqiyi.com"); add("iqiyipic.com"); add("71.am"); add("youku.com"); add("ip138.com")
                     add("bce.baidu.com")
                })
                addProperty("outbound", "direct")
            })

            // 5. Private IPs & CN DNS -> Direct
            // 5. Private IPs & CN IPs -> Direct
            // 5. Private IPs & CN IPs -> Direct
            rules.add(JsonObject().apply {
                add("ip_cidr", JsonArray().apply {
                    // Private IPv4
                    add("10.0.0.0/8"); add("172.16.0.0/12"); add("192.168.0.0/16")
                    add("127.0.0.0/8")
                    // Private IPv6
                    add("fc00::/7"); add("fe80::/10")
                    
                    // CN DNS
                    add("223.5.5.5/32"); add("223.6.6.6/32")
                    add("119.29.29.29/32"); add("114.114.114.114/32")
                    add("180.76.76.76/32")
                })
                addProperty("outbound", "direct")
            })
        }
        
        // 6. Proxy Server IPs -> Direct (在所有模式下都需要，避免路由死循环)
        if (nodeIPs.isNotEmpty()) {
            rules.add(JsonObject().apply {
                add("ip_cidr", JsonArray().apply {
                    nodeIPs.forEach { 
                        if (it.contains(":")) {
                            // 移除可能存在的方括号 (sing-box ip_cidr 不支持方括号)
                            val cleanIp = it.replace("[", "").replace("]", "")
                            add("$cleanIp/128") // IPv6
                        } else {
                            add("$it/32") // IPv4
                        }
                    }
                })
                addProperty("outbound", "direct")
            })
        }
        
        return JsonObject().apply {
            add("rules", rules)
            addProperty("final", "proxy")
            addProperty("auto_detect_interface", true)
        }
    }
    

    
    private fun createExperimental(): JsonObject {
        return JsonObject().apply {
            add("cache_file", JsonObject().apply { addProperty("enabled", true) })
            add("clash_api", JsonObject().apply { addProperty("external_controller", "127.0.0.1:9090") })
        }
    }


}
