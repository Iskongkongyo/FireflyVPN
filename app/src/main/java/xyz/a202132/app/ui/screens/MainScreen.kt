package xyz.a202132.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import xyz.a202132.app.ui.theme.*
import xyz.a202132.app.viewmodel.AutoTestStage
import xyz.a202132.app.viewmodel.BestNodePriority
import xyz.a202132.app.viewmodel.MainViewModel
import xyz.a202132.app.viewmodel.StartupDefaultTestMode
import xyz.a202132.app.viewmodel.TestPreferMode
import xyz.a202132.app.viewmodel.UnlockPriorityMode

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel(),
    onStartVpn: (action: () -> Unit) -> Unit,
    onOpenPerAppProxy: () -> Unit = {},
    onOpenSubscriptionManagement: () -> Unit = {},
    onOpenRuleManagement: () -> Unit = {},
    onOpenNodeList: () -> Unit = {},
    onOpenNetworkToolbox: () -> Unit = {},
    onOpenUnlockTest: () -> Unit = {},
    onOpenOtherConfig: () -> Unit = {},
    onOpenLanProxy: () -> Unit = {},
    onOpenRuntimeLog: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    
    // Collect状态
    val currentNode by viewModel.currentNode.collectAsState()
    val proxyMode by viewModel.proxyMode.collectAsState()
    val vpnState by viewModel.vpnState.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val updateInfo by viewModel.updateInfo.collectAsState()
    val remoteAppSettings by viewModel.remoteAppSettings.collectAsState()
    val updateCheckAvailable by viewModel.updateCheckAvailable.collectAsState()
    val infoDialogMessage by viewModel.infoDialogMessage.collectAsState()
    val isAutoSelecting by viewModel.isAutoSelecting.collectAsState()
    val isUserAgreementAccepted by viewModel.isUserAgreementAccepted.collectAsState()
    val showStartupDefaultTestChoiceDialog by viewModel.showStartupDefaultTestChoiceDialog.collectAsState()
    
    // UI状态
    var showSpeedTestDialog by remember { mutableStateOf(false) }
    var showNodeIpInfoDialog by remember { mutableStateOf(false) }
    var nodeForIpInfo by remember { mutableStateOf<Node?>(null) }
    var showAutoTestResultDialog by remember { mutableStateOf(false) }
    var nodeAutoTestDetail by remember { mutableStateOf<Node?>(null) }
    var autoTestWasRunning by remember { mutableStateOf(false) }
    var showQuickModePicker by remember { mutableStateOf(false) }
    var showTestPreferPanelPage by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    
    // 流量统计
    val uploadSpeed by viewModel.uploadSpeed.collectAsState()
    val downloadSpeed by viewModel.downloadSpeed.collectAsState()
    val uploadTotal by viewModel.uploadTotal.collectAsState()
    val downloadTotal by viewModel.downloadTotal.collectAsState()
    
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
    val autoTestResultSnapshot by viewModel.autoTestResultSnapshot.collectAsState()
    val autoTestResultMode by viewModel.autoTestResultMode.collectAsState()
    val autoTestResultPriority by viewModel.autoTestResultPriority.collectAsState()
    val preferTestModes by viewModel.preferTestModes.collectAsState()
    val preferTestSelectedModeId by viewModel.preferTestSelectedModeId.collectAsState()
    val selectedNodeGroupId by viewModel.selectedNodeGroupId.collectAsState()
    val nodeGroups by viewModel.nodeGroups.collectAsState()
    val nodeIpInfoTestOnVpnStart by viewModel.nodeIpInfoTestOnVpnStart.collectAsState()
    val speedTestDownloadTimeoutMs by viewModel.speedTestDownloadTimeoutMs.collectAsState()

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

    var lastVpnState by remember { mutableStateOf(vpnState) }
    LaunchedEffect(vpnState, nodeIpInfoTestOnVpnStart) {
        val justConnected = lastVpnState != VpnState.CONNECTED && vpnState == VpnState.CONNECTED
        if (justConnected && nodeIpInfoTestOnVpnStart) {
            val selectedNode = currentNode
            if (selectedNode == null) {
                Toast.makeText(context, "当前无已选节点，无法执行节点IP信息测试", Toast.LENGTH_SHORT).show()
            } else {
                nodeForIpInfo = selectedNode
                showNodeIpInfoDialog = true
            }
        }
        lastVpnState = vpnState
    }

    // Drawer
    Box(modifier = Modifier.fillMaxSize()) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                windowInsets = WindowInsets(0, 0, 0, 0)
            ) {
                DrawerContent(
                    remoteAppSettings = remoteAppSettings,
                    onOpenAbout = { showAboutDialog = true },
                    onOpenPerAppProxy = onOpenPerAppProxy,
                    onOpenSubscriptionManagement = onOpenSubscriptionManagement,
                    onOpenRuleManagement = onOpenRuleManagement,
                    onOpenOtherConfig = onOpenOtherConfig,
                    onOpenLanProxy = onOpenLanProxy,
                    onOpenRuntimeLog = onOpenRuntimeLog,
                    onOpenTestPreferPanel = { showTestPreferPanelPage = true },
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
                    onSelectBestNodeByPriority = { priority, connect -> viewModel.selectBestNodeByPriorityFromSnapshot(priority, connect) },
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
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    modifier = Modifier.windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility),
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    title = {
                        Text(
                            text = "流萤加速器",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = { scope.launch { drawerState.open() } }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "菜单",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
                                    text = { Text("\uD83D\uDD0C TCPing") },
                                    onClick = {
                                        showToolsMenu = false
                                        if (viewModel.showNodeListForTest("tcping")) {
                                            onOpenNodeList()
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("\uD83C\uDF10 URL Test") },
                                    onClick = {
                                        showToolsMenu = false
                                        if (viewModel.showNodeListForTest("urltest")) {
                                            onOpenNodeList()
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("⚡ 网速测试") },
                                    onClick = {
                                        showToolsMenu = false
                                        showSpeedTestDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("\uD83D\uDCE1 节点IP信息") },
                                    onClick = {
                                        showToolsMenu = false
                                        val selectedNode = currentNode
                                        if (selectedNode == null) {
                                            Toast.makeText(context, "请先选择节点", Toast.LENGTH_SHORT).show()
                                        } else {
                                            nodeForIpInfo = selectedNode
                                            showNodeIpInfoDialog = true
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("\uD83E\uDDF0 网络工具箱") },
                                    onClick = {
                                        showToolsMenu = false
                                        onOpenNetworkToolbox()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("\uD83E\uDDF9 隐藏不合格节点") },
                                    onClick = {
                                        showToolsMenu = false
                                        viewModel.cleanUnavailableNodes()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("\uD83C\uDFAC 流媒体解锁测试") },
                                    onClick = {
                                        showToolsMenu = false
                                        onOpenUnlockTest()
                                    }
                                )

                            }
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
                    .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(32.dp))
                
                // 已选择节点信息
                if (currentNode != null) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            NodeIcon(
                                node = currentNode,
                                size = 28.dp,
                                flagFontSize = 24.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = currentNode!!.getDisplayName(),
                                modifier = Modifier.weight(1f, fill = false),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (currentNode!!.latency > 0) {
                                Spacer(modifier = Modifier.width(8.dp))
                                LatencyBadge(node = currentNode!!)
                            }
                        }

                        val bandwidthSummary = buildList {
                            if (currentNode!!.downloadMbps > 0f) {
                                add("下行 %.1f Mbps".format(currentNode!!.downloadMbps))
                            }
                            if (currentNode!!.uploadMbps > 0f) {
                                add("上行 %.1f Mbps".format(currentNode!!.uploadMbps))
                            }
                        }.joinToString(" · ")
                        if (bandwidthSummary.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = bandwidthSummary,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else if (isLoading) {
                    Text(
                        text = "节点正在后台更新...",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Spacer(modifier = Modifier.weight(1f))
                
                // 连接按钮
                ConnectButton(
                    vpnState = vpnState,
                    onClick = {
                        if (vpnState == VpnState.CONNECTED) {
                            viewModel.toggleVpn()
                        } else if (currentNode == null) {
                            showQuickModePicker = true
                        } else {
                            // 手动连接 (需授权)
                            onStartVpn {
                                viewModel.toggleVpn()
                            }
                        }
                    },
                    customLabel = if (currentNode == null && vpnState == VpnState.DISCONNECTED) "点击中间按钮进行自动择优连接" else null
                )
                
                Spacer(modifier = Modifier.weight(1f))

                // 与节点选择器组成固定间距区域，避免流量卡片随可用空间上下漂移。
                if (vpnState == VpnState.CONNECTED) {
                    TrafficStatsRow(
                        uploadSpeed = uploadSpeed,
                        downloadSpeed = downloadSpeed,
                        uploadTotal = uploadTotal,
                        downloadTotal = downloadTotal
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
                
                // 节点选择器
                NodeSelector(
                    currentNode = currentNode,
                    onClick = onOpenNodeList
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

    if (showAboutDialog) {
        AboutDialog(
            githubUrl = remoteAppSettings.githubUrl.trim(),
            updateCheckAvailable = updateCheckAvailable,
            onCheckUpdate = {
                showAboutDialog = false
                viewModel.checkUpdate()
                scope.launch { drawerState.close() }
            },
            onDismiss = { showAboutDialog = false }
        )
    }
    }
    
    // 节点列表弹窗
    if (showAutoTestResultDialog) {
        val qualifiedNodes = autoTestResultSnapshot
        val resultMode = autoTestResultMode
        val resultPriority = autoTestResultPriority
        val autoConnectLabel = "连接推荐节点（${priorityDisplayLabel(resultPriority, resultMode)}）"
        AutoTestResultDialog(
            nodes = qualifiedNodes,
            priority = resultPriority,
            onDismiss = { showAutoTestResultDialog = false },
            onNodeClick = { node -> nodeAutoTestDetail = node },
            autoConnectLabel = autoConnectLabel,
            onAutoConnectBest = {
                onStartVpn {
                    viewModel.selectBestNodeByPriorityFromSnapshot(resultPriority, true, resultMode)
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
            hasRecentAutoTestResults = !autoTestProgress.running &&
                autoTestProgress.stage == AutoTestStage.DONE &&
                autoTestResultSnapshot.isNotEmpty(),
            currentTestNodeGroupName = nodeGroups.firstOrNull { it.id == selectedNodeGroupId }?.name
                ?: "加载中…",
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
            onSaveCurrentPreferTestMode = { name, config ->
                viewModel.saveCurrentPreferTestMode(name, config)
            },
            onDeleteCurrentPreferTestMode = { viewModel.deleteCurrentPreferTestMode() },
            onUpdateCurrentPreferModePriorityOrder = { viewModel.updateCurrentPreferModePriorityOrder(it) },
            onUpdateCurrentPreferModeUnlockPriority = { mode, siteIds ->
                viewModel.updateCurrentPreferModeUnlockPriority(mode, siteIds)
            },
            onUpdateCurrentPreferModeAutoConnect = { viewModel.updateCurrentPreferModeAutoConnect(it) },
            onShowRecentAutoTestResults = { showAutoTestResultDialog = true },
            onStartAutomatedTest = { panelConfig, autoConnectBest, modeOverride ->
                val startTest = {
                    viewModel.startAutomatedTest(
                        preferPriority = if (autoConnectBest) {
                            modeOverride?.priorityOrder?.firstOrNull { priority ->
                                when (priority) {
                                    BestNodePriority.LATENCY -> modeOverride.latencyEnabled
                                    BestNodePriority.UPLOAD -> modeOverride.bandwidthEnabled && modeOverride.bandwidthUploadEnabled
                                    BestNodePriority.DOWNLOAD -> modeOverride.bandwidthEnabled && modeOverride.bandwidthDownloadEnabled
                                    BestNodePriority.UNLOCK_COUNT -> modeOverride.unlockEnabled
                                }
                            } ?: BestNodePriority.LATENCY
                        } else {
                            null
                        },
                        connectBestAfterDone = autoConnectBest,
                        configOverride = panelConfig,
                        modeOverride = modeOverride
                    )
                }
                if (autoConnectBest) {
                    onStartVpn(startTest)
                } else {
                    startTest()
                }
            },
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
    
    // 更新弹窗
    if (updateInfo != null) {
        UpdateDialog(
            version = updateInfo?.version ?: "",
            changelog = updateInfo?.changelog ?: "",
            isForce = updateInfo?.isForce == 1,
            onUpdate = { viewModel.openDownloadUrl() },
            onIgnore = { viewModel.ignoreCurrentUpdate() },
            onDismiss = { viewModel.dismissUpdate() }
        )
    }
    
    // 网速测试弹窗
    if (showSpeedTestDialog) {
        SpeedTestDialog(
            node = currentNode,
            useNodeBandwidth = currentNode != null && vpnState == VpnState.CONNECTED,
            downloadTimeoutMs = speedTestDownloadTimeoutMs,
            onDismiss = { showSpeedTestDialog = false }
        )
    }

    if (showNodeIpInfoDialog) {
        nodeForIpInfo?.let { node ->
            NodeIpInfoDialog(
                node = node,
                onDismiss = {
                    showNodeIpInfoDialog = false
                    nodeForIpInfo = null
                },
                fetchIpInfo = { targetNode -> viewModel.fetchNodeIpInfo(targetNode) }
            )
        }
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
        if (
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()
        ) {
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
    val websiteUrl = remoteAppSettings.websiteUrl.trim()
    
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

    if (showStartupDefaultTestChoiceDialog && isUserAgreementAccepted) {
        StartupDefaultTestChoiceDialog(
            onDismiss = { viewModel.dismissStartupDefaultTestChoiceDialog() },
            onSelectTcping = {
                viewModel.confirmStartupDefaultTestChoice(StartupDefaultTestMode.TCPING)
                Toast.makeText(context, "已设置APP启动默认执行TCPing测试", Toast.LENGTH_SHORT).show()
            },
            onSelectUrlTest = {
                viewModel.confirmStartupDefaultTestChoice(StartupDefaultTestMode.URL_TEST)
                Toast.makeText(context, "已设置APP启动默认执行URL Test测试", Toast.LENGTH_SHORT).show()
            },
            onSkip = {
                viewModel.confirmStartupDefaultTestChoice(StartupDefaultTestMode.NONE)
                Toast.makeText(context, "已跳过启动默认测试设置", Toast.LENGTH_SHORT).show()
            }
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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

private fun priorityDisplayLabel(priority: BestNodePriority, mode: TestPreferMode?): String {
    return when (priority) {
        BestNodePriority.LATENCY -> "延迟优先"
        BestNodePriority.UPLOAD -> "上行优先"
        BestNodePriority.DOWNLOAD -> "下行优先"
        BestNodePriority.UNLOCK_COUNT -> when (mode?.unlockPriorityMode ?: UnlockPriorityMode.COUNT) {
            UnlockPriorityMode.COUNT -> "按解锁数优选"
            UnlockPriorityMode.TARGET_SITES -> "按指定网站优选"
        }
    }
}

@Composable
private fun StartupDefaultTestChoiceDialog(
    onDismiss: () -> Unit,
    onSelectTcping: () -> Unit,
    onSelectUrlTest: () -> Unit,
    onSkip: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择启动默认测试") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "APP 启动默认只拉取节点。你可以选择默认执行一种测试，也可以跳过。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = onSelectTcping, modifier = Modifier.fillMaxWidth()) {
                    Text("默认执行 TCPing")
                }
                OutlinedButton(onClick = onSelectUrlTest, modifier = Modifier.fillMaxWidth()) {
                    Text("默认执行 URL Test")
                }
                TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                    Text("跳过")
                }
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}
