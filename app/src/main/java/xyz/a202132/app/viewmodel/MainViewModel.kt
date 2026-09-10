package xyz.a202132.app.viewmodel

import android.app.Application
import android.content.Intent
import android.util.Log
import com.google.gson.Gson
import io.nekohasekai.libbox.Libbox
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.a202132.app.AppConfig
import xyz.a202132.app.BuildConfig
import xyz.a202132.app.UnlockPrioritySite
import xyz.a202132.app.data.local.AppDatabase
import xyz.a202132.app.data.model.*
import xyz.a202132.app.data.repository.SettingsRepository
import xyz.a202132.app.network.LatencyTester
import xyz.a202132.app.network.NetworkClient
import xyz.a202132.app.network.SpeedTestService
import xyz.a202132.app.network.SubscriptionParser
import xyz.a202132.app.network.DownloadManager
import xyz.a202132.app.network.FireflyAccessBanEvents
import xyz.a202132.app.network.UnlockTestManager
import xyz.a202132.app.network.isFireflyAccessBanned
import xyz.a202132.app.service.BoxVpnService
import xyz.a202132.app.service.ServiceManager
import xyz.a202132.app.util.NetworkUtils
import xyz.a202132.app.util.Hysteria2BandwidthAdvisor
import xyz.a202132.app.util.RuntimeLog
import xyz.a202132.app.util.SingBoxConfigGenerator
import xyz.a202132.app.util.UnlockTestsRunner
import xyz.a202132.app.util.LegacyRawLinkMigrationCrypto
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import java.util.UUID

enum class StartupDefaultTestMode {
    NONE,
    TCPING,
    URL_TEST
}

enum class NodeImportResult {
    IMPORTED,
    DUPLICATE,
    INVALID
}

