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
import xyz.a202132.app.data.local.NodeDao
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.FAVORITES_NODE_GROUP_ID
import xyz.a202132.app.data.model.NodeSource
import xyz.a202132.app.data.model.ProxyMode
import xyz.a202132.app.data.model.TunStackMode
import xyz.a202132.app.data.repository.SettingsRepository
import xyz.a202132.app.rules.model.RuleRoutingOptions
import xyz.a202132.app.network.FireflyUsageReporter
import xyz.a202132.app.util.LanProxyConfig
import xyz.a202132.app.util.SingBoxConfigGenerator
import xyz.a202132.app.util.RuleManager
import xyz.a202132.app.util.RuntimeLog
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.first

/**
 * VPN服务 - 使用sing-box核心
 */
class BoxVpnService : VpnService() {
    private enum class TrafficSource {
        WAITING_FOR_CORE,
        SING_BOX_STATUS,
        TRAFFIC_STATS
    }

    companion object {
        private const val TAG = "BoxVpnService"
        private const val CORE_STATUS_FRESH_MS = 3_500L
        private const val CORE_STATUS_STARTUP_GRACE_MS = 6_000L
        private const val SLOW_CORE_SHUTDOWN_MS = 2_000L
        
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
            
        // 褰撳墠娴侀噺缁熻
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
        @Volatile private var currentLanProxyHttpPort: Int? = null
        @Volatile private var currentLanProxySocksPort: Int? = null
        @Volatile private var currentInternalSocksPort: Int? = null
        
        fun selectNode(nodeId: String) {
            instance?.selectNodeInternal(nodeId)
        }

        /**
         * 返回主 VPN 核心的本机 SOCKS 端口。
         * 仅用于让应用内测试复用当前 VPN 实例，避免另起核心造成重复或缺失统计。
         */
        fun getInternalSocksPort(): Int? {
            return currentInternalSocksPort?.takeIf { isRunning }
        }
    }
    
