package xyz.a202132.app.network

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import xyz.a202132.app.AppConfig
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeType
import android.net.Uri
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * 订阅解析器 - 解析Base64编码的节点列表
 */
class SubscriptionParser(context: Context, clientOverride: OkHttpClient? = null) {
    
    private val gson = Gson()
    private val tag = "SubscriptionParser"
    private val apiService = clientOverride?.let(NetworkClient::createApiService) ?: NetworkClient.apiService
    private val fireflySubscriptionClient = FireflySubscriptionClient(context, clientOverride)

    private companion object {
        val SUPPORTED_SCHEMES = listOf(
            "vless", "vmess", "trojan", "hysteria2", "hy2", "anytls", "tuic",
            "naive", "naive+https", "wireguard", "ss", "socks", "socks5",
            "socks4", "socks4a", "http", "https"
        )
    }
    
    /**
     * 从订阅URL获取并解析节点列表
     */
    suspend fun fetchAndParse(
        url: String = AppConfig.SUBSCRIPTION_URL,
        userAgent: String = AppConfig.HTTP_USER_AGENT
    ): Result<List<Node>> {
        return try {
            val response = withTimeout(AppConfig.NODE_REQUEST_TIMEOUT_MS) {
                if (isTrustedFireflyUrl(url)) {
                    fireflySubscriptionClient.fetch(url, userAgent)
                } else {
                    apiService.getSubscription(url, userAgent)
                }
            }
            val nodes = parseSubscription(response)
            Result.success(nodes)
        } catch (e: Exception) {
            Log.e(tag, "Failed to fetch subscription", e)
            Result.failure(e)
        }
    }
    
    /**
     * 解析订阅内容
     */
    fun parseSubscription(content: String): List<Node> {
        Log.d(tag, "Original content length: ${content.length}")
        val normalized = content.trim()
        val finalContent = if (containsSupportedNodeLink(normalized)) {
            normalized
        } else {
            runCatching { decodeBase64Compat(normalized) }
                .getOrNull()
                ?.takeIf(::containsSupportedNodeLink)
                ?: normalized
        }
        
        val lines = finalContent.split("\n", "\r\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            
        Log.d(tag, "Parsed ${lines.size} lines")
        
        return lines.mapNotNull { parseNodeLink(it) }
    }

    /** Fetches the built-in catalog without flattening its server-defined groups. */
    suspend fun fetchAndParseFireflyCatalog(
        url: String = AppConfig.SUBSCRIPTION_URL,
        userAgent: String = AppConfig.HTTP_USER_AGENT
    ): Result<List<ParsedFireflySubscriptionGroup>> = runCatching {
        require(isTrustedFireflyUrl(url)) { "不是受信任的 Firefly 线路目录" }
        withTimeout(AppConfig.NODE_REQUEST_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                fireflySubscriptionClient.fetchCatalogSubscriptions(url, userAgent).map { entry ->
                    ParsedFireflySubscriptionGroup(
                        sourceId = entry.sourceId,
                        name = entry.name,
                        nodes = entry.content?.let(::parseSubscription),
                        error = entry.error
                    )
                }
            }
        }
    }