data class Hysteria2BandwidthAdaptationState(
    val isRunning: Boolean = false,
    val uploadMbps: Int? = null,
    val downloadMbps: Int? = null,
    val resultGeneration: Long = 0L
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    private val tag = "MainViewModel"
    private val database = AppDatabase.getInstance(application)
    private val nodeDao = database.nodeDao()
    private val subscriptionDao = database.subscriptionDao()
    private val settingsRepository = SettingsRepository(application)
    private val subscriptionParser = SubscriptionParser(application)
    private val configGenerator = SingBoxConfigGenerator()
    private val latencyTester = LatencyTester()
    private val gson = Gson()

    private data class NodeIpInfoAttemptResult(
        val result: Result<NodeIpInfo>,
        val shouldRetry: Boolean
    )
    
    // 节流控制
    private val THROTTLE_INTERVAL = 5000L // 5秒节流间隔
    private val CLIENT_CATALOG_CACHE_MS = 5000L
    private var lastFetchNodesTime = 0L
    private var lastCheckUpdateTime = 0L
    private val startupAutoUpdatedSubscriptionGroupIds = mutableSetOf<String>()
    // 缓存最新拉取的节点，供launchStartupDefaultTestIfNeeded使用（避免 Room Flow 延迟导致测旧节点）
    private var lastFetchedNodes: List<Node>? = null
    
    // UI状态
    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()
    
    private val _isTesting = MutableStateFlow(false)
    val isTesting = _isTesting.asStateFlow()
    
    // 测试类型标签 (用于 UI 显示)
    private val _testingLabel = MutableStateFlow<String?>(null)
    val testingLabel = _testingLabel.asStateFlow()
    
    // 过滤不可用节点
    private val _filterUnavailable = MutableStateFlow(false)
    val filterUnavailable = _filterUnavailable.asStateFlow()
    
    private val _notice = MutableStateFlow<NoticeInfo?>(null)
    val notice = _notice.asStateFlow()
    
    // 持久通知配置（与对话框可见性无关）
    private val _noticeConfig = MutableStateFlow<NoticeInfo?>(null)
    val noticeConfig = _noticeConfig.asStateFlow()
    
    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo = _updateInfo.asStateFlow()
    private val _remoteAppSettings = MutableStateFlow(RemoteAppSettings())
    val remoteAppSettings = _remoteAppSettings.asStateFlow()
    private val _updateCheckAvailable = MutableStateFlow(false)
    val updateCheckAvailable = _updateCheckAvailable.asStateFlow()
    private var cachedClientCatalog: ClientCatalogInfo? = null
    private var cachedClientCatalogAt = 0L
    private val _startupUpdateCheckCompleted =
        MutableStateFlow(AppConfig.STARTUP_SPLASH_DURATION_SECONDS <= 0)
    val startupUpdateCheckCompleted = _startupUpdateCheckCompleted.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)

    val error = _error.asStateFlow()
    private val newBuiltInGroupMessageChannel = Channel<String>(Channel.BUFFERED)
    val newBuiltInGroupMessages = newBuiltInGroupMessageChannel.receiveAsFlow()
    private val _infoDialogMessage = MutableStateFlow<String?>(null)
    val infoDialogMessage = _infoDialogMessage.asStateFlow()
    private val _fireflyAccessBanDialogVisible = MutableStateFlow(false)
    val fireflyAccessBanDialogVisible = _fireflyAccessBanDialogVisible.asStateFlow()
    private val _showStartupDefaultTestChoiceDialog = MutableStateFlow(false)
    val showStartupDefaultTestChoiceDialog = _showStartupDefaultTestChoiceDialog.asStateFlow()

    // 仅在本次 APP 进程存活期间有效，不写入 DataStore。
    private val _skipFavoriteRemovalConfirmation = MutableStateFlow(false)
    val skipFavoriteRemovalConfirmation = _skipFavoriteRemovalConfirmation.asStateFlow()
    
    // 阻止自动选择状态
    private val _isAutoSelecting = MutableStateFlow(false)
    val isAutoSelecting = _isAutoSelecting.asStateFlow()

    // 自动化测试状态
    private val _autoTestProgress = MutableStateFlow(AutoTestProgress())
    val autoTestProgress = _autoTestProgress.asStateFlow()
    private val _autoTestResultSnapshot = MutableStateFlow<List<Node>>(emptyList())
    val autoTestResultSnapshot = _autoTestResultSnapshot.asStateFlow()
    private val _autoTestResultMode = MutableStateFlow<TestPreferMode?>(null)
    val autoTestResultMode = _autoTestResultMode.asStateFlow()
    private val _autoTestResultPriority = MutableStateFlow(BestNodePriority.LATENCY)
    val autoTestResultPriority = _autoTestResultPriority.asStateFlow()
    private var autoTestJob: Job? = null
    
    val selectedNodeGroupId = settingsRepository.selectedNodeGroupId.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        DEFAULT_SUBSCRIPTION_GROUP_ID
    )

    val subscriptionGroups = subscriptionDao.getGroups().stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        emptyList()
    )

    val nodeGroups = combine(
        subscriptionGroups,
        settingsRepository.favoritesGroupSortOrder,
        settingsRepository.unreadBuiltInGroupIds
    ) { groups, favoritesSortOrder, unreadBuiltInGroupIds ->
        (groups.map { group ->
            NodeGroup(
                id = group.id,
                name = group.name,
                sortOrder = group.sortOrder,
                isBuiltIn = isBuiltInSubscriptionGroup(group.id),
                hasUnreadNodes = group.id in unreadBuiltInGroupIds
            )
        } + NodeGroup(
            id = FAVORITES_NODE_GROUP_ID,
            name = "收藏节点",
            sortOrder = favoritesSortOrder,
            isFavorites = true
        )).sortedWith(compareBy<NodeGroup> { it.sortOrder }.thenBy { it.name })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val favoriteNodeCount = nodeDao.getFavoriteNodes()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val subscriptionGroupSummaries = combine(
        subscriptionGroups,
        subscriptionDao.getAllLinks()
    ) { groups, links ->
        groups.map { group ->
            SubscriptionGroupSummary(group, links.filter { it.groupId == group.id })
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val favoriteSourceNodeIds = nodeDao.getFavoriteSourceNodeIds()
        .map { it.filterNotNull().toSet() }
        .stateIn(
            viewModelScope,
            SharingStarted.Lazily,
            emptySet()
        )

    val allNodeListNodes = combine(
        nodeDao.getAllNodes(),
        _filterUnavailable
    ) { list, filterOut ->
        sortNodesForList(list, filterOut)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Data
    val nodes = combine(
        allNodeListNodes,
        selectedNodeGroupId
    ) { allNodes, groupId ->
        if (groupId == FAVORITES_NODE_GROUP_ID) {
            allNodes.filter { it.source == NodeSource.FAVORITE }
        } else {
            allNodes.filter {
                it.source == NodeSource.SUBSCRIPTION && it.subscriptionGroupId == groupId
            }
        }
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

    val tunStackMode = settingsRepository.tunStackMode.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        TunStackMode.GVISOR
    )
    
    val isUserAgreementAccepted = settingsRepository.isUserAgreementAccepted.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        true // 默认 true 防止闪烁，init 里会 reset 状态或者第一次启动时读出来是 false
    )

    // 自动化测试设置
    val autoTestEnabled = settingsRepository.autoTestEnabled.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val autoTestFilterUnavailable = settingsRepository.autoTestFilterUnavailable.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val autoTestLatencyEnabled = settingsRepository.autoTestLatencyEnabled.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val autoTestLatencyMode = settingsRepository.autoTestLatencyMode.stateIn(viewModelScope, SharingStarted.Lazily, AutoTestLatencyMode.URL_TEST)
    val autoTestLatencyThresholdMs = settingsRepository.autoTestLatencyThresholdMs.stateIn(viewModelScope, SharingStarted.Lazily, 600)
    val autoTestBandwidthEnabled = settingsRepository.autoTestBandwidthEnabled.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val autoTestBandwidthDownloadEnabled = settingsRepository.autoTestBandwidthDownloadEnabled.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val autoTestBandwidthUploadEnabled = settingsRepository.autoTestBandwidthUploadEnabled.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val autoTestBandwidthDownloadThresholdMbps = settingsRepository.autoTestBandwidthDownloadThresholdMbps.stateIn(viewModelScope, SharingStarted.Lazily, 10)
    val autoTestBandwidthUploadThresholdMbps = settingsRepository.autoTestBandwidthUploadThresholdMbps.stateIn(viewModelScope, SharingStarted.Lazily, 10)
    val autoTestBandwidthWifiOnly = settingsRepository.autoTestBandwidthWifiOnly.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val autoTestBandwidthDownloadSizeMb = settingsRepository.autoTestBandwidthDownloadSizeMb.stateIn(viewModelScope, SharingStarted.Lazily, 10)
    val autoTestBandwidthUploadSizeMb = settingsRepository.autoTestBandwidthUploadSizeMb.stateIn(viewModelScope, SharingStarted.Lazily, 10)
    val autoTestUnlockEnabled = settingsRepository.autoTestUnlockEnabled.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val autoTestByRegion = settingsRepository.autoTestByRegion.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val autoTestNodeLimit = settingsRepository.autoTestNodeLimit.stateIn(viewModelScope, SharingStarted.Lazily, 20)
    val appThemeMode = settingsRepository.appThemeMode.stateIn(viewModelScope, SharingStarted.Lazily, AppThemeMode.SYSTEM)
    val preferTestModes = settingsRepository.preferTestModes.stateIn(viewModelScope, SharingStarted.Lazily, builtInPreferTestModes())
    val preferTestSelectedModeId = settingsRepository.preferTestSelectedModeId.stateIn(viewModelScope, SharingStarted.Lazily, BUILTIN_PREFER_MODE_CHAT)
    val startupDefaultTestMode = settingsRepository.startupDefaultTestMode.stateIn(viewModelScope, SharingStarted.Lazily, StartupDefaultTestMode.NONE)
    val rememberLastSelectedNodeEnabled = settingsRepository.rememberLastSelectedNodeEnabled.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val nodeIpInfoTestOnVpnStart = settingsRepository.nodeIpInfoTestOnVpnStart.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val vpnConnectivityRecoveryEnabled = settingsRepository.vpnConnectivityRecoveryEnabled.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        AppConfig.VPN_CONNECTIVITY_RECOVERY_DEFAULT_ENABLED
    )
    val hysteria2UploadMbps = settingsRepository.hysteria2UploadMbps.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        AppConfig.HYSTERIA2_DEFAULT_BANDWIDTH_MBPS
    )
    val hysteria2DownloadMbps = settingsRepository.hysteria2DownloadMbps.stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        AppConfig.HYSTERIA2_DEFAULT_BANDWIDTH_MBPS
    )
    private val _hysteria2BandwidthAdaptationState = MutableStateFlow(Hysteria2BandwidthAdaptationState())
    val hysteria2BandwidthAdaptationState = _hysteria2BandwidthAdaptationState.asStateFlow()
    val tcpingTestTimeoutMs = settingsRepository.tcpingTestTimeoutMs.stateIn(viewModelScope, SharingStarted.Lazily, AppConfig.TCPING_TEST_TIMEOUT)
    val urlTestTimeoutMs = settingsRepository.urlTestTimeoutMs.stateIn(viewModelScope, SharingStarted.Lazily, AppConfig.URL_TEST_TIMEOUT)
    val nodeIpInfoTimeoutMs = settingsRepository.nodeIpInfoTimeoutMs.stateIn(viewModelScope, SharingStarted.Lazily, AppConfig.NODE_IP_INFO_TIMEOUT_MS)
    val speedTestDownloadTimeoutMs = settingsRepository.speedTestDownloadTimeoutMs.stateIn(viewModelScope, SharingStarted.Lazily, AppConfig.AUTO_TEST_BANDWIDTH_DOWNLOAD_TIMEOUT_MS)
    val tcpingConcurrency = settingsRepository.tcpingConcurrency.stateIn(viewModelScope, SharingStarted.Lazily, AppConfig.TCPING_CONCURRENCY)
    val urlTestConcurrency = settingsRepository.urlTestConcurrency.stateIn(viewModelScope, SharingStarted.Lazily, AppConfig.URL_TEST_CONCURRENCY)
    val bandwidthTestConcurrency = settingsRepository.bandwidthTestConcurrency.stateIn(viewModelScope, SharingStarted.Lazily, 1)
    val unlockTestConcurrency = settingsRepository.unlockTestConcurrency.stateIn(viewModelScope, SharingStarted.Lazily, AppConfig.AUTO_TEST_UNLOCK_CONCURRENCY)
    val vpnMtu = settingsRepository.vpnMtu.stateIn(viewModelScope, SharingStarted.Lazily, AppConfig.VPN_MTU)
    val lanProxyEnabled = settingsRepository.lanProxyEnabled.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val lanProxyAutoPort = settingsRepository.lanProxyAutoPort.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val lanProxyPort = settingsRepository.lanProxyPort.stateIn(viewModelScope, SharingStarted.Lazily, AppConfig.LAN_PROXY_DEFAULT_PORT)
    val lanProxyAuthEnabled = settingsRepository.lanProxyAuthEnabled.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val lanProxyUsername = settingsRepository.lanProxyUsername.stateIn(viewModelScope, SharingStarted.Lazily, "firefly")
    val lanProxyPassword = settingsRepository.lanProxyPassword.stateIn(viewModelScope, SharingStarted.Lazily, "firefly")
    
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
        viewModelScope.launch {
            FireflyAccessBanEvents.events.collect { event ->
                handleFireflyAccessBan(event.errorCode)
            }
        }
        viewModelScope.launch {
            ensureDefaultSubscriptionGroup()
        }
        // 将旧静态密钥字段迁移回已由 SQLCipher 整库保护的数据（一次性后台处理）
        viewModelScope.launch(Dispatchers.IO) {
            migrateLegacyRawLinksToSqlCipher()
        }

        // 启动时加载数据
        viewModelScope.launch {
            // 每次启动重置选择状态 (不记住上次选择)
            ensureDefaultSubscriptionGroup()
            settingsRepository.setSelectedNodeId(null)
            
            // 标记初始化完成，此时 selectedNodeId 才开始发出真实值
            // 这样 UI 不会看到旧的选中状态闪烁
            _isInitialized.value = true
            
            // 监听用户协议状态，只有同意后才初始化网络请求
            isUserAgreementAccepted.collect { accepted ->
                if (accepted) {
                    // 远程入口、公告和更新不依赖节点刷新是否成功。
                    checkNotice()
                    checkUpdate(isAuto = true, trackStartupSplash = true)
                    val selectedGroupAtStartup = settingsRepository.selectedNodeGroupId.first()
                    val restoreBuiltInSelection = selectedGroupAtStartup == FAVORITES_NODE_GROUP_ID &&
                        subscriptionDao.getGroupsOnce().none { isBuiltInSubscriptionGroup(it.id) }
                    subscriptionDao.getGroup(selectedGroupAtStartup)
                        ?.takeIf {
                            it.autoUpdateEnabled &&
                                SubscriptionAutoUpdatePolicy.isStartupOnce(it.updateIntervalMinutes)
                        }
                        ?.let { startupAutoUpdatedSubscriptionGroupIds.add(it.id) }
                    // 启动时始终检查内置服务。封禁清理后可能只剩“收藏”分组，若在此
                    // 跳过请求，客户端将无法感知解禁，也无法重新取得内置节点。
                    val builtInFetchOk = fetchNodesInternal(
                        bypassThrottle = true,
                        runUrlTest = false,
                        fetchingLabel = "同步内置节点分组",
                        showToast = false,
                        groupId = DEFAULT_SUBSCRIPTION_GROUP_ID,
                        notifyPartialBuiltInFailures = true
                    )
                    if (builtInFetchOk && restoreBuiltInSelection) {
                        subscriptionDao.getGroupsOnce()
                            .firstOrNull { isBuiltInSubscriptionGroup(it.id) }
                            ?.let { settingsRepository.setSelectedNodeGroupId(it.id) }
                    }
                    val autoEnabled = settingsRepository.autoTestEnabled.first()
                    if (autoEnabled) {
                        val selectedModeId = settingsRepository.preferTestSelectedModeId.first()
                        val modeName = settingsRepository.preferTestModes.first()
                            .firstOrNull { it.id == selectedModeId }
                            ?.name
                            ?: "当前模式"
                        _error.value = "APP启动后已自动执行${modeName}测试"
                        startAutomatedTest()
                    } else {
                        val selectedGroup = settingsRepository.selectedNodeGroupId.first()
                        val fetchOk = if (isBuiltInSubscriptionGroup(selectedGroup)) {
                            builtInFetchOk
                        } else if (selectedGroup == FAVORITES_NODE_GROUP_ID) {
                            true
                        } else {
                            fetchNodesInternal(
                                bypassThrottle = true,
                                runUrlTest = false,
                                fetchingLabel = "请求节点中",
                                groupId = selectedGroup
                            )
                        }
                        if (!fetchOk) return@collect
                        restoreRememberedLastSelectedNodeIfNeeded(lastFetchedNodes)
                        val choiceDone = settingsRepository.startupDefaultTestChoiceDone.first()
                        if (!choiceDone) {
                            _showStartupDefaultTestChoiceDialog.value = true
                        } else {
                            launchStartupDefaultTestIfNeeded(showHint = false)
                        }
                    }
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

        observeSubscriptionGroupAutoUpdates()
    }

    private suspend fun ensureDefaultSubscriptionGroup() {
        // 内置接口不写入可编辑订阅表，避免在管理界面或数据库记录中暴露。
        subscriptionDao.deleteLinksForGroup(DEFAULT_SUBSCRIPTION_GROUP_ID)
    }

    private fun observeSubscriptionGroupAutoUpdates() {
        viewModelScope.launch {
            combine(subscriptionGroups, isUserAgreementAccepted) { groups, accepted ->
                if (accepted) groups else emptyList()
            }.collectLatest { groups ->
                if (groups.none { it.autoUpdateEnabled }) return@collectLatest

                // 等数据库、启动请求和页面状态稳定后，再处理“启动后仅更新一次”的分组。
                delay(SubscriptionAutoUpdatePolicy.STARTUP_STABILITY_DELAY_MS)
                val startupOnceGroups = groups.filter { group ->
                    group.autoUpdateEnabled &&
                        SubscriptionAutoUpdatePolicy.isStartupOnce(group.updateIntervalMinutes) &&
                        group.id !in startupAutoUpdatedSubscriptionGroupIds
                }
                startupOnceGroups.forEach { group ->
                    if (!startupAutoUpdatedSubscriptionGroupIds.add(group.id)) return@forEach
                    fetchNodesInternal(
                        bypassThrottle = true,
                        runUrlTest = false,
                        fetchingLabel = "启动更新 ${group.name}",
                        showToast = false,
                        groupId = group.id
                    )
                }

                if (groups.none {
                        it.autoUpdateEnabled &&
                            !SubscriptionAutoUpdatePolicy.isStartupOnce(it.updateIntervalMinutes)
                    }
                ) return@collectLatest

                while (true) {
                    delay(SubscriptionAutoUpdatePolicy.PERIODIC_CHECK_INTERVAL_MS)
                    val now = System.currentTimeMillis()
                    groups.filter { group ->
                        group.autoUpdateEnabled &&
                            SubscriptionAutoUpdatePolicy.isPeriodicDue(
                                intervalMinutes = group.updateIntervalMinutes,
                                lastUpdatedAt = group.lastUpdatedAt,
                                now = now
                            )
                    }.forEach { group ->
                        fetchNodesInternal(
                            bypassThrottle = true,
                            runUrlTest = false,
                            fetchingLabel = "自动更新 ${group.name}",
                            showToast = false,
                            groupId = group.id
                        )
                    }
                }
            }
        }
    }

    /**
     * 获取节点列表
     */
    fun fetchNodes(
        bypassThrottle: Boolean = false,
        runUrlTest: Boolean = true,
        allowWhenTestRunning: Boolean = false,
        fetchingLabel: String = "请求节点中"
    ) {
        viewModelScope.launch {
            fetchNodesInternal(
                bypassThrottle = bypassThrottle,
                runUrlTest = runUrlTest,
                allowWhenTestRunning = allowWhenTestRunning,
                fetchingLabel = fetchingLabel
            )
        }
    }

    private suspend fun fetchNodesInternal(
        bypassThrottle: Boolean = false,
        runUrlTest: Boolean = true,
        allowWhenTestRunning: Boolean = false,
        fetchingLabel: String = "请求节点中",
        showToast: Boolean = true,
        groupId: String? = null,
        notifyPartialBuiltInFailures: Boolean = false
    ): Boolean {
        val requestedGroupId = groupId ?: settingsRepository.selectedNodeGroupId.first()
        if (requestedGroupId == FAVORITES_NODE_GROUP_ID) {
            lastFetchedNodes = null
            return true
        }
        if (!allowWhenTestRunning && GlobalTestExecution.mutex.isLocked) {
            showNodeFetchToast(showToast, GlobalTestExecution.busyHint())
            return false
        }
        // 节流检查 (内部调用可跳过)
        if (!bypassThrottle) {
            val now = System.currentTimeMillis()
            if (now - lastFetchNodesTime < THROTTLE_INTERVAL) {
                showNodeFetchToast(showToast, "操作过于频繁，请稍后再试")
                return false
            }
            lastFetchNodesTime = now
        }

        if (!GlobalTestExecution.tryBeginFetching(fetchingLabel)) {
            showNodeFetchToast(showToast, GlobalTestExecution.fetchingHint())
            return false
        }
        _isLoading.value = true
        _filterUnavailable.value = false // 刷新时重置过滤
        try {
            // 1. 检查网络状态
            if (!NetworkUtils.isNetworkAvailable(getApplication())) {
                showNodeFetchToast(showToast, "当前无网络连接，无法获取节点")
                return false
            }

            if (isBuiltInSubscriptionGroup(requestedGroupId)) {
                return refreshBuiltInCatalogGroups(
                    requestedGroupId = requestedGroupId,
                    runUrlTest = runUrlTest,
                    showToast = showToast,
                    notifyPartialFailures = notifyPartialBuiltInFailures
                )
            }
            val resolvedGroupId = requestedGroupId
            val group = subscriptionDao.getGroup(resolvedGroupId)
                ?: throw IllegalStateException("订阅分组不存在")
            val links = subscriptionDao.getLinksForGroup(resolvedGroupId)
            if (links.isEmpty()) {
                subscriptionDao.updateRefreshResult(
                    resolvedGroupId,
                    group.lastUpdatedAt,
                    "未添加订阅链接",
                    group.lastNodeCount
                )
                showNodeFetchToast(showToast, "${group.name} 未添加订阅链接")
                return false
            }
            val subscriptionUserAgent = group.userAgent.trim().ifBlank { AppConfig.HTTP_USER_AGENT }

            val results = coroutineScope {
                links.map { link ->
                    async(Dispatchers.IO) {
                        var result = subscriptionParser.fetchAndParse(link.url, subscriptionUserAgent)
                        if (result.isFailure && !result.exceptionOrNull().isFireflyAccessBanned()) {
                            Log.w(tag, "Subscription link failed, retrying group=${group.id}", result.exceptionOrNull())
                            result = subscriptionParser.fetchAndParse(link.url, subscriptionUserAgent)
                        }
                        link to result
                    }
                }.awaitAll()
            }
            val successful = results.filter { it.second.isSuccess }
            val failedCount = results.size - successful.size

            if (successful.isNotEmpty()) {
                val candidates = successful.flatMap { (link, result) ->
                    result.getOrThrow().mapIndexed { index, node -> Triple(link, index, node) }
                }
                val merged = if (group.deduplicateEnabled) {
                    candidates.distinctBy { it.third.getRawLinkPlain().trim() }
                } else {
                    candidates
                }
                val fetchedNodes = merged.mapIndexed { sortOrder, (link, index, node) ->
                    val identity = if (group.deduplicateEnabled) {
                        node.id
                    } else {
                        "${link.id}|$index|${node.id}"
                    }
                    node.copy(
                        id = stableSubscriptionNodeId(group.id, identity),
                        source = NodeSource.SUBSCRIPTION,
                        subscriptionGroupId = group.id,
                        favoriteSourceNodeId = null,
                        favoriteCreatedAt = 0L,
                        sortOrder = sortOrder
                    )
                }

                nodeDao.replaceSubscriptionNodes(group.id, fetchedNodes)
                subscriptionDao.updateRefreshResult(
                    group.id,
                    System.currentTimeMillis(),
                    failedCount.takeIf { it > 0 }?.let { "$it/${results.size} 个链接更新失败" },
                    fetchedNodes.size
                )
                // 仅缓存当前页签的结果，避免后台更新其他分组干扰后续测试。
                if (settingsRepository.selectedNodeGroupId.first() == group.id) {
                    lastFetchedNodes = fetchedNodes
                }

                // 自动测试延迟 (直接传入节点列表，避免等待 Flow 更新)
                // 用户更关心连通性与真实延迟，改用 URL Test
                if (runUrlTest) {
                    urlTestAllNodes(fetchedNodes)
                }

                if (showToast && failedCount > 0) {
                    showNodeFetchToast(true, "${group.name} 已更新，$failedCount 个链接失败")
                }
                Log.d(tag, "Fetched ${fetchedNodes.size} nodes for group=${group.id}")
                return true
            } else {
                val e = results.firstNotNullOfOrNull { it.second.exceptionOrNull() }
                Log.e(tag, "Failed to fetch nodes", e)
                val message = e?.message ?: "未知错误"
                subscriptionDao.updateRefreshResult(
                    group.id,
                    group.lastUpdatedAt,
                    message,
                    group.lastNodeCount
                )
                showNodeFetchToast(showToast, "${group.name} 更新失败: $message")
                return false
            }
        } catch (e: Exception) {
            Log.e(tag, "Error fetching nodes", e)
            showNodeFetchToast(showToast, "网络错误")
            return false
        } finally {
            _isLoading.value = false
            GlobalTestExecution.endFetching()
        }
    }

    private fun showNodeFetchToast(showToast: Boolean, message: String) {
        if (showToast) {
            _error.value = message
        }
    }

    private suspend fun refreshBuiltInCatalogGroups(
        requestedGroupId: String,
        runUrlTest: Boolean,
        showToast: Boolean,
        notifyPartialFailures: Boolean
    ): Boolean {
        var catalogResult = subscriptionParser.fetchAndParseFireflyCatalog()
        if (catalogResult.isFailure && !catalogResult.exceptionOrNull().isFireflyAccessBanned()) {
            Log.w(tag, "Firefly catalog failed, retrying", catalogResult.exceptionOrNull())
            catalogResult = subscriptionParser.fetchAndParseFireflyCatalog()
        }
        val entries = catalogResult.getOrElse { error ->
            val message = error.message ?: "未知错误"
            showNodeFetchToast(showToast, "内置节点分组更新失败: $message")
            return false
        }

        if (entries.isNotEmpty()) {
            val catalogGroupIdsInOrder = entries.map { builtInSubscriptionGroupId(it.sourceId) }
            val newlyAddedIds = settingsRepository.recordBuiltInGroupIds(catalogGroupIdsInOrder.toSet())
            if (newlyAddedIds.isNotEmpty()) {
                val newGroupNames = entries.mapIndexedNotNull { index, entry ->
                    val groupId = catalogGroupIdsInOrder[index]
                    if (groupId in newlyAddedIds) {
                        entry.name.trim().ifBlank { "节点分组 ${index + 1}" }.take(80)
                    } else {
                        null
                    }
                }
                newBuiltInGroupMessageChannel.send(
                    "新增 ${newGroupNames.joinToString("、")}分组节点"
                )
            }
        }

        val existingGroups = subscriptionDao.getGroupsOnce()
        val existingById = existingGroups.associateBy { it.id }
        val legacyGroup = existingById[DEFAULT_SUBSCRIPTION_GROUP_ID]
        val catalogGroupIds = entries.map { builtInSubscriptionGroupId(it.sourceId) }.toSet()
        var nextSortOrder = (existingGroups.maxOfOrNull { it.sortOrder } ?: -1) + 1
        var successfulGroups = 0
        var failedGroups = 0
        var totalNodes = 0
        val refreshedNodes = mutableMapOf<String, List<Node>>()
        val failedGroupNames = mutableListOf<String>()

        entries.forEachIndexed { catalogIndex, entry ->
            val groupId = builtInSubscriptionGroupId(entry.sourceId)
            val groupName = entry.name.trim().ifBlank { "节点分组 ${catalogIndex + 1}" }.take(80)
            val existing = existingById[groupId]
            val sortOrder = existing?.sortOrder ?: if (catalogIndex == 0 && legacyGroup != null) {
                legacyGroup.sortOrder
            } else {
                nextSortOrder++
            }
            val parsedNodes = entry.nodes?.distinctBy { it.getRawLinkPlain().trim() }
            val errorMessage = entry.error?.message?.take(300)
            val group = SubscriptionGroup(
                id = groupId,
                name = groupName,
                autoUpdateEnabled = false,
                updateIntervalMinutes = existing?.updateIntervalMinutes ?: 360,
                deduplicateEnabled = true,
                userAgent = "",
                lastUpdatedAt = if (parsedNodes != null) System.currentTimeMillis() else existing?.lastUpdatedAt ?: 0L,
                lastError = errorMessage,
                lastNodeCount = parsedNodes?.size ?: existing?.lastNodeCount ?: 0,
                sortOrder = sortOrder,
                createdAt = existing?.createdAt ?: System.currentTimeMillis()
            )
            subscriptionDao.insertGroup(group)

            if (parsedNodes != null) {
                val nodesForGroup = parsedNodes.mapIndexed { nodeIndex, node ->
                    node.copy(
                        id = stableSubscriptionNodeId(groupId, node.id),
                        source = NodeSource.SUBSCRIPTION,
                        subscriptionGroupId = groupId,
                        favoriteSourceNodeId = null,
                        favoriteCreatedAt = 0L,
                        sortOrder = nodeIndex
                    )
                }
                nodeDao.replaceSubscriptionNodes(groupId, nodesForGroup)
                refreshedNodes[groupId] = nodesForGroup
                successfulGroups++
                totalNodes += nodesForGroup.size
            } else {
                failedGroups++
                failedGroupNames += groupName
            }
        }

        val staleBuiltInGroupIds = existingGroups
            .filter { isBuiltInSubscriptionGroup(it.id) && it.id !in catalogGroupIds }
            .map { it.id }
            .toSet()
        val selectedNodeBeforeCleanup = settingsRepository.selectedNodeId.first()
            ?.let { nodeDao.getNodeById(it) }
        staleBuiltInGroupIds.forEach { staleId -> subscriptionDao.deleteGroup(staleId) }
        if (selectedNodeBeforeCleanup?.subscriptionGroupId in staleBuiltInGroupIds) {
            settingsRepository.setSelectedNodeId(null)
        }

        val currentSelectedGroupId = settingsRepository.selectedNodeGroupId.first()
        val selectedCatalogGroupId = when {
            requestedGroupId in catalogGroupIds -> requestedGroupId
            currentSelectedGroupId in catalogGroupIds -> currentSelectedGroupId
            else -> catalogGroupIds.firstOrNull()
        }
        if (isBuiltInSubscriptionGroup(currentSelectedGroupId) &&
            currentSelectedGroupId != selectedCatalogGroupId
        ) {
            val fallback = selectedCatalogGroupId
                ?: subscriptionDao.getGroupsOnce().firstOrNull { !isBuiltInSubscriptionGroup(it.id) }?.id
                ?: FAVORITES_NODE_GROUP_ID
            settingsRepository.setSelectedNodeGroupId(fallback)
        }

        selectedCatalogGroupId?.let { selectedId ->
            val selectedNodes = refreshedNodes[selectedId]
                ?: nodeDao.getSubscriptionNodes(selectedId).first()
            lastFetchedNodes = selectedNodes
            if (runUrlTest && selectedNodes.isNotEmpty()) {
                urlTestAllNodes(selectedNodes)
            }
        }

        if (entries.isEmpty()) {
            showNodeFetchToast(showToast, "后台没有可用的节点分组")
            return false
        }
        if (failedGroupNames.isNotEmpty()) {
            if (showToast || notifyPartialFailures) {
                showNodeFetchToast(
                    showToast = true,
                    message = "${failedGroupNames.joinToString("、")}分组的节点数据更新失败"
                )
            }
        } else if (showToast) {
            showNodeFetchToast(true, "已同步 ${entries.size} 个节点分组、$totalNodes 个节点")
        }
        Log.d(tag, "Synced Firefly catalog groups=${entries.size} nodes=$totalNodes failures=$failedGroups")
        return successfulGroups > 0
    }

    private fun stableSubscriptionNodeId(groupId: String, identity: String): String =
        if (groupId == DEFAULT_SUBSCRIPTION_GROUP_ID && '|' !in identity) {
            identity
        } else {
            "sub_${UUID.nameUUIDFromBytes("$groupId|$identity".toByteArray(Charsets.UTF_8))}"
        }
    fun testAllNodes(targetNodes: List<Node>? = null) {
        viewModelScope.launch {
            if (GlobalTestExecution.isFetching()) {
                _error.value = GlobalTestExecution.fetchingHint()
                return@launch
            }
            if (!GlobalTestExecution.tryStart("TCPing 测试")) {
                _error.value = GlobalTestExecution.busyHint()
                return@launch
            }
            _isTesting.value = true
            _testingLabel.value = "TCPing 测试中..."
            try {
                val currentNodes = targetNodes ?: currentNodeListSnapshot()
                internalTestNodes(currentNodes) { completed, total ->
                    _testingLabel.value = "TCPing 测试中 ($completed/$total)"
                }
            } finally {
                _isTesting.value = false
                _testingLabel.value = null
                GlobalTestExecution.finish()
            }
        }
    }
    
    /**
     * URL Test 所有节点延迟 (通过 ClashAPI)
     * VPN 运行中 → 用现有 ClashAPI (port 9090)
     * VPN 未运行 → 启动临时无头 sing-box 实例 (port 19090)
     */
    fun urlTestAllNodes(targetNodes: List<Node>? = null) {
        urlTestAllNodesInternal(
            targetNodes = targetNodes,
            requireGlobalLock = true
        )
    }

    private fun urlTestAllNodesInternal(
        targetNodes: List<Node>? = null,
        requireGlobalLock: Boolean,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null
    ): Job = viewModelScope.launch {
            var locked = false
            if (requireGlobalLock) {
                if (GlobalTestExecution.isFetching()) {
                    _error.value = GlobalTestExecution.fetchingHint()
                    return@launch
                }
                if (!GlobalTestExecution.tryStart("URL Test 测试")) {
                    _error.value = GlobalTestExecution.busyHint()
                    return@launch
                }
                locked = true
            }
            _isTesting.value = true
            _testingLabel.value = "URL Test 测试中..."
            
            val isVpnRunning = vpnState.value == VpnState.CONNECTED
            val clashApiPort: Int
            var startedHeadless = false
            
            try {
                val currentNodes = targetNodes ?: currentNodeListSnapshot()
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
                
                val results = latencyTester.urlTestAllNodes(
                    nodes = currentNodes,
                    clashApiPort = clashApiPort,
                    targetUrl = AppConfig.URL_TEST_URL,
                    timeoutMs = urlTestTimeoutMs.value,
                    concurrency = urlTestConcurrency.value
                ) { completed, total ->
                    _testingLabel.value = "URL Test 测试中 ($completed/$total)"
                    onProgress?.invoke(completed, total)
                }
                Log.d(tag, "Got ${results.size} URL test results")
                
                // 更新数据库
                results.forEach { result ->
                    val updatedRows = nodeDao.updateLatency(
                        nodeId = result.nodeId,
                        latency = result.latency,
                        isAvailable = result.isAvailable,
                        testedAt = System.currentTimeMillis()
                    )
                    if (updatedRows == 0) {
                        Log.w(tag, "URL Test result not applied, node not found in DB: ${result.nodeId}")
                    }
                }
            } finally {
                withContext(NonCancellable) {
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
                    if (locked) {
                        GlobalTestExecution.finish()
                    }
                }
            }
    }
    
    /**
     * 打开节点列表并开始指定类型的测试
     */
    fun showNodeListForTest(testType: String): Boolean {
        if (GlobalTestExecution.isFetching()) {
            _error.value = GlobalTestExecution.fetchingHint()
            return false
        }
        if (_autoTestProgress.value.running || _isTesting.value || GlobalTestExecution.mutex.isLocked) {
            _error.value = GlobalTestExecution.busyHint()
            return false
        }
        if (testType != "tcping" && testType != "urltest") {
            return false
        }

        when (testType) {
            "tcping" -> testAllNodes()
            "urltest" -> urlTestAllNodes()
        }
        return true
    }

    fun setStartupDefaultTestMode(mode: StartupDefaultTestMode) {
        viewModelScope.launch {
            settingsRepository.setStartupDefaultTestMode(mode)
            settingsRepository.setStartupDefaultTestChoiceDone(true)
        }
    }

    fun clearStartupDefaultTestMode() {
        setStartupDefaultTestMode(StartupDefaultTestMode.NONE)
    }

    fun setRememberLastSelectedNodeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setRememberLastSelectedNodeEnabled(enabled)
        }
    }

    fun setNodeIpInfoTestOnVpnStart(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setNodeIpInfoTestOnVpnStart(enabled)
        }
    }

    fun setVpnConnectivityRecoveryEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setVpnConnectivityRecoveryEnabled(enabled)
        }
    }

    fun setHysteria2Bandwidth(uploadMbps: Int, downloadMbps: Int) {
        viewModelScope.launch {
            settingsRepository.setHysteria2Bandwidth(uploadMbps, downloadMbps)
        }
    }

    fun adaptHysteria2Bandwidth() {
        if (_hysteria2BandwidthAdaptationState.value.isRunning) return
        viewModelScope.launch {
            if (_hysteria2BandwidthAdaptationState.value.isRunning) return@launch
            if (GlobalTestExecution.isFetching()) {
                _error.value = GlobalTestExecution.fetchingHint()
                return@launch
            }
            if (!GlobalTestExecution.tryStart("Hysteria2 带宽自适应")) {
                _error.value = GlobalTestExecution.busyHint()
                return@launch
            }
            _hysteria2BandwidthAdaptationState.value =
                _hysteria2BandwidthAdaptationState.value.copy(isRunning = true)
            try {
                val measurement = withContext(Dispatchers.IO) {
                    val directClient = NetworkClient.createUnderlyingNetworkClient(
                        getApplication(),
                        AppConfig.AUTO_TEST_BANDWIDTH_UPLOAD_TIMEOUT_MS
                    ) ?: throw IOException("当前没有可用的 Wi-Fi 或移动网络")
                    val speedTestService = SpeedTestService(
                        downloadTimeoutMs = AppConfig.AUTO_TEST_BANDWIDTH_DOWNLOAD_TIMEOUT_MS,
                        uploadTimeoutMs = AppConfig.AUTO_TEST_BANDWIDTH_UPLOAD_TIMEOUT_MS,
                        baseClient = directClient
                    )
                    val download = speedTestService.startDownloadTest(
                        AppConfig.HYSTERIA2_ADAPTIVE_TEST_BYTES
                    ) { _, _ -> }
                    val upload = speedTestService.startUploadTest(
                        AppConfig.HYSTERIA2_ADAPTIVE_TEST_BYTES
                    ) { _, _ -> }
                    download.avgSpeedMbps to upload.avgSpeedMbps
                }
                val recommendedDownload = Hysteria2BandwidthAdvisor.recommend(measurement.first)
                val recommendedUpload = Hysteria2BandwidthAdvisor.recommend(measurement.second)
                val generation = _hysteria2BandwidthAdaptationState.value.resultGeneration + 1L
                _hysteria2BandwidthAdaptationState.value = Hysteria2BandwidthAdaptationState(
                    uploadMbps = recommendedUpload,
                    downloadMbps = recommendedDownload,
                    resultGeneration = generation
                )
                _error.value = buildString {
                    append("自适应完成：实测下行 %.1f、上行 %.1f Mbps，建议 ".format(measurement.first, measurement.second))
                    append("下行 $recommendedDownload、上行 $recommendedUpload Mbps")
                }
            } catch (e: Exception) {
                Log.e(tag, "Hysteria2 bandwidth adaptation failed", e)
                _error.value = "Hysteria2 带宽自适应失败：${e.message ?: "测速失败"}"
            } finally {
                _hysteria2BandwidthAdaptationState.value =
                    _hysteria2BandwidthAdaptationState.value.copy(isRunning = false)
                GlobalTestExecution.finish()
            }
        }
    }

    fun setTcpingTestTimeoutMs(value: Long) {
        viewModelScope.launch {
            settingsRepository.setTcpingTestTimeoutMs(value)
        }
    }

    fun setUrlTestTimeoutMs(value: Long) {
        viewModelScope.launch {
            settingsRepository.setUrlTestTimeoutMs(value)
        }
    }

    fun setNodeIpInfoTimeoutMs(value: Long) {
        viewModelScope.launch {
            settingsRepository.setNodeIpInfoTimeoutMs(value)
        }
    }

    fun setSpeedTestDownloadTimeoutMs(value: Long) {
        viewModelScope.launch {
            settingsRepository.setSpeedTestDownloadTimeoutMs(value)
        }
    }

    fun setTcpingConcurrency(value: Int) {
        viewModelScope.launch {
            settingsRepository.setTcpingConcurrency(value)
        }
    }

    fun setUrlTestConcurrency(value: Int) {
        viewModelScope.launch {
            settingsRepository.setUrlTestConcurrency(value)
        }
    }

    fun setBandwidthTestConcurrency(value: Int) {
        viewModelScope.launch {
            settingsRepository.setBandwidthTestConcurrency(value)
        }
    }

    fun setUnlockTestConcurrency(value: Int) {
        viewModelScope.launch {
            settingsRepository.setUnlockTestConcurrency(value)
        }
    }

    fun setVpnMtu(value: Int) {
        viewModelScope.launch {
            settingsRepository.setVpnMtu(value)
        }
    }

    fun confirmStartupDefaultTestChoice(mode: StartupDefaultTestMode?) {
        viewModelScope.launch {
            if (mode == null) {
                settingsRepository.setStartupDefaultTestMode(StartupDefaultTestMode.NONE)
            } else {
                settingsRepository.setStartupDefaultTestMode(mode)
            }
            settingsRepository.setStartupDefaultTestChoiceDone(true)
            _showStartupDefaultTestChoiceDialog.value = false
            if (mode != null && mode != StartupDefaultTestMode.NONE) {
                launchStartupDefaultTestIfNeeded(showHint = false)
            }
        }
    }

    fun dismissStartupDefaultTestChoiceDialog() {
        _showStartupDefaultTestChoiceDialog.value = false
    }
    
    /**
     * 清理不可用节点 (UI 过滤，不删除数据库)
     */
    fun cleanUnavailableNodes() {
        hideUnqualifiedNodesInternal()
    }

    private fun sortNodesForList(list: List<Node>, filterOut: Boolean): List<Node> {
        val filtered = if (filterOut) list.filterNot { shouldHideByQuickCleanup(it) } else list
        return filtered.sortedWith(
            compareByDescending<Node> { it.isAvailable }
                .thenBy { it.sortOrder }
                .thenBy { if (it.latency >= 0) it.latency else Int.MAX_VALUE }
        )
    }

    private fun shouldHideByQuickCleanup(node: Node): Boolean {
        return node.autoTestStatus.contains("FILTERED", ignoreCase = true) ||
            node.autoTestStatus.contains("FAILED", ignoreCase = true) ||
            node.latency == -2 ||
            !node.isAvailable
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
        val results = latencyTester.testAllNodes(
            nodes = currentNodes,
            timeoutMs = tcpingTestTimeoutMs.value,
            concurrency = tcpingConcurrency.value,
            onProgress = onProgress
        )
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
        startPreferModeAutoSelectAndConnect(BUILTIN_PREFER_MODE_CHAT)
    }

    fun startPreferModeAutoSelectAndConnect(modeId: String) {
        viewModelScope.launch {
            if (GlobalTestExecution.isFetching()) {
                _error.value = GlobalTestExecution.fetchingHint()
                return@launch
            }
            if (_autoTestProgress.value.running || _isTesting.value || GlobalTestExecution.mutex.isLocked) {
                _error.value = GlobalTestExecution.busyHint()
                return@launch
            }
            val mode = preferTestModes.value.firstOrNull { it.id == modeId }
            if (mode == null) {
                _error.value = "未找到测试模式"
                return@launch
            }
            val currentNodeLimit = settingsRepository.autoTestNodeLimit.first()
            applyPreferTestModeInternal(mode)
            settingsRepository.setAutoTestNodeLimit(currentNodeLimit)
            _isAutoSelecting.value = true
            startAutomatedTest(
                preferPriority = mode.activePriorityOrder().firstOrNull() ?: mode.defaultPriority,
                connectBestAfterDone = true,
                configOverride = mode.toAutoTestConfig(autoRunEnabled = autoTestEnabled.value)
                    .copy(nodeLimit = currentNodeLimit),
                modeOverride = mode
            )
        }
    }

    fun selectBestNodeByPriority(priority: BestNodePriority, connect: Boolean) {
        viewModelScope.launch {
            val currentMode = preferTestModes.value.firstOrNull { it.id == preferTestSelectedModeId.value }
            if (currentMode == null) {
                _error.value = "当前模式不存在，请重新选择模式"
                return@launch
            }
            if (!isPrioritySupportedByMode(priority, currentMode)) {
                _infoDialogMessage.value = "当前模式未启用${priorityDisplayName(priority)}相关测试。\n\n已保存该择优规则，请先调整模式配置或执行一次测试后再自动选择连接最优。"
                return@launch
            }

            val dbNodes = currentNodeListSnapshot()
            val candidates = dbNodes
                .filter { it.isAvailable }
                .let { filterPriorityReadyCandidates(it, priority) }

            if (candidates.isEmpty()) {
                if (priority != BestNodePriority.LATENCY) {
                    val latencyFallback = dbNodes.filter { it.isAvailable && it.latency > 0 }
                    if (latencyFallback.isNotEmpty()) {
                        val bestLatency = pickBestNode(latencyFallback, BestNodePriority.LATENCY, currentMode)
                        if (bestLatency != null) {
                            settingsRepository.setSelectedNodeId(bestLatency.id)
                            if (connect) {
                                ServiceManager.startVpn(getApplication(), bestLatency, proxyMode.value)
                            }
                            _error.value = if (connect) {
                                "当前模式暂无${priorityDisplayName(priority)}数据，已按延迟优先连接：${bestLatency.getDisplayName()}"
                            } else {
                                "当前模式暂无${priorityDisplayName(priority)}数据，已按延迟优先选择：${bestLatency.getDisplayName()}"
                            }
                            return@launch
                        }
                    }
                }
                _infoDialogMessage.value = "已保存${priorityDisplayName(priority)}优先规则。\n\n当前模式还没有对应测试结果，请先点击“开始测试”，完成后再自动选择连接最优。"
                return@launch
            }

            val best = pickBestNode(candidates, priority, currentMode)
            if (best == null) {
                _error.value = "没有可用于${priorityDisplayName(priority)}择优的节点"
                return@launch
            }
            settingsRepository.setSelectedNodeId(best.id)
            if (connect) {
                ServiceManager.startVpn(getApplication(), best, proxyMode.value)
            }
            _error.value = if (connect) "已连接最优节点：${best.getDisplayName()}" else "已选择最优节点：${best.getDisplayName()}"
        }
    }

    fun selectBestNodeByPriorityFromSnapshot(
        priority: BestNodePriority,
        connect: Boolean,
        modeOverride: TestPreferMode? = null
    ) {
        viewModelScope.launch {
            val currentMode = modeOverride
                ?: _autoTestResultMode.value
                ?: preferTestModes.value.firstOrNull { it.id == preferTestSelectedModeId.value }
            if (currentMode == null) {
                _error.value = "当前模式不存在，请重新选择模式"
                return@launch
            }
            if (!isPrioritySupportedByMode(priority, currentMode)) {
                _infoDialogMessage.value = "当前模式未启用${priorityDisplayName(priority)}相关测试。\n\n请先调整模式配置或重新执行测试。"
                return@launch
            }

            val snapshotNodes = autoTestResultSnapshot.value
            if (snapshotNodes.isEmpty()) {
                _error.value = "当前没有可用的自动化测试结果快照，请先执行一次测试"
                return@launch
            }

            val reorderedPriority = listOf(priority) + currentMode.normalizePriorityOrder().priorityOrder
                .filterNot { it == priority }
            val resultMode = currentMode.copy(
                defaultPriority = priority,
                priorityOrder = reorderedPriority
            )
            _autoTestResultMode.value = resultMode
            _autoTestResultPriority.value = priority
            _autoTestResultSnapshot.value = sortNodesForSnapshot(snapshotNodes, priority, resultMode)

            val candidates = snapshotNodes
                .filter { it.isAvailable }
                .let { filterPriorityReadyCandidates(it, priority) }

            if (candidates.isEmpty()) {
                _infoDialogMessage.value = "快照中暂无${priorityDisplayName(priority)}可用数据，请重新执行测试后再自动连接最优。"
                return@launch
            }

            val best = pickBestNode(candidates, priority, resultMode)
            if (best == null) {
                _error.value = "快照中没有可用于${priorityDisplayName(priority)}择优的节点"
                return@launch
            }
            settingsRepository.setSelectedNodeId(best.id)
            if (connect) {
                ServiceManager.startVpn(getApplication(), best, proxyMode.value)
            }
            _error.value = if (connect) "已连接最优节点：${best.getDisplayName()}" else "已选择最优节点：${best.getDisplayName()}"
        }
    }

    private fun isPrioritySupportedByMode(priority: BestNodePriority, mode: TestPreferMode): Boolean {
        return mode.supportsPriority(priority)
    }

    private fun filterPriorityReadyCandidates(nodes: List<Node>, priority: BestNodePriority): List<Node> {
        return when (priority) {
            BestNodePriority.LATENCY -> nodes.filter { it.latency > 0 }
            BestNodePriority.UPLOAD -> nodes.filter { it.autoTestedAt > 0L && it.uploadMbps > 0f }
            BestNodePriority.DOWNLOAD -> nodes.filter { it.autoTestedAt > 0L && it.downloadMbps > 0f }
            BestNodePriority.UNLOCK_COUNT -> nodes.filter {
                it.autoTestedAt > 0L && (
                    it.unlockSummary.isNotBlank() || it.unlockPassed
                )
            }
        }
    }

    private fun priorityDisplayName(priority: BestNodePriority): String = when (priority) {
        BestNodePriority.LATENCY -> "延迟"
        BestNodePriority.UPLOAD -> "上行"
        BestNodePriority.DOWNLOAD -> "下行"
        BestNodePriority.UNLOCK_COUNT -> "解锁情况"
    }

    fun hideUnqualifiedAutoTestNodes() {
        hideUnqualifiedNodesInternal()
    }

    private fun hideUnqualifiedNodesInternal() {
        val hiddenCount = nodes.value.count { shouldHideByQuickCleanup(it) }
        if (hiddenCount > 0) {
            _filterUnavailable.value = true
            _error.value = "隐藏${hiddenCount}个超时/不可用/未达标节点"
        } else {
            _error.value = "没有可隐藏的不合格节点"
        }
    }
    
    /**
     * 自动选择最佳节点
     */
    fun autoSelectBestNode() {
        viewModelScope.launch {
            val bestNode = currentNodeListSnapshot()
                .filter { it.isAvailable }
                .minWithOrNull(compareBy<Node> { if (it.latency >= 0) it.latency else Int.MAX_VALUE })
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
            RuntimeLog.info(tag, "Selected node changed: source=${node.source}, type=${node.type}")
            
            // If VPN is connected, restart to switch to new node
            if (vpnState.value == VpnState.CONNECTED) {
                // Notify user (Optional)
                Log.i(tag, "Restarting VPN to apply new Node: ${node.name}")
                RuntimeLog.info(tag, "Restarting VPN after node selection")
                ServiceManager.startVpn(getApplication(), node, proxyMode.value)
            }
        }
    }

    fun setSelectedNodeGroup(groupId: String) {
        viewModelScope.launch {
            settingsRepository.setSelectedNodeGroupId(groupId)
        }
    }

    private val _refreshingSubscriptionGroupIds = MutableStateFlow<Set<String>>(emptySet())
    val refreshingSubscriptionGroupIds = _refreshingSubscriptionGroupIds.asStateFlow()

    fun saveSubscriptionGroup(
        groupId: String?,
        name: String,
        urls: List<String>,
        userAgent: String,
        autoUpdateEnabled: Boolean,
        updateIntervalMinutes: Int,
        deduplicateEnabled: Boolean,
        onResult: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            if (groupId != null && isBuiltInSubscriptionGroup(groupId)) {
                onResult(false, "内置节点分组不可修改")
                return@launch
            }
            val normalizedName = name.trim()
            val normalizedUrls = urls.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val normalizedUserAgent = userAgent.trim().ifBlank { AppConfig.HTTP_USER_AGENT }
            when {
                normalizedName.isEmpty() -> {
                    onResult(false, "请输入分组名称")
                    return@launch
                }
                normalizedUrls.isEmpty() -> {
                    onResult(false, "请至少添加一个订阅链接")
                    return@launch
                }
                normalizedUrls.any { !it.startsWith("http://", true) && !it.startsWith("https://", true) } -> {
                    onResult(false, "订阅链接仅支持 http 或 https")
                    return@launch
                }
                normalizedUserAgent.length > 256 || normalizedUserAgent.any { it.code !in 32..126 } -> {
                    onResult(false, "用户代理仅支持 256 个以内的英文可见字符")
                    return@launch
                }
                autoUpdateEnabled &&
                    updateIntervalMinutes != SubscriptionAutoUpdatePolicy.STARTUP_ONCE_INTERVAL_MINUTES &&
                    updateIntervalMinutes !in SubscriptionAutoUpdatePolicy.MIN_PERIODIC_INTERVAL_MINUTES..
                        SubscriptionAutoUpdatePolicy.MAX_PERIODIC_INTERVAL_MINUTES -> {
                    onResult(
                        false,
                        "更新间隔有效范围为0-10080分钟！0分钟：APP 启动稳定后仅更新一次；1-10080分钟：APP 会定时更新"
                    )
                    return@launch
                }
            }

            val existingGroups = subscriptionDao.getGroupsOnce()
            if (existingGroups.any { it.id != groupId && it.name.equals(normalizedName, true) }) {
                onResult(false, "已存在同名分组")
                return@launch
            }

            val old = groupId?.let { subscriptionDao.getGroup(it) }
            val id = old?.id ?: UUID.randomUUID().toString()
            val favoritesOrder = settingsRepository.favoritesGroupSortOrder.first()
            val newSortOrder = if (favoritesOrder == Int.MAX_VALUE) {
                (existingGroups.maxOfOrNull { it.sortOrder } ?: -1) + 1
            } else {
                maxOf(existingGroups.maxOfOrNull { it.sortOrder } ?: -1, favoritesOrder) + 1
            }
            val group = SubscriptionGroup(
                id = id,
                name = normalizedName,
                autoUpdateEnabled = autoUpdateEnabled,
                updateIntervalMinutes = SubscriptionAutoUpdatePolicy.normalizeInterval(updateIntervalMinutes),
                deduplicateEnabled = deduplicateEnabled,
                userAgent = normalizedUserAgent,
                lastUpdatedAt = old?.lastUpdatedAt ?: 0L,
                lastError = old?.lastError,
                lastNodeCount = old?.lastNodeCount ?: 0,
                sortOrder = old?.sortOrder ?: newSortOrder,
                createdAt = old?.createdAt ?: System.currentTimeMillis()
            )
            val links = normalizedUrls.mapIndexed { index, url ->
                SubscriptionLink(
                    id = UUID.randomUUID().toString(),
                    groupId = id,
                    url = url,
                    sortOrder = index
                )
            }
            subscriptionDao.saveGroup(group, links)
            onResult(true, if (old == null) "订阅分组已创建" else "订阅分组已保存")
        }
    }

    fun deleteSubscriptionGroup(groupId: String, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            if (isBuiltInSubscriptionGroup(groupId) || groupId == FAVORITES_NODE_GROUP_ID) {
                onResult(false, "该分组受保护，不能删除")
                return@launch
            }
            val groups = subscriptionDao.getGroupsOnce()
            val group = groups.firstOrNull { it.id == groupId }
            if (group == null) {
                onResult(false, "订阅分组不存在")
                return@launch
            }
            val selectedNode = settingsRepository.selectedNodeId.first()?.let { nodeDao.getNodeById(it) }
            subscriptionDao.deleteGroup(groupId)
            if (selectedNode?.subscriptionGroupId == groupId) {
                settingsRepository.setSelectedNodeId(null)
            }
            if (settingsRepository.selectedNodeGroupId.first() == groupId) {
                settingsRepository.setSelectedNodeGroupId(groups.first { it.id != groupId }.id)
            }
            onResult(true, "已删除 ${group.name}")
        }
    }

    fun refreshSubscriptionGroup(groupId: String) {
        if (groupId == FAVORITES_NODE_GROUP_ID) return
        if (groupId in _refreshingSubscriptionGroupIds.value) return
        viewModelScope.launch {
            _refreshingSubscriptionGroupIds.value += groupId
            try {
                fetchNodesInternal(
                    bypassThrottle = true,
                    runUrlTest = false,
                    fetchingLabel = "更新订阅中",
                    showToast = true,
                    groupId = groupId
                )
            } finally {
                _refreshingSubscriptionGroupIds.value -= groupId
            }
        }
    }

    fun refreshAllSubscriptionGroups() {
        if (_refreshingSubscriptionGroupIds.value.isNotEmpty()) return
        viewModelScope.launch {
            val groups = subscriptionDao.getGroupsOnce()
            val builtInGroups = groups.filter { isBuiltInSubscriptionGroup(it.id) }
            val customGroups = groups.filterNot { isBuiltInSubscriptionGroup(it.id) }
            _refreshingSubscriptionGroupIds.value =
                (groups.map { it.id } + DEFAULT_SUBSCRIPTION_GROUP_ID).toSet()
            var succeeded = 0
            val total = customGroups.size + 1
            try {
                val builtInRequestId = builtInGroups.firstOrNull()?.id ?: DEFAULT_SUBSCRIPTION_GROUP_ID
                if (fetchNodesInternal(
                        bypassThrottle = true,
                        runUrlTest = false,
                        fetchingLabel = "更新内置节点分组",
                        showToast = false,
                        groupId = builtInRequestId
                    )
                ) {
                    succeeded++
                }
                customGroups.forEach { group ->
                    if (fetchNodesInternal(
                            bypassThrottle = true,
                            runUrlTest = false,
                            fetchingLabel = "更新 ${group.name}",
                            showToast = false,
                            groupId = group.id
                        )
                    ) {
                        succeeded++
                    }
                }
                _error.value = "分组更新完成：$succeeded/$total"
            } finally {
                _refreshingSubscriptionGroupIds.value = emptySet()
            }
        }
    }

    fun setNodeGroupOrder(groupIds: List<String>) {
        viewModelScope.launch {
            val current = nodeGroups.value
            val validIds = current.map { it.id }.toSet()
            val normalizedIds = groupIds.filter { it in validIds }.distinct() +
                current.map { it.id }.filterNot { it in groupIds }
            val ordered = normalizedIds.mapNotNull { id -> current.firstOrNull { it.id == id } }
            val groupsById = subscriptionDao.getGroupsOnce().associateBy { it.id }
            ordered.forEachIndexed { index, nodeGroup ->
                if (nodeGroup.isFavorites) {
                    settingsRepository.setFavoritesGroupSortOrder(index)
                } else {
                    groupsById[nodeGroup.id]?.let { group ->
                        if (group.sortOrder != index) {
                            subscriptionDao.updateGroup(group.copy(sortOrder = index))
                        }
                    }
                }
            }
        }
    }

    fun skipFavoriteRemovalConfirmationForSession() {
        _skipFavoriteRemovalConfirmation.value = true
    }

    fun toggleFavoriteNode(node: Node) {
        viewModelScope.launch(Dispatchers.IO) {
            if (node.source == NodeSource.FAVORITE) {
                nodeDao.deleteNodeById(node.id)
                if (settingsRepository.selectedNodeId.first() == node.id) {
                    settingsRepository.setSelectedNodeId(null)
                }
                return@launch
            }

            val existingFavorite = nodeDao.getFavoriteBySourceNodeId(node.id)
            if (existingFavorite != null) {
                nodeDao.deleteNodeById(existingFavorite.id)
                if (settingsRepository.selectedNodeId.first() == existingFavorite.id) {
                    settingsRepository.setSelectedNodeId(null)
                }
                return@launch
            }

            val now = System.currentTimeMillis()
            nodeDao.insertNode(
                node.copy(
                    id = "fav_${UUID.randomUUID()}",
                    source = NodeSource.FAVORITE,
                    subscriptionGroupId = null,
                    favoriteSourceNodeId = node.id,
                    favoriteCreatedAt = now
                )
            )
        }
    }

    fun importNodesToFavoritesFromText(text: String, onResult: (NodeImportResult, Int) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val importResult = withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val parsedNodes = subscriptionParser.parseSubscription(text)
                if (parsedNodes.isEmpty()) return@withContext NodeImportResult.INVALID to 0

                setupLibboxForConfigCheck()
                val coreCompatibleNodes = parsedNodes.filter { node ->
                    isNodeCoreConfigCompatible(node)
                }
                if (coreCompatibleNodes.isEmpty()) return@withContext NodeImportResult.INVALID to 0

                val existingFavoriteRawLinks = nodeDao.getFavoriteNodes().first()
                    .map { it.getRawLinkPlain() }
                    .toMutableSet()
                val uniqueNodes = coreCompatibleNodes.filter { node ->
                    existingFavoriteRawLinks.add(node.getRawLinkPlain())
                }
                if (uniqueNodes.isEmpty()) return@withContext NodeImportResult.DUPLICATE to 0

                val favorites = uniqueNodes.mapIndexed { index, node ->
                    node.copy(
                        id = "fav_${UUID.randomUUID()}",
                        source = NodeSource.FAVORITE,
                        favoriteSourceNodeId = null,
                        favoriteCreatedAt = now + index,
                        latency = -1,
                        isAvailable = true,
                        lastTestedAt = 0L,
                        downloadMbps = 0f,
                        uploadMbps = 0f,
                        unlockSummary = "",
                        unlockPassed = false,
                        autoTestStatus = "",
                        autoTestedAt = 0L
                    )
                }
                nodeDao.insertNodes(favorites)
                NodeImportResult.IMPORTED to favorites.size
            }
            val result = importResult.first
            val importedCount = importResult.second

            if (result == NodeImportResult.IMPORTED) {
                settingsRepository.setSelectedNodeGroupId(FAVORITES_NODE_GROUP_ID)
                RuntimeLog.info(tag, "Imported $importedCount nodes to favorites")
            } else {
                RuntimeLog.warn(tag, "Node import finished without new nodes: result=$result")
            }
            onResult(result, importedCount)
        }
    }

    private fun setupLibboxForConfigCheck() {
        val app = getApplication<Application>()
        val workDir = File(app.filesDir, "sing-box")
        if (!workDir.exists()) workDir.mkdirs()
        val options = io.nekohasekai.libbox.SetupOptions().apply {
            basePath = workDir.absolutePath
            workingPath = workDir.absolutePath
            tempPath = app.cacheDir.absolutePath
        }
        Libbox.setup(options)
    }

    private fun isNodeCoreConfigCompatible(node: Node): Boolean {
        return runCatching {
            val config = configGenerator.generateTestConfig(node)
            Libbox.checkConfig(config)
        }.onFailure { error ->
            Log.w(tag, "Skip incompatible imported node: type=${node.type}, server=${node.server}, error=${error.message}")
            RuntimeLog.warn(tag, "Skip incompatible imported node: type=${node.type}", error)
        }.isSuccess
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
        when (vpnState.value) {
            VpnState.DISCONNECTED -> {
                val node = currentNode.value ?: run {
                    RuntimeLog.warn(tag, "VPN start ignored: no selected node")
                    _error.value = "请先选择节点"
                    return
                }
                // 4. 无网络连接节点时给用户发Toast，但不阻止
                if (!NetworkUtils.isNetworkAvailable(getApplication())) {
                    RuntimeLog.warn(tag, "VPN start requested while network is unavailable")
                     _error.value = "当前无网络连接节点！"
                }
                RuntimeLog.info(tag, "VPN start requested from UI")
                ServiceManager.startVpn(getApplication(), node, proxyMode.value)
            }
            VpnState.CONNECTED -> {
                RuntimeLog.info(tag, "VPN stop requested from UI")
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
                RuntimeLog.info(tag, "Restarting VPN after settings change")
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

    fun setTunStackMode(mode: TunStackMode) {
        viewModelScope.launch {
            settingsRepository.setTunStackMode(mode)
            // TUN 栈在核心启动时创建，运行中修改需要重启 VPN。
            restartVpnIfNeeded()
        }
    }

    /**
     * 检查通知公告
     */
    fun saveLanProxySettings(
        enabled: Boolean,
        autoPort: Boolean,
        port: Int,
        authEnabled: Boolean,
        username: String,
        password: String
    ) {
        viewModelScope.launch {
            settingsRepository.setLanProxyEnabled(enabled)
            settingsRepository.setLanProxyAutoPort(autoPort)
            settingsRepository.setLanProxyPort(port)
            settingsRepository.setLanProxyAuthEnabled(authEnabled)
            settingsRepository.setLanProxyUsername(username)
            settingsRepository.setLanProxyPassword(password)
            restartVpnIfNeeded()
        }
    }

    private suspend fun checkNotice() {
        val result = fetchClientCatalog(useRecentCache = false)
        result.onSuccess { catalog ->
            val noticeInfo = catalog.notice
            _noticeConfig.value = noticeInfo

            if (noticeInfo?.hasNotice == true) {
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
        }.onFailure { e ->
            Log.e(tag, "Failed to check notice", e)
            RuntimeLog.warn(tag, "Failed to check notice", e)
        }
    }
    
    
    /**
     * 检查更新
     * @param isAuto 是否为自动检查 (不显示"已是最新"提示)
     */
    fun checkUpdate(isAuto: Boolean = false, trackStartupSplash: Boolean = false) {
        viewModelScope.launch {
            if (trackStartupSplash) {
                _startupUpdateCheckCompleted.value = false
            }
            // 节流检查 (自动检查可跳过)
            if (!isAuto) {
                val now = System.currentTimeMillis()
                if (now - lastCheckUpdateTime < THROTTLE_INTERVAL) {
                    _error.value = "操作过于频繁，请稍后再试"
                    return@launch
                }
                lastCheckUpdateTime = now
            }
            
            val result = fetchClientCatalog(useRecentCache = isAuto)
            result.onSuccess { catalog ->
                val info = catalog.appUpdate
                if (info == null) {
                    _updateInfo.value = null
                    if (!isAuto) {
                        _error.value = "后台未启用版本更新"
                    }
                    return@onSuccess
                }
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
                    val ignoredVersionCode =
                        settingsRepository.ignoredUpdateVersionCode.first()
                    val isIgnoredOptionalUpdate =
                        info.isForce != 1 && ignoredVersionCode == info.versionCode

                    if (isIgnoredOptionalUpdate) {
                        _updateInfo.value = null
                        if (!isAuto) {
                            _error.value = "当前新版本已被忽略"
                        }
                    } else {
                        _updateInfo.value = info
                    }
                } else {
                    if (!isAuto) {
                        _error.value = "已是最新版本"
                    }
                }
            }.onFailure { e ->
                Log.e(tag, "Failed to check update", e)
                RuntimeLog.warn(tag, "Failed to check update", e)
            }.also {
                if (trackStartupSplash) {
                    _startupUpdateCheckCompleted.value = true
                }
            }
        }
    }

    private fun applyRemoteCatalog(catalog: ClientCatalogInfo) {
        _remoteAppSettings.value = catalog.settings ?: RemoteAppSettings()
        _updateCheckAvailable.value = catalog.appUpdate != null
    }

    private suspend fun fetchClientCatalog(useRecentCache: Boolean): Result<ClientCatalogInfo> {
        val now = System.currentTimeMillis()
        val cached = cachedClientCatalog
        if (useRecentCache && cached != null && now - cachedClientCatalogAt <= CLIENT_CATALOG_CACHE_MS) {
            return Result.success(cached)
        }
        return requestWithAutoRetry("远程配置") {
            withTimeout(AppConfig.NODE_REQUEST_TIMEOUT_MS) {
                val envelope = NetworkClient.apiService.getClientBootstrap(AppConfig.BOOTSTRAP_URL)
                val catalog = envelope.data
                if (!envelope.ok || catalog == null) {
                    throw IOException("远程配置请求失败：${envelope.error ?: "invalid_response"}")
                }
                val crypto = catalog.crypto
                if (crypto?.version != 2 ||
                    crypto.algorithm != xyz.a202132.app.util.crypto.CryptoV2Protocol.ALGORITHM
                ) {
                    throw IOException("服务端 Crypto V2 配置不兼容")
                }
                catalog
            }
        }.onSuccess { catalog ->
            cachedClientCatalog = catalog
            cachedClientCatalogAt = System.currentTimeMillis()
            applyRemoteCatalog(catalog)
        }
    }
    
    // 下载状态
    val downloadState = DownloadManager.downloadState
    
    /**
     * 打开下载链接 (现在改为应用内下载)
     */
    fun openDownloadUrl() {
        updateInfo.value?.let { info ->
            // 使用 DownloadManager 检查文件是否已下载且有效
            val existingFile = DownloadManager.isApkReady(getApplication(), info.version)
            if (existingFile != null) {
                // 此处强制执行 installApk 逻辑
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
                     // 重置状态以允许重新下载
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
    
    // 用户界面操作
    fun resetFilter() {
        _filterUnavailable.value = false
    }
    
    fun dismissNotice() {
        _notice.value = null
    }
    
    fun dismissUpdate() {
        _updateInfo.value = null
    }

    fun ignoreCurrentUpdate() {
        val info = _updateInfo.value ?: return
        if (info.isForce == 1) return

        _updateInfo.value = null
        viewModelScope.launch {
            settingsRepository.setIgnoredUpdateVersionCode(info.versionCode)
            RuntimeLog.info(tag, "Ignored optional update: versionCode=${info.versionCode}")
        }
    }
    
    fun clearError() {
        _error.value = null
    }

    fun clearInfoDialogMessage() {
        _infoDialogMessage.value = null
    }

    fun dismissFireflyAccessBanDialog() {
        _fireflyAccessBanDialogVisible.value = false
    }

    fun retryFireflyAccessAfterBan() {
        viewModelScope.launch {
            _fireflyAccessBanDialogVisible.value = false
            val recovered = fetchNodesInternal(
                bypassThrottle = true,
                runUrlTest = false,
                fetchingLabel = "重新检测访问状态",
                showToast = true,
                groupId = DEFAULT_SUBSCRIPTION_GROUP_ID
            )
            if (!recovered) return@launch

            val firstBuiltInGroup = subscriptionDao.getGroupsOnce()
                .firstOrNull { isBuiltInSubscriptionGroup(it.id) }
            if (firstBuiltInGroup != null &&
                settingsRepository.selectedNodeGroupId.first() == FAVORITES_NODE_GROUP_ID
            ) {
                settingsRepository.setSelectedNodeGroupId(firstBuiltInGroup.id)
            }
            _error.value = "访问状态已恢复，内置节点已重新加载"
        }
    }

    private suspend fun handleFireflyAccessBan(errorCode: String) {
        // 先展示提示，再执行数据库清理，避免慢磁盘或并发事务让弹窗迟迟不出现。
        _fireflyAccessBanDialogVisible.value = true
        try {
            withContext(NonCancellable + Dispatchers.IO) {
                val selectedNode = settingsRepository.selectedNodeId.first()
                    ?.let { nodeDao.getNodeById(it) }

                subscriptionDao.deleteBuiltInGroups(
                    defaultGroupId = DEFAULT_SUBSCRIPTION_GROUP_ID,
                    builtInPrefix = BUILT_IN_SUBSCRIPTION_GROUP_PREFIX
                )

                if (
                    selectedNode?.source == NodeSource.SUBSCRIPTION &&
                    selectedNode.subscriptionGroupId?.let(::isBuiltInSubscriptionGroup) == true
                ) {
                    settingsRepository.setSelectedNodeId(null)
                }
                // 保留被清理的内置分组 ID。这样下次启动或手动重试仍会访问
                // Firefly 服务，而不会永久落入只读本地收藏分组。
            }

            lastFetchedNodes = null
            _filterUnavailable.value = false
            _autoTestResultSnapshot.value = _autoTestResultSnapshot.value.filterNot { node ->
                node.source == NodeSource.SUBSCRIPTION &&
                    node.subscriptionGroupId?.let(::isBuiltInSubscriptionGroup) == true
            }
            RuntimeLog.warn(tag, "Firefly access banned; local built-in subscription groups removed ($errorCode)")
        } catch (error: Exception) {
            Log.e(tag, "Failed to clear built-in subscription data after access ban", error)
            RuntimeLog.error(tag, "Failed to clear built-in subscription data after access ban", error)
        }
    }
    
    fun acceptUserAgreement() {
        viewModelScope.launch {
            settingsRepository.setUserAgreementAccepted(true)
        }
    }

    // 自动化测试设置写入
    private fun invalidateAutoTestResults() {
        _autoTestResultSnapshot.value = emptyList()
        _autoTestResultMode.value = null
    }

    fun setAutoTestEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoTestEnabled(enabled) }
    }

    fun setAutoTestFilterUnavailable(enabled: Boolean) {
        invalidateAutoTestResults()
        viewModelScope.launch { settingsRepository.setAutoTestFilterUnavailable(enabled) }
    }

    fun setAutoTestLatencyEnabled(enabled: Boolean) {
        invalidateAutoTestResults()
        viewModelScope.launch { settingsRepository.setAutoTestLatencyEnabled(enabled) }
    }

    fun setAutoTestLatencyMode(mode: AutoTestLatencyMode) {
        invalidateAutoTestResults()
        viewModelScope.launch { settingsRepository.setAutoTestLatencyMode(mode) }
    }

    fun setAutoTestLatencyThresholdMs(value: Int) {
        invalidateAutoTestResults()
        viewModelScope.launch { settingsRepository.setAutoTestLatencyThresholdMs(value) }
    }

    fun setAutoTestBandwidthEnabled(enabled: Boolean) {
        invalidateAutoTestResults()
        viewModelScope.launch { settingsRepository.setAutoTestBandwidthEnabled(enabled) }
    }

    fun setAutoTestBandwidthDownloadEnabled(enabled: Boolean) {
        invalidateAutoTestResults()
        viewModelScope.launch { settingsRepository.setAutoTestBandwidthDownloadEnabled(enabled) }
    }

    fun setAutoTestBandwidthUploadEnabled(enabled: Boolean) {
        invalidateAutoTestResults()
        viewModelScope.launch { settingsRepository.setAutoTestBandwidthUploadEnabled(enabled) }
    }

    fun setAutoTestBandwidthDownloadThresholdMbps(value: Int) {
        invalidateAutoTestResults()
        viewModelScope.launch { settingsRepository.setAutoTestBandwidthDownloadThresholdMbps(value) }
    }

    fun setAutoTestBandwidthUploadThresholdMbps(value: Int) {
        invalidateAutoTestResults()
        viewModelScope.launch { settingsRepository.setAutoTestBandwidthUploadThresholdMbps(value) }
    }

    fun setAutoTestBandwidthWifiOnly(enabled: Boolean) {
        invalidateAutoTestResults()
        viewModelScope.launch { settingsRepository.setAutoTestBandwidthWifiOnly(enabled) }
    }

    fun setAutoTestBandwidthDownloadSizeMb(value: Int) {
        invalidateAutoTestResults()
        viewModelScope.launch { settingsRepository.setAutoTestBandwidthDownloadSizeMb(value) }
    }

    fun setAutoTestBandwidthUploadSizeMb(value: Int) {
        invalidateAutoTestResults()
        viewModelScope.launch { settingsRepository.setAutoTestBandwidthUploadSizeMb(value) }
    }

    fun setAutoTestUnlockEnabled(enabled: Boolean) {
        invalidateAutoTestResults()
        viewModelScope.launch { settingsRepository.setAutoTestUnlockEnabled(enabled) }
    }

    fun setAutoTestByRegion(enabled: Boolean) {
        invalidateAutoTestResults()
        viewModelScope.launch { settingsRepository.setAutoTestByRegion(enabled) }
    }

    fun setAutoTestNodeLimit(value: Int) {
        invalidateAutoTestResults()
        viewModelScope.launch { settingsRepository.setAutoTestNodeLimit(value) }
    }

    fun setAppThemeMode(mode: AppThemeMode) {
        viewModelScope.launch { settingsRepository.setAppThemeMode(mode) }
    }

    fun applyPreferTestMode(modeId: String) {
        invalidateAutoTestResults()
        viewModelScope.launch {
            val mode = preferTestModes.value.firstOrNull { it.id == modeId } ?: return@launch
            applyPreferTestModeInternal(mode)
        }
    }

    private suspend fun applyPreferTestModeInternal(mode: TestPreferMode) {
        settingsRepository.applyPreferTestMode(mode)
    }

    fun saveCurrentPreferTestMode(name: String, configOverride: AutoTestConfig? = null) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            _error.value = "模式名称不能为空"
            return
        }
        viewModelScope.launch {
            val config = configOverride ?: readAutoTestConfigFromSettings()
            val existing = preferTestModes.value
            val selectedId = preferTestSelectedModeId.value
            val selectedMode = existing.firstOrNull { it.id == selectedId }
            val savingBuiltIn = selectedMode?.builtIn == true
            val modeId = selectedMode?.id ?: "custom_${UUID.randomUUID()}"
            val modeName = if (savingBuiltIn) selectedMode?.name ?: trimmed else trimmed
            val newMode = TestPreferMode(
                id = modeId,
                name = modeName,
                builtIn = savingBuiltIn,
                filterUnavailable = config.filterUnavailable,
                latencyEnabled = config.latencyEnabled,
                latencyMode = config.latencyMode,
                latencyThresholdMs = config.latencyThresholdMs,
                bandwidthEnabled = config.bandwidthEnabled,
                bandwidthDownloadEnabled = config.bandwidthDownloadEnabled,
                bandwidthUploadEnabled = config.bandwidthUploadEnabled,
                bandwidthDownloadThresholdMbps = config.bandwidthDownloadThresholdMbps,
                bandwidthUploadThresholdMbps = config.bandwidthUploadThresholdMbps,
                bandwidthWifiOnly = config.bandwidthWifiOnly,
                bandwidthDownloadSizeMb = config.bandwidthDownloadSizeMb,
                bandwidthUploadSizeMb = config.bandwidthUploadSizeMb,
                unlockEnabled = config.unlockEnabled,
                byRegion = config.byRegion,
                nodeLimit = config.nodeLimit,
                defaultPriority = selectedMode?.defaultPriority ?: BestNodePriority.LATENCY,
                priorityOrder = selectedMode?.normalizePriorityOrder()?.priorityOrder
                    ?: BestNodePriority.entries.toList(),
                unlockPriorityMode = selectedMode?.unlockPriorityMode ?: UnlockPriorityMode.COUNT,
                unlockPriorityTargetSiteIds = selectedMode?.unlockPriorityTargetSiteIds ?: emptyList(),
                autoConnectBest = selectedMode?.autoConnectBest ?: false
            )
            val updated = existing.filterNot { it.id == modeId || (!it.builtIn && it.name == modeName) } + newMode
            settingsRepository.setPreferTestModes(updated)
            settingsRepository.setPreferTestSelectedModeId(newMode.id)
            _error.value = "已保存模式：${newMode.name}"
        }
    }

    fun createPreferTestModeFromCurrent() {
        viewModelScope.launch {
            val existing = preferTestModes.value
            val currentId = preferTestSelectedModeId.value
            val currentMode = existing.firstOrNull { it.id == currentId }
            val usedNames = existing.map { it.name }.toSet()
            val baseName = "新模式"
            var index = 1
            var candidateName = baseName
            while (candidateName in usedNames) {
                index++
                candidateName = "$baseName $index"
            }

            val newMode = TestPreferMode(
                id = "custom_${UUID.randomUUID()}",
                name = candidateName,
                builtIn = false,
                filterUnavailable = autoTestFilterUnavailable.value,
                latencyEnabled = autoTestLatencyEnabled.value,
                latencyMode = autoTestLatencyMode.value,
                latencyThresholdMs = autoTestLatencyThresholdMs.value,
                bandwidthEnabled = autoTestBandwidthEnabled.value,
                bandwidthDownloadEnabled = autoTestBandwidthDownloadEnabled.value,
                bandwidthUploadEnabled = autoTestBandwidthUploadEnabled.value,
                bandwidthDownloadThresholdMbps = autoTestBandwidthDownloadThresholdMbps.value,
                bandwidthUploadThresholdMbps = autoTestBandwidthUploadThresholdMbps.value,
                bandwidthWifiOnly = autoTestBandwidthWifiOnly.value,
                bandwidthDownloadSizeMb = autoTestBandwidthDownloadSizeMb.value,
                bandwidthUploadSizeMb = autoTestBandwidthUploadSizeMb.value,
                unlockEnabled = autoTestUnlockEnabled.value,
                byRegion = autoTestByRegion.value,
                nodeLimit = autoTestNodeLimit.value,
                defaultPriority = currentMode?.defaultPriority ?: BestNodePriority.LATENCY,
                priorityOrder = currentMode?.normalizePriorityOrder()?.priorityOrder
                    ?: BestNodePriority.entries.toList(),
                unlockPriorityMode = currentMode?.unlockPriorityMode ?: UnlockPriorityMode.COUNT,
                unlockPriorityTargetSiteIds = currentMode?.unlockPriorityTargetSiteIds ?: emptyList(),
                autoConnectBest = currentMode?.autoConnectBest ?: false
            )
            settingsRepository.setPreferTestModes(existing + newMode)
            settingsRepository.setPreferTestSelectedModeId(newMode.id)
            _error.value = "已新增模式：${newMode.name}"
        }
    }

    fun deleteCurrentPreferTestMode() {
        viewModelScope.launch {
            val currentId = preferTestSelectedModeId.value
            val currentMode = preferTestModes.value.firstOrNull { it.id == currentId }
            if (currentMode == null) return@launch
            if (currentMode.builtIn) {
                _error.value = "内置模式不可删除"
                return@launch
            }
            val updated = preferTestModes.value.filterNot { it.id == currentMode.id }
            settingsRepository.setPreferTestModes(updated)
            settingsRepository.setPreferTestSelectedModeId(BUILTIN_PREFER_MODE_CHAT)
            applyPreferTestMode(BUILTIN_PREFER_MODE_CHAT)
            _error.value = "已删除模式：${currentMode.name}"
        }
    }

    fun updateCurrentPreferModePriority(priority: BestNodePriority) {
        viewModelScope.launch {
            val currentId = preferTestSelectedModeId.value
            val existing = preferTestModes.value
            val target = existing.firstOrNull { it.id == currentId } ?: return@launch
            val priorityOrder = listOf(priority) + target.normalizePriorityOrder().priorityOrder
                .filterNot { it == priority }
            val updatedMode = target.copy(
                defaultPriority = priority,
                priorityOrder = priorityOrder
            )
            val updated = existing.map { if (it.id == currentId) updatedMode else it }
            settingsRepository.setPreferTestModes(updated)
        }
    }

    fun updateCurrentPreferModePriorityOrder(priorityOrder: List<BestNodePriority>) {
        viewModelScope.launch {
            val currentId = preferTestSelectedModeId.value
            val existing = preferTestModes.value
            val target = existing.firstOrNull { it.id == currentId } ?: return@launch
            val normalizedOrder = buildList {
                priorityOrder.distinct().forEach(::add)
                BestNodePriority.entries.filterNot { it in this }.forEach(::add)
            }
            if (normalizedOrder.isEmpty()) return@launch
            val updatedMode = target.copy(
                defaultPriority = normalizedOrder.first(),
                priorityOrder = normalizedOrder
            )
            val updated = existing.map { if (it.id == currentId) updatedMode else it }
            settingsRepository.setPreferTestModes(updated)
        }
    }

    fun updateCurrentPreferModeAutoConnect(autoConnectBest: Boolean) {
        viewModelScope.launch {
            val currentId = preferTestSelectedModeId.value
            val existing = preferTestModes.value
            val target = existing.firstOrNull { it.id == currentId } ?: return@launch
            val updated = existing.map {
                if (it.id == currentId) target.copy(autoConnectBest = autoConnectBest) else it
            }
            settingsRepository.setPreferTestModes(updated)
        }
    }

    fun updateCurrentPreferModeUnlockPriority(
        mode: UnlockPriorityMode,
        targetSiteIds: List<String>
    ) {
        viewModelScope.launch {
            val currentId = preferTestSelectedModeId.value
            val existing = preferTestModes.value
            val target = existing.firstOrNull { it.id == currentId } ?: return@launch
            val updatedMode = target.copy(
                unlockPriorityMode = mode,
                unlockPriorityTargetSiteIds = targetSiteIds.distinct()
            )
            val updated = existing.map { if (it.id == currentId) updatedMode else it }
            settingsRepository.setPreferTestModes(updated)
        }
    }

    fun startAutomatedTest(
        preferPriority: BestNodePriority? = null,
        connectBestAfterDone: Boolean = false,
        configOverride: AutoTestConfig? = null,
        modeOverride: TestPreferMode? = null
    ) {
        if (autoTestJob?.isActive == true || _autoTestProgress.value.running) return

        autoTestJob = viewModelScope.launch {
            val config = configOverride ?: readAutoTestConfigFromSettings()
            val bandwidthRunnable = config.bandwidthEnabled &&
                (config.bandwidthDownloadEnabled || config.bandwidthUploadEnabled)
            if (!config.latencyEnabled && !bandwidthRunnable && !config.unlockEnabled) {
                _error.value = "请至少启用一个可执行的测试项目"
                return@launch
            }
            if (
                !config.latencyEnabled &&
                !config.unlockEnabled &&
                bandwidthRunnable &&
                config.bandwidthWifiOnly &&
                !NetworkUtils.isWifiConnected(getApplication())
            ) {
                _error.value = "当前不是 Wi-Fi 网络，无法执行仅 Wi-Fi 带宽测试"
                return@launch
            }
            val selectedModeForRun = modeOverride
                ?: preferTestModes.value.firstOrNull { it.id == preferTestSelectedModeId.value }
            val modeForRun = selectedModeForRun?.withAutoTestConfig(config)
            _autoTestResultSnapshot.value = emptyList()
            _autoTestResultMode.value = null
            if (GlobalTestExecution.isFetching()) {
                _error.value = GlobalTestExecution.fetchingHint()
                _isAutoSelecting.value = false
                return@launch
            }
            if (!GlobalTestExecution.tryStart("测试择优")) {
                _error.value = GlobalTestExecution.busyHint()
                _isAutoSelecting.value = false
                return@launch
            }
            try {
                RuntimeLog.info(
                    tag,
                    "Automated test started: latency=${config.latencyEnabled}, bandwidth=${config.bandwidthEnabled}, unlock=${config.unlockEnabled}, limit=${config.nodeLimit}"
                )
                _autoTestProgress.value = AutoTestProgress(
                    running = true,
                    stage = AutoTestStage.FETCH_NODES,
                    message = "正在拉取节点..."
                )

                val fetchOk = if (settingsRepository.selectedNodeGroupId.first() != FAVORITES_NODE_GROUP_ID) fetchNodesInternal(
                    bypassThrottle = true,
                    runUrlTest = false,
                    allowWhenTestRunning = true,
                    fetchingLabel = "请求节点中"
                ) else true
                if (!fetchOk) {
                    RuntimeLog.warn(tag, "Automated test failed: fetch nodes failed")
                    _error.value = "自动化测试失败：请求节点失败"
                    _autoTestProgress.value = AutoTestProgress(
                        running = false,
                        stage = AutoTestStage.FAILED,
                        message = "请求节点失败"
                    )
                    return@launch
                }

                // 在获取后始终读取新的数据库快照，以避免陈旧的 Flow 缓存
                // 导致在长时间运行的自动化测试中按 ID 更新失败。
                val latestSnapshot = currentNodeListSnapshot()
                var workingNodes = selectNodesForAutoTest(latestSnapshot, config).map { node ->
                    node.copy(
                        downloadMbps = 0f,
                        uploadMbps = 0f,
                        unlockSummary = "",
                        unlockPassed = false,
                        autoTestStatus = "",
                        autoTestedAt = 0L
                    )
                }
                val initialSelectedCount = workingNodes.size
                if (workingNodes.isEmpty()) {
                    RuntimeLog.warn(tag, "Automated test failed: no available nodes")
                    _error.value = "自动化测试失败：没有可用节点"
                    _autoTestProgress.value = AutoTestProgress(running = false, stage = AutoTestStage.FAILED, message = "没有可用节点")
                    return@launch
                }

                if (config.latencyEnabled) {
                    val latencyModeLabel = if (config.latencyMode == AutoTestLatencyMode.TCPING) "TCPing" else "URL Test"
                    _autoTestProgress.value = AutoTestProgress(
                        running = true,
                        stage = AutoTestStage.LATENCY_TEST,
                        message = "正在进行 $latencyModeLabel...",
                        total = workingNodes.size
                    )

                    if (config.latencyMode == AutoTestLatencyMode.TCPING) {
                        internalTestNodes(workingNodes) { completed, total ->
                            _autoTestProgress.value = AutoTestProgress(
                                running = true,
                                stage = AutoTestStage.LATENCY_TEST,
                                message = "正在进行 TCPing...",
                                completed = completed,
                                total = total
                            )
                        }
                    } else {
                        val urlTestJob = urlTestAllNodesInternal(
                            targetNodes = workingNodes,
                            requireGlobalLock = false,
                            onProgress = { completed, total ->
                                _autoTestProgress.value = AutoTestProgress(
                                    running = true,
                                    stage = AutoTestStage.LATENCY_TEST,
                                    message = "正在进行 URL Test...",
                                    completed = completed,
                                    total = total
                                )
                            }
                        )
                        try {
                            urlTestJob.join()
                        } catch (e: CancellationException) {
                            urlTestJob.cancel()
                            withContext(NonCancellable) { urlTestJob.join() }
                            throw e
                        }
                    }

                    val latestNodesById = nodeDao.getAllNodes().first().associateBy { it.id }
                    workingNodes = workingNodes.mapNotNull { node ->
                        latestNodesById[node.id]?.let { latest ->
                            node.copy(
                                latency = latest.latency,
                                isAvailable = latest.isAvailable,
                                lastTestedAt = latest.lastTestedAt
                            )
                        }
                    }

                    _autoTestProgress.value = AutoTestProgress(
                        running = true,
                        stage = AutoTestStage.FILTER_LATENCY,
                        message = "按延迟阈值筛选节点...",
                        total = workingNodes.size
                    )

                    val latencyFiltered = workingNodes.filter { node ->
                        AutoTestQualificationPolicy.keepAfterLatency(
                            filterUnqualified = config.filterUnavailable,
                            isAvailable = node.isAvailable,
                            latencyMs = node.latency,
                            thresholdMs = config.latencyThresholdMs
                        )
                    }

                    val removedByLatency = workingNodes.filterNot { node -> latencyFiltered.any { it.id == node.id } }
                    removedByLatency.forEach { node ->
                        nodeDao.updateAutoTestStatus(node.id, node.isAvailable, "LATENCY_FILTERED", System.currentTimeMillis())
                    }
                    workingNodes = latencyFiltered.map { node ->
                        val status = if (node.latency > 0) "LATENCY_PASSED" else "LATENCY_SKIPPED"
                        nodeDao.updateAutoTestStatus(node.id, node.isAvailable, status, System.currentTimeMillis())
                        node.copy(autoTestStatus = status, autoTestedAt = System.currentTimeMillis())
                    }
                } else {
                    workingNodes = workingNodes.map { node ->
                        nodeDao.updateAutoTestStatus(node.id, node.isAvailable, "LATENCY_SKIPPED", System.currentTimeMillis())
                        node.copy(autoTestStatus = "LATENCY_SKIPPED", autoTestedAt = System.currentTimeMillis())
                    }
                }

                if (config.bandwidthEnabled) {
                    val wifiAllowed = !config.bandwidthWifiOnly || NetworkUtils.isWifiConnected(getApplication())
                    if (!wifiAllowed) {
                        _error.value = "当前非 Wi-Fi 网络，已跳过带宽测试"
                    } else if (!config.bandwidthDownloadEnabled && !config.bandwidthUploadEnabled) {
                        _error.value = "未选择上下行带宽测试方向，已跳过带宽测试"
                    } else {
                        val bandwidthDirectionList = mutableListOf<String>()
                        if (config.bandwidthDownloadEnabled) bandwidthDirectionList.add("下行")
                        if (config.bandwidthUploadEnabled) bandwidthDirectionList.add("上行")
                        val bandwidthDirections = bandwidthDirectionList.joinToString("+")
                        val bandwidthConcurrency = settingsRepository.bandwidthTestConcurrency.first().coerceIn(1, 3)
                        val bandwidthConcurrencyLabel = if (bandwidthConcurrency > 1) {
                            "，并发 $bandwidthConcurrency"
                        } else {
                            ""
                        }
                        _autoTestProgress.value = AutoTestProgress(
                            running = true,
                            stage = AutoTestStage.BANDWIDTH_TEST,
                            message = "带宽测试中（$bandwidthDirections$bandwidthConcurrencyLabel）...",
                            total = workingNodes.size
                        )

                        val totalBandwidthNodes = workingNodes.size
                        val completedBandwidthNodes = AtomicInteger(0)
                        val updatedNodes = coroutineScope {
                            val semaphore = Semaphore(bandwidthConcurrency)
                            workingNodes.map { node ->
                                async(Dispatchers.IO) {
                                    semaphore.withPermit {
                                        _autoTestProgress.value = AutoTestProgress(
                                            running = true,
                                            stage = AutoTestStage.BANDWIDTH_TEST,
                                            message = "带宽测试($bandwidthDirections): ${node.getDisplayName()} (${completedBandwidthNodes.get()}/${totalBandwidthNodes}$bandwidthConcurrencyLabel)",
                                            completed = completedBandwidthNodes.get(),
                                            total = totalBandwidthNodes
                                        )
                                        val downloadMbps = if (config.bandwidthDownloadEnabled) {
                                            testNodeDownloadBandwidthMbps(node, config.bandwidthDownloadSizeMb)
                                        } else 0f
                                        val uploadMbps = if (config.bandwidthUploadEnabled) {
                                            testNodeUploadBandwidthMbps(node, config.bandwidthUploadSizeMb)
                                        } else 0f
                                        val testedAt = System.currentTimeMillis()
                                        nodeDao.updateBandwidth(node.id, downloadMbps, uploadMbps, testedAt)
                                        val completed = completedBandwidthNodes.incrementAndGet()
                                        _autoTestProgress.value = AutoTestProgress(
                                            running = true,
                                            stage = AutoTestStage.BANDWIDTH_TEST,
                                            message = "带宽测试($bandwidthDirections): 已完成 $completed/$totalBandwidthNodes$bandwidthConcurrencyLabel",
                                            completed = completed,
                                            total = totalBandwidthNodes
                                        )
                                        node.copy(
                                            downloadMbps = downloadMbps,
                                            uploadMbps = uploadMbps,
                                            autoTestedAt = testedAt
                                        )
                                    }
                                }
                            }.awaitAll()
                        }
                        workingNodes = updatedNodes

                        _autoTestProgress.value = AutoTestProgress(
                            running = true,
                            stage = AutoTestStage.FILTER_BANDWIDTH,
                            message = "按带宽阈值筛选节点...",
                            total = workingNodes.size
                        )

                        val bandwidthQualified = workingNodes.filter { node ->
                            AutoTestQualificationPolicy.bandwidthPassed(
                                downloadEnabled = config.bandwidthDownloadEnabled,
                                uploadEnabled = config.bandwidthUploadEnabled,
                                downloadMbps = node.downloadMbps,
                                uploadMbps = node.uploadMbps,
                                downloadThresholdMbps = config.bandwidthDownloadThresholdMbps,
                                uploadThresholdMbps = config.bandwidthUploadThresholdMbps
                            )
                        }
                        val qualifiedIds = bandwidthQualified.mapTo(hashSetOf()) { it.id }
                        val filtered = workingNodes.filter { node ->
                            AutoTestQualificationPolicy.keepAfterRule(
                                filterUnqualified = config.filterUnavailable,
                                passed = node.id in qualifiedIds
                            )
                        }
                        val removed = workingNodes.filterNot { node -> filtered.any { it.id == node.id } }
                        removed.forEach { node ->
                            nodeDao.updateAutoTestStatus(node.id, node.isAvailable, "BANDWIDTH_FILTERED", System.currentTimeMillis())
                        }
                        workingNodes = filtered.map { node ->
                            val passed = node.id in qualifiedIds
                            val status = if (passed) "BANDWIDTH_PASSED" else "BANDWIDTH_FAILED"
                            val available = AutoTestQualificationPolicy.connectivityAfterSuccessfulTest(
                                wasAvailable = node.isAvailable,
                                testPassed = passed
                            )
                            nodeDao.updateAutoTestStatus(node.id, available, status, System.currentTimeMillis())
                            node.copy(
                                isAvailable = available,
                                autoTestStatus = status,
                                autoTestedAt = System.currentTimeMillis()
                            )
                        }
                    }
                    if (!wifiAllowed || (!config.bandwidthDownloadEnabled && !config.bandwidthUploadEnabled)) {
                        workingNodes = workingNodes.map { node ->
                            nodeDao.updateAutoTestStatus(node.id, node.isAvailable, "BANDWIDTH_SKIPPED", System.currentTimeMillis())
                            node.copy(
                                downloadMbps = 0f,
                                uploadMbps = 0f,
                                autoTestStatus = "BANDWIDTH_SKIPPED",
                                autoTestedAt = System.currentTimeMillis()
                            )
                        }
                    }
                }

                if (config.unlockEnabled && workingNodes.isNotEmpty()) {
                    val unlockConcurrency = unlockTestConcurrency.value.coerceAtLeast(1)
                    _autoTestProgress.value = AutoTestProgress(
                        running = true,
                        stage = AutoTestStage.UNLOCK_TEST,
                        message = "流媒体解锁测试中（并发 $unlockConcurrency）...",
                        total = workingNodes.size
                    )

                    val total = workingNodes.size
                    val completed = AtomicInteger(0)
                    val unlockResults = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Boolean>>()
                    coroutineScope {
                        val semaphore = Semaphore(unlockConcurrency)
                        workingNodes.map { node ->
                            async(Dispatchers.IO) {
                                semaphore.withPermit {
                                    val (summary, passed) = testNodeUnlock(node)
                                    val now = System.currentTimeMillis()
                                    unlockResults[node.id] = summary to passed
                                    nodeDao.updateUnlock(node.id, summary, passed, now)
                                    nodeDao.updateAutoTestStatus(
                                        nodeId = node.id,
                                        available = AutoTestQualificationPolicy.connectivityAfterSuccessfulTest(
                                            wasAvailable = node.isAvailable,
                                            testPassed = passed
                                        ),
                                        status = if (passed) "UNLOCK_PASSED" else "UNLOCK_FAILED",
                                        testedAt = now
                                    )

                                    val done = completed.incrementAndGet()
                                    _autoTestProgress.value = AutoTestProgress(
                                        running = true,
                                        stage = AutoTestStage.UNLOCK_TEST,
                                        message = "流媒体解锁测试中（并发 $unlockConcurrency）...",
                                        completed = done,
                                        total = total
                                    )
                                }
                            }
                        }.awaitAll()
                    }
                    val unlockTestedNodes = workingNodes.map { node ->
                        val result = unlockResults[node.id] ?: ("" to false)
                        val passed = result.second
                        node.copy(
                            unlockSummary = result.first,
                            unlockPassed = passed,
                            isAvailable = AutoTestQualificationPolicy.connectivityAfterSuccessfulTest(
                                wasAvailable = node.isAvailable,
                                testPassed = passed
                            ),
                            autoTestStatus = if (passed) "UNLOCK_PASSED" else "UNLOCK_FAILED",
                            autoTestedAt = System.currentTimeMillis()
                        )
                    }
                    workingNodes = unlockTestedNodes.filter { node ->
                        AutoTestQualificationPolicy.keepAfterRule(
                            filterUnqualified = config.filterUnavailable,
                            passed = node.unlockPassed
                        )
                    }
                }

                val priorityOrder = resolvePriorityOrder(modeForRun, preferPriority)
                val snapshotPriority = priorityOrder.first()
                val sortedSnapshot = sortNodesForSnapshotByOrder(
                    nodes = workingNodes,
                    priorityOrder = priorityOrder,
                    mode = modeForRun
                )
                _autoTestResultSnapshot.value = sortedSnapshot.map { it.copy() }
                _autoTestResultMode.value = modeForRun
                _autoTestResultPriority.value = snapshotPriority
                _autoTestProgress.value = AutoTestProgress(
                    running = false,
                    stage = AutoTestStage.DONE,
                    message = "完成：保留 ${workingNodes.size} 个节点",
                    completed = initialSelectedCount,
                    total = initialSelectedCount
                )
                RuntimeLog.info(tag, "Automated test completed: kept=${workingNodes.size}, selected=$initialSelectedCount")

                if (preferPriority != null) {
                    val finalCandidates = workingNodes.filter { it.isAvailable }
                    val bestNode = pickBestNodeByOrder(finalCandidates, priorityOrder, modeForRun)
                    if (bestNode != null) {
                        settingsRepository.setSelectedNodeId(bestNode.id)
                        if (connectBestAfterDone) {
                            ServiceManager.startVpn(getApplication(), bestNode, proxyMode.value)
                            _error.value = "已自动连接最优节点：${bestNode.getDisplayName()}"
                        } else {
                            _error.value = "已选择最优节点：${bestNode.getDisplayName()}"
                        }
                    } else if (connectBestAfterDone) {
                        _error.value = "测试完成，但未找到符合择优规则的节点"
                    }
                }
            } catch (e: CancellationException) {
                RuntimeLog.info(tag, "Automated test canceled")
                _autoTestProgress.value = AutoTestProgress(
                    running = false,
                    stage = AutoTestStage.CANCELED,
                    message = "自动化测试已取消"
                )
                throw e
            } catch (e: Exception) {
                Log.e(tag, "Automated test failed", e)
                RuntimeLog.error(tag, "Automated test failed", e)
                _autoTestProgress.value = AutoTestProgress(
                    running = false,
                    stage = AutoTestStage.FAILED,
                    message = "失败: ${e.message}"
                )
                _error.value = "自动化测试失败: ${e.message}"
            } finally {
                if (connectBestAfterDone) {
                    _isAutoSelecting.value = false
                }
                autoTestJob = null
                GlobalTestExecution.finish()
            }
        }
    }

    private suspend fun readAutoTestConfigFromSettings(): AutoTestConfig {
        return AutoTestConfig(
            enabled = settingsRepository.autoTestEnabled.first(),
            filterUnavailable = settingsRepository.autoTestFilterUnavailable.first(),
            latencyEnabled = settingsRepository.autoTestLatencyEnabled.first(),
            latencyMode = settingsRepository.autoTestLatencyMode.first(),
            latencyThresholdMs = settingsRepository.autoTestLatencyThresholdMs.first(),
            bandwidthEnabled = settingsRepository.autoTestBandwidthEnabled.first(),
            bandwidthDownloadEnabled = settingsRepository.autoTestBandwidthDownloadEnabled.first(),
            bandwidthUploadEnabled = settingsRepository.autoTestBandwidthUploadEnabled.first(),
            bandwidthDownloadThresholdMbps = settingsRepository.autoTestBandwidthDownloadThresholdMbps.first(),
            bandwidthUploadThresholdMbps = settingsRepository.autoTestBandwidthUploadThresholdMbps.first(),
            bandwidthWifiOnly = settingsRepository.autoTestBandwidthWifiOnly.first(),
            bandwidthDownloadSizeMb = settingsRepository.autoTestBandwidthDownloadSizeMb.first(),
            bandwidthUploadSizeMb = settingsRepository.autoTestBandwidthUploadSizeMb.first(),
            unlockEnabled = settingsRepository.autoTestUnlockEnabled.first(),
            byRegion = settingsRepository.autoTestByRegion.first(),
            nodeLimit = settingsRepository.autoTestNodeLimit.first()
        )
    }

    private fun TestPreferMode.withAutoTestConfig(config: AutoTestConfig): TestPreferMode = copy(
        filterUnavailable = config.filterUnavailable,
        latencyEnabled = config.latencyEnabled,
        latencyMode = config.latencyMode,
        latencyThresholdMs = config.latencyThresholdMs,
        bandwidthEnabled = config.bandwidthEnabled,
        bandwidthDownloadEnabled = config.bandwidthDownloadEnabled,
        bandwidthUploadEnabled = config.bandwidthUploadEnabled,
        bandwidthDownloadThresholdMbps = config.bandwidthDownloadThresholdMbps,
        bandwidthUploadThresholdMbps = config.bandwidthUploadThresholdMbps,
        bandwidthWifiOnly = config.bandwidthWifiOnly,
        bandwidthDownloadSizeMb = config.bandwidthDownloadSizeMb,
        bandwidthUploadSizeMb = config.bandwidthUploadSizeMb,
        unlockEnabled = config.unlockEnabled,
        byRegion = config.byRegion,
        nodeLimit = config.nodeLimit
    )

    fun cancelAutomatedTest() {
        autoTestJob?.cancel()
        _isAutoSelecting.value = false
        _autoTestProgress.value = AutoTestProgress(
            running = true,
            stage = AutoTestStage.CANCELED,
            message = "正在取消测试..."
        )
    }

    private fun selectNodesForAutoTest(allNodes: List<Node>, config: AutoTestConfig): List<Node> {
        val limit = config.nodeLimit.coerceAtLeast(1)
        if (!config.byRegion) {
            return allNodes.take(limit)
        }

        return allNodes
            .groupBy { resolveRegionBucket(it) }
            .values
            .flatMap { regionNodes -> regionNodes.take(limit) }
    }

    private suspend fun currentNodeListSnapshot(): List<Node> {
        val groupId = settingsRepository.selectedNodeGroupId.first()
        val snapshot = if (groupId == FAVORITES_NODE_GROUP_ID) {
            nodeDao.getFavoriteNodes().first()
        } else {
            nodeDao.getSubscriptionNodes(groupId).first()
        }
        return sortNodesForList(snapshot, _filterUnavailable.value)
    }

    private fun resolveRegionBucket(node: Node): String {
        val normalized = node.country
            ?.trim()
            ?.uppercase()
            ?.takeIf { it.length == 2 && it.all { ch -> ch in 'A'..'Z' } }
        if (normalized != null) {
            return normalized
        }

        return extractRegionCodeFromFlag(node.name) ?: "UNKNOWN"
    }

    private fun extractRegionCodeFromFlag(text: String): String? {
        var i = 0
        while (i < text.length) {
            val first = text.codePointAt(i)
            val firstLen = Character.charCount(first)
            if (first in 0x1F1E6..0x1F1FF && i + firstLen < text.length) {
                val second = text.codePointAt(i + firstLen)
                if (second in 0x1F1E6..0x1F1FF) {
                    val c1 = (first - 0x1F1E6 + 'A'.code).toChar()
                    val c2 = (second - 0x1F1E6 + 'A'.code).toChar()
                    return "$c1$c2"
                }
            }
            i += firstLen
        }
        return null
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

    private suspend fun restoreRememberedLastSelectedNodeIfNeeded(freshNodes: List<Node>? = null) {
        if (!settingsRepository.rememberLastSelectedNodeEnabled.first()) return
        val rememberedNodeId = settingsRepository.lastSelectedNodeId.first()?.takeIf { it.isNotBlank() } ?: return
        val candidateNodes = freshNodes ?: nodeDao.getAllNodes().first()
        if (candidateNodes.any { it.id == rememberedNodeId } || nodeDao.getNodeById(rememberedNodeId) != null) {
            settingsRepository.setSelectedNodeId(rememberedNodeId)
        }
    }

    private fun launchStartupDefaultTestIfNeeded(showHint: Boolean = false) {
        // 取出缓存的最新节点（避免 Room Flow 延迟导致测旧节点）
        val freshNodes = lastFetchedNodes
        lastFetchedNodes = null
        viewModelScope.launch {
            val mode = settingsRepository.startupDefaultTestMode.first()
            val ready = waitForNodesReadyWithAutoRetry(timeoutMs = AppConfig.NODE_REQUEST_TIMEOUT_MS)
            if (!ready) return@launch
            runDefaultStartupTestIfNeeded(mode, showHint, freshNodes)
        }
    }

    fun refreshNodesWithDefaultTest() {
        viewModelScope.launch {
            if (GlobalTestExecution.isFetching()) {
                _error.value = GlobalTestExecution.fetchingHint()
                return@launch
            }
            if (_autoTestProgress.value.running || _isTesting.value || GlobalTestExecution.mutex.isLocked) {
                _error.value = GlobalTestExecution.busyHint()
                return@launch
            }
            if (selectedNodeGroupId.value == FAVORITES_NODE_GROUP_ID) {
                val wasFiltering = _filterUnavailable.value
                _filterUnavailable.value = false
                _error.value = if (wasFiltering) "已恢复显示全部收藏节点" else "收藏节点已刷新"
                return@launch
            }
            val mode = settingsRepository.startupDefaultTestMode.first()
            val fetchOk = fetchNodesInternal(
                bypassThrottle = true,
                runUrlTest = false,
                fetchingLabel = "请求节点中"
            )
            if (!fetchOk) return@launch
            // 取出缓存的最新节点（避免 Room Flow 延迟导致测旧节点）
            val freshNodes = lastFetchedNodes
            lastFetchedNodes = null
            val ready = waitForNodesReadyWithAutoRetry(timeoutMs = AppConfig.NODE_REQUEST_TIMEOUT_MS)
            if (!ready) return@launch
            runDefaultStartupTestIfNeeded(mode, showHint = false, freshNodes)
        }
    }

    private suspend fun waitForNodesReadyWithAutoRetry(timeoutMs: Long): Boolean {
        val firstAttempt = runCatching {
            waitForCondition(timeoutMs = timeoutMs) {
                !isLoading.value && (nodes.value.isNotEmpty() || selectedNodeGroupId.value == FAVORITES_NODE_GROUP_ID)
            }
        }
        if (firstAttempt.isSuccess) return true

        _error.value = "请求节点超时，已自动再次请求节点..."
        val retryOk = fetchNodesInternal(
            bypassThrottle = true,
            runUrlTest = false,
            fetchingLabel = "请求节点中"
        )
        if (!retryOk) {
            _error.value = "请求节点超时，自动重试后仍未完成，请稍后再试"
            return false
        }

        val secondAttempt = runCatching {
            waitForCondition(timeoutMs = timeoutMs) {
                !isLoading.value && (nodes.value.isNotEmpty() || selectedNodeGroupId.value == FAVORITES_NODE_GROUP_ID)
            }
        }
        if (secondAttempt.isSuccess) return true

        _error.value = "请求节点超时，自动重试后仍失败，请稍后再试"
        return false
    }

    private suspend fun <T> requestWithAutoRetry(
        requestName: String,
        requestBlock: suspend () -> T
    ): Result<T> {
        val first = runCatching { requestBlock() }
        if (first.isSuccess) return Result.success(first.getOrThrow())

        _error.value = "${requestName}请求失败，已自动重试..."
        val second = runCatching { requestBlock() }
        if (second.isSuccess) return Result.success(second.getOrThrow())

        _error.value = "${requestName}请求失败，自动重试后仍失败，请稍后再试"
        return Result.failure(second.exceptionOrNull() ?: first.exceptionOrNull() ?: IllegalStateException("${requestName}请求失败"))
    }

    private fun runDefaultStartupTestIfNeeded(
        mode: StartupDefaultTestMode,
        showHint: Boolean,
        freshNodes: List<Node>? = null
    ) {
        if (mode == StartupDefaultTestMode.NONE) return
        if (_isTesting.value || _autoTestProgress.value.running) return
        val freshTargetNodes = if (selectedNodeGroupId.value != FAVORITES_NODE_GROUP_ID) freshNodes else null
        when (mode) {
            StartupDefaultTestMode.TCPING -> {
                if (showHint) _error.value = "已按默认设置执行 TCPing 测试"
                testAllNodes(freshTargetNodes)
            }
            StartupDefaultTestMode.URL_TEST -> {
                if (showHint) _error.value = "已按默认设置执行 URL Test 测试"
                urlTestAllNodes(freshTargetNodes)
            }
            StartupDefaultTestMode.NONE -> Unit
        }
    }

    suspend fun fetchNodeIpInfo(node: Node): Result<NodeIpInfo> = withContext(Dispatchers.IO) {
        val port = pickFreePort()
        val session = UnlockTestManager.createSession(getApplication(), node, port)
        val started = session.start()
        if (!started) return@withContext Result.failure(IllegalStateException("启动节点测试代理失败"))

        try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
            val timeoutMs = nodeIpInfoTimeoutMs.value.coerceAtLeast(1000L)
            val client = NetworkClient.withUserAgent(OkHttpClient.Builder())
                .proxy(proxy)
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()

            var lastResult: Result<NodeIpInfo> = Result.failure(IllegalStateException("节点 IP 信息请求失败"))
            repeat(AppConfig.NODE_IP_INFO_RETRY_COUNT.coerceAtLeast(0) + 1) { attempt ->
                val attemptResult = fetchNodeIpInfoOnce(node, client)
                lastResult = attemptResult.result
                if (lastResult.isSuccess || !attemptResult.shouldRetry || attempt >= AppConfig.NODE_IP_INFO_RETRY_COUNT) {
                    return@withContext lastResult
                }
                Log.w(
                    tag,
                    "Node IP info retry ${attempt + 1}/${AppConfig.NODE_IP_INFO_RETRY_COUNT}: ${node.getDisplayName()} (${node.id})"
                )
                delay(300)
            }

            lastResult
        } catch (e: Exception) {
            Log.e(tag, "Fetch node IP info failed for ${node.getDisplayName()}: ${e.message}")
            Result.failure(e)
        } finally {
            session.stop()
        }
    }

    private fun fetchNodeIpInfoOnce(
        node: Node,
        client: OkHttpClient
    ): NodeIpInfoAttemptResult {
        return try {
            val request = Request.Builder()
                .url(AppConfig.NODE_IP_INFO_URL)
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val retryable = response.code in listOf(429, 500, 502, 503, 504)
                    val error = IllegalStateException("请求失败: HTTP ${response.code}")
                    NodeIpInfoAttemptResult(Result.failure(error), retryable)
                } else {
                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) {
                        NodeIpInfoAttemptResult(
                            Result.failure(IllegalStateException("接口返回为空")),
                            shouldRetry = false
                        )
                    } else {
                        val parsed = gson.fromJson(body, NodeIpInfo::class.java)
                        if (parsed == null || parsed.ip.isBlank()) {
                            NodeIpInfoAttemptResult(
                                Result.failure(IllegalStateException("解析 IP 信息失败")),
                                shouldRetry = false
                            )
                        } else {
                            NodeIpInfoAttemptResult(Result.success(parsed), shouldRetry = false)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Fetch node IP info attempt failed for ${node.getDisplayName()}: ${e.message}")
            NodeIpInfoAttemptResult(Result.failure(e), shouldRetry = shouldRetryNodeIpInfo(e))
        }
    }

    private fun shouldRetryNodeIpInfo(error: Exception): Boolean {
        return error is IOException // 涵盖 SocketTimeoutException、InterruptedIOException 等
    }

    private suspend fun testNodeDownloadBandwidthMbps(node: Node, sizeMb: Int): Float = withContext(Dispatchers.IO) {
        val port = pickFreePort()
        val session = UnlockTestManager.createSession(getApplication(), node, port)
        val started = session.start()
        if (!started) return@withContext 0f

        try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
            val bytes = sizeMb.toLong() * 1_000_000L
            SpeedTestService(
                downloadTimeoutMs = speedTestDownloadTimeoutMs.value,
                proxy = proxy
            ).startDownloadTest(bytes) { _, _ -> }.avgSpeedMbps
        } catch (e: Exception) {
            Log.e(tag, "Download bandwidth test failed for ${node.getDisplayName()}: ${e.message}")
            0f
        } finally {
            session.stop()
        }
    }

    private suspend fun testNodeUploadBandwidthMbps(node: Node, sizeMb: Int): Float = withContext(Dispatchers.IO) {
        val port = pickFreePort()
        val session = UnlockTestManager.createSession(getApplication(), node, port)
        val started = session.start()
        if (!started) return@withContext 0f

        try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
            val bytes = sizeMb.toLong() * 1_000_000L
            SpeedTestService(
                uploadTimeoutMs = AppConfig.AUTO_TEST_BANDWIDTH_UPLOAD_TIMEOUT_MS,
                proxy = proxy
            ).startUploadTest(bytes) { _, _ -> }.avgSpeedMbps
        } catch (e: Exception) {
            Log.e(tag, "Upload bandwidth test failed for ${node.getDisplayName()}: ${e.message}")
            0f
        } finally {
            session.stop()
        }
    }

    private suspend fun testNodeUnlock(node: Node): Pair<String, Boolean> = withContext(Dispatchers.IO) {
        val port = pickFreePort()
        val session = UnlockTestManager.createSession(getApplication(), node, port)
        val started = session.start()
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
                .filterNot { it.contains(Regex("""\bYES\s*=\s*\d+""", RegexOption.IGNORE_CASE)) || it.contains(Regex("""\bNO\s*=\s*\d+""", RegexOption.IGNORE_CASE)) }
                .distinct()
                .take(200)
                .toList()

            val yes = lines.count {
                (it.contains(Regex("""\bYES\b""", RegexOption.IGNORE_CASE)) || it.contains("Available", true)) &&
                    !it.contains(Regex("""\bNO\b""", RegexOption.IGNORE_CASE))
            }
            val no = lines.count { it.contains(Regex("""\bNO\b""", RegexOption.IGNORE_CASE)) }
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
            session.stop()
        }
    }

    private fun pickBestNode(
        candidates: List<Node>,
        priority: BestNodePriority,
        mode: TestPreferMode? = null
    ): Node? {
        if (candidates.isEmpty()) return null
        return when (priority) {
            BestNodePriority.LATENCY -> candidates
                .filter { it.latency > 0 }
                .minWithOrNull(compareBy<Node> { it.latency }.thenByDescending { it.downloadMbps }.thenByDescending { it.uploadMbps })
            BestNodePriority.DOWNLOAD -> candidates
                .filter { it.downloadMbps > 0f }
                .maxWithOrNull(compareBy<Node> { it.downloadMbps }.thenBy { -safeLatency(it) })
            BestNodePriority.UPLOAD -> candidates
                .filter { it.uploadMbps > 0f }
                .maxWithOrNull(compareBy<Node> { it.uploadMbps }.thenBy { -safeLatency(it) })
            BestNodePriority.UNLOCK_COUNT -> candidates
                .maxWithOrNull(
                    compareBy<Node> { unlockPriorityScore(it, mode) }
                        .thenBy { extractUnlockYesCount(it.unlockSummary) }
                        .thenBy { it.unlockPassed.compareTo(false) }
                        .thenBy { if (mode?.bandwidthEnabled == true && mode.bandwidthDownloadEnabled) it.downloadMbps else 0f }
                        .thenBy { -safeLatency(it) }
                )
        }
    }

    private fun resolvePriorityOrder(
        mode: TestPreferMode?,
        preferredPriority: BestNodePriority?
    ): List<BestNodePriority> {
        val configured = mode?.normalizePriorityOrder()?.priorityOrder
            ?: BestNodePriority.entries.toList()
        val preferredFirst = if (preferredPriority == null) {
            configured
        } else {
            listOf(preferredPriority) + configured.filterNot { it == preferredPriority }
        }
        val active = if (mode == null) preferredFirst else preferredFirst.filter(mode::supportsPriority)
        return active.ifEmpty { listOf(preferredPriority ?: BestNodePriority.LATENCY) }
    }

    private fun pickBestNodeByOrder(
        candidates: List<Node>,
        priorityOrder: List<BestNodePriority>,
        mode: TestPreferMode?
    ): Node? = candidates.minWithOrNull(priorityOrderComparator(priorityOrder, mode))

    private fun sortNodesForSnapshotByOrder(
        nodes: List<Node>,
        priorityOrder: List<BestNodePriority>,
        mode: TestPreferMode?
    ): List<Node> = nodes.sortedWith(priorityOrderComparator(priorityOrder, mode))

    private fun priorityOrderComparator(
        priorityOrder: List<BestNodePriority>,
        mode: TestPreferMode?
    ): Comparator<Node> = Comparator { left, right ->
        priorityOrder.forEach { priority ->
            val comparison = compareNodeMetric(left, right, priority, mode)
            if (comparison != 0) return@Comparator comparison
        }
        val nameComparison = left.getDisplayName().compareTo(right.getDisplayName(), ignoreCase = true)
        if (nameComparison != 0) nameComparison else left.id.compareTo(right.id)
    }

    private fun compareNodeMetric(
        left: Node,
        right: Node,
        priority: BestNodePriority,
        mode: TestPreferMode?
    ): Int = when (priority) {
        BestNodePriority.LATENCY -> {
            val leftValue = if (left.latency > 0) left.latency else Int.MAX_VALUE
            val rightValue = if (right.latency > 0) right.latency else Int.MAX_VALUE
            leftValue.compareTo(rightValue)
        }
        BestNodePriority.UPLOAD -> right.uploadMbps.compareTo(left.uploadMbps)
        BestNodePriority.DOWNLOAD -> right.downloadMbps.compareTo(left.downloadMbps)
        BestNodePriority.UNLOCK_COUNT -> {
            val scoreComparison = unlockPriorityScore(right, mode).compareTo(unlockPriorityScore(left, mode))
            if (scoreComparison != 0) {
                scoreComparison
            } else {
                extractUnlockYesCount(right.unlockSummary).compareTo(extractUnlockYesCount(left.unlockSummary))
            }
        }
    }

    private fun sortNodesForSnapshot(
        nodes: List<Node>,
        priority: BestNodePriority,
        mode: TestPreferMode?
    ): List<Node> {
        return when (priority) {
            BestNodePriority.LATENCY -> nodes.sortedWith(
                compareBy<Node> { if (it.latency > 0) it.latency else Int.MAX_VALUE }
                    .thenByDescending { it.downloadMbps }
                    .thenByDescending { it.uploadMbps }
            )

            BestNodePriority.DOWNLOAD -> nodes.sortedWith(
                compareByDescending<Node> { it.downloadMbps }
                    .thenBy { safeLatency(it) }
            )

            BestNodePriority.UPLOAD -> nodes.sortedWith(
                compareByDescending<Node> { it.uploadMbps }
                    .thenBy { safeLatency(it) }
            )

            BestNodePriority.UNLOCK_COUNT -> nodes.sortedWith(
                compareByDescending<Node> { unlockPriorityScore(it, mode) }
                    .thenByDescending { extractUnlockYesCount(it.unlockSummary) }
                    .thenByDescending { it.unlockPassed }
                    .thenByDescending { if (mode?.bandwidthEnabled == true && mode.bandwidthDownloadEnabled) it.downloadMbps else 0f }
                    .thenBy { safeLatency(it) }
            )
        }
    }

    private fun unlockPriorityScore(node: Node, mode: TestPreferMode?): Int {
        if (mode == null) return extractUnlockYesCount(node.unlockSummary)
        return when (mode.unlockPriorityMode) {
            UnlockPriorityMode.COUNT -> extractUnlockYesCount(node.unlockSummary)
            UnlockPriorityMode.TARGET_SITES -> {
                if (mode.unlockPriorityTargetSiteIds.isEmpty()) {
                    extractUnlockYesCount(node.unlockSummary)
                } else {
                    countMatchedUnlockTargetSites(node.unlockSummary, mode.unlockPriorityTargetSiteIds)
                }
            }
        }
    }

    private fun countMatchedUnlockTargetSites(summary: String, targetSiteIds: List<String>): Int {
        val positiveLines = summary.lineSequence()
            .map { cleanLogLine(it) }
            .filter { it.isNotBlank() }
            .filter { line ->
                (line.contains("YES", true) || line.contains("Available", true)) &&
                    !line.contains(Regex("""\bNO\b""", RegexOption.IGNORE_CASE))
            }
            .toList()
        if (positiveLines.isEmpty()) return 0
        val presets = AppConfig.UNLOCK_PRIORITY_PRESET_SITES.associateBy { it.id }
        return targetSiteIds.distinct().count { siteId ->
            val site = presets[siteId] ?: return@count false
            positiveLines.any { line -> unlockSiteMatches(line, site) }
        }
    }

    private fun unlockSiteMatches(line: String, site: UnlockPrioritySite): Boolean {
        return site.keywords.any { keyword -> line.contains(keyword, ignoreCase = true) }
    }

    private fun safeLatency(node: Node): Int = if (node.latency > 0) node.latency else Int.MAX_VALUE

    private fun extractUnlockYesCount(summary: String): Int {
        val match = Regex("""\bYES\s*=\s*(\d+)""", RegexOption.IGNORE_CASE).find(summary)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: if (summary.contains("YES", ignoreCase = true) || summary.contains("Available", ignoreCase = true)) 1 else 0
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

    private suspend fun migrateLegacyRawLinksToSqlCipher() {
        runCatching {
            val current = nodeDao.getAllNodes().first()
            var migrated = 0
            var failed = 0
            current.forEach { node ->
                if (node.isRawLinkEncrypted()) {
                    val plaintext = LegacyRawLinkMigrationCrypto.decryptOrNull(node.rawLink)
                    if (plaintext != null) {
                        nodeDao.updateNode(node.copy(rawLink = plaintext))
                        migrated++
                    } else {
                        failed++
                    }
                }
            }
            Log.i(tag, "Legacy rawLink migration completed: migrated=$migrated failed=$failed")
        }.onFailure {
            Log.w(tag, "Legacy rawLink migration skipped: ${it.message}")
        }
    }
}
