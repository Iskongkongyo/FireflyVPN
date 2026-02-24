package xyz.a202132.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import xyz.a202132.app.data.model.ProxyMode
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.VpnState
import xyz.a202132.app.ui.components.*
import xyz.a202132.app.ui.dialogs.*
import xyz.a202132.app.ui.dialogs.AutoTestDetailDialog
import xyz.a202132.app.ui.dialogs.AutoTestResultDialog
import xyz.a202132.app.ui.dialogs.SpeedTestDialog
import xyz.a202132.app.ui.dialogs.UnlockTestDialog
import xyz.a202132.app.ui.theme.*
import xyz.a202132.app.viewmodel.AutoTestStage
import xyz.a202132.app.viewmodel.BestNodePriority
import xyz.a202132.app.viewmodel.MainViewModel
import xyz.a202132.app.viewmodel.TestPreferMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel(),
    onStartVpn: (action: () -> Unit) -> Unit,
    onOpenPerAppProxy: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    
    // Collect states
    val nodes by viewModel.nodes.collectAsState()
    val currentNode by viewModel.currentNode.collectAsState()
    val selectedNodeId by viewModel.selectedNodeId.collectAsState()
    val proxyMode by viewModel.proxyMode.collectAsState()
    val vpnState by viewModel.vpnState.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isTesting by viewModel.isTesting.collectAsState()
    val showNodeList by viewModel.showNodeList.collectAsState()
    val testingLabel by viewModel.testingLabel.collectAsState()
    val filterUnavailable by viewModel.filterUnavailable.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val noticeConfig by viewModel.noticeConfig.collectAsState()
    val updateInfo by viewModel.updateInfo.collectAsState()
    val error by viewModel.error.collectAsState()
    val infoDialogMessage by viewModel.infoDialogMessage.collectAsState()
    val isAutoSelecting by viewModel.isAutoSelecting.collectAsState()
    val isUserAgreementAccepted by viewModel.isUserAgreementAccepted.collectAsState()
    
    // UI States
    var showSpeedTestDialog by remember { mutableStateOf(false) }
    var showUnlockTestDialog by remember { mutableStateOf(false) }
    var showNetworkToolboxDialog by remember { mutableStateOf(false) }
    var showAutoTestResultDialog by remember { mutableStateOf(false) }
    var nodeAutoTestDetail by remember { mutableStateOf<Node?>(null) }
    var autoTestWasRunning by remember { mutableStateOf(false) }
    var showQuickModePicker by remember { mutableStateOf(false) }
    var showTestPreferPanelPage by remember { mutableStateOf(false) }
    
    // 流量统计
    val uploadSpeed by viewModel.uploadSpeed.collectAsState()
    val downloadSpeed by viewModel.downloadSpeed.collectAsState()
    val uploadTotal by viewModel.uploadTotal.collectAsState()
    val downloadTotal by viewModel.downloadTotal.collectAsState()
    
    // 绕过局域网设置
    val bypassLan by viewModel.bypassLan.collectAsState()
    
    // IPv6 路由设置
    val ipv6RoutingMode by viewModel.ipv6RoutingMode.collectAsState()
    
    // 备用节点设置
    val backupNodeEnabled by viewModel.backupNodeEnabled.collectAsState()

    // 自动化测试设置/状态
    val autoTestEnabled by viewModel.autoTestEnabled.collectAsState()
    val autoTestFilterUnavailable by viewModel.autoTestFilterUnavailable.collectAsState()
    val autoTestLatencyEnabled by viewModel.autoTestLatencyEnabled.collectAsState()
    val autoTestLatencyMode by viewModel.autoTestLatencyMode.collectAsState()
    val autoTestLatencyThresholdMs by viewModel.autoTestLatencyThresholdMs.collectAsState()
    val autoTestBandwidthEnabled by viewModel.autoTestBandwidthEnabled.collectAsState()
    val autoTestBandwidthDownloadEnabled by viewModel.autoTestBandwidthDownloadEnabled.collectAsState()
    val autoTestBandwidthUploadEnabled by viewModel.autoTestBandwidthUploadEnabled.collectAsState()
    val autoTestBandwidthDownloadThresholdMbps by viewModel.autoTestBandwidthDownloadThresholdMbps.collectAsState()
    val autoTestBandwidthUploadThresholdMbps by viewModel.autoTestBandwidthUploadThresholdMbps.collectAsState()
    val autoTestBandwidthWifiOnly by viewModel.autoTestBandwidthWifiOnly.collectAsState()
    val autoTestBandwidthDownloadSizeMb by viewModel.autoTestBandwidthDownloadSizeMb.collectAsState()
    val autoTestBandwidthUploadSizeMb by viewModel.autoTestBandwidthUploadSizeMb.collectAsState()
    val autoTestUnlockEnabled by viewModel.autoTestUnlockEnabled.collectAsState()
    val autoTestByRegion by viewModel.autoTestByRegion.collectAsState()
    val autoTestNodeLimit by viewModel.autoTestNodeLimit.collectAsState()
    val autoTestProgress by viewModel.autoTestProgress.collectAsState()
    val preferTestModes by viewModel.preferTestModes.collectAsState()
    val preferTestSelectedModeId by viewModel.preferTestSelectedModeId.collectAsState()
    // val showBackupFailedDialog by viewModel.showBackupFailedDialog.collectAsState() // Removed
    
    // Show error toast
    LaunchedEffect(error) {
        error?.let { errorMessage ->
            Toast.makeText(context, errorMessage, Toast.LENGTH_SHORT).show()
            viewModel.clearError()
        }
    }
    
    // 移动网络提醒
    val cellularWarning by xyz.a202132.app.service.ServiceManager.cellularWarning.collectAsState()
    LaunchedEffect(cellularWarning) {
        cellularWarning?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            xyz.a202132.app.service.ServiceManager.clearCellularWarning()
        }
    }

    LaunchedEffect(autoTestProgress.running, autoTestProgress.stage) {
        if (autoTestWasRunning && !autoTestProgress.running && autoTestProgress.stage == AutoTestStage.DONE) {
            showAutoTestResultDialog = true
        }
        autoTestWasRunning = autoTestProgress.running
    }

    // Drawer
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surfaceVariant
            ) {
                DrawerContent(
                    onCheckUpdate = { viewModel.checkUpdate() },
                    onOpenPerAppProxy = onOpenPerAppProxy,
                    onOpenTestPreferPanel = { showTestPreferPanelPage = true },
                    bypassLan = bypassLan,
                    onToggleBypassLan = { viewModel.setBypassLan(it) },
                    ipv6RoutingMode = ipv6RoutingMode,
                    onIPv6RoutingModeChange = { viewModel.setIPv6RoutingMode(it) },
                    notice = noticeConfig, // Use noticeConfig for Drawer (Backup Node visibility)
                    backupNodeEnabled = backupNodeEnabled,
                    onToggleBackupNode = { viewModel.setBackupNodeEnabled(it) },
                    autoTestEnabled = autoTestEnabled,
                    autoTestFilterUnavailable = autoTestFilterUnavailable,
                    autoTestLatencyEnabled = autoTestLatencyEnabled,
                    autoTestLatencyMode = autoTestLatencyMode,
                    autoTestLatencyThresholdMs = autoTestLatencyThresholdMs,
                    autoTestBandwidthEnabled = autoTestBandwidthEnabled,
                    autoTestBandwidthDownloadEnabled = autoTestBandwidthDownloadEnabled,
                    autoTestBandwidthUploadEnabled = autoTestBandwidthUploadEnabled,
                    autoTestBandwidthDownloadThresholdMbps = autoTestBandwidthDownloadThresholdMbps,
                    autoTestBandwidthUploadThresholdMbps = autoTestBandwidthUploadThresholdMbps,
                    autoTestBandwidthWifiOnly = autoTestBandwidthWifiOnly,
                    autoTestBandwidthDownloadSizeMb = autoTestBandwidthDownloadSizeMb,
                    autoTestBandwidthUploadSizeMb = autoTestBandwidthUploadSizeMb,
                    autoTestUnlockEnabled = autoTestUnlockEnabled,
                    autoTestByRegion = autoTestByRegion,
                    autoTestNodeLimit = autoTestNodeLimit,
                    autoTestProgress = autoTestProgress,
                    preferTestModes = preferTestModes,
                    preferTestSelectedModeId = preferTestSelectedModeId,
                    onSetAutoTestEnabled = { viewModel.setAutoTestEnabled(it) },
                    onSetAutoTestFilterUnavailable = { viewModel.setAutoTestFilterUnavailable(it) },
                    onSetAutoTestLatencyEnabled = { viewModel.setAutoTestLatencyEnabled(it) },
                    onSetAutoTestLatencyMode = { viewModel.setAutoTestLatencyMode(it) },
                    onSetAutoTestLatencyThresholdMs = { viewModel.setAutoTestLatencyThresholdMs(it) },
                    onSetAutoTestBandwidthEnabled = { viewModel.setAutoTestBandwidthEnabled(it) },
                    onSetAutoTestBandwidthDownloadEnabled = { viewModel.setAutoTestBandwidthDownloadEnabled(it) },
                    onSetAutoTestBandwidthUploadEnabled = { viewModel.setAutoTestBandwidthUploadEnabled(it) },
                    onSetAutoTestBandwidthDownloadThresholdMbps = { viewModel.setAutoTestBandwidthDownloadThresholdMbps(it) },
                    onSetAutoTestBandwidthUploadThresholdMbps = { viewModel.setAutoTestBandwidthUploadThresholdMbps(it) },
                    onSetAutoTestBandwidthWifiOnly = { viewModel.setAutoTestBandwidthWifiOnly(it) },
                    onSetAutoTestBandwidthDownloadSizeMb = { viewModel.setAutoTestBandwidthDownloadSizeMb(it) },
                    onSetAutoTestBandwidthUploadSizeMb = { viewModel.setAutoTestBandwidthUploadSizeMb(it) },
                    onSetAutoTestUnlockEnabled = { viewModel.setAutoTestUnlockEnabled(it) },
                    onSetAutoTestByRegion = { viewModel.setAutoTestByRegion(it) },
                    onSetAutoTestNodeLimit = { viewModel.setAutoTestNodeLimit(it) },
                    onApplyPreferTestMode = { viewModel.applyPreferTestMode(it) },
                    onSaveCurrentPreferTestMode = { viewModel.saveCurrentPreferTestMode(it) },
                    onDeleteCurrentPreferTestMode = { viewModel.deleteCurrentPreferTestMode() },
                    onHideUnqualifiedAutoTestNodes = { viewModel.hideUnqualifiedAutoTestNodes() },
                    onSelectBestNodeByPriority = { priority, connect -> viewModel.selectBestNodeByPriority(priority, connect) },
                    onUpdateCurrentPreferModePriority = { viewModel.updateCurrentPreferModePriority(it) },
                    onStartAutomatedTest = { viewModel.startAutomatedTest() },
                    onCancelAutomatedTest = { viewModel.cancelAutomatedTest() },
                    onClose = { scope.launch { drawerState.close() } }
                )
            }
        },
        gesturesEnabled = drawerState.isOpen
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "流萤加速器",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    },
                    actions = {
                        // 工具菜单按钮
                        var showToolsMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(
                                onClick = { showToolsMenu = true }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "工具",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            DropdownMenu(
                                expanded = showToolsMenu,
                                onDismissRequest = { showToolsMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("⚡ 网速测试") },
                                    onClick = {
                                        showToolsMenu = false
                                        showSpeedTestDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("\uD83D\uDD0C TCPing") },
                                    onClick = {
                                        showToolsMenu = false
                                        viewModel.showNodeListForTest("tcping")
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("\uD83C\uDF10 URL Test") },
                                    onClick = {
                                        showToolsMenu = false
                                        viewModel.showNodeListForTest("urltest")
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("\uD83E\uDDF0 网络工具箱") },
                                    onClick = {
                                        showToolsMenu = false
                                        showNetworkToolboxDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("\uD83E\uDDF9 隐藏超时节点") },
                                    onClick = {
                                        showToolsMenu = false
                                        viewModel.cleanUnavailableNodes()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("\uD83C\uDFAC 流媒体解锁测试") },
                                    onClick = {
                                        showToolsMenu = false
                                        showUnlockTestDialog = true
                                    }
                                )

                            }
                        }
                        
                        // 设置按钮
                        IconButton(
                            onClick = { scope.launch { drawerState.open() } }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "设置",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(32.dp))
                
                // 当前节点信息
                if (currentNode != null) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "当前节点",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = currentNode!!.getFlagEmoji(),
                                fontSize = 24.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = currentNode!!.getDisplayName(),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        if (currentNode!!.latency > 0) {
                            LatencyBadge(node = currentNode!!)
                        }

                        if (currentNode!!.downloadMbps > 0f) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "带宽: %.1f Mbps".format(currentNode!!.downloadMbps),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    Text(
                        text = if (isLoading) "正在获取节点..." else "请选择节点",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Spacer(modifier = Modifier.weight(1f))
                
                // 连接按钮
                ConnectButton(
                    vpnState = vpnState,
                    onClick = {
                        if (currentNode == null) {
                            showQuickModePicker = true
                        } else {
                            // 手动连接 (需授权)
                            onStartVpn {
                                viewModel.toggleVpn()
                            }
                        }
                    },
                    customLabel = if (currentNode == null && vpnState == VpnState.DISCONNECTED) "点击选择测试模式后自动择优连接" else null
                )
                
                // 流量统计 (仅在连接时显示)
                if (vpnState == VpnState.CONNECTED) {
                    Spacer(modifier = Modifier.height(16.dp))
                    TrafficStatsRow(
                        uploadSpeed = uploadSpeed,
                        downloadSpeed = downloadSpeed,
                        uploadTotal = uploadTotal,
                        downloadTotal = downloadTotal
                    )
                }
                
                Spacer(modifier = Modifier.weight(1f))
                
                // 节点选择器
                NodeSelector(
                    currentNode = currentNode,
                    onClick = { viewModel.showNodeList() }
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 代理模式选择
                ProxyModeSelector(
                    isGlobalMode = proxyMode == ProxyMode.GLOBAL,
                    onModeChange = { isGlobal ->
                        viewModel.setProxyMode(if (isGlobal) ProxyMode.GLOBAL else ProxyMode.SMART)
                    }
                )
                
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
    
    // 节点列表弹窗
    if (showNodeList) {
        NodeListDialog(
            nodes = nodes,
            selectedNodeId = selectedNodeId,
            isTesting = isTesting,
            testingLabel = testingLabel,
            onNodeSelected = { node -> viewModel.selectNode(node) },
            onRefresh = { viewModel.fetchNodes() },
            onDismiss = { viewModel.hideNodeList() }
        )
    }

    if (showAutoTestResultDialog) {
        val qualifiedNodes = nodes.filter { it.autoTestedAt > 0 && it.isAvailable }
        val currentPreferMode = preferTestModes.firstOrNull { it.id == preferTestSelectedModeId }
        val currentPriority = currentPreferMode?.defaultPriority ?: BestNodePriority.LATENCY
        AutoTestResultDialog(
            nodes = qualifiedNodes,
            onDismiss = { showAutoTestResultDialog = false },
            onNodeClick = { node -> nodeAutoTestDetail = node },
            onAutoConnectBest = {
                onStartVpn {
                    viewModel.selectBestNodeByPriority(currentPriority, true)
                }
            }
        )
    }

    infoDialogMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.clearInfoDialogMessage() },
            title = { Text("提示") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearInfoDialogMessage() }) {
                    Text("知道了")
                }
            }
        )
    }

    if (showQuickModePicker) {
        QuickModePickerDialog(
            modes = preferTestModes,
            selectedModeId = preferTestSelectedModeId,
            onDismiss = { showQuickModePicker = false },
            onConfirm = { modeId ->
                showQuickModePicker = false
                onStartVpn { viewModel.startPreferModeAutoSelectAndConnect(modeId) }
            }
        )
    }

    if (showTestPreferPanelPage) {
        TestPreferPanelDialog(
            autoTestEnabled = autoTestEnabled,
            autoTestFilterUnavailable = autoTestFilterUnavailable,
            autoTestLatencyEnabled = autoTestLatencyEnabled,
            autoTestLatencyMode = autoTestLatencyMode,
            autoTestLatencyThresholdMs = autoTestLatencyThresholdMs,
            autoTestBandwidthEnabled = autoTestBandwidthEnabled,
            autoTestBandwidthDownloadEnabled = autoTestBandwidthDownloadEnabled,
            autoTestBandwidthUploadEnabled = autoTestBandwidthUploadEnabled,
            autoTestBandwidthDownloadThresholdMbps = autoTestBandwidthDownloadThresholdMbps,
            autoTestBandwidthUploadThresholdMbps = autoTestBandwidthUploadThresholdMbps,
            autoTestBandwidthWifiOnly = autoTestBandwidthWifiOnly,
            autoTestBandwidthDownloadSizeMb = autoTestBandwidthDownloadSizeMb,
            autoTestBandwidthUploadSizeMb = autoTestBandwidthUploadSizeMb,
            autoTestUnlockEnabled = autoTestUnlockEnabled,
            autoTestByRegion = autoTestByRegion,
            autoTestNodeLimit = autoTestNodeLimit,
            autoTestProgress = autoTestProgress,
            preferTestModes = preferTestModes,
            preferTestSelectedModeId = preferTestSelectedModeId,
            onSetAutoTestEnabled = { viewModel.setAutoTestEnabled(it) },
            onSetAutoTestFilterUnavailable = { viewModel.setAutoTestFilterUnavailable(it) },
            onSetAutoTestLatencyEnabled = { viewModel.setAutoTestLatencyEnabled(it) },
            onSetAutoTestLatencyMode = { viewModel.setAutoTestLatencyMode(it) },
            onSetAutoTestLatencyThresholdMs = { viewModel.setAutoTestLatencyThresholdMs(it) },
            onSetAutoTestBandwidthEnabled = { viewModel.setAutoTestBandwidthEnabled(it) },
            onSetAutoTestBandwidthDownloadEnabled = { viewModel.setAutoTestBandwidthDownloadEnabled(it) },
            onSetAutoTestBandwidthUploadEnabled = { viewModel.setAutoTestBandwidthUploadEnabled(it) },
            onSetAutoTestBandwidthDownloadThresholdMbps = { viewModel.setAutoTestBandwidthDownloadThresholdMbps(it) },
            onSetAutoTestBandwidthUploadThresholdMbps = { viewModel.setAutoTestBandwidthUploadThresholdMbps(it) },
            onSetAutoTestBandwidthWifiOnly = { viewModel.setAutoTestBandwidthWifiOnly(it) },
            onSetAutoTestBandwidthDownloadSizeMb = { viewModel.setAutoTestBandwidthDownloadSizeMb(it) },
            onSetAutoTestBandwidthUploadSizeMb = { viewModel.setAutoTestBandwidthUploadSizeMb(it) },
            onSetAutoTestUnlockEnabled = { viewModel.setAutoTestUnlockEnabled(it) },
            onSetAutoTestByRegion = { viewModel.setAutoTestByRegion(it) },
            onSetAutoTestNodeLimit = { viewModel.setAutoTestNodeLimit(it) },
            onApplyPreferTestMode = { viewModel.applyPreferTestMode(it) },
            onCreatePreferTestMode = { viewModel.createPreferTestModeFromCurrent() },
            onSaveCurrentPreferTestMode = { viewModel.saveCurrentPreferTestMode(it) },
            onDeleteCurrentPreferTestMode = { viewModel.deleteCurrentPreferTestMode() },
            onHideUnqualifiedAutoTestNodes = { viewModel.hideUnqualifiedAutoTestNodes() },
            onSelectBestNodeByPriority = { priority, connect ->
                if (connect) {
                    onStartVpn { viewModel.selectBestNodeByPriority(priority, true) }
                } else {
                    viewModel.selectBestNodeByPriority(priority, false)
                }
            },
            onUpdateCurrentPreferModePriority = { viewModel.updateCurrentPreferModePriority(it) },
            onUpdateCurrentPreferModeUnlockPriority = { mode, siteIds ->
                viewModel.updateCurrentPreferModeUnlockPriority(mode, siteIds)
            },
            onStartAutomatedTest = { onStartVpn { viewModel.startAutomatedTest() } },
            onCancelAutomatedTest = { viewModel.cancelAutomatedTest() },
            onDismiss = { showTestPreferPanelPage = false }
        )
    }

    nodeAutoTestDetail?.let { node ->
        AutoTestDetailDialog(
            node = node,
            onDismiss = { nodeAutoTestDetail = null },
            onUseNode = {
                viewModel.selectNode(node)
                nodeAutoTestDetail = null
            }
        )
    }
    
    // 通知弹窗
    notice?.let { noticeInfo ->
        NoticeDialog(
            notice = noticeInfo,
            onDismiss = { viewModel.dismissNotice() }
        )
    }
    
    // 备用节点请求失败弹窗 (已移除，改为 Toast)
    
    // 更新弹窗
    if (updateInfo != null) {
        UpdateDialog(
            version = updateInfo?.version ?: "",
            changelog = updateInfo?.changelog ?: "",
            isForce = updateInfo?.isForce == 1,
            onUpdate = { viewModel.openDownloadUrl() },
            onDismiss = { viewModel.dismissUpdate() }
        )
    }
    
    // 网速测试弹窗
    if (showSpeedTestDialog) {
        SpeedTestDialog(onDismiss = { showSpeedTestDialog = false })
    }

    if (showUnlockTestDialog) {
        UnlockTestDialog(onDismiss = { showUnlockTestDialog = false })
    }

    if (showNetworkToolboxDialog) {
        NetworkToolboxDialog(onDismiss = { showNetworkToolboxDialog = false })
    }
    
    // 加载弹窗
    if (isLoading && nodes.isEmpty()) {
        LoadingDialog(message = "获取节点中...")
    }
    
    // 自动选择弹窗 (Blocking)
    if (isAutoSelecting) {
        LoadingDialog(
            message = buildString {
                append("正在选择最优节点中...")
                if (autoTestProgress.running) {
                    val detail = autoTestProgress.message.ifBlank { autoTestProgress.stage.name }
                    if (detail.isNotBlank()) {
                        append("\n")
                        append(detail)
                    }
                }
            }
        )
    }
    
    // 下载状态监听
    val downloadState by viewModel.downloadState.collectAsState()
    var showPermissionDialog by remember { mutableStateOf(false) }
    
    val installLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        // 从设置页返回后再次尝试安装
        if (context.packageManager.canRequestPackageInstalls()) {
            viewModel.installApk()
        }
    }
    
    // 下载完成后自动安装
    LaunchedEffect(downloadState.status) {
        if (downloadState.status == xyz.a202132.app.network.DownloadStatus.COMPLETED) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    showPermissionDialog = true
                } else {
                    viewModel.installApk()
                }
            } else {
                viewModel.installApk()
            }
        }
    }
    
    if (showPermissionDialog) {
        PermissionDialog(
            onDismiss = { showPermissionDialog = false },
            onOpenSettings = {
                showPermissionDialog = false
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    intent.data = android.net.Uri.parse("package:${context.packageName}")
                    installLauncher.launch(intent)
                }
            }
        )
    }
    
    // 下载弹窗
    if (downloadState.status == xyz.a202132.app.network.DownloadStatus.DOWNLOADING || 
        downloadState.status == xyz.a202132.app.network.DownloadStatus.PAUSED ||
        downloadState.status == xyz.a202132.app.network.DownloadStatus.ERROR) {
        DownloadDialog(
            progress = downloadState.progress,
            speed = downloadState.speed,
            status = downloadState.status,
            errorMessage = downloadState.error,
            onPause = { viewModel.pauseDownload() },
            onResume = { viewModel.resumeDownload() },
            onCancel = { viewModel.cancelDownload() },
            onRetry = { viewModel.retryDownload() }
        )
    }
    
    // 连续下载失败后提示从官网下载
    var showWebsiteFallback by remember { mutableStateOf(false) }
    val websiteUrl = xyz.a202132.app.AppConfig.WEBSITE_URL
    
    LaunchedEffect(downloadState.consecutiveFailures) {
        if (downloadState.consecutiveFailures >= 3 && websiteUrl.isNotBlank()) {
            showWebsiteFallback = true
        }
    }
    
    if (showWebsiteFallback) {
        Dialog(onDismissRequest = { showWebsiteFallback = false }) {
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "下载失败",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = "APP已连续 ${downloadState.consecutiveFailures} 次下载失败，是否选择从官网下载？",
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 22.sp
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showWebsiteFallback = false },
                            modifier = Modifier.weight(1f),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                        ) {
                            Text("继续重试")
                        }
                        
                        Button(
                            onClick = {
                                showWebsiteFallback = false
                                viewModel.cancelDownload()
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(websiteUrl))
                                context.startActivity(intent)
                            },
                            modifier = Modifier.weight(1f),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Primary)
                        ) {
                            Text("去官网")
                        }
                    }
                }
            }
        }
    }
    
    // 用户协议弹窗 (仅在未同意时显示)
    if (!isUserAgreementAccepted) {
        UserAgreementDialog(
            onAgree = { viewModel.acceptUserAgreement() },
            onDisagree = { (context as? android.app.Activity)?.finish() }
        )
    }
}

@Composable
private fun QuickModePickerDialog(
    modes: List<TestPreferMode>,
    selectedModeId: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var localSelectedId by remember(selectedModeId, modes) {
        mutableStateOf(
            modes.firstOrNull { it.id == selectedModeId }?.id
                ?: modes.firstOrNull()?.id
                ?: ""
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择测试模式") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "执行后会按该模式测试并自动选择最优节点连接",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                modes.forEach { mode ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { localSelectedId = mode.id },
                        shape = MaterialTheme.shapes.medium,
                        color = if (localSelectedId == mode.id) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = mode.name,
                                color = if (localSelectedId == mode.id) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                            if (mode.builtIn) {
                                Text(
                                    text = "内置",
                                    fontSize = 11.sp,
                                    color = if (localSelectedId == mode.id) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (localSelectedId.isNotBlank()) onConfirm(localSelectedId) },
                enabled = localSelectedId.isNotBlank()
            ) {
                Text("开始")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