    /** 获取单个内置目录分组，避免故障恢复时刷新无关分组。 */
    suspend fun fetchAndParseFireflyCatalogGroup(
        sourceId: String?,
        url: String = AppConfig.SUBSCRIPTION_URL,
        userAgent: String = AppConfig.HTTP_USER_AGENT
    ): Result<ParsedFireflySubscriptionGroup> = runCatching {
        require(isTrustedFireflyUrl(url)) { "不是受信任的 Firefly 线路目录" }
        withTimeout(AppConfig.NODE_REQUEST_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                val entry = fireflySubscriptionClient.fetchCatalogSubscription(url, userAgent, sourceId)
                ParsedFireflySubscriptionGroup(
                    sourceId = entry.sourceId,
                    name = entry.name,
                    nodes = entry.content?.let(::parseSubscription),
                    error = entry.error
                )
            }
        }
    }

    private fun containsSupportedNodeLink(value: String): Boolean =
        SUPPORTED_SCHEMES.any { scheme -> value.contains("$scheme://", ignoreCase = true) }
    
    /**
     * 解析单个节点链接
     */
    fun parseNodeLink(link: String): Node? {
        return try {
            val lowerLink = link.lowercase()
            when {
                lowerLink.startsWith("vless://") -> parseVlessLink(link)
                lowerLink.startsWith("vmess://") -> parseVmessLink(link)
                lowerLink.startsWith("trojan://") -> parseTrojanLink(link)
                lowerLink.startsWith("hysteria2://") || lowerLink.startsWith("hy2://") -> parseHysteria2Link(link)
                lowerLink.startsWith("anytls://") -> parseAnyTlsLink(link)
                lowerLink.startsWith("tuic://") -> parseTuicLink(link)
                lowerLink.startsWith("naive://") || lowerLink.startsWith("naive+https://") -> parseNaiveLink(link)
                lowerLink.startsWith("wireguard://") -> parseWireGuardLink(link)
                lowerLink.startsWith("ss://") -> parseShadowsocksLink(link)
                lowerLink.startsWith("socks://") || lowerLink.startsWith("socks5://") || lowerLink.startsWith("socks4://") || lowerLink.startsWith("socks4a://") -> parseSocksLink(link)
                lowerLink.startsWith("http://") || lowerLink.startsWith("https://") -> parseHttpLink(link)
                else -> null
            }
        } catch (e: Exception) {
            val scheme = link.substringBefore("://", "unknown")
            Log.e(tag, "Failed to parse link, scheme=$scheme, length=${link.length}", e)
            null
        }
    }
    
    /**
     * 解析 VLESS 链接
     * 格式: vless://uuid@host:port?params#name
     */
    private fun parseVlessLink(link: String): Node {
        val uri = Uri.parse(link)
        val name = URLDecoder.decode(uri.fragment ?: "VLESS Node", "UTF-8")
        val server = uri.host ?: ""
        val port = if (uri.port == -1) 80 else uri.port
        
        return Node(
            id = generateId(link),
            name = name,
            type = NodeType.VLESS,
            server = server,
            port = port,
            rawLink = link
        )
    }
    
    /**
     * 解析 VMess 链接
     * 格式: vmess://base64-encoded-json
     */
    private fun parseVmessLink(link: String): Node {
        val base64Content = link.removePrefix("vmess://")
        val jsonStr = decodeBase64Compat(base64Content)
        val json = gson.fromJson(jsonStr, JsonObject::class.java)
        
        val name = json.get("ps")?.asString ?: "VMess Node"
        val server = json.get("add")?.asString ?: ""
        val port = json.get("port")?.asInt ?: json.get("port")?.asString?.toIntOrNull() ?: 443
        
        return Node(
            id = generateId(link),
            name = name,
            type = NodeType.VMESS,
            server = server,
            port = port,
            rawLink = link
        )
    }
    
    /**
     * 解析 Trojan 链接
     * 格式: trojan://password@host:port?params#name
     */
    private fun parseTrojanLink(link: String): Node {
        val uri = Uri.parse(link)
        val name = URLDecoder.decode(uri.fragment ?: "Trojan Node", "UTF-8")
        val server = uri.host ?: ""
        val port = if (uri.port == -1) 443 else uri.port
        
        return Node(
            id = generateId(link),
            name = name,
            type = NodeType.TROJAN,
            server = server,
            port = port,
            rawLink = link
        )
    }
    
    /**
     * 解析 Hysteria2 链接
     * 格式: hysteria2://password@host:port?params#name
     */
    private fun parseHysteria2Link(link: String): Node {
        val normalizedLink = link.replace("hy2://", "hysteria2://")
        val uri = Uri.parse(normalizedLink)
        val name = URLDecoder.decode(uri.fragment ?: "Hysteria2 Node", "UTF-8")
        val endpoint = parseHysteria2Endpoint(normalizedLink)
        val server = endpoint.first ?: (uri.host ?: "")
        val port = endpoint.second ?: if (uri.port == -1) 443 else uri.port
        
        return Node(
            id = generateId(link),
            name = name,
            type = NodeType.HYSTERIA2,
            server = server,
            port = port,
            rawLink = link
        )
    }

    /**
     * 解析 AnyTLS 链接
     * 格式: anytls://password@host:port?params#name
     */
    private fun parseAnyTlsLink(link: String): Node {
        val uri = Uri.parse(link)
        val name = URLDecoder.decode(uri.fragment ?: "AnyTLS Node", "UTF-8")
        val server = uri.host ?: ""
        val port = if (uri.port == -1) 443 else uri.port

        return Node(
            id = generateId(link),
            name = name,
            type = NodeType.ANYTLS,
            server = server,
            port = port,
            rawLink = link
        )
    }

    /**
     * 解析 TUIC 链接
     * 格式: tuic://uuid:password@host:port?params#name
     */
    private fun parseTuicLink(link: String): Node {
        val uri = Uri.parse(link)
        val name = URLDecoder.decode(uri.fragment ?: "TUIC Node", "UTF-8")
        val server = uri.host ?: ""
        val port = if (uri.port == -1) 443 else uri.port

        return Node(
            id = generateId(link),
            name = name,
            type = NodeType.TUIC,
            server = server,
            port = port,
            rawLink = link
        )
    }

    /**
     * 解析 Naive 链接
     * 格式: naive+https://username:password@host:port?params#name
     */
    private fun parseNaiveLink(link: String): Node {
        val uri = Uri.parse(link)
        val name = URLDecoder.decode(uri.fragment ?: "Naive Node", "UTF-8")
        val server = uri.host ?: ""
        val port = if (uri.port == -1) 443 else uri.port

        return Node(
            id = generateId(link),
            name = name,
            type = NodeType.NAIVE,
            server = server,
            port = port,
            rawLink = link
        )
    }

    /**
     * 解析 WireGuard 链接
     * 格式: wireguard://private_key@host:port?params#name
     */
    private fun parseWireGuardLink(link: String): Node {
        val uri = Uri.parse(link)
        val name = URLDecoder.decode(uri.fragment ?: "WireGuard Node", "UTF-8")
        val server = uri.host ?: ""
        val port = if (uri.port == -1) 51820 else uri.port

        return Node(
            id = generateId(link),
            name = name,
            type = NodeType.WIREGUARD,
            server = server,
            port = port,
            rawLink = link
        )
    }
    
    /**
     * 解析 Shadowsocks 链接
     * 格式: ss://base64(method:password)@host:port#name
     * 或: ss://base64(method:password@host:port)#name
     */
    private fun parseShadowsocksLink(link: String): Node {
        val uri = Uri.parse(link)
        val name = URLDecoder.decode(uri.fragment ?: "Shadowsocks Node", "UTF-8")
        
        var server = uri.host
        var port = uri.port
        
        // 如果host为空，说明整个部分都被base64编码了
        if (server == null) {
            val base64Part = link.removePrefix("ss://").substringBefore("#")
            val decoded = decodeBase64Compat(base64Part)
            // 格式: method:password@host:port
            val hostPort = decoded.substringAfter("@")
            server = hostPort.substringBefore(":")
            port = hostPort.substringAfter(":").toIntOrNull() ?: 443
        }
        
        return Node(
            id = generateId(link),
            name = name,
            type = NodeType.SHADOWSOCKS,
            server = server,
            port = if (port == -1) 443 else port,
            rawLink = link
        )
    }

    /**
     * 解析 Socks 链接
     * 格式: socks://user:pass@host:port#name
     * 也支持: socks5://... 和 socks4://...
     */
    private fun parseSocksLink(link: String): Node {
        val uri = Uri.parse(link)
        val name = URLDecoder.decode(uri.fragment ?: "Socks Node", "UTF-8")
        val server = uri.host ?: ""
        val port = if (uri.port == -1) 1080 else uri.port
        
        return Node(
            id = generateId(link),
            name = name,
            type = NodeType.SOCKS,
            server = server,
            port = port,
            rawLink = link
        )
    }
    
    /**
     * 解析 HTTP/HTTPS 代理链接
     * 格式: http://user:pass@host:port#name
     * 或: https://user:pass@host:port#name
     */
    private fun parseHttpLink(link: String): Node {
        val uri = Uri.parse(link)
        val name = URLDecoder.decode(uri.fragment ?: "HTTP Proxy", "UTF-8")
        val server = uri.host ?: ""
        val defaultPort = if (link.startsWith("https://")) 443 else 80
        val port = if (uri.port == -1) defaultPort else uri.port
        
        return Node(
            id = generateId(link),
            name = name,
            type = NodeType.HTTP,
            server = server,
            port = port,
            rawLink = link
        )
    }
    
    /**
     * 生成节点ID (MD5 hash)
     */
    private fun generateId(link: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(link.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun isTrustedFireflyUrl(url: String): Boolean {
        val trusted = Uri.parse(AppConfig.SUBSCRIPTION_URL)
        val candidate = Uri.parse(url)
        return candidate.scheme.equals("https", ignoreCase = true) &&
            candidate.host.equals(trusted.host, ignoreCase = true) &&
            candidate.port == trusted.port
    }

    private fun decodeBase64Compat(rawValue: String): String {
        val normalized = rawValue.trim()
            .replace('-', '+')
            .replace('_', '/')
        val padding = (4 - normalized.length % 4) % 4
        val padded = normalized + "=".repeat(padding)
        return String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
    }

    private fun parseHysteria2Endpoint(link: String): Pair<String?, Int?> {
        val authority = link.substringAfter("@", "").substringBefore("?").substringBefore("#").trim()
        if (authority.isEmpty()) return null to null

        return if (authority.startsWith("[")) {
            val endBracket = authority.indexOf(']')
            if (endBracket <= 0) {
                null to null
            } else {
                val host = authority.substring(1, endBracket)
                val portSpec = authority.substring(endBracket + 1).removePrefix(":")
                host to parseFirstPort(portSpec)
            }
        } else {
            val lastColon = authority.lastIndexOf(':')
            if (lastColon <= 0) {
                authority to null
            } else {
                val host = authority.substring(0, lastColon)
                val portSpec = authority.substring(lastColon + 1)
                host to parseFirstPort(portSpec)
            }
        }
    }

    private fun parseFirstPort(portSpec: String): Int? {
        return portSpec
            .substringBefore(",")
            .substringBefore("-")
            .trim()
            .toIntOrNull()
    }
}

data class ParsedFireflySubscriptionGroup(
    val sourceId: String,
    val name: String,
    val nodes: List<Node>? = null,
    val error: Throwable? = null
)
