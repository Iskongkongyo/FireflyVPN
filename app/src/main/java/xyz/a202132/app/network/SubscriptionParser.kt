package xyz.a202132.app.network

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import xyz.a202132.app.AppConfig
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeType
import android.net.Uri
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * 订阅解析器 - 解析Base64编码的节点列表
 */
class SubscriptionParser {
    
    private val gson = Gson()
    private val tag = "SubscriptionParser"
    
    /**
     * 从订阅URL获取并解析节点列表
     */
    suspend fun fetchAndParse(url: String = AppConfig.SUBSCRIPTION_URL): Result<List<Node>> {
        return try {
            val response = NetworkClient.apiService.getSubscription(url)
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
        Log.d(tag, "Original content preview: ${content.take(100)}...")

        var finalContent = ""
        
        try {
            var decrypted = xyz.a202132.app.util.CryptoUtils.decryptNodes(content.trim())
            Log.d(tag, "Decryption result preview: ${decrypted.take(100)}...")
             
            // 如果解密成功但不含 "://"，可能是先 Base64 编码再 AES 加密的
            // 尝试再做一次 Base64 解码
            if (!decrypted.startsWith("Error") && !decrypted.contains("://")) {
                Log.d(tag, "Decrypted content has no '://', trying extra Base64 decode...")
                try {
                    val extraDecoded = String(Base64.decode(decrypted.trim(), Base64.DEFAULT), Charsets.UTF_8)
                    if (extraDecoded.contains("://")) {
                        decrypted = extraDecoded
                        Log.d(tag, "✅ Extra Base64 decode successful")
                    }
                } catch (e: Exception) {
                    Log.d(tag, "Extra Base64 decode failed, using original decrypted content")
                }
            }
             
            // 校验：如果解密结果包含 "://" (说明是明文链接)，且没有返回 Error
            if (!decrypted.startsWith("Error") && decrypted.contains("://")) {
                finalContent = decrypted
                Log.d(tag, "✅ AES decryption successful, using decrypted content")
            } else {
                Log.d(tag, "⚠️ AES decryption result invalid (no '://' or error header), falling back")
            }
        } catch (e: Exception) {
            Log.e(tag, "❌ AES decryption failed/skipped: ${e.message}")
        }

        // 如果 AES 没拿到有效内容，尝试 Legacy Base64 解码
        if (finalContent.isEmpty()) {
            Log.d(tag, "Falling back to Legacy Base64 decoding...")
            finalContent = try {
                String(Base64.decode(content.trim(), Base64.DEFAULT), Charsets.UTF_8)
            } catch (e: Exception) {
                // 如果 Base64 也挂了，可能是纯文本
                Log.w(tag, "Legacy Base64 decode failed, using raw content")
                content
            }
        }
        
        val lines = finalContent.split("\n", "\r\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            
        Log.d(tag, "Parsed ${lines.size} lines")
        
        return lines.mapNotNull { parseNodeLink(it) }
    }
    
    /**
     * 解析单个节点链接
     */
    private fun parseNodeLink(link: String): Node? {
        return try {
            when {
                link.startsWith("vless://") -> parseVlessLink(link)
                link.startsWith("vmess://") -> parseVmessLink(link)
                link.startsWith("trojan://") -> parseTrojanLink(link)
                link.startsWith("hysteria2://") || link.startsWith("hy2://") -> parseHysteria2Link(link)
                link.startsWith("ss://") -> parseShadowsocksLink(link)
                link.startsWith("socks://") -> parseSocksLink(link)
                else -> null
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse link: $link", e)
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
        val jsonStr = String(Base64.decode(base64Content, Base64.DEFAULT), Charsets.UTF_8)
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
        val server = uri.host ?: ""
        val port = if (uri.port == -1) 443 else uri.port
        
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
            val decoded = String(Base64.decode(base64Part, Base64.DEFAULT), Charsets.UTF_8)
            // 格式: method:password@host:port
            val hostPort = decoded.substringAfter("@")
            server = hostPort.substringBefore(":")
            port = hostPort.substringAfter(":").toIntOrNull() ?: 443
        }
        
        return Node(
            id = generateId(link),
            name = name,
            type = NodeType.SHADOWSOCKS,
            server = server ?: "",
            port = if (port == -1) 443 else port,
            rawLink = link
        )
    }

    /**
     * 解析 Socks 链接
     * 格式: socks://user:pass@host:port#name
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
     * 生成节点ID (MD5 hash)
     */
    private fun generateId(link: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(link.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
