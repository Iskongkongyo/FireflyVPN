package xyz.a202132.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import io.nekohasekai.libbox.Libbox
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.service.HeadlessPlatformInterface
import xyz.a202132.app.util.SingBoxConfigGenerator
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * URL Test 管理器
 * 启动临时无头 sing-box 实例进行 HTTP 握手延迟测试
 * 不需要 VPN 权限，配置中没有 TUN inbound
 */
object UrlTestManager {
    
    private const val TAG = "UrlTestManager"
    const val CLASH_API_PORT = 19090
    
    private var commandServer: io.nekohasekai.libbox.CommandServer? = null
    private var isRunning = false
    
    /**
     * 检测当前活动网络接口名称
     */
    private fun detectActiveInterface(context: Context): String? {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetwork ?: return null
            val linkProperties = cm.getLinkProperties(activeNetwork) ?: return null
            return linkProperties.interfaceName
        } catch (e: Exception) {
            Log.e(TAG, "Failed to detect active interface", e)
            return null
        }
    }
    
    /**
     * 启动临时 sing-box 实例用于 URL 测试
     */
    @Synchronized
    fun start(context: Context, nodes: List<Node>): Boolean {
        if (isRunning) {
            Log.w(TAG, "URL Test instance already running")
            return true
        }
        
        return try {
            Log.i(TAG, "Starting headless sing-box for URL test (${nodes.size} nodes)")
            
            // 检测活动网络接口
            val activeInterface = detectActiveInterface(context)
            Log.i(TAG, "Active interface: $activeInterface")
            
            // 使用与 VPN 服务相同的工作目录
            val workDir = File(context.filesDir, "sing-box")
            if (!workDir.exists()) workDir.mkdirs()
            
            // 日志文件 - 用于调试
            val logFile = File(workDir, "urltest.log")
            if (logFile.exists()) logFile.delete()
            
            val options = io.nekohasekai.libbox.SetupOptions().apply {
                basePath = workDir.absolutePath
                workingPath = workDir.absolutePath
                tempPath = context.cacheDir.absolutePath
            }
            Libbox.setup(options)
            
            // 生成测试配置 (无 TUN，有 ClashAPI，指定网络接口)
            val configGenerator = SingBoxConfigGenerator()
            val configContent = configGenerator.generateUrlTestConfig(nodes, CLASH_API_PORT, activeInterface, logFile.absolutePath)
            Log.d(TAG, "Test config generated, length: ${configContent.length}")
            
            // 创建 CommandServerHandler
            val serverHandler = object : io.nekohasekai.libbox.CommandServerHandler {
                override fun serviceStop() {
                    Log.d(TAG, "URL Test service stop requested")
                }
                override fun serviceReload() {}
                override fun getSystemProxyStatus(): io.nekohasekai.libbox.SystemProxyStatus {
                    return io.nekohasekai.libbox.SystemProxyStatus()
                }
                override fun setSystemProxyEnabled(enabled: Boolean) {}
                override fun writeDebugMessage(message: String?) {
                    Log.d(TAG, "Debug: $message")
                }
            }
            
            // 使用无头平台接口 (不需要 VPN/TUN)
            val platformInterface = HeadlessPlatformInterface(context)
            
            // 创建并启动 CommandServer
            commandServer = Libbox.newCommandServer(serverHandler, platformInterface)
            commandServer?.start()
            
            // 启动 sing-box 服务
            val overrideOptions = io.nekohasekai.libbox.OverrideOptions()
            commandServer?.startOrReloadService(configContent, overrideOptions)
            
            isRunning = true
            Log.i(TAG, "Headless sing-box started, ClashAPI at 127.0.0.1:$CLASH_API_PORT")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start headless sing-box", e)
            cleanup()
            false
        }
    }
    
    /**
     * 诊断：查询 ClashAPI /proxies 端点
     */
    fun diagnoseProxies(): String? {
        return try {
            val url = URL("http://127.0.0.1:$CLASH_API_PORT/proxies")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            response
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query /proxies", e)
            null
        }
    }
    
    /**
     * 读取 sing-box 核心日志文件（最后 100 行）
     */
    fun readLogFile(context: Context): String? {
        return try {
            val logFile = File(File(context.filesDir, "sing-box"), "urltest.log")
            if (logFile.exists()) {
                val lines = logFile.readLines()
                val last100 = lines.takeLast(100)
                last100.joinToString("\n")
            } else {
                Log.w(TAG, "Log file not found")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read log file", e)
            null
        }
    }
    
    /**
     * 停止临时 sing-box 实例
     */
    @Synchronized
    fun stop() {
        if (!isRunning) return
        
        Log.i(TAG, "Stopping headless sing-box")
        cleanup()
    }
    
    private fun cleanup() {
        try {
            commandServer?.closeService()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing service", e)
        }
        try {
            commandServer?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing command server", e)
        }
        commandServer = null
        isRunning = false
    }
    
    fun isRunning(): Boolean = isRunning
}