    @Volatile private var commandServer: io.nekohasekai.libbox.CommandServer? = null
    @Volatile private var platformInterface: BoxPlatformInterface? = null
    private val configGenerator = SingBoxConfigGenerator()
    private val usageReporter by lazy { FireflyUsageReporter(applicationContext) }
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isStopping = AtomicBoolean(false)
    private val resourceLock = Any()
    @Volatile private var currentUsageSessionId: String? = null
    
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
        // VPN 连接依赖节点与模式参数，系统不能在 Intent 丢失后自行重建旧连接。
        return START_NOT_STICKY
    }
    
    private fun startVpn(rawLink: String, nodeName: String, proxyMode: ProxyMode) {
        Log.d(TAG, "Starting VPN for node: $nodeName")
        RuntimeLog.info(TAG, "VPN start requested")
        
        serviceScope.launch {
            val keepLanProxyPort = isRunning
            if (keepLanProxyPort) {
                Log.w(TAG, "VPN is already running, stopping first")
                RuntimeLog.warn(TAG, "VPN was already running; stopping previous instance first")
                stopVpnInternal()
            }
            
            try {
                // 1. 获取所有节点 (用于生成包含所有节点的配置)
                val nodeDao = xyz.a202132.app.data.local.AppDatabase.getInstance(application).nodeDao()
                val settingsRepo = SettingsRepository(application)
                // 使用 Flow 的 first() 获取当前节点列表
                val rawNodes = try {
                    loadNodesForVpnStart(nodeDao, settingsRepo, rawLink, nodeName)
                } catch (e: Exception) {
                    emptyList<Node>()
                }
                
                if (rawNodes.isEmpty()) {
                    Log.e(TAG, "No nodes available")
                    RuntimeLog.error(TAG, "VPN start failed: no nodes available")
                     withContext(Dispatchers.Main) {
                        ServiceManager.notifyError("娌℃湁鍙敤鑺傜偣")
                    }
                    return@launch
                }

                // 2. 确定选中的节点ID
                // 如果传入的是 specific node (非 Auto)，找到它的 ID
                // 这里的 rawLink 可能只是为了兼容旧逻辑，实际我们更关心 nodeName 或 ID
                // 但为了保险，我们尝试匹配 rawLink 对应的节点
                var selectedNodeId: String? = null
                if (nodeName != "自动选择") {
                    selectedNodeId = rawNodes.find { it.getRawLinkPlain() == rawLink }?.id
                }

                setupLibboxForConfigCheck()
                val allNodes = filterCoreCompatibleNodes(rawNodes)
                if (allNodes.isEmpty()) {
                    Log.e(TAG, "No core compatible nodes available")
                    RuntimeLog.error(TAG, "VPN start failed: no core compatible nodes available")
                    withContext(Dispatchers.Main) {
                        ServiceManager.notifyError("\u5f53\u524d\u8282\u70b9\u5217\u8868\u6ca1\u6709\u6838\u5fc3\u652f\u6301\u7684\u8282\u70b9")
                    }
                    return@launch
                }
                if (selectedNodeId != null && allNodes.none { it.id == selectedNodeId }) {
                    Log.e(TAG, "Selected node is not core compatible: $selectedNodeId")
                    RuntimeLog.error(TAG, "VPN start failed: selected node is not core compatible")
                    withContext(Dispatchers.Main) {
                        ServiceManager.notifyError("\u6240\u9009\u8282\u70b9\u914d\u7f6e\u4e0d\u88ab\u6838\u5fc3\u652f\u6301")
                    }
                    return@launch
                }

                // 读取绕过局域网设置
                val bypassLan = try {
                    settingsRepo.bypassLan.first()
                } catch (e: Exception) {
                    true // 默认开启
                }

                // 读取 IPv6 路由模式
                val ipv6Mode = try {
                    settingsRepo.ipv6RoutingMode.first()
                } catch (e: Exception) {
                    xyz.a202132.app.data.model.IPv6RoutingMode.DISABLED  // 默认禁用
                }
                val vpnMtu = try {
                    settingsRepo.vpnMtu.first()
                } catch (e: Exception) {
                    AppConfig.VPN_MTU
                }
                val tunStackMode = try {
                    settingsRepo.tunStackMode.first()
                } catch (e: Exception) {
                    TunStackMode.GVISOR
                }
                val ruleOptions = try {
                    settingsRepo.ruleRoutingOptions.first()
                } catch (e: Exception) {
                    RuleRoutingOptions()
                }
                val hysteria2UploadMbps = try {
                    settingsRepo.hysteria2UploadMbps.first()
                } catch (e: Exception) {
                    AppConfig.HYSTERIA2_DEFAULT_BANDWIDTH_MBPS
                }
                val hysteria2DownloadMbps = try {
                    settingsRepo.hysteria2DownloadMbps.first()
                } catch (e: Exception) {
                    AppConfig.HYSTERIA2_DEFAULT_BANDWIDTH_MBPS
                }
                val lanProxy = readLanProxyConfig(settingsRepo, keepConfiguredPort = keepLanProxyPort)
                val internalSocksPort = pickFreeInternalSocksPort(lanProxy)
                
                Log.d(TAG, "Generating config with ${allNodes.size} nodes, selected: $selectedNodeId, bypassLan: $bypassLan, ipv6Mode: $ipv6Mode, mtu: $vpnMtu, lanProxy: ${lanProxy.enabled}:http=${lanProxy.port},socks=${lanProxy.socksPort}, internalSocks=$internalSocksPort")

                // 3. 确保规则集文件存在（从 assets 拷贝兜底）
                RuleManager.ensureRuleSets(application)

                // 4. 生成sing-box配置
                val config = configGenerator.generateConfig(
                    allNodes,
                    selectedNodeId,
                    proxyMode,
                    bypassLan,
                    ipv6Mode,
                    vpnMtu,
                    lanProxy,
                    internalSocksPort,
                    hysteria2UploadMbps,
                    hysteria2DownloadMbps,
                    tunStackMode,
                    ruleOptions
                )
                Libbox.checkConfig(config)
                deleteLegacyConfigFile()
                logConfigForDebug(config)

                // 初始化 libbox
                initializeLibbox(config, nodeName, internalSocksPort)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start VPN", e)
                RuntimeLog.error(TAG, "VPN start failed", e)
                withContext(Dispatchers.Main) {
                    ServiceManager.notifyError("VPN启动失败: ${e.message}")
                }
                stopVpn()
            }
        }
    }
    
    private suspend fun initializeLibbox(
        configContent: String,
        nodeName: String,
        internalSocksPort: Int
    ) {
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
                deleteLegacyConfigFile()

                // 启动流量监控和组状态监控
                startCommandClient()
                startTrafficMonitor()
                
                withContext(Dispatchers.Main) {
                    currentInternalSocksPort = internalSocksPort
                    isRunning = true
                    currentNodeName = nodeName

                    // 通知UI更新
                    ServiceManager.notifyStateChange()
                }
                currentUsageSessionId = usageReporter.newSessionId()
                usageReporter.flushAsync()
                
                Log.d(TAG, "VPN started successfully with libbox")
                RuntimeLog.info(TAG, "VPN started successfully")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize libbox", e)
                RuntimeLog.error(TAG, "Failed to initialize libbox", e)
                throw e
            }
        }
    }

    // Command Client 相关
    private var commandClientJob: kotlinx.coroutines.Job? = null
    @Volatile private var commandClient: io.nekohasekai.libbox.CommandClient? = null
    @Volatile private var currentTrafficSource: TrafficSource = TrafficSource.WAITING_FOR_CORE
    @Volatile private var lastSingBoxTrafficStatusAt: Long = 0L
    @Volatile private var trafficStatsFallbackActive = false
    private var trafficMonitorStartedAt = 0L
    private var fallbackUploadTotalOffset = 0L
    private var fallbackDownloadTotalOffset = 0L
    
    private fun startCommandClient() {
        commandClientJob = serviceScope.launch {
            try {
                delay(1000)  // 等待服务启动

                // 检查服务是否仍在运行，避免在 VPN 已停止后尝试连接
                if (!isRunning) {
                    Log.d(TAG, "Command client skipped: VPN already stopped")
                    return@launch
                }
                
                val options = io.nekohasekai.libbox.CommandClientOptions().apply {
                    addCommand(Libbox.CommandLog)
                    addCommand(Libbox.CommandGroup) // 监听组变化（延迟测试结果）
                    addCommand(Libbox.CommandStatus)
                    statusInterval = 1_000_000_000L // 1秒，用于流量状态更新
                }
                
                val handler = object : io.nekohasekai.libbox.CommandClientHandler {
                    override fun connected() {
                         Log.d(TAG, "Command client connected")
                    }
                    override fun disconnected(message: String?) {
                        Log.d(TAG, "Command client disconnected: $message")
                    }
                    override fun writeStatus(message: io.nekohasekai.libbox.StatusMessage?) {
                        if (message == null) return
                        handleSingBoxTrafficStatus(message)
                    }
                    
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
                    override fun writeLogs(messageList: io.nekohasekai.libbox.LogIterator?) {
                        if (messageList == null) return
                        try {
                            while (messageList.hasNext()) {
                                val message = messageList.next()?.toString().orEmpty()
                                val lower = message.lowercase()
                                when {
                                    lower.contains("error") ||
                                        lower.contains("fatal") ||
                                        lower.contains("panic") -> RuntimeLog.error("sing-box", message)
                                    lower.contains("warn") -> RuntimeLog.warn("sing-box", message)
                                }
                            }
                        } catch (e: Exception) {
                            RuntimeLog.warn(TAG, "Failed to collect sing-box logs", e)
                        }
                    }
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
                    RuntimeLog.warn(TAG, "Command client failed to start", e)
                }
            }
        }
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
                RuntimeLog.error(TAG, "Failed to select outbound node", e)
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
        currentTrafficSource = TrafficSource.WAITING_FOR_CORE
        lastSingBoxTrafficStatusAt = 0L
        trafficStatsFallbackActive = false
        trafficMonitorStartedAt = System.currentTimeMillis()
        fallbackUploadTotalOffset = 0L
        fallbackDownloadTotalOffset = 0L
        uploadSpeed = 0L
        downloadSpeed = 0L
        uploadTotal = 0L
        downloadTotal = 0L
        Log.i(TAG, "Traffic source -> ${TrafficSource.WAITING_FOR_CORE.name}")
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
                    val singBoxFresh =
                        lastSingBoxTrafficStatusAt > 0L &&
                            (currentTime - lastSingBoxTrafficStatusAt) <= CORE_STATUS_FRESH_MS
                    val waitingForFirstCoreStatus =
                        lastSingBoxTrafficStatusAt == 0L &&
                            (currentTime - trafficMonitorStartedAt) < CORE_STATUS_STARTUP_GRACE_MS
                    val currentTxBytes = android.net.TrafficStats.getUidTxBytes(uid)
                    val currentRxBytes = android.net.TrafficStats.getUidRxBytes(uid)
                    
                    if (currentTxBytes != android.net.TrafficStats.UNSUPPORTED.toLong() &&
                        currentRxBytes != android.net.TrafficStats.UNSUPPORTED.toLong()) {
                        if (singBoxFresh) {
                            // 保持基准更新，避免切回 TrafficStats 时速度突刺
                            lastTxBytes = currentTxBytes
                            lastRxBytes = currentRxBytes
                            lastUpdateTime = currentTime
                            trafficStatsFallbackActive = false
                            continue
                        }
                        if (waitingForFirstCoreStatus) {
                            // 启动阶段只等待核心，不能先发布一帧 UID 流量，否则 UI 会闪现
                            // 本应用自身及本地回环产生的、并不属于 VPN 核心的数据。
                            baseTxBytes = currentTxBytes
                            baseRxBytes = currentRxBytes
                            lastTxBytes = currentTxBytes
                            lastRxBytes = currentRxBytes
                            lastUpdateTime = currentTime
                            continue
                        }

                        if (!trafficStatsFallbackActive) {
                            // 从最后一份核心累计值继续计数，避免用整个 UID 会话值覆盖核心值。
                            baseTxBytes = currentTxBytes
                            baseRxBytes = currentRxBytes
                            lastTxBytes = currentTxBytes
                            lastRxBytes = currentRxBytes
                            lastUpdateTime = currentTime
                            fallbackUploadTotalOffset = uploadTotal
                            fallbackDownloadTotalOffset = downloadTotal
                            uploadSpeed = 0L
                            downloadSpeed = 0L
                            trafficStatsFallbackActive = true
                            updateTrafficSource(TrafficSource.TRAFFIC_STATS, "sing-box status stale")
                            continue
                        }
                        
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
                                 
                                uploadTotal = fallbackUploadTotalOffset + txTotal.coerceAtLeast(0L)
                                downloadTotal = fallbackDownloadTotalOffset + rxTotal.coerceAtLeast(0L)

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
        
        Log.d(TAG, "Traffic monitor started in hybrid mode (prefer sing-box status, fallback TrafficStats)")
    }
    
    private fun stopTrafficMonitor() {
        trafficMonitorJob?.cancel()
        trafficMonitorJob = null
        lastSingBoxTrafficStatusAt = 0L
        trafficMonitorStartedAt = 0L
        trafficStatsFallbackActive = false
        fallbackUploadTotalOffset = 0L
        fallbackDownloadTotalOffset = 0L
        currentTrafficSource = TrafficSource.WAITING_FOR_CORE
        Log.d(TAG, "Traffic monitor stopped")
    }

    private fun handleSingBoxTrafficStatus(message: io.nekohasekai.libbox.StatusMessage) {
        try {
            if (!message.trafficAvailable) return

            lastSingBoxTrafficStatusAt = System.currentTimeMillis()
            trafficStatsFallbackActive = false
            updateTrafficSource(TrafficSource.SING_BOX_STATUS, "writeStatus")

            uploadSpeed = message.uplink.coerceAtLeast(0L)
            downloadSpeed = message.downlink.coerceAtLeast(0L)
            uploadTotal = message.uplinkTotal.coerceAtLeast(0L)
            downloadTotal = message.downlinkTotal.coerceAtLeast(0L)

            serviceScope.launch(Dispatchers.Main) {
                ServiceManager.notifyStateChange()
                currentNodeName?.let { updateNotification(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse sing-box status traffic, fallback TrafficStats: ${e.message}")
        }
    }

    private fun updateTrafficSource(source: TrafficSource, reason: String) {
        if (currentTrafficSource == source) return
        currentTrafficSource = source
        Log.i(TAG, "Traffic source -> ${source.name} ($reason)")
        RuntimeLog.info(TAG, "Traffic statistics source changed: source=${source.name}, reason=$reason")
    }

    private fun logConfigForDebug(config: String) {
        // 避免日志过长，只打印前1000字符
        if (config.length > 1000) {
             Log.d(TAG, "Config content (truncated): ${config.substring(0, 1000)}...")
        } else {
             Log.d(TAG, "Config content: $config")
        }
    }
    
    private fun deleteLegacyConfigFile() {
        try {
            val configFile = File(File(filesDir, "sing-box"), "config.json")
            if (configFile.exists() && !configFile.delete()) {
                Log.w(TAG, "Failed to delete legacy config file: ${configFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error deleting legacy config file", e)
        }
    }

    private suspend fun loadNodesForVpnStart(
        nodeDao: NodeDao,
        settingsRepo: SettingsRepository,
        rawLink: String,
        nodeName: String
    ): List<Node> {
        val categoryNodes = loadNodesForCurrentCategory(nodeDao, settingsRepo)
        if (rawLink.isBlank() || categoryNodes.any { it.getRawLinkPlain() == rawLink }) {
            return categoryNodes
        }

        val selectedNode = nodeDao.getAllNodes().first().firstOrNull { it.getRawLinkPlain() == rawLink }
            ?: return categoryNodes
        Log.w(TAG, "Selected node $nodeName is outside current category, using source=${selectedNode.source}")
        return loadNodesForSource(nodeDao, selectedNode.source)
    }

    private suspend fun loadNodesForCurrentCategory(
        nodeDao: NodeDao,
        settingsRepo: SettingsRepository
    ): List<Node> {
        val groupId = settingsRepo.selectedNodeGroupId.first()
        return if (groupId == FAVORITES_NODE_GROUP_ID) {
            nodeDao.getFavoriteNodes().first()
        } else {
            nodeDao.getSubscriptionNodes(groupId).first()
        }
    }

    private suspend fun loadNodesForSource(nodeDao: NodeDao, source: NodeSource): List<Node> {
        return when (source) {
            NodeSource.SUBSCRIPTION -> nodeDao.getSubscriptionNodes().first()
            NodeSource.FAVORITE -> nodeDao.getFavoriteNodes().first()
        }
    }

    private fun setupLibboxForConfigCheck() {
        val workDir = File(filesDir, "sing-box")
        if (!workDir.exists()) workDir.mkdirs()
        val options = io.nekohasekai.libbox.SetupOptions().apply {
            basePath = workDir.absolutePath
            workingPath = workDir.absolutePath
            tempPath = cacheDir.absolutePath
        }
        Libbox.setup(options)
    }

    private fun filterCoreCompatibleNodes(nodes: List<Node>): List<Node> {
        val compatibleNodes = nodes.filter { node ->
            isNodeCoreConfigCompatible(node)
        }
        val skippedCount = nodes.size - compatibleNodes.size
        if (skippedCount > 0) {
            Log.w(TAG, "Skipped $skippedCount core-incompatible nodes before VPN start")
            RuntimeLog.warn(TAG, "Skipped $skippedCount core-incompatible nodes before VPN start")
        }
        return compatibleNodes
    }

    private fun isNodeCoreConfigCompatible(node: Node): Boolean {
        return runCatching {
            val config = configGenerator.generateTestConfig(node)
            Libbox.checkConfig(config)
        }.onFailure { error ->
            Log.w(TAG, "Core-incompatible node skipped: id=${node.id}, type=${node.type}, server=${node.server}, error=${error.message}")
            RuntimeLog.warn(TAG, "Core-incompatible node skipped: type=${node.type}", error)
        }.isSuccess
    }

    private fun stopVpn() {
        val stopStartTime = System.currentTimeMillis()
        Log.d(TAG, "Stopping VPN - start")
        RuntimeLog.info(TAG, "VPN stop requested")

        if (!isStopping.compareAndSet(false, true)) {
            Log.d(TAG, "Stopping VPN skipped: stop already in progress")
            return
        }

        // TUN 必须是关闭请求到达后的第一个阻塞操作。命令客户端或网络监控的
        // JNI 调用都不能排在它前面，否则它们一旦卡住，系统 VPN 图标也会一直保留。
        val hadTun = try {
            platformInterface?.closeTun() == true
        } catch (e: Exception) {
            Log.e(TAG, "Error closing TUN immediately", e)
            false
        }
        Log.d(TAG, "TUN closed in ${System.currentTimeMillis() - stopStartTime}ms")

        if (hadTun) {
            forceDeactivateVpnInterface()
        }

        // 停止状态采集。活动连接必须在核心仍存活时由清理协程按顺序关闭，
        // 不能另起协程，否则 onDestroy() 取消 serviceScope 后关闭请求会丢失。
        stopTrafficMonitor()

        try {
            platformInterface?.prepareForVpnShutdown()
            Log.d(TAG, "Underlying network detached in ${System.currentTimeMillis() - stopStartTime}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Error detaching underlying network", e)
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
        RuntimeLog.info(TAG, "VPN restart requested")
        
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
                val settingsRepo = xyz.a202132.app.data.repository.SettingsRepository(application)
                val rawNodes = try {
                    loadNodesForCurrentCategory(nodeDao, settingsRepo)
                } catch (e: Exception) {
                    emptyList<Node>()
                }
                
                if (rawNodes.isNotEmpty()) {
                    // 读取代理模式
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
                    val vpnMtu = try {
                        settingsRepo.vpnMtu.first()
                    } catch (e: Exception) {
                        AppConfig.VPN_MTU
                    }
                    val tunStackMode = try {
                        settingsRepo.tunStackMode.first()
                    } catch (e: Exception) {
                        TunStackMode.GVISOR
                    }
                    val ruleOptions = try {
                        settingsRepo.ruleRoutingOptions.first()
                    } catch (e: Exception) {
                        RuleRoutingOptions()
                    }
                    val hysteria2UploadMbps = try {
                        settingsRepo.hysteria2UploadMbps.first()
                    } catch (e: Exception) {
                        AppConfig.HYSTERIA2_DEFAULT_BANDWIDTH_MBPS
                    }
                    val hysteria2DownloadMbps = try {
                        settingsRepo.hysteria2DownloadMbps.first()
                    } catch (e: Exception) {
                        AppConfig.HYSTERIA2_DEFAULT_BANDWIDTH_MBPS
                    }
                    val lanProxy = readLanProxyConfig(settingsRepo, keepConfiguredPort = true)
                    val internalSocksPort = pickFreeInternalSocksPort(lanProxy)

                    // 获取选中的节点
                    val selectedNodeId = try {
                        settingsRepo.selectedNodeId.first()?.takeIf { selectedId ->
                            rawNodes.any { it.id == selectedId }
                        }
                    } catch (e: Exception) {
                        null
                    }

                    // 确保规则集文件存在
                    setupLibboxForConfigCheck()
                    val allNodes = filterCoreCompatibleNodes(rawNodes)
                    if (allNodes.isEmpty()) {
                        Log.e(TAG, "No core compatible nodes available during restart")
                        RuntimeLog.error(TAG, "VPN restart failed: no core compatible nodes available")
                        withContext(Dispatchers.Main) {
                            ServiceManager.notifyError("\u5f53\u524d\u8282\u70b9\u5217\u8868\u6ca1\u6709\u6838\u5fc3\u652f\u6301\u7684\u8282\u70b9")
                        }
                        return@launch
                    }
                    if (selectedNodeId != null && allNodes.none { it.id == selectedNodeId }) {
                        Log.e(TAG, "Selected node is not core compatible during restart: $selectedNodeId")
                        RuntimeLog.error(TAG, "VPN restart failed: selected node is not core compatible")
                        withContext(Dispatchers.Main) {
                            ServiceManager.notifyError("\u6240\u9009\u8282\u70b9\u914d\u7f6e\u4e0d\u88ab\u6838\u5fc3\u652f\u6301")
                        }
                        return@launch
                    }

                    RuleManager.ensureRuleSets(application)
                    
                    val config = configGenerator.generateConfig(
                        allNodes,
                        selectedNodeId,
                        proxyMode,
                        bypassLan,
                        ipv6Mode,
                        vpnMtu,
                        lanProxy,
                        internalSocksPort,
                        hysteria2UploadMbps,
                        hysteria2DownloadMbps,
                        tunStackMode,
                        ruleOptions
                    )
                    Libbox.checkConfig(config)
                    deleteLegacyConfigFile()
                    logConfigForDebug(config)

                    initializeLibbox(config, savedNodeName, internalSocksPort)
                }
            }
        }
    }

    /**
     * libbox 会复制 Android 返回的 TUN fd。部分旧核心/gVisor 连接关闭后仍可能短暂
     * 持有该副本，使 Android 继续认为旧 VPN 接口处于活动状态。建立一个无路由的
     * 临时接口会让系统立即停用旧接口；随后马上关闭临时 fd，完成确定性的 teardown。
     */
    private fun forceDeactivateVpnInterface() {
        val startedAt = System.currentTimeMillis()
        try {
            val teardownTun = Builder()
                .setSession(getString(R.string.app_name))
                .addAddress("10.255.255.1", 32)
                .establish()

            if (teardownTun == null) {
                Log.w(TAG, "Temporary teardown TUN was not established")
                return
            }
            teardownTun.close()
            Log.d(
                TAG,
                "VPN interface force-deactivated in ${System.currentTimeMillis() - startedAt}ms"
            )
        } catch (e: Exception) {
            // 系统撤销 VPN 授权或并发切换其他 VPN 时 establish() 允许失败；
            // 原有的核心关闭流程仍会继续执行。
            Log.w(TAG, "Failed to force-deactivate VPN interface", e)
        }
    }

    private fun pickFreeInternalSocksPort(lanProxy: LanProxyConfig): Int {
        repeat(20) {
            val port = ServerSocket(0).use { it.localPort }
            val conflictsWithLanProxy = lanProxy.enabled &&
                (port == lanProxy.port || port == lanProxy.socksPort)
            if (!conflictsWithLanProxy) {
                return port
            }
        }
        throw IllegalStateException("无法分配内部测速代理端口")
    }

    private suspend fun readLanProxyConfig(
        settingsRepo: SettingsRepository,
        keepConfiguredPort: Boolean = false
    ): LanProxyConfig {
        val enabled = runCatching { settingsRepo.lanProxyEnabled.first() }.getOrDefault(false)
        if (!enabled) {
            currentLanProxyHttpPort = null
            currentLanProxySocksPort = null
            return LanProxyConfig()
        }

        val configuredPort = runCatching { settingsRepo.lanProxyPort.first() }
            .getOrDefault(AppConfig.LAN_PROXY_DEFAULT_PORT)
            .coerceIn(AppConfig.LAN_PROXY_MIN_PORT, AppConfig.LAN_PROXY_MAX_PORT)
        val autoPort = runCatching { settingsRepo.lanProxyAutoPort.first() }.getOrDefault(true)
        val configuredSocksPort = lanProxySocksPortFor(configuredPort)
        val keepExistingPorts = keepConfiguredPort &&
            currentLanProxyHttpPort == configuredPort &&
            currentLanProxySocksPort == configuredSocksPort
        val actualPort = if (autoPort && !keepExistingPorts && !canBindLanProxyPorts(configuredPort)) {
            findAvailableLanProxyPort(configuredPort)
        } else {
            configuredPort
        }
        if (autoPort && actualPort != configuredPort) {
            runCatching { settingsRepo.setLanProxyPort(actualPort) }
        }
        val socksPort = lanProxySocksPortFor(actualPort)
        currentLanProxyHttpPort = actualPort
        currentLanProxySocksPort = socksPort

        return LanProxyConfig(
            enabled = true,
            port = actualPort,
            socksPort = socksPort,
            authEnabled = runCatching { settingsRepo.lanProxyAuthEnabled.first() }.getOrDefault(false),
            username = runCatching { settingsRepo.lanProxyUsername.first() }.getOrDefault("firefly"),
            password = runCatching { settingsRepo.lanProxyPassword.first() }.getOrDefault("firefly")
        )
    }

    private fun findAvailableLanProxyPort(startPort: Int): Int {
        val start = startPort.coerceIn(AppConfig.LAN_PROXY_MIN_PORT, AppConfig.LAN_PROXY_MAX_PORT)
        for (port in start..AppConfig.LAN_PROXY_MAX_PORT) {
            if (canBindLanProxyPorts(port)) return port
        }
        for (port in AppConfig.LAN_PROXY_MIN_PORT until start) {
            if (canBindLanProxyPorts(port)) return port
        }
        return start
    }

    private fun canBindLanProxyPorts(httpPort: Int): Boolean {
        val socksPort = lanProxySocksPortFor(httpPort)
        return httpPort != socksPort && canBindPort(httpPort) && canBindPort(socksPort)
    }

    private fun lanProxySocksPortFor(httpPort: Int): Int {
        return if (httpPort < AppConfig.LAN_PROXY_MAX_PORT) {
            httpPort + 1
        } else {
            AppConfig.LAN_PROXY_MIN_PORT
        }
    }

    private fun canBindPort(port: Int): Boolean {
        return runCatching {
            ServerSocket().use { socket ->
                socket.reuseAddress = false
                socket.bind(InetSocketAddress("0.0.0.0", port))
            }
        }.isSuccess
    }
    
    private fun cleanupResources() {
        try {
            currentInternalSocksPort = null
            // 先原子摘除资源引用，onDestroy 或重复回调只能看到 null，不会并发执行
            // 第二次核心关闭。平台和核心的实际关闭仍在锁外进行，避免长时间持锁。
            val resources = synchronized(resourceLock) {
                commandClientJob?.cancel()
                commandClientJob = null
                val captured = Triple(platformInterface, commandServer, commandClient)
                platformInterface = null
                commandServer = null
                commandClient = null
                captured
            }
            val pi = resources.first
            val server = resources.second
            val client = resources.third

            // 0. 最优先关闭 TUN 接口，确保系统状态栏 VPN 图标立即消失
            try {
                pi?.closeTun()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing TUN", e)
            }
            
            // 1. 停止流量监控
            stopTrafficMonitor()

            // 2. 停止 Network Monitor
            pi?.prepareForVpnShutdown()

            // 3. 核心仍存活时先同步关闭活动连接。特别是分应用代理中的浏览器
            // 长连接，否则异步关闭请求可能被 onDestroy() 取消，gVisor 会继续
            // 持有 libbox 复制的 TUN fd，导致系统 VPN 图标延迟消失。
            val connectionsShutdownStartedAt = System.currentTimeMillis()
            try {
                client?.closeConnections()
            } catch (e: Exception) {
                Log.d(TAG, "Active connection close failed during shutdown", e)
            } finally {
                Log.d(
                    TAG,
                    "Active connections closed in ${System.currentTimeMillis() - connectionsShutdownStartedAt}ms"
                )
            }

            // 4. 关闭 sing-box 服务，释放核心复制的 TUN fd
            val coreShutdownStartedAt = System.currentTimeMillis()
            try {
                server?.closeService()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing service", e)
            } finally {
                val elapsed = System.currentTimeMillis() - coreShutdownStartedAt
                Log.d(TAG, "sing-box service closed in ${elapsed}ms")
                if (elapsed >= SLOW_CORE_SHUTDOWN_MS) {
                    RuntimeLog.warn(TAG, "Slow sing-box shutdown: ${elapsed}ms")
                }
            }

            // 5. 服务端关闭后再断开命令通道，避免 closeConnections 与
            // closeService 并发竞争同一个 CommandServer。
            try {
                client?.disconnect()
            } catch (e: Exception) {
                Log.d(TAG, "Command client disconnect failed", e)
            }
            try {
                server?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing commandServer", e)
            }
            deleteLegacyConfigFile()
            
            Log.d(TAG, "Resources cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up resources", e)
        }
    }
    
    private suspend fun stopVpnInternal() {
        var stopFailure: Throwable? = null
        try {
            runCatching { finishUsageSession() }
                .onFailure { Log.w(TAG, "Failed to finish usage session during VPN shutdown", it) }
            // 切换到 IO 线程执行清理，避免主线程卡顿（如果 cleanup 耗时）
            // 但对于 onDestroy，我们必须同步清理
            withContext(Dispatchers.IO) {
                cleanupResources()
            }

        } catch (e: Exception) {
            stopFailure = e
            Log.e(TAG, "Error stopping VPN", e)
            RuntimeLog.error(TAG, "Error stopping VPN", e)
        } finally {
            // 即使某个原生清理调用抛出异常，也必须结束 DISCONNECTING 状态。
            withContext(NonCancellable + Dispatchers.Main) {
                isRunning = false
                isStopping.set(false)
                currentNodeName = null
                uploadSpeed = 0L
                downloadSpeed = 0L
                uploadTotal = 0L
                downloadTotal = 0L

                // 通知UI更新
                ServiceManager.notifyStateChange()
            }
        }

        if (stopFailure == null) {
            Log.d(TAG, "VPN stopped")
            RuntimeLog.info(TAG, "VPN stopped")
        }
    }
    
    override fun onRevoke() {
        Log.d(TAG, "VPN revoked by system")
        stopVpn()
    }
    
    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        finishUsageSession()
        // 立即取消所有协程
        serviceScope.cancel()

        // 确保资源被清理（特别是 TUN 接口）
        // 即使 stopVpnInternal 正在运行，这里再次调用也是安全的（已做空判断）
        cleanupResources()

        // 更新状态
        isRunning = false
        isStopping.set(false)
        if (instance == this) {
            instance = null
        }

        // 通知 UI (防止 UI 停留在已连接状态)
        // 注意：onDestroy 后 ServiceManager可能无法立即通知到已销毁的 Activity，但 StateFlow 会更新
        ServiceManager.notifyStateChange()
        
        super.onDestroy()
    }

    private fun finishUsageSession() {
        val sessionId = currentUsageSessionId ?: return
        currentUsageSessionId = null
        usageReporter.enqueueAndFlush(sessionId, uploadTotal, downloadTotal)
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
