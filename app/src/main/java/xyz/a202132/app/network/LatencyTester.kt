package xyz.a202132.app.network

import android.util.Log
import kotlinx.coroutines.*
import xyz.a202132.app.data.model.Node
import java.net.InetAddress

/**
 * 节点延迟测试器 - 简化版
 */
class LatencyTester {
    
    private val tag = "LatencyTester"
    
    data class LatencyResult(
        val nodeId: String,
        val latency: Int,
        val isAvailable: Boolean
    )
    
    /**
     * 测试单个节点 - Socket 连接测试
     * 如果连接成功或被拒绝(RST)，都视为网络连通
     */
    private suspend fun testNodeSimple(node: Node): LatencyResult {
        return try {
            Log.d(tag, ">>> START testing: ${node.name} (${node.server}:${node.port})")
            
            val start = System.currentTimeMillis()
            val socket = java.net.Socket()
            
            try {
                // 尝试连接，超时 3000ms
                socket.connect(java.net.InetSocketAddress(node.server, node.port), 3000)
                
                val elapsed = (System.currentTimeMillis() - start).toInt() 
                val finalLatency = if (elapsed < 1) 1 else elapsed
                
                Log.d(tag, "<<< SUCCESS: ${node.name}, time=${finalLatency}ms")
                LatencyResult(node.id, finalLatency, true)
                
            } catch (e: java.net.ConnectException) {
                // Connection refused (RST) 说明服务器在线但端口未开放或协议不匹配
                // 这也算连通，计算 RTT
                val elapsed = (System.currentTimeMillis() - start).toInt()
                val finalLatency = if (elapsed < 1) 1 else elapsed
                
                if (e.message?.contains("refused") == true) {
                    Log.d(tag, "<<< REFUSED (Available): ${node.name}, time=${finalLatency}ms")
                    LatencyResult(node.id, finalLatency, true)
                } else {
                    throw e
                }
            } finally {
                runCatching { socket.close() }
            }
            
        } catch (e: java.net.SocketTimeoutException) {
            Log.w(tag, "<<< TIMEOUT: ${node.name}")
            // 超时设为 -2 (无法直连)
            LatencyResult(node.id, -2, false)
        } catch (e: Exception) {
            Log.e(tag, "<<< FAILED: ${node.name}, error=${e.message}")
            LatencyResult(node.id, -2, false)
        }
    }
    
    /**
     * 批量测试 - 串行执行避免并发问题
     */
    /**
     * 批量测试 - 并发执行
     */
    suspend fun testAllNodes(nodes: List<Node>): List<LatencyResult> = withContext(Dispatchers.IO) {
        Log.d(tag, "========== BATCH TEST START: ${nodes.size} nodes (Concurrent) ==========")
        
        // 限制并发数为 16，避免过多 socket 占用 fd 或这被系统限制
        val semaphore = kotlinx.coroutines.sync.Semaphore(16)
        
        val deferredResults = nodes.map { node ->
            async {
                semaphore.acquire()
                try {
                    testNodeSimple(node)
                } finally {
                    semaphore.release()
                }
            }
        }
        
        val results = deferredResults.awaitAll()
        
        Log.d(tag, "========== BATCH TEST END: ${results.size} results ==========")
        results
    }
    
    // 保持接口兼容
    suspend fun testNode(node: Node): LatencyResult = withContext(Dispatchers.IO) {
        testNodeSimple(node)
    }
}
