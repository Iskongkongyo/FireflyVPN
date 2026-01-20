package xyz.a202132.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import io.nekohasekai.libbox.BoxService
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.*
import xyz.a202132.app.AppConfig
import xyz.a202132.app.MainActivity
import xyz.a202132.app.R
import xyz.a202132.app.data.model.ProxyMode
import xyz.a202132.app.util.SingBoxConfigGenerator
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.first

/**
 * VPN服务 - 使用sing-box核心
 */
class BoxVpnService : VpnService() {
    
    companion object {
        private const val TAG = "BoxVpnService"
        
        const val ACTION_START = "xyz.a202132.app.START_VPN"
        const val ACTION_STOP = "xyz.a202132.app.STOP_VPN"
        const val EXTRA_NODE_RAW_LINK = "node_raw_link"
        const val EXTRA_NODE_NAME = "node_name"
        const val EXTRA_PROXY_MODE = "proxy_mode"
        
        var isRunning = false
            private set
        
        var currentNodeName: String? = null
            private set
            
        // 当前流量统计
        var uploadSpeed: Long = 0L
            private set
        var downloadSpeed: Long = 0L
            private set
        var uploadTotal: Long = 0L
            private set
        var downloadTotal: Long = 0L
            private set
            
        // 静态引用方便外部调用 (注意内存泄漏风险，但在单进程VPN服务中通常可控，或者在onDestroy置空)
        private var instance: BoxVpnService? = null
        
        fun selectNode(nodeId: String) {
            instance?.selectNodeInternal(nodeId)
        }
    }
    
    private var boxService: BoxService? = null
    private var platformInterface: BoxPlatformInterface? = null
    private val configGenerator = SingBoxConfigGenerator()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val rawLink = intent.getStringExtra(EXTRA_NODE_RAW_LINK)
                val nodeName = intent.getStringExtra(EXTRA_NODE_NAME) ?: "Unknown"
                val proxyModeName = intent.getStringExtra(EXTRA_PROXY_MODE) ?: ProxyMode.SMART.name
                val proxyMode = try {
                    ProxyMode.valueOf(proxyModeName)
                } catch (e: Exception) {
                    ProxyMode.SMART
                }
                
                // 立即启动前台服务，避免 ForegroundServiceDidNotStartInTimeException
                startForeground(AppConfig.NOTIFICATION_ID, createNotification(nodeName))
                
