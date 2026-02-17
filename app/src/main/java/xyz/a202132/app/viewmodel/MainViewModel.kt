package xyz.a202132.app.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.a202132.app.AppConfig
import xyz.a202132.app.BuildConfig
import xyz.a202132.app.data.local.AppDatabase
import xyz.a202132.app.data.model.*
import xyz.a202132.app.data.repository.SettingsRepository
import xyz.a202132.app.network.LatencyTester
import xyz.a202132.app.network.NetworkClient
import xyz.a202132.app.network.SubscriptionParser
import xyz.a202132.app.network.DownloadManager
import xyz.a202132.app.network.UnlockTestManager
import xyz.a202132.app.service.BoxVpnService
import xyz.a202132.app.service.ServiceManager
import xyz.a202132.app.util.NetworkUtils
import xyz.a202132.app.util.UnlockTestsRunner
import xyz.a202132.app.util.CryptoUtils
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    private val tag = "MainViewModel"
    private val database = AppDatabase.getInstance(application)
    private val nodeDao = database.nodeDao()
    private val settingsRepository = SettingsRepository(application)
    private val subscriptionParser = SubscriptionParser()
    private val latencyTester = LatencyTester()
    
    // 节流控制
    private val THROTTLE_INTERVAL = 5000L // 5秒节流间隔
    private var lastFetchNodesTime = 0L
    private var lastBackupSwitchTime = 0L
    private var lastCheckUpdateTime = 0L
    
    // UI State
    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()
    
    private val _isTesting = MutableStateFlow(false)
    val isTesting = _isTesting.asStateFlow()
    
    private val _showNodeList = MutableStateFlow(false)
    val showNodeList = _showNodeList.asStateFlow()
    
    // 测试类型标签 (用于 UI 显示)
    private val _testingLabel = MutableStateFlow<String?>(null)
    val testingLabel = _testingLabel.asStateFlow()
    
    // 过滤不可用节点
    private val _filterUnavailable = MutableStateFlow(false)
    val filterUnavailable = _filterUnavailable.asStateFlow()
    
    private val _notice = MutableStateFlow<NoticeInfo?>(null)
    val notice = _notice.asStateFlow()
    
    // Persistent notice config (independent of dialog visibility)
    private val _noticeConfig = MutableStateFlow<NoticeInfo?>(null)
    val noticeConfig = _noticeConfig.asStateFlow()
    
    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo = _updateInfo.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)

    val error = _error.asStateFlow()
    
    // Blocking Auto-Select State
    private val _isAutoSelecting = MutableStateFlow(false)
    val isAutoSelecting = _isAutoSelecting.asStateFlow()

    // 自动化测试状态
    private val _autoTestProgress = MutableStateFlow(AutoTestProgress())
    val autoTestProgress = _autoTestProgress.asStateFlow()
    private var autoTestJob: Job? = null
    
    // Data
    val nodes = combine(
        nodeDao.getAllNodes(),
        _filterUnavailable
    ) { list, filterOut ->
        val filtered = if (filterOut) list.filter { it.latency != -2 } else list
        filtered.sortedWith(
            compareByDescending<Node> { it.isAvailable }
                .thenBy { it.sortOrder }
                .thenBy { if (it.latency >= 0) it.latency else Int.MAX_VALUE }
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        emptyList()
    )
    
    // 初始化完成标志 - 用于防止 UI 闪烁
    private val _isInitialized = MutableStateFlow(false)
    
    val selectedNodeId = combine(
        settingsRepository.selectedNodeId,
        _isInitialized
    ) { nodeId, initialized ->
        // 只有在初始化完成后才发出真实值，否则返回 null
        if (initialized) nodeId else null
    }.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        null
    )
    
    val proxyMode = settingsRepository.proxyMode.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        ProxyMode.SMART
    )
    
    val bypassLan = settingsRepository.bypassLan.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        true // 默认开启绕过局域网
    )
    
    val ipv6RoutingMode = settingsRepository.ipv6RoutingMode.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        IPv6RoutingMode.DISABLED
    )
    
    val isUserAgreementAccepted = settingsRepository.isUserAgreementAccepted.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        true // 默认 true 防止闪烁，init 里会 reset 状态或者第一次启动时读出来是 false
    )

    // 自动化测试设置
    val autoTestEnabled = settingsRepository.autoTestEnabled.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val autoTestFilterUnavailable = settingsRepository.autoTestFilterUnavailable.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val autoTestLatencyThresholdMs = settingsRepository.autoTestLatencyThresholdMs.stateIn(viewModelScope, SharingStarted.Lazily, 600)
    val autoTestBandwidthEnabled = settingsRepository.autoTestBandwidthEnabled.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val autoTestBandwidthThresholdMbps = settingsRepository.autoTestBandwidthThresholdMbps.stateIn(viewModelScope, SharingStarted.Lazily, 10)
    val autoTestBandwidthWifiOnly = settingsRepository.autoTestBandwidthWifiOnly.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val autoTestBandwidthSizeMb = settingsRepository.autoTestBandwidthSizeMb.stateIn(viewModelScope, SharingStarted.Lazily, 10)
    val autoTestUnlockEnabled = settingsRepository.autoTestUnlockEnabled.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val autoTestNodeLimit = settingsRepository.autoTestNodeLimit.stateIn(viewModelScope, SharingStarted.Lazily, 20)
    
    val vpnState = ServiceManager.vpnState
    
    // 流量统计
    val uploadSpeed = ServiceManager.uploadSpeed
    val downloadSpeed = ServiceManager.downloadSpeed
    val uploadTotal = ServiceManager.uploadTotal
    val downloadTotal = ServiceManager.downloadTotal
    
    val currentNode = combine(nodeDao.getAllNodes(), selectedNodeId) { nodeList, selectedId ->
        nodeList.find { it.id == selectedId }
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)
    
    init {
        // 将历史明文 rawLink 迁移为加密存储（一次性后台处理）
        viewModelScope.launch(Dispatchers.IO) {
            migrateRawLinksToEncrypted()
            logRawLinkEncryptionStats("startup_migration")
        }

        // 启动时加载数据
        viewModelScope.launch {
            // 每次启动重置选择状态 (不记住上次选择)
            settingsRepository.setSelectedNodeId(null)
            
            // 标记初始化完成，此时 selectedNodeId 才开始发出真实值
            // 这样 UI 不会看到旧的选中状态闪烁
            _isInitialized.value = true
            
            // 监听用户协议状态，只有同意后才初始化网络请求
            isUserAgreementAccepted.collect { accepted ->
                if (accepted) {
                    val autoEnabled = settingsRepository.autoTestEnabled.first()
                    if (autoEnabled) {
                        startAutomatedTest()
                    } else {
                        fetchNodes(bypassThrottle = true)
                    }
                    checkNotice()
                    checkUpdate(isAuto = true)
                }
            }
        }
        
        // 监听 ServiceManager 的错误消息
        viewModelScope.launch {
            ServiceManager.errorMessage.collect { message ->
                if (message != null) {
                    _error.value = message
                    ServiceManager.clearError()
                }
            }
        }
        

        // 监听自动选择 (当已连接 && 自动模式 && 有延迟数据时触发)
        viewModelScope.launch {
            // 组合观察: 节点列表, VPN状态, 当前选中ID(null=auto)
            combine(nodes, vpnState, selectedNodeId) { currentNodes, state, selectedId ->
                Triple(currentNodes, state, selectedId)
            }.collect { (currentNodes, state, selectedId) ->
                if (state == VpnState.CONNECTED && selectedId == null) {
                    // 只有在自动选择模式(selectedId==null)且已连接时才执行
                    // 寻找有延迟数据的最佳节点
                    val validNodes = currentNodes.filter { it.latency > 0 }
                    if (validNodes.isNotEmpty()) {
                        // 找到延迟最低的节点
                        val bestNode = validNodes.minByOrNull { it.latency }
                        if (bestNode != null) {
                            Log.i(tag, "Auto-selecting best node: ${bestNode.name} (${bestNode.latency}ms)")
                            
                            // 1. 切换代理
                            BoxVpnService.selectNode(bestNode.id)
                            
                            // 2. 更新选中状态 (这会停止后续的自动选择，因为 selectedId 不再是 null)
                            // 稍微延迟一下确保切换成功
                            delay(500)
                            settingsRepository.setSelectedNodeId(bestNode.id)
                            
                            // 3. 通知用户
                            _error.value = "已自动选择: ${bestNode.name}"
                        }
                    }
                }
            }
        }
    }

    
    /**
     * 获取节点列表
     */
    val backupNodeEnabled = settingsRepository.backupNodeEnabled.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        false
    )
    
    /**
     * 获取节点列表
     */
    // 备用节点 URL (本地存储)
    val backupNodeUrl = settingsRepository.backupNodeUrl.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        null
    )
    
    /**
     * 获取节点列表
     */
    /**
     * @param skipBackupMode 强制跳过备用模式逻辑 (用于回退场景，避免 DataStore 异步更新导致重复触发)
     */
    fun fetchNodes(skipBackupMode: Boolean = false, bypassThrottle: Boolean = false, runUrlTest: Boolean = true) {
        viewModelScope.launch {
            // 节流检查 (内部调用可跳过)
            if (!bypassThrottle) {
                val now = System.currentTimeMillis()
                if (now - lastFetchNodesTime < THROTTLE_INTERVAL) {
                    _error.value = "操作过于频繁，请稍后再试"
                    return@launch
                }
                lastFetchNodesTime = now
            }
            
            _isLoading.value = true
            _filterUnavailable.value = false // 刷新时重置过滤
            try {
                // 1. 检查网络状态
                if (!NetworkUtils.isNetworkAvailable(getApplication())) {
                    _isLoading.value = false
                     _error.value = "当前无网络连接，无法获取节点"
                    return@launch
                }
                
                // 2. 检查是否开启备用节点 (skipBackupMode 时强制为 false)
                val isBackupEnabled = if (skipBackupMode) false else backupNodeEnabled.value
                var useBackup = false
                var targetUrl: String? = null
                
                if (isBackupEnabled) {
                    // 每次刷新都获取最新 Notice 配置，确保能检测到远程配置变化
                    Log.d(tag, "Backup mode enabled, fetching latest notice config...")
                    val notice = fetchNoticeSync()
                    
                    if (notice == null) {
                        // Notice 请求失败 (服务器错误等)，触发回退
                        Log.e(tag, "Failed to fetch notice for backup node")
                        _error.value = "备用节点当前不可用，已退回默认节点！"
                        handleBackupFallback()
                        return@launch
                    }
                    
                    // 验证备用节点配置有效性
                    val backupUrl = notice.backupNodes?.url
                    val isValidUrl = backupUrl != null && 
                                     backupUrl.isNotBlank() && 
                                     (backupUrl.startsWith("http://") || backupUrl.startsWith("https://"))
                    
                    if (isValidUrl) {
                        useBackup = true
                        targetUrl = backupUrl
                        Log.d(tag, "Using backup node URL: $targetUrl")
                    } else {
                        // 配置无效 (无 backupNodes / 无 url / url 为空 / url 格式错误)
                        // 触发完整回退
                        Log.w(tag, "Backup node config invalid: backupNodes=$${notice.backupNodes}, url=$backupUrl")
                        _error.value = "备用节点当前不可用，已退回默认节点！"
                        handleBackupFallback()
                        return@launch
                    }
                }
                
                val result = if (targetUrl != null) {
                     subscriptionParser.fetchAndParse(targetUrl)
                } else {
                     subscriptionParser.fetchAndParse()
                }

                result.onSuccess { fetchedNodes ->
                    // 检查由备用节点返回的空列表
                    if (isBackupEnabled && fetchedNodes.isEmpty()) {
                        Log.w(tag, "Backup node returned empty list, treating as failure")
                        _error.value = "备用节点当前不可用，已退回默认节点！"
                        handleBackupFallback()
                        return@onSuccess
                    }

                    // 保存到数据库
                    nodeDao.deleteAllNodes()
                    nodeDao.insertNodes(fetchedNodes)

                    if (BuildConfig.DEBUG) {
                        viewModelScope.launch(Dispatchers.IO) {
                            logRawLinkEncryptionStats("after_fetch_insert")
                        }
                    }
                    
                    // 自动测试延迟 (直接传入节点列表，避免等待 Flow 更新)
                    // 用户更关心连通性与真实延迟，改用 URL Test
                    if (runUrlTest) {
                        urlTestAllNodes(fetchedNodes)
                    }
                    
                    Log.d(tag, "Fetched ${fetchedNodes.size} nodes")
                }.onFailure { e ->
                    Log.e(tag, "Failed to fetch nodes", e)
                    if (isBackupEnabled) {
                        // 备用节点请求失败 (非网络原因，或者是备用URL本身有问题/服务器挂了)
                        // 需求: "各种失败情况...均删除备用节点按钮并清空本地存储备用节点url...发送toast备用节点当前不可用，已退回默认节点！"
                        
                        _error.value = "备用节点当前不可用，已退回默认节点！"
                        
                        // 执行回退操作 (关闭，清除URL，重试默认)
                        handleBackupFallback()
                    } else {
                        _error.value = "获取节点失败: ${e.message}"
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error fetching nodes", e)
                _error.value = "网络错误"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    // 处理备用节点回退逻辑
    private suspend fun handleBackupFallback() {
        // 0. 如果 VPN 正在运行，先停止（因为节点来源已失效）
        if (vpnState.value != VpnState.DISCONNECTED) {
            Log.d(tag, "Stopping VPN before backup fallback")
            ServiceManager.stopVpn(getApplication())
            delay(500) // 等待 VPN 停止
        }
        
        // 1. 关闭备用节点开关
        settingsRepository.setBackupNodeEnabled(false)
        // 2. 清除备用节点 URL
        settingsRepository.setBackupNodeUrl(null)
        // 3. 清除当前选中的节点（备用节点 ID 在默认列表中不存在）
        settingsRepository.setSelectedNodeId(null)
        
        // 4. 更新 Notice Config 以隐藏按钮
        val currentConfig = _noticeConfig.value
        if (currentConfig != null) {
            _noticeConfig.value = currentConfig.copy(backupNodes = null)
        }
        
        // 5. 重新请求，强制跳过备用模式 (避免 DataStore 异步更新导致重复触发)
        fetchNodes(skipBackupMode = true, bypassThrottle = true)
    }
    
    // 辅助: 同步获取 Notice
    private suspend fun fetchNoticeSync(): NoticeInfo? {
        return try {
            val info = NetworkClient.apiService.getNoticeInfo(AppConfig.NOTICE_URL)
            updateNoticeConfig(info)
            info
        } catch (e: Exception) {
            null
        }
    }
    
    private suspend fun updateNoticeConfig(info: NoticeInfo) {
        _noticeConfig.value = info
        
        val backupInfo = info.backupNodes
        val isValid = backupInfo?.url?.let { 
             it.startsWith("http://") || it.startsWith("https://") 
        } == true
        
        if (isValid) {
            settingsRepository.setBackupNodeUrl(backupInfo!!.url)
        } else {
            // 无效配置，清除本地记录
            settingsRepository.setBackupNodeUrl(null)
            // 注意: 不在这里触发 handleBackupFallback，让 fetchNodes 的调用者统一处理
            // 避免重复触发 Toast
        }
    }
    
    fun setBackupNodeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            // 节流检查
            val now = System.currentTimeMillis()
            if (now - lastBackupSwitchTime < THROTTLE_INTERVAL) {
                _error.value = "切换过于频繁，请稍后再试"
                return@launch
            }
            lastBackupSwitchTime = now
            
            // 如果 VPN 正在运行，先停止（因为节点来源将改变）
            if (vpnState.value != VpnState.DISCONNECTED) {
                Log.d(tag, "Stopping VPN before switching node source")
                ServiceManager.stopVpn(getApplication())
                // 等待 VPN 停止
                delay(500)
            }
            
            // 清除当前选中的节点（因为它可能不在新列表中）
            settingsRepository.setSelectedNodeId(null)
            
            // 切换设置
            settingsRepository.setBackupNodeEnabled(enabled)
            delay(100)
            
            // 获取新的节点列表 (跳过节流检查，因为这是内部调用)
            fetchNodes(bypassThrottle = true)
        }
    }
    
    fun testAllNodes(targetNodes: List<Node>? = null) {
        viewModelScope.launch {
            _isTesting.value = true
            _testingLabel.value = "TCPing 测试中..."
            try {
                val currentNodes = targetNodes ?: nodes.value
                internalTestNodes(currentNodes) { completed, total ->
                    _testingLabel.value = "TCPing 测试中 ($completed/$total)"
                }
            } finally {
                _isTesting.value = false
                _testingLabel.value = null
            }
        }
    }
    
    /**
     * URL Test 所有节点延迟 (通过 ClashAPI)
     * VPN 运行中 → 用现有 ClashAPI (port 9090)
     * VPN 未运行 → 启动临时无头 sing-box 实例 (port 19090)
     */
    fun urlTestAllNodes(targetNodes: List<Node>? = null) {
        viewModelScope.launch {
            _isTesting.value = true
            _testingLabel.value = "URL Test 测试中..."
            
            val isVpnRunning = vpnState.value == VpnState.CONNECTED
            val clashApiPort: Int
            var startedHeadless = false
            
            try {
                val currentNodes = targetNodes ?: nodes.value
                if (currentNodes.isEmpty()) {
                    _error.value = "没有可用节点"
                    return@launch
                }
                
                if (isVpnRunning) {
                    // VPN 已连接，直接使用现有 ClashAPI
                    clashApiPort = 9090
                    Log.d(tag, "URL Test via existing VPN ClashAPI (port $clashApiPort)")
                } else {
                    // VPN 未连接，启动临时无头 sing-box 实例
                    clashApiPort = xyz.a202132.app.network.UrlTestManager.CLASH_API_PORT
                    Log.d(tag, "URL Test via headless instance (port $clashApiPort)")
                    _testingLabel.value = "启动测试引擎..."
                    
                    val started = withContext(Dispatchers.IO) {
                        xyz.a202132.app.network.UrlTestManager.start(getApplication(), currentNodes)
                    }
                    if (!started) {
                        _error.value = "启动测试引擎失败"
                        return@launch
                    }
                    startedHeadless = true
                    
                    // 等待 sing-box 初始化 + ClashAPI 就绪
                    delay(2000)
                    
                    // 健康检查：等待 ClashAPI 可用 (必须在 IO 线程)
                    val clashReady = withContext(Dispatchers.IO) {
                        var ready = false
                        for (retry in 1..6) {
                            try {
                                val checkUrl = java.net.URL("http://127.0.0.1:$clashApiPort/proxies")
                                val conn = checkUrl.openConnection() as java.net.HttpURLConnection
                                conn.connectTimeout = 2000
                                conn.readTimeout = 2000
                                if (conn.responseCode == 200) {
                                    ready = true
                                    conn.disconnect()
                                    break
                                }
                                conn.disconnect()
                            } catch (e: Exception) {
                                Log.d(tag, "ClashAPI not ready yet (attempt $retry/6): ${e.message}")
                            }
                            delay(1000)
                        }
                        ready
                    }
                    
                    if (!clashReady) {
                        _error.value = "测试引擎启动超时"
                        return@launch
                    }
                    
                    _testingLabel.value = "URL Test 测试中..."
                }
                
                // 诊断：查询 ClashAPI 注册的代理
                if (startedHeadless) {
                    withContext(Dispatchers.IO) {
                        val proxiesResponse = xyz.a202132.app.network.UrlTestManager.diagnoseProxies()
                        Log.d(tag, "ClashAPI proxies: ${proxiesResponse?.take(500)}")
                    }
                }
                
                Log.d(tag, "URL Testing ${currentNodes.size} nodes via ClashAPI (port=$clashApiPort)")
                
                val results = latencyTester.urlTestAllNodes(currentNodes, clashApiPort) { completed, total ->
                    _testingLabel.value = "URL Test 测试中 ($completed/$total)"
                }
                Log.d(tag, "Got ${results.size} URL test results")
                
                // 更新数据库
                results.forEach { result ->
                    nodeDao.updateLatency(
                        nodeId = result.nodeId,
                        latency = result.latency,
                        isAvailable = result.isAvailable,
                        testedAt = System.currentTimeMillis()
                    )
                }
            } finally {
                // 读取 sing-box 核心日志
                if (startedHeadless) {
                    withContext(Dispatchers.IO) {
                        val coreLog = xyz.a202132.app.network.UrlTestManager.readLogFile(getApplication())
                        if (coreLog != null) {
                            // 分段输出日志（logcat 单条消息有长度限制）
                            coreLog.lines().forEach { line ->
                                Log.d("SingBoxCoreLog", line)
                            }
                        } else {
                            Log.w(tag, "No sing-box core log available")
                        }
                    }
                }
                // 如果启动了临时实例，关闭它
                if (startedHeadless) {
                    xyz.a202132.app.network.UrlTestManager.stop()
                }
                _isTesting.value = false
                _testingLabel.value = null
            }
        }
    }
    
    /**
     * 打开节点列表并开始指定类型的测试
     */
    fun showNodeListForTest(testType: String) {
        _showNodeList.value = true
        when (testType) {
            "tcping" -> testAllNodes()
            "urltest" -> urlTestAllNodes()
        }
    }
    
    /**
     * 清理不可用节点 (UI 过滤，不删除数据库)
     */
    fun cleanUnavailableNodes() {
        val hiddenCount = nodes.value.count { it.latency == -2 || !it.isAvailable }
        if (hiddenCount > 0) {
            _filterUnavailable.value = true
            _error.value = "已隐藏 $hiddenCount 个超时/不可用节点"
        } else {
            _error.value = "没有需要清理的超时/不可用节点"
        }
    }

    /**
     * 内部测试逻辑 (Suspend)
     */
    private suspend fun internalTestNodes(
        currentNodes: List<Node>,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null
    ) {
        if (currentNodes.isEmpty()) return
        
        Log.d(tag, "Testing ${currentNodes.size} nodes")
        
        // 统一使用 Socket 测试 (直接连接节点服务器测试可达性)
        // 即使 VPN 运行中也可以工作，因为测试的是节点服务器本身
        val results = latencyTester.testAllNodes(currentNodes, onProgress)
        Log.d(tag, "Got ${results.size} test results")
        
        // 更新数据库
        results.forEach { result ->
            nodeDao.updateLatency(
                nodeId = result.nodeId,
                latency = result.latency,
                isAvailable = result.isAvailable,
                testedAt = System.currentTimeMillis()
            )
        }
    }

    /**
     * 开始自动选择并连接
     * (Blocking UI Flow)
     */
    fun startAutoSelectAndConnect() {
        viewModelScope.launch {
            if (_isAutoSelecting.value || _isTesting.value) return@launch
            
            _isAutoSelecting.value = true
            try {
                Log.i(tag, "Starting Auto-Select and Connect Flow")
                
                // 1. 获取所有节点
                val allNodes = nodes.value
                if (allNodes.isEmpty()) {
                    _error.value = "没有可用节点"
                    return@launch
                }
                
                // 2. 强制重新测试所有节点延迟 (使用 URL Test，因为用户偏好真实连通性)
                // 注意：如果 VPN 未连接，这会启动临时无头实例，耗时较长 (2-3s)
                urlTestAllNodes(allNodes)
                
                // 等待测试完成 (urlTestAllNodes 是异步开启的，但 updateLatency 是同步写入数据库的)
                // 这里需要一种机制等待测试结束或者轮询数据库
                // 由于 urlTestAllNodes 在 viewModelScope.launch 中开启了另一个 launch (fire-and-forget for UI), 
                // 我们需要将其改为 suspend 或者等待 _isTesting 变回 false
                
                // 简单的做法：等待 _isTesting 变为 true (如果还没变)，然后等待它变为 false
                delay(500) // 等待测试启动
                while (_isTesting.value) {
                    delay(500)
                }
                
                // 3. 寻找最佳节点 (latency > 0)
                // 重新从数据库获取最新状态 (或者 internalTestNodes 返回结果，这里直接查库更保险)
                val updatedNodes = nodeDao.getAllNodes().first()
                val bestNode = updatedNodes.filter { it.latency > 0 }.minByOrNull { it.latency }
                
                if (bestNode != null) {
                    Log.i(tag, "Found best node: ${bestNode.name} (${bestNode.latency}ms)")
                    
                    // 4. 设置选中节点
                    settingsRepository.setSelectedNodeId(bestNode.id)
                    delay(500) // 稍微等待状态更新
                    
                    // 5. 启动连接
                    // 注意：这里我们手动调用 startVpn，并传入刚才选中的节点
                    // ServiceManager.startVpn 需要 Context，ViewModel 有 Application
                    ServiceManager.startVpn(getApplication(), bestNode, proxyMode.value)
                    
                } else {
                    _error.value = "无法连接所有节点，请检查网络"
                }
                
            } catch (e: Exception) {
                Log.e(tag, "Auto-select failed", e)
                _error.value = "自动选择失败: ${e.message}"
            } finally {
                _isAutoSelecting.value = false
            }
        }
    }
    
    /**
     * 自动选择最佳节点
     */
    fun autoSelectBestNode() {
        viewModelScope.launch {
            val bestNode = nodeDao.getBestNode()
            if (bestNode != null) {
                selectNode(bestNode)
            }
        }
    }
    
    /**
     * 选择节点
     */
    fun selectNode(node: Node) {
        viewModelScope.launch {
            settingsRepository.setSelectedNodeId(node.id)
            _showNodeList.value = false
            
            // If VPN is connected, restart to switch to new node
            if (vpnState.value == VpnState.CONNECTED) {
                // Notify user (Optional)
                Log.i(tag, "Restarting VPN to apply new Node: ${node.name}")
                ServiceManager.startVpn(getApplication(), node, proxyMode.value)
            }
        }
    }
    
    /**
     * 切换代理模式
     */
    fun setProxyMode(mode: ProxyMode) {
        viewModelScope.launch {
            settingsRepository.setProxyMode(mode)
            
            // If VPN is connected, restart to apply new mode config
            if (vpnState.value == VpnState.CONNECTED) {
                currentNode.value?.let { node ->
                    // Notify user (Optional, usually ConnectButton shows 'Connecting...')
                    Log.i(tag, "Restarting VPN to apply Proxy Mode: $mode")
                    ServiceManager.startVpn(getApplication(), node, mode)
                }
            }
        }
    }
    
    /**
     * 切换VPN连接
     */
    fun toggleVpn() {
        val node = currentNode.value ?: run {
            _error.value = "请先选择节点"
            return
        }
        
        when (vpnState.value) {
            VpnState.DISCONNECTED -> {
                // 4. 无网络连接节点时给用户发Toast，但不阻止
                if (!NetworkUtils.isNetworkAvailable(getApplication())) {
                     _error.value = "当前无网络连接节点！"
                }
                ServiceManager.startVpn(getApplication(), node, proxyMode.value)
            }
            VpnState.CONNECTED -> {
                ServiceManager.stopVpn(getApplication())
            }
            else -> {
                // 正在连接或断开中，忽略
            }
        }
    }
    
    /**
     * 重启 VPN 如果正在运行 (用于应用设置变更)
     */
    fun restartVpnIfNeeded() {
        if (vpnState.value == VpnState.CONNECTED) {
            currentNode.value?.let { node ->
                Log.i(tag, "Settings changed, restarting VPN to apply...")
                ServiceManager.startVpn(getApplication(), node, proxyMode.value)
            }
        }
    }
    
    /**
     * 设置绕过局域网
     */
    fun setBypassLan(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setBypassLan(enabled)
            // 如果 VPN 正在运行，重启以应用新设置
            restartVpnIfNeeded()
        }
    }
    
    /**
     * 设置 IPv6 路由模式
     */
    fun setIPv6RoutingMode(mode: IPv6RoutingMode) {
        viewModelScope.launch {
            settingsRepository.setIPv6RoutingMode(mode)
            // 如果 VPN 正在运行，重启以应用新设置
            restartVpnIfNeeded()
        }
    }

    /**
     * 检查通知公告
     */
    private suspend fun checkNotice() {
        try {
            val noticeInfo = NetworkClient.apiService.getNoticeInfo(AppConfig.NOTICE_URL)
            
            // Always update config for features like Backup Node
            _noticeConfig.value = noticeInfo
            
            if (noticeInfo.hasNotice) {
                // 如果 showOnce 为 true，检查是否已显示过
                // 如果 showOnce 为 false，则每次都显示
                if (!noticeInfo.showOnce) {
                    _notice.value = noticeInfo
                    // 更新最后显示的 ID，以便后续如果服务器端改为 true，也能正确判断
                    settingsRepository.setLastNoticeId(noticeInfo.noticeId)
                } else {
                    val lastNoticeId = settingsRepository.lastNoticeId.first()
                    if (lastNoticeId != noticeInfo.noticeId) {
                        _notice.value = noticeInfo
                        settingsRepository.setLastNoticeId(noticeInfo.noticeId)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to check notice", e)
        }
    }
    
    
    /**
     * 检查更新
     * @param isAuto 是否为自动检查 (不显示"已是最新"提示)
     */
    fun checkUpdate(isAuto: Boolean = false) {
        viewModelScope.launch {
            // 节流检查 (自动检查可跳过)
            if (!isAuto) {
                val now = System.currentTimeMillis()
                if (now - lastCheckUpdateTime < THROTTLE_INTERVAL) {
                    _error.value = "操作过于频繁，请稍后再试"
                    return@launch
                }
                lastCheckUpdateTime = now
            }
            
            try {
                val info = NetworkClient.apiService.getUpdateInfo(AppConfig.UPDATE_URL)
                val currentVersionCode = getApplication<Application>().packageManager
                    .getPackageInfo(getApplication<Application>().packageName, 0)
                    .let { 
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            it.longVersionCode.toInt()
                        } else {
                            @Suppress("DEPRECATION")
                            it.versionCode
                        }
                    }
                
                if (info.versionCode > currentVersionCode) {
                    _updateInfo.value = info
                } else {
                    if (!isAuto) {
                        _error.value = "已是最新版本"
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to check update", e)
                if (!isAuto) {
                    _error.value = "检查更新失败"
                }
            }
        }
    }
    
    // Download State
    val downloadState = DownloadManager.downloadState
    
    /**
     * 打开下载链接 (现在改为应用内下载)
     */
    fun openDownloadUrl() {
        updateInfo.value?.let { info ->
            // Use DownloadManager to check if the file is already downloaded and valid
            val existingFile = DownloadManager.isApkReady(getApplication(), info.version)
            if (existingFile != null) {
                // Determine if we need to show permission dialog first (handled in UI via status)
                // But since we want to trigger install, we can set status to COMPLETED
                // However, UI listens to status change. 
                // If status is already COMPLETED, no change triggers.
                // We should force installApk logic here.
                installApk()
                return
            }
            
            viewModelScope.launch {
                DownloadManager.startDownload(info.downloadUrl, getApplication(), info.version)
            }
        }
    }
    
    fun pauseDownload() {
        DownloadManager.pauseDownload()
    }
    
    fun resumeDownload() {
        updateInfo.value?.let { info ->
            viewModelScope.launch {
                DownloadManager.startDownload(info.downloadUrl, getApplication(), info.version)
            }
        }
    }
    
    fun retryDownload() {
         updateInfo.value?.let { info ->
            viewModelScope.launch {
                DownloadManager.startDownload(info.downloadUrl, getApplication(), info.version)
            }
        }
    }
    
    fun cancelDownload() {
        DownloadManager.cancelDownload()
    }
    
    fun installApk() {
        val targetFile = downloadState.value.file ?: run {
             updateInfo.value?.let { info ->
                 DownloadManager.isApkReady(getApplication(), info.version)
             }
        }

        targetFile?.let { file ->
            val context = getApplication<Application>()
            try {
                if (file.exists()) { 
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                    context.startActivity(intent)
                } else {
                     _error.value = "安装文件丢失，请重新下载"
                     // Reset state to allow re-download
                     DownloadManager.resetState()
                }
            } catch (e: Exception) {
                Log.e(tag, "Install failed", e)
                _error.value = "无法启动安装程序: ${e.message}"
            }
        } ?: run {
             _error.value = "找不到安装包，请重试下载"
             DownloadManager.resetState()
        }
    }
    
    // UI Actions
    fun showNodeList() {
        _showNodeList.value = true
    }
    
    fun hideNodeList() {
        _showNodeList.value = false
    }
    
    fun resetFilter() {
        _filterUnavailable.value = false
    }
    
    fun dismissNotice() {
        _notice.value = null
    }
    
    fun dismissUpdate() {
        _updateInfo.value = null
    }
    
    fun clearError() {
        _error.value = null
    }
    
    fun acceptUserAgreement() {
        viewModelScope.launch {
            settingsRepository.setUserAgreementAccepted(true)
        }
    }

    // 自动化测试设置写入
    fun setAutoTestEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoTestEnabled(enabled) }
    }

    fun setAutoTestFilterUnavailable(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoTestFilterUnavailable(enabled) }
    }

    fun setAutoTestLatencyThresholdMs(value: Int) {
        viewModelScope.launch { settingsRepository.setAutoTestLatencyThresholdMs(value) }
    }

    fun setAutoTestBandwidthEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoTestBandwidthEnabled(enabled) }
    }

    fun setAutoTestBandwidthThresholdMbps(value: Int) {
        viewModelScope.launch { settingsRepository.setAutoTestBandwidthThresholdMbps(value) }
    }

    fun setAutoTestBandwidthWifiOnly(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoTestBandwidthWifiOnly(enabled) }
    }

    fun setAutoTestBandwidthSizeMb(value: Int) {
        viewModelScope.launch { settingsRepository.setAutoTestBandwidthSizeMb(value) }
    }

    fun setAutoTestUnlockEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoTestUnlockEnabled(enabled) }
    }

    fun setAutoTestNodeLimit(value: Int) {
        viewModelScope.launch { settingsRepository.setAutoTestNodeLimit(value) }
    }

    fun startAutomatedTest() {
        if (_autoTestProgress.value.running) return

        val config = AutoTestConfig(
            enabled = autoTestEnabled.value,
            filterUnavailable = autoTestFilterUnavailable.value,
            latencyThresholdMs = autoTestLatencyThresholdMs.value,
            bandwidthEnabled = autoTestBandwidthEnabled.value,
            bandwidthThresholdMbps = autoTestBandwidthThresholdMbps.value,
            bandwidthWifiOnly = autoTestBandwidthWifiOnly.value,
            bandwidthSizeMb = autoTestBandwidthSizeMb.value,
            unlockEnabled = autoTestUnlockEnabled.value,
            nodeLimit = autoTestNodeLimit.value
        )

        autoTestJob = viewModelScope.launch {
            try {
                _autoTestProgress.value = AutoTestProgress(
                    running = true,
                    stage = AutoTestStage.FETCH_NODES,
                    message = "正在拉取节点..."
                )

                fetchNodes(bypassThrottle = true, runUrlTest = false)
                waitForCondition(timeoutMs = 25_000) { !isLoading.value && nodes.value.isNotEmpty() }

                var workingNodes = nodes.value.take(config.nodeLimit)
                if (workingNodes.isEmpty()) {
                    _error.value = "自动化测试失败：没有可用节点"
                    _autoTestProgress.value = AutoTestProgress(running = false, stage = AutoTestStage.FAILED, message = "没有可用节点")
                    return@launch
                }

                _autoTestProgress.value = AutoTestProgress(
                    running = true,
                    stage = AutoTestStage.URL_TEST,
                    message = "正在进行 URL Test...",
                    total = workingNodes.size
                )

                urlTestAllNodes(workingNodes)
                waitForCondition(timeoutMs = 120_000) { !isTesting.value }

                workingNodes = nodeDao.getAllNodes().first().take(config.nodeLimit)

                _autoTestProgress.value = AutoTestProgress(
                    running = true,
                    stage = AutoTestStage.FILTER_LATENCY,
                    message = "按延迟阈值筛选节点...",
                    total = workingNodes.size
                )

                val latencyFiltered = if (config.filterUnavailable) {
                    workingNodes.filter { it.isAvailable && it.latency > 0 && it.latency <= config.latencyThresholdMs }
                } else {
                    workingNodes
                }

                val removedByLatency = workingNodes.filterNot { node -> latencyFiltered.any { it.id == node.id } }
                removedByLatency.forEach { node ->
                    nodeDao.updateAutoTestStatus(node.id, false, "LATENCY_FILTERED", System.currentTimeMillis())
                }
                latencyFiltered.forEach { node ->
                    nodeDao.updateAutoTestStatus(node.id, true, "LATENCY_PASSED", System.currentTimeMillis())
                }

                workingNodes = latencyFiltered

                if (config.bandwidthEnabled) {
                    val wifiAllowed = !config.bandwidthWifiOnly || NetworkUtils.isWifiConnected(getApplication())
                    if (!wifiAllowed) {
                        _error.value = "当前非 Wi-Fi 网络，已跳过带宽测试"
                    } else {
                        _autoTestProgress.value = AutoTestProgress(
                            running = true,
                            stage = AutoTestStage.BANDWIDTH_TEST,
                            message = "逐节点带宽测试中...",
                            total = workingNodes.size
                        )

                        val bandwidthMap = mutableMapOf<String, Float>()
                        workingNodes.forEachIndexed { index, node ->
                            _autoTestProgress.value = AutoTestProgress(
                                running = true,
                                stage = AutoTestStage.BANDWIDTH_TEST,
                                message = "带宽测试: ${node.getDisplayName()} (${index + 1}/${workingNodes.size})",
                                completed = index + 1,
                                total = workingNodes.size
                            )
                            val mbps = testNodeBandwidthMbps(node, config.bandwidthSizeMb)
                            bandwidthMap[node.id] = mbps
                            nodeDao.updateBandwidth(node.id, mbps, 0f, System.currentTimeMillis())
                        }

                        _autoTestProgress.value = AutoTestProgress(
                            running = true,
                            stage = AutoTestStage.FILTER_BANDWIDTH,
                            message = "按带宽阈值筛选节点...",
                            total = workingNodes.size
                        )

                        val filtered = workingNodes.filter { (bandwidthMap[it.id] ?: 0f) >= config.bandwidthThresholdMbps.toFloat() }
                        val removed = workingNodes.filterNot { node -> filtered.any { it.id == node.id } }
                        removed.forEach { node ->
                            nodeDao.updateAutoTestStatus(node.id, false, "BANDWIDTH_FILTERED", System.currentTimeMillis())
                        }
                        filtered.forEach { node ->
                            nodeDao.updateAutoTestStatus(node.id, true, "BANDWIDTH_PASSED", System.currentTimeMillis())
                        }
                        workingNodes = filtered
                    }
                }

                if (config.unlockEnabled && workingNodes.isNotEmpty()) {
                    _autoTestProgress.value = AutoTestProgress(
                        running = true,
                        stage = AutoTestStage.UNLOCK_TEST,
                        message = "逐节点流媒体解锁测试中...",
                        total = workingNodes.size
                    )

                    workingNodes.forEachIndexed { index, node ->
                        _autoTestProgress.value = AutoTestProgress(
                            running = true,
                            stage = AutoTestStage.UNLOCK_TEST,
                            message = "解锁测试: ${node.getDisplayName()} (${index + 1}/${workingNodes.size})",
                            completed = index + 1,
                            total = workingNodes.size
                        )
                        val (summary, passed) = testNodeUnlock(node)
                        nodeDao.updateUnlock(node.id, summary, passed, System.currentTimeMillis())
                        nodeDao.updateAutoTestStatus(
                            nodeId = node.id,
                            available = node.isAvailable && passed,
                            status = if (passed) "UNLOCK_PASSED" else "UNLOCK_FAILED",
                            testedAt = System.currentTimeMillis()
                        )
                    }
                }

                _autoTestProgress.value = AutoTestProgress(
                    running = false,
                    stage = AutoTestStage.DONE,
                    message = "完成：保留 ${workingNodes.size} 个节点",
                    completed = workingNodes.size,
                    total = config.nodeLimit
                )
            } catch (e: Exception) {
                Log.e(tag, "Automated test failed", e)
                _autoTestProgress.value = AutoTestProgress(
                    running = false,
                    stage = AutoTestStage.FAILED,
                    message = "失败: ${e.message}"
                )
                _error.value = "自动化测试失败: ${e.message}"
            } finally {
                autoTestJob = null
            }
        }
    }

    fun cancelAutomatedTest() {
        autoTestJob?.cancel()
        autoTestJob = null
        _autoTestProgress.value = AutoTestProgress(
            running = false,
            stage = AutoTestStage.CANCELED,
            message = "自动化测试已取消"
        )
    }

    private suspend fun waitForCondition(timeoutMs: Long, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                throw IllegalStateException("等待测试阶段超时")
            }
            delay(300)
        }
    }

    private suspend fun testNodeBandwidthMbps(node: Node, sizeMb: Int): Float = withContext(Dispatchers.IO) {
        val port = pickFreePort()
        val started = UnlockTestManager.start(getApplication(), node, port)
        if (!started) return@withContext 0f

        try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
            val client = OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            val bytes = sizeMb.toLong() * 1_000_000L
            val url = "${AppConfig.SPEED_TEST_DOWNLOAD_URL}?bytes=$bytes&r=${System.currentTimeMillis()}"
            val request = Request.Builder().url(url).build()

            val start = System.currentTimeMillis()
            var total = 0L
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext 0f
                val source = resp.body?.source() ?: return@withContext 0f
                val buffer = ByteArray(8192)
                var read: Int
                while (source.read(buffer).also { read = it } != -1) {
                    total += read
                }
            }
            val durationMs = (System.currentTimeMillis() - start).coerceAtLeast(1)
            (total * 8f / 1_000_000f) / (durationMs / 1000f)
        } catch (e: Exception) {
            Log.e(tag, "Bandwidth test failed for ${node.getDisplayName()}: ${e.message}")
            0f
        } finally {
            UnlockTestManager.stop()
        }
    }

    private suspend fun testNodeUnlock(node: Node): Pair<String, Boolean> = withContext(Dispatchers.IO) {
        val port = pickFreePort()
        val started = UnlockTestManager.start(getApplication(), node, port)
        if (!started) return@withContext "启动测试代理失败" to false

        try {
            val result = UnlockTestsRunner.run(
                context = getApplication(),
                args = listOf(
                    "-socks-proxy", "socks5://127.0.0.1:$port",
                    "-f", "0",
                    "-L", "zh",
                    "-b=false",
                    "-s=false"
                ),
                timeoutSeconds = 120
            )
            val lines = result.stdout.lineSequence()
                .map { cleanLogLine(it) }
                .filter { it.isNotEmpty() }
                .filter { it.contains("YES", true) || it.contains("NO", true) || it.contains("Available", true) || it.contains("Region", true) }
                .take(200)
                .toList()

            val yes = lines.count { it.contains("YES", true) || it.contains("Available", true) }
            val no = lines.count { it.contains("NO", true) }
            val pass = yes >= 3 && yes >= no
            val fullResult = if (lines.isEmpty()) {
                if (result.stdout.isBlank()) "未识别到解锁结果" else "未识别关键结果（可在流媒体工具里查看完整输出）"
            } else {
                "YES=$yes, NO=$no\n" + lines.joinToString("\n")
            }
            fullResult to pass
        } catch (e: Exception) {
            Log.e(tag, "Unlock test failed for ${node.getDisplayName()}: ${e.message}")
            ("解锁测试失败: ${e.message}") to false
        } finally {
            UnlockTestManager.stop()
        }
    }

    private fun cleanLogLine(input: String): String {
        return input
            .replace(Regex("\\u001B\\[[;\\d]*[ -/]*[@-~]"), "")
            .replace(Regex("\\[[0-9;]*m"), "")
            .replace("[0m", "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun pickFreePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    private suspend fun migrateRawLinksToEncrypted() {
        runCatching {
            val current = nodeDao.getAllNodes().first()
            var migrated = 0
            current.forEach { node ->
                if (!node.isRawLinkEncrypted()) {
                    val encrypted = CryptoUtils.encryptForStorage(node.rawLink)
                    if (encrypted != node.rawLink) {
                        nodeDao.updateNode(node.copy(rawLink = encrypted))
                        migrated++
                    }
                }
            }
            if (migrated > 0) {
                Log.i(tag, "Migrated $migrated node rawLink entries to encrypted storage")
            }
        }.onFailure {
            Log.w(tag, "RawLink migration skipped: ${it.message}")
        }
    }

    private suspend fun logRawLinkEncryptionStats(scene: String) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val current = nodeDao.getAllNodes().first()
            val total = current.size
            val encrypted = current.count { it.isRawLinkEncrypted() }
            val plain = total - encrypted
            Log.d("RawLinkCryptoStats", "scene=$scene total=$total encrypted=$encrypted plain=$plain")
        }.onFailure {
            Log.w("RawLinkCryptoStats", "scene=$scene stats_failed=${it.message}")
        }
    }
}
