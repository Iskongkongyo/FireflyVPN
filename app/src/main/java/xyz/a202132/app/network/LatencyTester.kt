package xyz.a202132.app.network

import android.util.Log
import kotlinx.coroutines.*
import xyz.a202132.app.data.model.Node
import java.net.HttpURLConnection
import java.net.URL

/**
 * 节点延迟测试器
 * 支持 TCPing (Socket) 和 URL Test (ClashAPI HTTP)
 */
class LatencyTester {
    
    private val tag = "LatencyTester"
    
    data class LatencyResult(
        val nodeId: String,
        val latency: Int,
        val isAvailable: Boolean
    )
    
    // ==================== TCPing (Socket) ====================
    
    /**
     * 测试单个节点 - Socket 连接测试
     * 如果连接成功或被拒绝(RST)，都视为网络连通
     */
    private suspend fun testNodeSimple(node: Node): LatencyResult {
        return try {
            Log.d(tag, ">>> TCPing: ${node.name} (${node.server}:${node.port})")
            
            val start = System.currentTimeMillis()
            val socket = java.net.Socket()
            
            try {
                socket.connect(java.net.InetSocketAddress(node.server, node.port), xyz.a202132.app.AppConfig.TCPING_TEST_TIMEOUT.toInt())
                
                val elapsed = (System.currentTimeMillis() - start).toInt() 
                val finalLatency = if (elapsed < 1) 1 else elapsed
                
                Log.d(tag, "<<< TCPing OK: ${node.name}, ${finalLatency}ms")
                LatencyResult(node.id, finalLatency, true)
                
            } catch (e: java.net.ConnectException) {
                val elapsed = (System.currentTimeMillis() - start).toInt()
                val finalLatency = if (elapsed < 1) 1 else elapsed
                
                if (e.message?.contains("refused") == true) {
                    Log.d(tag, "<<< TCPing REFUSED (Available): ${node.name}, ${finalLatency}ms")
                    LatencyResult(node.id, finalLatency, true)
                } else {
                    throw e
                }
            } finally {
                runCatching { socket.close() }
            }
            
        } catch (e: java.net.SocketTimeoutException) {
            Log.w(tag, "<<< TCPing TIMEOUT: ${node.name}")
            LatencyResult(node.id, -2, false)
        } catch (e: Exception) {
            Log.e(tag, "<<< TCPing FAILED: ${node.name}, error=${e.message}")
            LatencyResult(node.id, -2, false)
        }
    }
    
    /**
     * 批量 TCPing - 并发执行
     */
    suspend fun testAllNodes(
        nodes: List<Node>, 
        onProgress: ((completed: Int, total: Int) -> Unit)? = null
    ): List<LatencyResult> = withContext(Dispatchers.IO) {
        Log.d(tag, "========== TCPing START: ${nodes.size} nodes ==========")
        
        val semaphore = kotlinx.coroutines.sync.Semaphore(xyz.a202132.app.AppConfig.TCPING_CONCURRENCY)
        val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val totalCount = nodes.size
        
        val deferredResults = nodes.map { node ->
            async {
                semaphore.acquire()
                try {
                    val result = testNodeSimple(node)
                    val current = completedCount.incrementAndGet()
                    onProgress?.invoke(current, totalCount)
                    result
                } finally {
                    semaphore.release()
                }
            }
        }
        
        val results = deferredResults.awaitAll()
        Log.d(tag, "========== TCPing END: ${results.size} results ==========")
        results
    }
    
    // 保持接口兼容
    suspend fun testNode(node: Node): LatencyResult = withContext(Dispatchers.IO) {
        testNodeSimple(node)
    }
    
    // ==================== URL Test (ClashAPI) ====================
    
    companion object {
        // Moved to AppConfig
    }
    
    /**
     * 通过 ClashAPI 测试单个节点的 HTTP 握手延迟
     * 需要 VPN 已连接（sing-box 运行中）
     */
    private suspend fun urlTestNode(node: Node, clashApiPort: Int): LatencyResult = withContext(Dispatchers.IO) {
        try {
            Log.d(tag, ">>> URL Test: ${node.name} (${node.id})")
            
            val encodedId = java.net.URLEncoder.encode(node.id, "UTF-8")
            val targetUrl = java.net.URLEncoder.encode(xyz.a202132.app.AppConfig.URL_TEST_URL, "UTF-8")
            val timeout = xyz.a202132.app.AppConfig.URL_TEST_TIMEOUT.toInt()
            
            val apiUrl = "http://127.0.0.1:$clashApiPort/proxies/$encodedId/delay?url=$targetUrl&timeout=$timeout"
            
            val connection = URL(apiUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = timeout + 1000
            connection.readTimeout = timeout + 1000
            connection.requestMethod = "GET"
            
            try {
                val responseCode = connection.responseCode
                
                if (responseCode == 200) {
                    val responseBody = connection.inputStream.bufferedReader().readText()
                    // Response: {"delay": 123}
                    val delayMatch = Regex("\"delay\"\\s*:\\s*(\\d+)").find(responseBody)
                    val delay = delayMatch?.groupValues?.get(1)?.toIntOrNull()
                    
                    if (delay != null && delay > 0) {
                        Log.d(tag, "<<< URL Test OK: ${node.name}, ${delay}ms")
                        LatencyResult(node.id, delay, true)
                    } else {
                        Log.w(tag, "<<< URL Test invalid delay: ${node.name}, response=$responseBody")
                        LatencyResult(node.id, -2, false)
                    }
                } else {
                    // 可能是 408 (timeout) 或其他错误
                    val errorBody = try { connection.errorStream?.bufferedReader()?.readText() } catch (e: Exception) { null }
                    Log.w(tag, "<<< URL Test HTTP $responseCode: ${node.name}, error=$errorBody")
                    LatencyResult(node.id, -2, false)
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.e(tag, "<<< URL Test FAILED: ${node.name}, error=${e.message}")
            LatencyResult(node.id, -2, false)
        }
    }
    
    /**
     * 批量 URL Test - 并发执行 (通过 ClashAPI)
     * @param clashApiPort ClashAPI 端口 (VPN=9090, 无头=19090)
     */
    suspend fun urlTestAllNodes(
        nodes: List<Node>, 
        clashApiPort: Int = 9090,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null
    ): List<LatencyResult> = withContext(Dispatchers.IO) {
        Log.d(tag, "========== URL Test START: ${nodes.size} nodes (port=$clashApiPort) ==========")
        
        // 并发限制 10，避免ClashAPI过载
        val semaphore = kotlinx.coroutines.sync.Semaphore(xyz.a202132.app.AppConfig.URL_TEST_CONCURRENCY)
        val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val totalCount = nodes.size
        
        val deferredResults = nodes.map { node ->
            async {
                semaphore.acquire()
                try {
                    val result = urlTestNode(node, clashApiPort)
                    val current = completedCount.incrementAndGet()
                    onProgress?.invoke(current, totalCount)
                    result
                } finally {
                    semaphore.release()
                }
            }
        }
        
        val results = deferredResults.awaitAll()
        Log.d(tag, "========== URL Test END: ${results.size} results ==========")
        results
    }
}