                if (rawLink != null) {
                    startVpn(rawLink, nodeName, proxyMode)
                } else {
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopVpn()
            }
            else -> {
                // 未知 action，启动前台服务然后停止
                startForeground(AppConfig.NOTIFICATION_ID, createNotification("正在停止..."))
                stopSelf()
            }
        }
        return START_STICKY
    }
    
    private fun startVpn(rawLink: String, nodeName: String, proxyMode: ProxyMode) {
        Log.d(TAG, "Starting VPN for node: $nodeName")
        
        serviceScope.launch {
            if (isRunning) {
                Log.w(TAG, "VPN is already running, stopping first")
                stopVpnInternal()
            }
            
            try {
                // 1. 获取所有节点 (用于生成包含所有节点的配置)
                val nodeDao = xyz.a202132.app.data.local.AppDatabase.getInstance(application).nodeDao()
                // 使用 Flow 的 first() 获取当前节点列表
                val allNodes = try {
                    nodeDao.getAllNodes().first()
                } catch (e: Exception) {
                    emptyList<xyz.a202132.app.data.model.Node>()
                }
                
                if (allNodes.isEmpty()) {
                    Log.e(TAG, "No nodes available")
                     withContext(Dispatchers.Main) {
                        ServiceManager.notifyError("没有可用节点")
                    }
                    return@launch
                }
                
                // 2. 确定选中的节点ID
                // 如果传入的是 specific node (非 Auto)，找到它的 ID
                // 这里的 rawLink 可能只是为了兼容旧逻辑，实际我们更关心 nodeName 或 ID
                // 但为了保险，我们尝试匹配 rawLink 对应的节点
                var selectedNodeId: String? = null
                if (nodeName != "自动选择") {
                    selectedNodeId = allNodes.find { it.rawLink == rawLink }?.id
                }
                
                // 读取绕过局域网设置
                val settingsRepo = xyz.a202132.app.data.repository.SettingsRepository(application)
                val bypassLan = try {
                    settingsRepo.bypassLan.first()
                } catch (e: Exception) {
                    true // 默认开启
                }
                
                Log.d(TAG, "Generating config with ${allNodes.size} nodes, selected: $selectedNodeId, bypassLan: $bypassLan")

                // 3. 生成sing-box配置
                val config = configGenerator.generateConfig(allNodes, selectedNodeId, proxyMode, bypassLan)
                val configFile = saveConfigToFile(config)
                
                // 初始化 libbox
                initializeLibbox(configFile.absolutePath, nodeName)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start VPN", e)
                withContext(Dispatchers.Main) {
                    ServiceManager.notifyError("VPN启动失败: ${e.message}")
                }
                stopVpn()
            }
        }
    }
    
    private suspend fun initializeLibbox(configPath: String, nodeName: String) {
        withContext(Dispatchers.IO) {
            try {
                // 设置工作目录
                val workDir = File(filesDir, "sing-box")
                if (!workDir.exists()) {
                    workDir.mkdirs()
                }
                
                // libbox 1.12.x 使用 SetupOptions
                val options = io.nekohasekai.libbox.SetupOptions().apply {
                    basePath = workDir.absolutePath
                    workingPath = workDir.absolutePath
                    tempPath = cacheDir.absolutePath
                }
                Libbox.setup(options)
                
                // 读取配置文件内容
                val configFile = File(configPath)
                val configContent = configFile.readText()
                Log.d(TAG, "Config content length: ${configContent.length}")
                
                // 创建平台接口并启动网络监控
                platformInterface = BoxPlatformInterface(this@BoxVpnService)
                platformInterface?.startNetworkMonitor()
                
                // 创建并启动 BoxService - 传递配置内容
                boxService = Libbox.newService(configContent, platformInterface)
                boxService?.start()
                
                // 启动流量监控和组状态监控
                startCommandClient()
                startTrafficMonitor()
                
                withContext(Dispatchers.Main) {
                    isRunning = true
                    currentNodeName = nodeName
                    
                    // 通知UI更新
                    ServiceManager.notifyStateChange()
                }
                
                Log.d(TAG, "VPN started successfully with libbox")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize libbox", e)
                throw e
            }
        }
    }
    
    // Command Client 相关
    private var commandClientJob: kotlinx.coroutines.Job? = null
    private var commandClient: io.nekohasekai.libbox.CommandClient? = null
    
    private fun startCommandClient() {
        commandClientJob = serviceScope.launch {
            try {
                delay(1000) // 等待服务启动
                
                val options = io.nekohasekai.libbox.CommandClientOptions().apply {
                    command = Libbox.CommandGroup // 监听组变化（延迟测试结果）
                    statusInterval = 10_000_000_000L // 10秒 (对于组状态不需要太频繁)
                }
                
                val handler = object : io.nekohasekai.libbox.CommandClientHandler {
                    override fun connected() {
                         Log.d(TAG, "Command client connected")
                    }
                    override fun disconnected(message: String?) {
                        Log.d(TAG, "Command client disconnected: $message")
                    }
                    override fun writeStatus(message: io.nekohasekai.libbox.StatusMessage) {}
                    
                    override fun writeGroups(message: io.nekohasekai.libbox.OutboundGroupIterator?) {
                        if (message == null) return
                        try {
                            // 遍历组信息，获取延迟数据
                            while (message.hasNext()) {
                                val group = message.next()
                                if (group.type == "urltest" || group.type == "selector") {
                                    // 打印当前选中的节点
                                    Log.i(TAG, "Group ${group.tag} selected: ${group.selected}")
                                    
                                    val items = group.items
                                    while (items.hasNext()) {
                                        val item = items.next()
                                        // item.tag 是节点ID
                                        // item.urlTestDelay 是延迟 (ms)
                                        
                                        if (item.urlTestDelay > 0) {
                                            Log.d(TAG, "Got latency for ${item.tag}: ${item.urlTestDelay}ms")
                                            // 更新数据库 (异步)
                                            val nodeDao = xyz.a202132.app.data.local.AppDatabase.getInstance(application).nodeDao()
                                            serviceScope.launch {
                                                 nodeDao.updateLatency(
                                                    nodeId = item.tag,
                                                    latency = item.urlTestDelay,
                                                    isAvailable = true,
                                                    testedAt = System.currentTimeMillis()
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing groups", e)
                        }
                    }
                    
                    override fun clearLogs() {}
                    override fun writeLogs(messageList: io.nekohasekai.libbox.StringIterator?) {}
                    override fun initializeClashMode(modeList: io.nekohasekai.libbox.StringIterator?, currentMode: String?) {}
                    override fun updateClashMode(newMode: String?) {}
                    override fun writeConnections(message: io.nekohasekai.libbox.Connections?) {}
                }
                
                commandClient = io.nekohasekai.libbox.CommandClient(handler, options)
                commandClient?.connect()
                Log.d(TAG, "Command client started")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error starting command client", e)
            }
        }
    }
    
    private fun stopCommandClient() {
        commandClientJob?.cancel()
        commandClientJob = null
        try {
            commandClient?.disconnect()
        } catch (e: Exception) {
            // ignore
        }
        commandClient = null
    }

    private fun selectNodeInternal(nodeId: String) {
        serviceScope.launch {
            try {
                // 选择 proxy 组的节点
                commandClient?.selectOutbound("proxy", nodeId)
                Log.d(TAG, "Selected node: $nodeId")
                
                // 更新当前节点名称 (尝试从数据库获取或直接更新UI显示)
                // 这里简单更新 currentNodeName，实际上 MainViewModel 会负责 UI 更新
                // 也可以在这里查询数据库获取名称，但为了性能暂略
            } catch (e: Exception) {
                Log.e(TAG, "Failed to select node", e)
            }
        }
    }

    // Traffic Monitor (使用 TrafficStats)
    private var trafficMonitorJob: kotlinx.coroutines.Job? = null
    
    // 上次流量记录（用于计算速度）
    private var lastTxBytes = 0L
    private var lastRxBytes = 0L
    private var lastUpdateTime = 0L
    
    // 连接开始时的流量基准
    private var baseTxBytes = 0L
    private var baseRxBytes = 0L
    
    private fun startTrafficMonitor() {
        // 记录连接开始时的流量基准
        val uid = android.os.Process.myUid()
        baseTxBytes = android.net.TrafficStats.getUidTxBytes(uid)
        baseRxBytes = android.net.TrafficStats.getUidRxBytes(uid)
        lastTxBytes = baseTxBytes
        lastRxBytes = baseRxBytes
        lastUpdateTime = System.currentTimeMillis()
        
        trafficMonitorJob = serviceScope.launch {
            while (isActive) {
                delay(1000) // 每秒更新一次
                
                try {
                    val currentTime = System.currentTimeMillis()
                    val currentTxBytes = android.net.TrafficStats.getUidTxBytes(uid)
                    val currentRxBytes = android.net.TrafficStats.getUidRxBytes(uid)
                    
                    if (currentTxBytes != android.net.TrafficStats.UNSUPPORTED.toLong() &&
                        currentRxBytes != android.net.TrafficStats.UNSUPPORTED.toLong()) {
                        
                        val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                        val activeNetwork = cm.activeNetwork
                        val isConnected = activeNetwork != null
                        
                        val timeDelta = (currentTime - lastUpdateTime) / 1000.0
                        if (timeDelta > 0) {
                            // 计算速度 (bytes per second)
                            val txSpeed = ((currentTxBytes - lastTxBytes) / timeDelta).toLong()
                            val rxSpeed = ((currentRxBytes - lastRxBytes) / timeDelta).toLong()
                            
                            // 避免显示负数，且如果网络断开则强制为0 (避免 Loopback/Retry 流量被计入)
                            if (isConnected) {
                                uploadSpeed = if (txSpeed >= 0) txSpeed else 0
                                downloadSpeed = if (rxSpeed >= 0) rxSpeed else 0
                                
                                // 仅在有网络时计算总流量（避免断网重连产生的无效流量被计入）
                                val txTotal = currentTxBytes - baseTxBytes
                                val rxTotal = currentRxBytes - baseRxBytes
                                 
                                uploadTotal = if (txTotal >= 0) txTotal else 0
                                downloadTotal = if (rxTotal >= 0) rxTotal else 0
                                
                                // 仅在有网络时更新基准值
                                lastTxBytes = currentTxBytes
                                lastRxBytes = currentRxBytes
                                lastUpdateTime = currentTime
                            } else {
                                uploadSpeed = 0
                                downloadSpeed = 0
                                // 无网络时：不更新 lastBytes/baseBytes，这样断网期间的"幽灵流量"不会被计入
                                // 同时重置基准到当前值，确保恢复网络后从0开始计算新增流量（可选）
                                // 如果希望恢复网络后继续累加，可以注释掉下面两行
                                // baseTxBytes = currentTxBytes
                                // baseRxBytes = currentRxBytes
                            }
                            
                            // 通知 UI 更新
                            withContext(Dispatchers.Main) {
                                ServiceManager.notifyStateChange()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating traffic stats", e)
                }
            }
        }
        
        Log.d(TAG, "Traffic monitor started using TrafficStats")
    }
    
    private fun stopTrafficMonitor() {
        trafficMonitorJob?.cancel()
        trafficMonitorJob = null
        Log.d(TAG, "Traffic monitor stopped")
    }
    
    private fun saveConfigToFile(config: String): File {
        val configDir = File(filesDir, "sing-box")
        if (!configDir.exists()) {
            configDir.mkdirs()
        }
        
        val configFile = File(configDir, "config.json")
        FileOutputStream(configFile).use { fos ->
            fos.write(config.toByteArray())
        }
        
        Log.d(TAG, "Config saved to: ${configFile.absolutePath}")
        // 避免日志过长，只打印前1000字符
        if (config.length > 1000) {
             Log.d(TAG, "Config content (truncated): ${config.substring(0, 1000)}...")
        } else {
             Log.d(TAG, "Config content: $config")
        }
        return configFile
    }
    
    private fun stopVpn() {
        Log.d(TAG, "Stopping VPN")
        
        serviceScope.launch {
            stopVpnInternal()
            
            withContext(Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }
    
    private suspend fun stopVpnInternal() {
        try {
            // 停止流量监控
            stopTrafficMonitor()
            stopCommandClient()
            
            // 停止 BoxService
            boxService?.close()
            boxService = null
            
            // 停止网络监控并关闭 TUN
            platformInterface?.stopNetworkMonitor()
            platformInterface?.closeTun()
            platformInterface = null
            
            withContext(Dispatchers.Main) {
                isRunning = false
                currentNodeName = null
                uploadSpeed = 0L
                downloadSpeed = 0L
                uploadTotal = 0L
                downloadTotal = 0L
                
                // 通知UI更新
                ServiceManager.notifyStateChange()
            }
            
            Log.d(TAG, "VPN stopped")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping VPN", e)
        }
    }
    
    override fun onRevoke() {
        Log.d(TAG, "VPN revoked by system")
        stopVpn()
    }
    
    override fun onDestroy() {
        serviceScope.cancel()
        if (instance == this) {
            instance = null
        }
        super.onDestroy()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                AppConfig.NOTIFICATION_CHANNEL_ID,
                getString(R.string.vpn_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN服务通知"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(nodeName: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BoxVpnService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, AppConfig.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(getString(R.string.vpn_notification_content, nodeName))
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_vpn_key, "断开连接", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    /**
     * 提供给 BoxPlatformInterface 调用的 VPN Builder
     */
    fun createVpnBuilder(): Builder {
        return Builder()
    }
}
