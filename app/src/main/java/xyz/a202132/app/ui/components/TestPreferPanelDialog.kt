package xyz.a202132.app.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import xyz.a202132.app.viewmodel.AutoTestLatencyMode
import xyz.a202132.app.viewmodel.BestNodePriority
import xyz.a202132.app.viewmodel.TestPreferMode
import xyz.a202132.app.viewmodel.UnlockPriorityMode
import xyz.a202132.app.AppConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestPreferPanelDialog(
    autoTestEnabled: Boolean,
    autoTestFilterUnavailable: Boolean,
    autoTestLatencyEnabled: Boolean,
    autoTestLatencyMode: AutoTestLatencyMode,
    autoTestLatencyThresholdMs: Int,
    autoTestBandwidthEnabled: Boolean,
    autoTestBandwidthDownloadEnabled: Boolean,
    autoTestBandwidthUploadEnabled: Boolean,
    autoTestBandwidthDownloadThresholdMbps: Int,
    autoTestBandwidthUploadThresholdMbps: Int,
    autoTestBandwidthWifiOnly: Boolean,
    autoTestBandwidthDownloadSizeMb: Int,
    autoTestBandwidthUploadSizeMb: Int,
    autoTestUnlockEnabled: Boolean,
    autoTestByRegion: Boolean,
    autoTestNodeLimit: Int,
    autoTestProgress: xyz.a202132.app.viewmodel.AutoTestProgress,
    preferTestModes: List<TestPreferMode>,
    preferTestSelectedModeId: String,
    onSetAutoTestEnabled: (Boolean) -> Unit,
    onSetAutoTestFilterUnavailable: (Boolean) -> Unit,
    onSetAutoTestLatencyEnabled: (Boolean) -> Unit,
    onSetAutoTestLatencyMode: (AutoTestLatencyMode) -> Unit,
    onSetAutoTestLatencyThresholdMs: (Int) -> Unit,
    onSetAutoTestBandwidthEnabled: (Boolean) -> Unit,
    onSetAutoTestBandwidthDownloadEnabled: (Boolean) -> Unit,
    onSetAutoTestBandwidthUploadEnabled: (Boolean) -> Unit,
    onSetAutoTestBandwidthDownloadThresholdMbps: (Int) -> Unit,
    onSetAutoTestBandwidthUploadThresholdMbps: (Int) -> Unit,
    onSetAutoTestBandwidthWifiOnly: (Boolean) -> Unit,
    onSetAutoTestBandwidthDownloadSizeMb: (Int) -> Unit,
    onSetAutoTestBandwidthUploadSizeMb: (Int) -> Unit,
    onSetAutoTestUnlockEnabled: (Boolean) -> Unit,
    onSetAutoTestByRegion: (Boolean) -> Unit,
    onSetAutoTestNodeLimit: (Int) -> Unit,
    onApplyPreferTestMode: (String) -> Unit,
    onCreatePreferTestMode: () -> Unit,
    onSaveCurrentPreferTestMode: (String) -> Unit,
    onDeleteCurrentPreferTestMode: () -> Unit,
    onHideUnqualifiedAutoTestNodes: () -> Unit,
    onSelectBestNodeByPriority: (BestNodePriority, Boolean) -> Unit,
    onUpdateCurrentPreferModePriority: (BestNodePriority) -> Unit,
    onUpdateCurrentPreferModeUnlockPriority: (UnlockPriorityMode, List<String>) -> Unit,
    onStartAutomatedTest: () -> Unit,
    onCancelAutomatedTest: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val originalOrientation = activity?.requestedOrientation
        val window = activity?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        if (window != null && controller != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (window != null && controller != null) {
                controller.show(WindowInsetsCompat.Type.systemBars())
                WindowCompat.setDecorFitsSystemWindows(window, true)
            }
            if (activity != null && originalOrientation != null) {
                activity.requestedOrientation = originalOrientation
            }
        }
    }
    val currentMode = preferTestModes.firstOrNull { it.id == preferTestSelectedModeId }
    val autoRunLabel = "开启APP自动执行${currentMode?.name ?: "当前模式"}测试"
    val autoConnectBestButtonLabel = remember(currentMode) {
        when (currentMode?.defaultPriority ?: BestNodePriority.LATENCY) {
            BestNodePriority.LATENCY -> "自动连接最优（延迟优先）"
            BestNodePriority.UPLOAD -> "自动连接最优（上行优先）"
            BestNodePriority.DOWNLOAD -> "自动连接最优（下行优先）"
            BestNodePriority.UNLOCK_COUNT -> when (currentMode?.unlockPriorityMode ?: UnlockPriorityMode.COUNT) {
                UnlockPriorityMode.COUNT -> "自动连接最优（按解锁数优选）"
                UnlockPriorityMode.TARGET_SITES -> "自动连接最优（按指定网站优选）"
            }
        }
    }
    val availablePriorityOptions = remember(
        autoTestLatencyEnabled,
        autoTestBandwidthEnabled,
        autoTestBandwidthDownloadEnabled,
        autoTestBandwidthUploadEnabled,
        autoTestUnlockEnabled
    ) {
        buildList {
            if (autoTestLatencyEnabled) add(BestNodePriority.LATENCY)
            if (autoTestBandwidthEnabled && autoTestBandwidthUploadEnabled) add(BestNodePriority.UPLOAD)
            if (autoTestBandwidthEnabled && autoTestBandwidthDownloadEnabled) add(BestNodePriority.DOWNLOAD)
            if (autoTestUnlockEnabled) add(BestNodePriority.UNLOCK_COUNT)
            if (isEmpty()) add(BestNodePriority.LATENCY)
        }
    }

    var modeExpanded by remember { mutableStateOf(false) }
    var modeNameInput by remember(preferTestModes, preferTestSelectedModeId) {
        mutableStateOf(preferTestModes.firstOrNull { it.id == preferTestSelectedModeId }?.name.orEmpty())
    }
    var latencyInput by remember(autoTestLatencyThresholdMs) { mutableStateOf(autoTestLatencyThresholdMs.toString()) }
    var downloadThresholdInput by remember(autoTestBandwidthDownloadThresholdMbps) { mutableStateOf(autoTestBandwidthDownloadThresholdMbps.toString()) }
    var uploadThresholdInput by remember(autoTestBandwidthUploadThresholdMbps) { mutableStateOf(autoTestBandwidthUploadThresholdMbps.toString()) }
    var nodeLimitInput by remember(autoTestNodeLimit) { mutableStateOf(autoTestNodeLimit.toString()) }
    var showPriorityDialog by remember { mutableStateOf(false) }
    var showUnlockPriorityDialog by remember { mutableStateOf(false) }
    var pendingPriority by remember(currentMode?.defaultPriority, availablePriorityOptions) {
        mutableStateOf(
            currentMode?.defaultPriority?.takeIf { it in availablePriorityOptions }
                ?: availablePriorityOptions.first()
        )
    }
    var unlockPriorityModeDraft by remember(currentMode?.unlockPriorityMode) {
        mutableStateOf(currentMode?.unlockPriorityMode ?: UnlockPriorityMode.COUNT)
    }
    var unlockTargetSiteIdsDraft by remember(currentMode?.unlockPriorityTargetSiteIds) {
        mutableStateOf(currentMode?.unlockPriorityTargetSiteIds?.toSet() ?: emptySet())
    }

    Box(
        modifier = Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.TopStart
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize(),
            shape = RoundedCornerShape(0.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("测试择优面板", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(
                            "支持模式保存/删除、按规则测试、自动选择连接最优节点",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Divider()
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SectionTitle("测试模式")
                        ExposedDropdownMenuBox(
                            expanded = modeExpanded,
                            onExpandedChange = { modeExpanded = !modeExpanded }
                        ) {
                            OutlinedTextField(
                                value = preferTestModes.firstOrNull { it.id == preferTestSelectedModeId }?.name ?: "",
                                onValueChange = {},
                                modifier = Modifier
                                    .menuAnchor()
                                    .fillMaxWidth(),
                                readOnly = true,
                                label = { Text("当前模式") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modeExpanded) }
                            )
                            DropdownMenu(
                                expanded = modeExpanded,
                                onDismissRequest = { modeExpanded = false }
                            ) {
                                preferTestModes.forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(if (mode.builtIn) "${mode.name}（内置）" else mode.name) },
                                        onClick = {
                                            modeExpanded = false
                                            modeNameInput = mode.name
                                            onApplyPreferTestMode(mode.id)
                                        }
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = modeNameInput,
                            onValueChange = { modeNameInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("模式名称（保存/覆盖）") }
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onCreatePreferTestMode,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Outlined.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("新增模式")
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onSaveCurrentPreferTestMode(modeNameInput) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Outlined.Save, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("保存模式")
                            }
                            Button(
                                onClick = onDeleteCurrentPreferTestMode,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Outlined.Delete, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("删除当前")
                            }
                        }

                        Divider(modifier = Modifier.padding(vertical = 4.dp))
                        SectionTitle("启动自动执行")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = autoTestEnabled, onCheckedChange = onSetAutoTestEnabled)
                            Text(autoRunLabel)
                        }

                        SectionTitle("基础筛选")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = autoTestFilterUnavailable, onCheckedChange = onSetAutoTestFilterUnavailable)
                            Text("按阈值移除未达标 / 不可用节点")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = autoTestLatencyEnabled, onCheckedChange = onSetAutoTestLatencyEnabled)
                            Text("启用延迟测试（聊天模式默认用）")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = autoTestLatencyMode == AutoTestLatencyMode.URL_TEST,
                                onClick = { onSetAutoTestLatencyMode(AutoTestLatencyMode.URL_TEST) },
                                label = { Text("URL Test") },
                                enabled = autoTestLatencyEnabled
                            )
                            FilterChip(
                                selected = autoTestLatencyMode == AutoTestLatencyMode.TCPING,
                                onClick = { onSetAutoTestLatencyMode(AutoTestLatencyMode.TCPING) },
                                label = { Text("TCPing") },
                                enabled = autoTestLatencyEnabled
                            )
                        }
                        OutlinedTextField(
                            value = latencyInput,
                            onValueChange = {
                                latencyInput = it.filter(Char::isDigit)
                                latencyInput.toIntOrNull()?.let(onSetAutoTestLatencyThresholdMs)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = autoTestLatencyEnabled,
                            label = { Text("延迟阈值 (ms)") }
                        )

                        Divider(modifier = Modifier.padding(vertical = 4.dp))
                        SectionTitle("带宽测试")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = autoTestBandwidthEnabled, onCheckedChange = onSetAutoTestBandwidthEnabled)
                            Text("启用带宽测试（下载模式默认用）")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = autoTestBandwidthDownloadEnabled,
                                onCheckedChange = onSetAutoTestBandwidthDownloadEnabled,
                                enabled = autoTestBandwidthEnabled
                            )
                            Text("测试下行")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = autoTestBandwidthUploadEnabled,
                                onCheckedChange = onSetAutoTestBandwidthUploadEnabled,
                                enabled = autoTestBandwidthEnabled
                            )
                            Text("测试上行")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = autoTestBandwidthWifiOnly,
                                onCheckedChange = onSetAutoTestBandwidthWifiOnly,
                                enabled = autoTestBandwidthEnabled
                            )
                            Text("仅 Wi-Fi 执行带宽测试")
                        }
                        OutlinedTextField(
                            value = downloadThresholdInput,
                            onValueChange = {
                                downloadThresholdInput = it.filter(Char::isDigit)
                                downloadThresholdInput.toIntOrNull()?.let(onSetAutoTestBandwidthDownloadThresholdMbps)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = autoTestBandwidthEnabled && autoTestBandwidthDownloadEnabled,
                            label = { Text("下行阈值 (Mbps)") }
                        )
                        OutlinedTextField(
                            value = uploadThresholdInput,
                            onValueChange = {
                                uploadThresholdInput = it.filter(Char::isDigit)
                                uploadThresholdInput.toIntOrNull()?.let(onSetAutoTestBandwidthUploadThresholdMbps)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = autoTestBandwidthEnabled && autoTestBandwidthUploadEnabled,
                            label = { Text("上行阈值 (Mbps)") }
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SectionTitle("测试规模与高级项")
                        ChipRow(
                            title = "下行测试大小",
                            options = listOf(1, 10, 25, 50),
                            selected = autoTestBandwidthDownloadSizeMb,
                            enabled = autoTestBandwidthEnabled && autoTestBandwidthDownloadEnabled,
                            onSelect = onSetAutoTestBandwidthDownloadSizeMb
                        )
                        ChipRow(
                            title = "上行测试大小",
                            options = listOf(1, 10, 25, 50),
                            selected = autoTestBandwidthUploadSizeMb,
                            enabled = autoTestBandwidthEnabled && autoTestBandwidthUploadEnabled,
                            onSelect = onSetAutoTestBandwidthUploadSizeMb
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = autoTestUnlockEnabled, onCheckedChange = onSetAutoTestUnlockEnabled)
                            Text("启用解锁测试")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = autoTestByRegion, onCheckedChange = onSetAutoTestByRegion)
                            Text("按地区分组抽样测试")
                        }
                        OutlinedTextField(
                            value = nodeLimitInput,
                            onValueChange = {
                                nodeLimitInput = it.filter(Char::isDigit)
                                nodeLimitInput.toIntOrNull()?.let(onSetAutoTestNodeLimit)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(if (autoTestByRegion) "每地区节点上限" else "测试节点上限") }
                        )

                        Divider(modifier = Modifier.padding(vertical = 4.dp))
                        SectionTitle("执行与择优")
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = if (autoTestProgress.running) {
                                        "当前阶段：${autoTestProgress.stage}\n${autoTestProgress.message}"
                                    } else {
                                        "点击“开始测试”按当前面板设置执行；完成后可选择移除未达标或自动连接最优节点。"
                                    },
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (autoTestProgress.total > 0) {
                                    Text(
                                        text = "进度：${autoTestProgress.completed}/${autoTestProgress.total}",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    if (autoTestProgress.running) onCancelAutomatedTest() else onStartAutomatedTest()
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (autoTestProgress.running) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    }
                                )
                            ) {
                                Icon(
                                    imageVector = if (autoTestProgress.running) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                                    contentDescription = null
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (autoTestProgress.running) "取消测试" else "开始测试")
                            }
                            Button(
                                onClick = onHideUnqualifiedAutoTestNodes,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Outlined.Speed, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("移除未达标")
                            }
                        }

                        Button(
                            onClick = { showPriorityDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.Star, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(autoConnectBestButtonLabel)
                        }
                    }
                }
            }
        }
    }

    if (showPriorityDialog) {
        AlertDialog(
            onDismissRequest = { showPriorityDialog = false },
            title = { Text("选择最优判定标准") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "未手动调整时，将使用当前模式的默认择优规则：${priorityLabel(currentMode?.defaultPriority ?: availablePriorityOptions.first())}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    availablePriorityOptions.forEach { option ->
                        PriorityOption(
                            label = if (option == BestNodePriority.UNLOCK_COUNT) {
                                unlockPriorityLabel(
                                    mode = currentMode?.unlockPriorityMode ?: UnlockPriorityMode.COUNT,
                                    targetSiteIds = currentMode?.unlockPriorityTargetSiteIds ?: emptyList()
                                )
                            } else {
                                priorityLabel(option)
                            },
                            selected = pendingPriority == option
                        ) {
                            if (option == BestNodePriority.UNLOCK_COUNT) {
                                unlockPriorityModeDraft = currentMode?.unlockPriorityMode ?: UnlockPriorityMode.COUNT
                                unlockTargetSiteIdsDraft = currentMode?.unlockPriorityTargetSiteIds?.toSet() ?: emptySet()
                                showUnlockPriorityDialog = true
                            } else {
                                pendingPriority = option
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPriorityDialog = false
                        onUpdateCurrentPreferModePriority(pendingPriority)
                        onSelectBestNodeByPriority(pendingPriority, true)
                    },
                    modifier = Modifier.defaultMinSize(minHeight = 34.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                ) {
                    Text("确认并连接")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showPriorityDialog = false },
                    modifier = Modifier.defaultMinSize(minHeight = 34.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                ) { Text("取消") }
            }
        )
    }

    if (showUnlockPriorityDialog) {
        AlertDialog(
            onDismissRequest = { showUnlockPriorityDialog = false },
            title = { Text("解锁情况优先设置") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PriorityOption("按解锁数", unlockPriorityModeDraft == UnlockPriorityMode.COUNT) {
                        unlockPriorityModeDraft = UnlockPriorityMode.COUNT
                    }
                    PriorityOption("按指定网站（可多选）", unlockPriorityModeDraft == UnlockPriorityMode.TARGET_SITES) {
                        unlockPriorityModeDraft = UnlockPriorityMode.TARGET_SITES
                    }
                    if (unlockPriorityModeDraft == UnlockPriorityMode.TARGET_SITES) {
                        Text(
                            text = "请下滑选择你关心的网站（命中越多越优先；并列再按总解锁数和延迟/下行兜底）",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextButton(
                                onClick = {
                                    unlockTargetSiteIdsDraft = AppConfig.UNLOCK_PRIORITY_PRESET_SITES
                                        .map { it.id }
                                        .toSet()
                                },
                                modifier = Modifier.defaultMinSize(minHeight = 32.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                            ) {
                                Text("全选")
                            }
                            TextButton(
                                onClick = { unlockTargetSiteIdsDraft = emptySet() },
                                modifier = Modifier.defaultMinSize(minHeight = 32.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                            ) {
                                Text("清空")
                            }
                        }
                        AppConfig.UNLOCK_PRIORITY_PRESET_SITES.forEach { site ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        unlockTargetSiteIdsDraft = if (site.id in unlockTargetSiteIdsDraft) {
                                            unlockTargetSiteIdsDraft - site.id
                                        } else {
                                            unlockTargetSiteIdsDraft + site.id
                                        }
                                    }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = site.id in unlockTargetSiteIdsDraft,
                                    onCheckedChange = {
                                        unlockTargetSiteIdsDraft = if (site.id in unlockTargetSiteIdsDraft) {
                                            unlockTargetSiteIdsDraft - site.id
                                        } else {
                                            unlockTargetSiteIdsDraft + site.id
                                        }
                                    }
                                )
                                Text(site.label)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (unlockPriorityModeDraft == UnlockPriorityMode.TARGET_SITES && unlockTargetSiteIdsDraft.isEmpty()) {
                            return@TextButton
                        }
                        onUpdateCurrentPreferModeUnlockPriority(
                            unlockPriorityModeDraft,
                            unlockTargetSiteIdsDraft.toList()
                        )
                        pendingPriority = BestNodePriority.UNLOCK_COUNT
                        showUnlockPriorityDialog = false
                    },
                    modifier = Modifier.defaultMinSize(minHeight = 34.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                ) {
                    Text("确认")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showUnlockPriorityDialog = false },
                    modifier = Modifier.defaultMinSize(minHeight = 34.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                ) { Text("取消") }
            }
        )
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun priorityLabel(priority: BestNodePriority): String = when (priority) {
    BestNodePriority.LATENCY -> "延迟优先"
    BestNodePriority.UPLOAD -> "上行优先"
    BestNodePriority.DOWNLOAD -> "下行优先"
    BestNodePriority.UNLOCK_COUNT -> "解锁情况优先（按解锁数）"
}

private fun unlockPriorityLabel(mode: UnlockPriorityMode, targetSiteIds: List<String>): String {
    return when (mode) {
        UnlockPriorityMode.COUNT -> "解锁情况优先（按解锁数）"
        UnlockPriorityMode.TARGET_SITES -> {
            val selected = AppConfig.UNLOCK_PRIORITY_PRESET_SITES.filter { it.id in targetSiteIds }
            val preview = selected.take(2).joinToString("、") { it.label }
            if (selected.isEmpty()) {
                "解锁情况优先（按指定网站）"
            } else if (selected.size <= 2) {
                "解锁情况优先（$preview）"
            } else {
                "解锁情况优先（$preview 等${selected.size}项）"
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
}

@Composable
private fun PriorityOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(if (selected) "●" else "○", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(label)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChipRow(
    title: String,
    options: List<Int>,
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { value ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = { Text("${value}MB") },
                    enabled = enabled
                )
            }
        }
    }
}
