package xyz.a202132.app.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import xyz.a202132.app.AppConfig
import xyz.a202132.app.data.local.AppDatabase
import xyz.a202132.app.data.model.*
import xyz.a202132.app.data.repository.SettingsRepository
import xyz.a202132.app.network.LatencyTester
import xyz.a202132.app.network.NetworkClient
import xyz.a202132.app.network.SubscriptionParser
import xyz.a202132.app.network.DownloadManager
import xyz.a202132.app.service.BoxVpnService
import xyz.a202132.app.service.ServiceManager

class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    private val tag = "MainViewModel"
    private val database = AppDatabase.getInstance(application)
    private val nodeDao = database.nodeDao()
    private val settingsRepository = SettingsRepository(application)
    private val subscriptionParser = SubscriptionParser()
    private val latencyTester = LatencyTester()
    
    // UI State
    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()
    
    private val _isTesting = MutableStateFlow(false)
    val isTesting = _isTesting.asStateFlow()
    
    private val _showNodeList = MutableStateFlow(false)
    val showNodeList = _showNodeList.asStateFlow()
    
    private val _notice = MutableStateFlow<NoticeInfo?>(null)
    val notice = _notice.asStateFlow()
    
    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo = _updateInfo.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)

    val error = _error.asStateFlow()
    
    // Blocking Auto-Select State
    private val _isAutoSelecting = MutableStateFlow(false)
    val isAutoSelecting = _isAutoSelecting.asStateFlow()
    
    // Data
    val nodes = nodeDao.getAllNodes()
        .map { list ->
            list.sortedWith(
                compareByDescending<Node> { it.isAvailable } // Available first
                    .thenBy { it.sortOrder } // Then by sort order
                    .thenBy { if (it.latency >= 0) it.latency else Int.MAX_VALUE } // Then by latency (untested last)
            )
        }
        .stateIn(
            viewModelScope,
            SharingStarted.Lazily,
            emptyList()
        )
    
    val selectedNodeId = settingsRepository.selectedNodeId.stateIn(
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
    
    val vpnState = ServiceManager.vpnState
    
    // 流量统计
    val uploadSpeed = ServiceManager.uploadSpeed
    val downloadSpeed = ServiceManager.downloadSpeed
    val uploadTotal = ServiceManager.uploadTotal
    val downloadTotal = ServiceManager.downloadTotal
    
    val currentNode = combine(nodes, selectedNodeId) { nodeList, selectedId ->
        nodeList.find { it.id == selectedId }
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)
    
    init {
        // 启动时加载数据
        viewModelScope.launch {
            // 每次启动重置选择状态 (不记住上次选择)
            settingsRepository.setSelectedNodeId(null)
            
            // 监听用户协议状态，只有同意后才初始化网络请求
            isUserAgreementAccepted.collect { accepted ->
                if (accepted) {
                    fetchNodes()
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
    fun fetchNodes() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = subscriptionParser.fetchAndParse()
                result.onSuccess { fetchedNodes ->
                    // 保存到数据库
                    nodeDao.deleteAllNodes()
                    nodeDao.insertNodes(fetchedNodes)
                    
                    // 自动测试延迟 (直接传入节点列表，避免等待 Flow 更新)
                    testAllNodes(fetchedNodes)
                    
                    Log.d(tag, "Fetched ${fetchedNodes.size} nodes")
                }.onFailure { e ->
                    Log.e(tag, "Failed to fetch nodes", e)
                    _error.value = "获取节点失败: ${e.message}"
                }
            } catch (e: Exception) {
                Log.e(tag, "Error fetching nodes", e)
                _error.value = "网络错误"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * 测试所有节点延迟
     * @param targetNodes 指定要测试的节点列表，若为null则使用当前显示的节点
     */
    /**
     * 测试所有节点延迟
     * @param targetNodes 指定要测试的节点列表，若为null则使用当前显示的节点
     */
    fun testAllNodes(targetNodes: List<Node>? = null) {
        viewModelScope.launch {
            _isTesting.value = true
            try {
                // 如果传入了节点就用传入的，否则从 StateFlow 获取
                val currentNodes = targetNodes ?: nodes.value
                internalTestNodes(currentNodes)
            } finally {
                _isTesting.value = false
            }
        }
    }

    /**
     * 内部测试逻辑 (Suspend)
     */
    private suspend fun internalTestNodes(currentNodes: List<Node>) {
        if (currentNodes.isEmpty()) return
        
        Log.d(tag, "Testing ${currentNodes.size} nodes")
        
        // 检查VPN服务是否运行
        if (ServiceManager.isServiceRunning.value) {
            Log.d(tag, "VPN is running, using libbox URLTest")
            try {
                Log.d(tag, "Waiting for libbox background test results...")
                // 这里不需要做什么，只需等待 BoxVpnService 更新数据库
            } catch (e: Exception) {
                Log.e(tag, "Error triggering libbox test", e)
            }
        } else {
            Log.d(tag, "VPN not running, using Socket test")
            // 使用 LatencyTester 进行测试
            val results = latencyTester.testAllNodes(currentNodes)
            Log.d(tag, "Got ${results.size} test results (Socket)")
            
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
    }

    /**
     * 开始自动选择并连接
     * (Blocking UI Flow)
     */
    fun startAutoSelectAndConnect() {
        viewModelScope.launch {
            if (_isAutoSelecting.value) return@launch
            
            _isAutoSelecting.value = true
            try {
                Log.i(tag, "Starting Auto-Select and Connect Flow")
                
                // 1. 获取所有节点
                val allNodes = nodes.value
                if (allNodes.isEmpty()) {
                    _error.value = "没有可用节点"
                    return@launch
                }
                
                // 2. 强制重新测试所有节点延迟 (使用 Socket 测试，因为 VPN 此时未连接)
                internalTestNodes(allNodes)
                
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
            if (noticeInfo.hasNotice) {
                // 检查是否已显示过
                val lastNoticeId = settingsRepository.lastNoticeId.first()
                if (lastNoticeId != noticeInfo.noticeId) {
                    _notice.value = noticeInfo
                    settingsRepository.setLastNoticeId(noticeInfo.noticeId)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to check notice", e)
        }
    }
    
    /**
     * 检查更新
     */
    /**
     * 检查更新
     * @param isAuto 是否为自动检查 (不显示"已是最新"提示)
     */
    fun checkUpdate(isAuto: Boolean = false) {
        viewModelScope.launch {
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
}
