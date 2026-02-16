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
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.*
import xyz.a202132.app.AppConfig
import xyz.a202132.app.MainActivity
import xyz.a202132.app.R
import xyz.a202132.app.data.model.ProxyMode
import xyz.a202132.app.util.SingBoxConfigGenerator
// RuleSetManager removed
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
        const val ACTION_RESTART = "xyz.a202132.app.RESTART_VPN"
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
    
    private var commandServer: io.nekohasekai.libbox.CommandServer? = null
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
            ACTION_RESTART -> {
                // 重置连接：停止后重新启动
                restartVpn()
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
                
                // 读取 IPv6 路由模式
                val ipv6Mode = try {
                    settingsRepo.ipv6RoutingMode.first()
                } catch (e: Exception) {
                    xyz.a202132.app.data.model.IPv6RoutingMode.DISABLED // 默认禁用
                }
                
                Log.d(TAG, "Generating config with ${allNodes.size} nodes, selected: $selectedNodeId, bypassLan: $bypassLan, ipv6Mode: $ipv6Mode")

                // 3. 生成sing-box配置
                val config = configGenerator.generateConfig(allNodes, selectedNodeId, proxyMode, bypassLan, ipv6Mode)
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
                
                // libbox 1.13.x 使用 SetupOptions
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
                
                // 创建 CommandServerHandler
                val serverHandler = object : io.nekohasekai.libbox.CommandServerHandler {
                    override fun serviceStop() {
                        stopVpn()
                    }
                    override fun serviceReload() {
                        restartVpn()
                    }
                    override fun getSystemProxyStatus(): io.nekohasekai.libbox.SystemProxyStatus {
                        return io.nekohasekai.libbox.SystemProxyStatus()
                    }
                    override fun setSystemProxyEnabled(enabled: Boolean) {
                        // Android 不支持系统代理设置
                    }
                    override fun writeDebugMessage(message: String?) {
                        Log.d(TAG, "Debug: $message")
                    }
                }
                
                // 创建 CommandServer（替代旧版 BoxService）
                commandServer = Libbox.newCommandServer(serverHandler, platformInterface)
                commandServer?.start()
                
                // 启动或重载 sing-box 服务实例
                val overrideOptions = io.nekohasekai.libbox.OverrideOptions()
                commandServer?.startOrReloadService(configContent, overrideOptions)
                
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
                
                // 检查服务是否仍在运行，避免在 VPN 已停止后尝试连接
                if (!isRunning) {
                    Log.d(TAG, "Command client skipped: VPN already stopped")
                    return@launch
                }
                
                val options = io.nekohasekai.libbox.CommandClientOptions().apply {
                    addCommand(Libbox.CommandGroup) // 监听组变化（延迟测试结果）
                    statusInterval = 10_000_000_000L // 10秒 (对于组状态不需要太频繁)
                }
                
                val handler = object : io.nekohasekai.libbox.CommandClientHandler {
                    override fun connected() {
                         Log.d(TAG, "Command client connected")
                    }
                    override fun disconnected(message: String?) {
                        Log.d(TAG, "Command client disconnected: $message")
                    }
                    override fun writeStatus(message: io.nekohasekai.libbox.StatusMessage?) {}
                    
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
                    override fun setDefaultLogLevel(level: Int) {}
                    override fun writeLogs(messageList: io.nekohasekai.libbox.LogIterator?) {}
                    override fun initializeClashMode(modeList: io.nekohasekai.libbox.StringIterator?, currentMode: String?) {}
                    override fun updateClashMode(newMode: String?) {}
                    override fun writeConnectionEvents(events: io.nekohasekai.libbox.ConnectionEvents?) {}
                }
                
                // 再次检查，因为创建 handler 可能有些耗时
                if (!isRunning) {
                    Log.d(TAG, "Command client skipped: VPN stopped during setup")
                    return@launch
                }
                
                commandClient = io.nekohasekai.libbox.CommandClient(handler, options)
                commandClient?.connect()
                Log.d(TAG, "Command client started")
                
            } catch (e: Exception) {
                // 只在服务仍在运行时记录错误，否则静默忽略
                if (isRunning) {
                    Log.e(TAG, "Error starting command client", e)
                }
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
                                // 同时更新通知栏
                                currentNodeName?.let { updateNotification(it) }
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
        val stopStartTime = System.currentTimeMillis()
        Log.d(TAG, "Stopping VPN - start")

        // 立即关闭 TUN，确保状态栏 VPN 图标尽快消失
        try {
            platformInterface?.closeTun()
            Log.d(TAG, "TUN closed in ${System.currentTimeMillis() - stopStartTime}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing TUN immediately", e)
        }

        // 立即停止前台服务（在主线程同步执行，不等待协程）
        // 这可能有助于加快 VPN 图标消失，因为系统可以更早地开始清理
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            Log.d(TAG, "Foreground stopped in ${System.currentTimeMillis() - stopStartTime}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping foreground", e)
        }

        serviceScope.launch {
            stopVpnInternal()
            
            withContext(Dispatchers.Main) {
                Log.d(TAG, "Calling stopSelf after ${System.currentTimeMillis() - stopStartTime}ms")
                stopSelf()
            }
        }
    }
    
    private fun restartVpn() {
        Log.d(TAG, "Restarting VPN")
        
        serviceScope.launch {
            // 保存当前配置
            val savedNodeName = currentNodeName

            // 立即关闭 TUN，确保状态栏 VPN 图标尽快消失
            try {
                platformInterface?.closeTun()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing TUN immediately", e)
            }
            
            // 停止当前连接
            stopVpnInternal()
            
            // 短暂延迟确保资源完全释放
            delay(500)
            
            // 重新启动（使用当前节点）
            if (savedNodeName != null) {
                // 通知 UI 更新
                withContext(Dispatchers.Main) {
                    updateNotification(savedNodeName)
                }
                
                // 获取节点信息并重新连接
                val nodeDao = xyz.a202132.app.data.local.AppDatabase.getInstance(application).nodeDao()
                val allNodes = try {
                    nodeDao.getAllNodes().first()
                } catch (e: Exception) {
                    emptyList<xyz.a202132.app.data.model.Node>()
                }
                
                if (allNodes.isNotEmpty()) {
                    // 读取代理模式
                    val settingsRepo = xyz.a202132.app.data.repository.SettingsRepository(application)
                    val proxyMode = try {
                        settingsRepo.proxyMode.first()
                    } catch (e: Exception) {
                        ProxyMode.SMART
                    }
                    
                    // 读取绕过局域网设置
                    val bypassLan = try {
                        settingsRepo.bypassLan.first()
                    } catch (e: Exception) {
                        true
                    }
                    
                    // 读取 IPv6 路由模式
                    val ipv6Mode = try {
                        settingsRepo.ipv6RoutingMode.first()
                    } catch (e: Exception) {
                        xyz.a202132.app.data.model.IPv6RoutingMode.DISABLED
                    }
                    
                    // 获取选中的节点
                    val selectedNodeId = try {
                        settingsRepo.selectedNodeId.first()
                    } catch (e: Exception) {
                        null
                    }
                    
                    val config = configGenerator.generateConfig(allNodes, selectedNodeId, proxyMode, bypassLan, ipv6Mode)
                    val configFile = saveConfigToFile(config)
                    
                    initializeLibbox(configFile.absolutePath, savedNodeName)
                }
            }
        }
    }
    
    private fun cleanupResources() {
        try {
            // 保存引用以便后续清理
            val pi = platformInterface
            platformInterface = null
            
            // 0. 最优先关闭 TUN 接口，确保系统状态栏 VPN 图标立即消失
            try {
                pi?.closeTun()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing TUN", e)
            }
            
            // 1. 停止流量监控
            stopTrafficMonitor()
            stopCommandClient()
            
            // 2. 停止 Network Monitor
            pi?.stopNetworkMonitor()
            
            // 3. 停止 CommandServer 和 sing-box 服务
            try {
                commandServer?.closeService()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing service", e)
            }
            try {
                commandServer?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing commandServer", e)
            }
            commandServer = null
            
            Log.d(TAG, "Resources cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up resources", e)
        }
    }
    
    private suspend fun stopVpnInternal() {
        try {
            // 切换到 IO 线程执行清理，避免主线程卡顿（如果 cleanup 耗时）
            // 但对于 onDestroy，我们必须同步清理
            withContext(Dispatchers.IO) {
                cleanupResources()
            }
            
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
        Log.d(TAG, "onDestroy")
        // 立即取消所有协程
        serviceScope.cancel()
        
        // 确保资源被清理（特别是 TUN 接口）
        // 即使 stopVpnInternal 正在运行，这里再次调用也是安全的（已做空判断）
        cleanupResources()
        
        // 更新状态
        isRunning = false
        if (instance == this) {
            instance = null
        }
        
        // 通知 UI (防止 UI 停留在已连接状态)
        // 注意：onDestroy 后 ServiceManager可能无法立即通知到已销毁的 Activity，但 StateFlow 会更新
        ServiceManager.notifyStateChange()
        
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
        return buildNotification(nodeName, uploadSpeed, downloadSpeed)
    }
    
    private fun buildNotification(nodeName: String, upSpeed: Long, downSpeed: Long): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
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
        
        val restartIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, BoxVpnService::class.java).apply {
                action = ACTION_RESTART
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // 格式化速度显示
        val upSpeedStr = ServiceManager.formatSpeed(upSpeed)
        val downSpeedStr = ServiceManager.formatSpeed(downSpeed)
        
        // 构建通知内容 - 默认显示速度，展开显示总流量
        val contentText = "上传 $upSpeedStr  下载 $downSpeedStr"
        
        return NotificationCompat.Builder(this, AppConfig.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(nodeName)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("上传 $upSpeedStr   累计 ${ServiceManager.formatTraffic(uploadTotal)}\n下载 $downSpeedStr   累计 ${ServiceManager.formatTraffic(downloadTotal)}"))
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_vpn_key, "断开连接", stopIntent)
            .addAction(R.drawable.ic_vpn_key, "重置连接", restartIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true) // 更新时不发出声音
            .build()
    }
    
    /**
     * 更新通知内容（带宽信息）
     */
    private fun updateNotification(nodeName: String) {
        if (!isRunning) return
        
        val notification = buildNotification(nodeName, uploadSpeed, downloadSpeed)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(AppConfig.NOTIFICATION_ID, notification)
    }
    
    /**
     * 提供给 BoxPlatformInterface 调用的 VPN Builder
     */
    fun createVpnBuilder(): Builder {
        return Builder()
    }
}
