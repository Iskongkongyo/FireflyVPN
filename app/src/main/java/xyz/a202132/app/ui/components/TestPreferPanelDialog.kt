package xyz.a202132.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.a202132.app.viewmodel.AutoTestLatencyMode
import xyz.a202132.app.viewmodel.AutoTestConfig
import xyz.a202132.app.viewmodel.AutoTestStage
import xyz.a202132.app.viewmodel.BestNodePriority
import xyz.a202132.app.viewmodel.TestPreferMode
import xyz.a202132.app.viewmodel.UnlockPriorityMode
import xyz.a202132.app.viewmodel.normalizePriorityOrder
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
    hasRecentAutoTestResults: Boolean,
    currentTestNodeGroupName: String,
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
    onSaveCurrentPreferTestMode: (String, AutoTestConfig) -> Unit,
    onDeleteCurrentPreferTestMode: () -> Unit,
    onUpdateCurrentPreferModePriorityOrder: (List<BestNodePriority>) -> Unit,
    onUpdateCurrentPreferModeUnlockPriority: (UnlockPriorityMode, List<String>) -> Unit,
    onUpdateCurrentPreferModeAutoConnect: (Boolean) -> Unit,
    onShowRecentAutoTestResults: () -> Unit,
    onStartAutomatedTest: (AutoTestConfig, Boolean, TestPreferMode?) -> Unit,
    onCancelAutomatedTest: () -> Unit,
    onDismiss: () -> Unit
) {
    val currentMode = preferTestModes.firstOrNull { it.id == preferTestSelectedModeId }
    val controlsEnabled = !autoTestProgress.running
    val canceling = autoTestProgress.running && autoTestProgress.stage == AutoTestStage.CANCELED
    val autoRunLabel = "APP启动后自动执行“${currentMode?.name ?: "当前模式"}”"

    var modeExpanded by remember { mutableStateOf(false) }
    var modeNameInput by remember(preferTestModes, preferTestSelectedModeId) {
        mutableStateOf(preferTestModes.firstOrNull { it.id == preferTestSelectedModeId }?.name.orEmpty())
    }
    var latencyInput by remember(autoTestLatencyThresholdMs) { mutableStateOf(autoTestLatencyThresholdMs.toString()) }
    var downloadThresholdInput by remember(autoTestBandwidthDownloadThresholdMbps) { mutableStateOf(autoTestBandwidthDownloadThresholdMbps.toString()) }
    var uploadThresholdInput by remember(autoTestBandwidthUploadThresholdMbps) { mutableStateOf(autoTestBandwidthUploadThresholdMbps.toString()) }
    var nodeLimitInput by remember(autoTestNodeLimit) { mutableStateOf(autoTestNodeLimit.toString()) }
    var showUnlockPriorityPage by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var priorityOrderDraft by remember(preferTestSelectedModeId, currentMode?.priorityOrder) {
        mutableStateOf(
            currentMode?.normalizePriorityOrder()?.priorityOrder ?: BestNodePriority.entries.toList()
        )
    }
    var unlockPriorityModeDraft by remember(currentMode?.unlockPriorityMode) {
        mutableStateOf(currentMode?.unlockPriorityMode ?: UnlockPriorityMode.COUNT)
    }
    var unlockTargetSiteIdsDraft by remember(currentMode?.unlockPriorityTargetSiteIds) {
        mutableStateOf(currentMode?.unlockPriorityTargetSiteIds?.toSet() ?: emptySet())
    }
    var autoConnectBest by remember(preferTestSelectedModeId, currentMode?.autoConnectBest) {
        mutableStateOf(currentMode?.autoConnectBest ?: false)
    }
    val activePriorityOrder = priorityOrderDraft.filter { priority ->
        isPriorityTestEnabled(
            priority = priority,
            latencyEnabled = autoTestLatencyEnabled,
            bandwidthEnabled = autoTestBandwidthEnabled,
            downloadEnabled = autoTestBandwidthDownloadEnabled,
            uploadEnabled = autoTestBandwidthUploadEnabled,
            unlockEnabled = autoTestUnlockEnabled
        )
    }
    val priorityOrderText = activePriorityOrder.joinToString(" > ") { priority ->
        priorityLabel(
            priority = priority,
            unlockMode = unlockPriorityModeDraft
        )
    }
    val priorityOrderLabel = if (activePriorityOrder.size == 1) {
        "${priorityOrderText}优先"
    } else {
        priorityOrderText
    }
    val closeUnlockPriorityPage = {
        unlockPriorityModeDraft = currentMode?.unlockPriorityMode ?: UnlockPriorityMode.COUNT
        unlockTargetSiteIdsDraft = currentMode?.unlockPriorityTargetSiteIds?.toSet() ?: emptySet()
        showUnlockPriorityPage = false
    }
    BackHandler {
        if (showUnlockPriorityPage) closeUnlockPriorityPage() else onDismiss()
    }
    val currentPanelConfig = {
        AutoTestConfig(
            enabled = autoTestEnabled,
            filterUnavailable = autoTestFilterUnavailable,
            latencyEnabled = autoTestLatencyEnabled,
            latencyMode = autoTestLatencyMode,
            latencyThresholdMs = latencyInput.toIntOrNull() ?: autoTestLatencyThresholdMs,
            bandwidthEnabled = autoTestBandwidthEnabled,
            bandwidthDownloadEnabled = autoTestBandwidthDownloadEnabled,
            bandwidthUploadEnabled = autoTestBandwidthUploadEnabled,
            bandwidthDownloadThresholdMbps = downloadThresholdInput.toIntOrNull()
                ?: autoTestBandwidthDownloadThresholdMbps,
            bandwidthUploadThresholdMbps = uploadThresholdInput.toIntOrNull()
                ?: autoTestBandwidthUploadThresholdMbps,
            bandwidthWifiOnly = autoTestBandwidthWifiOnly,
            bandwidthDownloadSizeMb = autoTestBandwidthDownloadSizeMb,
            bandwidthUploadSizeMb = autoTestBandwidthUploadSizeMb,
            unlockEnabled = autoTestUnlockEnabled,
            byRegion = autoTestByRegion,
            nodeLimit = nodeLimitInput.toIntOrNull() ?: autoTestNodeLimit
        )
    }
    val currentExecutionMode = {
        currentMode?.copy(
            defaultPriority = priorityOrderDraft.firstOrNull() ?: BestNodePriority.LATENCY,
            priorityOrder = priorityOrderDraft,
            filterUnavailable = autoTestFilterUnavailable,
            latencyEnabled = autoTestLatencyEnabled,
            latencyMode = autoTestLatencyMode,
            latencyThresholdMs = latencyInput.toIntOrNull() ?: autoTestLatencyThresholdMs,
            bandwidthEnabled = autoTestBandwidthEnabled,
            bandwidthDownloadEnabled = autoTestBandwidthDownloadEnabled,
            bandwidthUploadEnabled = autoTestBandwidthUploadEnabled,
            bandwidthDownloadThresholdMbps = downloadThresholdInput.toIntOrNull()
                ?: autoTestBandwidthDownloadThresholdMbps,
            bandwidthUploadThresholdMbps = uploadThresholdInput.toIntOrNull()
                ?: autoTestBandwidthUploadThresholdMbps,
            bandwidthWifiOnly = autoTestBandwidthWifiOnly,
            bandwidthDownloadSizeMb = autoTestBandwidthDownloadSizeMb,
            bandwidthUploadSizeMb = autoTestBandwidthUploadSizeMb,
            unlockEnabled = autoTestUnlockEnabled,
            byRegion = autoTestByRegion,
            nodeLimit = nodeLimitInput.toIntOrNull() ?: autoTestNodeLimit,
            unlockPriorityMode = unlockPriorityModeDraft,
            unlockPriorityTargetSiteIds = unlockTargetSiteIdsDraft.toList(),
            autoConnectBest = autoConnectBest
        )
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
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("择优面板", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(
                            "配置测试规则，筛选并连接更合适的节点",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Divider()
                Spacer(modifier = Modifier.height(10.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SectionTitle("测试模式")
                        ExposedDropdownMenuBox(
                            expanded = modeExpanded,
                            onExpandedChange = {
                                if (controlsEnabled) modeExpanded = !modeExpanded
                            }
                        ) {
                            OutlinedTextField(
                                value = preferTestModes.firstOrNull { it.id == preferTestSelectedModeId }?.name ?: "",
                                onValueChange = {},
                                modifier = Modifier
                                    .menuAnchor()
                                    .fillMaxWidth(),
                                readOnly = true,
                                enabled = controlsEnabled,
                                label = { Text("当前模式") }
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
                            enabled = controlsEnabled && currentMode?.builtIn != true,
                            label = {
                                Text(if (currentMode?.builtIn == true) "内置模式名称" else "模式名称")
                            }
                        )
                        Text(
                            text = "面板调整会用于下一次测试；点击“保存模式”才会更新当前预设。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onCreatePreferTestMode,
                                enabled = controlsEnabled,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Outlined.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("新增模式")
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onSaveCurrentPreferTestMode(modeNameInput, currentPanelConfig()) },
                                enabled = controlsEnabled && modeNameInput.isNotBlank(),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Outlined.Save, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (currentMode?.builtIn == true) "保存设置" else "保存模式")
                            }
                            Button(
                                onClick = { showDeleteConfirmDialog = true },
                                enabled = controlsEnabled && currentMode?.builtIn == false,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Outlined.Delete, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (currentMode?.builtIn == true) "内置模式" else "删除")
                            }
                        }

                        Divider(modifier = Modifier.padding(vertical = 4.dp))
                        SectionTitle("延迟测试")
                        PanelDropdownItem(
                            title = "测试内容",
                            currentValue = when {
                                !autoTestLatencyEnabled -> "禁用"
                                autoTestLatencyMode == AutoTestLatencyMode.TCPING -> "TCPing"
                                else -> "URL Test"
                            },
                            options = listOf(
                                LatencyTestSelection.DISABLED to "禁用",
                                LatencyTestSelection.TCPING to "TCPing",
                                LatencyTestSelection.URL_TEST to "URL Test"
                            ),
                            enabled = controlsEnabled,
                            onSelect = { selection ->
                                when (selection) {
                                    LatencyTestSelection.DISABLED -> onSetAutoTestLatencyEnabled(false)
                                    LatencyTestSelection.TCPING -> {
                                        onSetAutoTestLatencyMode(AutoTestLatencyMode.TCPING)
                                        onSetAutoTestLatencyEnabled(true)
                                    }
                                    LatencyTestSelection.URL_TEST -> {
                                        onSetAutoTestLatencyMode(AutoTestLatencyMode.URL_TEST)
                                        onSetAutoTestLatencyEnabled(true)
                                    }
                                }
                            }
                        )
                        OutlinedTextField(
                            value = latencyInput,
                            onValueChange = {
                                latencyInput = it.filter(Char::isDigit).take(10)
                                latencyInput.toIntOrNull()
                                    ?.takeIf { value -> value >= 50 }
                                    ?.let(onSetAutoTestLatencyThresholdMs)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = controlsEnabled && autoTestLatencyEnabled,
                            isError = autoTestLatencyEnabled && (latencyInput.toIntOrNull()?.let { it >= 50 } != true),
                            supportingText = { Text("有效范围：50 ms 及以上") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            label = { Text("延迟阈值 (ms)") }
                        )

                        Divider(modifier = Modifier.padding(vertical = 4.dp))
                        SectionTitle("带宽测试")
                        SettingSwitchRow(
                            checked = autoTestBandwidthEnabled,
                            text = "启用带宽测试",
                            enabled = controlsEnabled,
                            onCheckedChange = onSetAutoTestBandwidthEnabled
                        )
                        SettingSwitchRow(
                            checked = autoTestBandwidthDownloadEnabled,
                            text = "测试下行",
                            enabled = controlsEnabled && autoTestBandwidthEnabled,
                            onCheckedChange = onSetAutoTestBandwidthDownloadEnabled
                        )
                        OutlinedTextField(
                            value = downloadThresholdInput,
                            onValueChange = {
                                downloadThresholdInput = it.filter(Char::isDigit).take(10)
                                downloadThresholdInput.toIntOrNull()
                                    ?.takeIf { value -> value >= 1 }
                                    ?.let(onSetAutoTestBandwidthDownloadThresholdMbps)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = controlsEnabled && autoTestBandwidthEnabled && autoTestBandwidthDownloadEnabled,
                            isError = autoTestBandwidthEnabled && autoTestBandwidthDownloadEnabled &&
                                (downloadThresholdInput.toIntOrNull()?.let { it >= 1 } != true),
                            supportingText = { Text("有效范围：1 Mbps 及以上") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            label = { Text("下行阈值 (Mbps)") }
                        )
                        PanelDropdownItem(
                            title = "下行测试大小",
                            currentValue = "${autoTestBandwidthDownloadSizeMb} MB",
                            options = listOf(1, 10, 25, 50).map { it to "$it MB" },
                            enabled = controlsEnabled && autoTestBandwidthEnabled && autoTestBandwidthDownloadEnabled,
                            onSelect = onSetAutoTestBandwidthDownloadSizeMb
                        )

                        SettingSwitchRow(
                            checked = autoTestBandwidthUploadEnabled,
                            text = "测试上行",
                            enabled = controlsEnabled && autoTestBandwidthEnabled,
                            onCheckedChange = onSetAutoTestBandwidthUploadEnabled
                        )
                        OutlinedTextField(
                            value = uploadThresholdInput,
                            onValueChange = {
                                uploadThresholdInput = it.filter(Char::isDigit).take(10)
                                uploadThresholdInput.toIntOrNull()
                                    ?.takeIf { value -> value >= 1 }
                                    ?.let(onSetAutoTestBandwidthUploadThresholdMbps)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = controlsEnabled && autoTestBandwidthEnabled && autoTestBandwidthUploadEnabled,
                            isError = autoTestBandwidthEnabled && autoTestBandwidthUploadEnabled &&
                                (uploadThresholdInput.toIntOrNull()?.let { it >= 1 } != true),
                            supportingText = { Text("有效范围：1 Mbps 及以上") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            label = { Text("上行阈值 (Mbps)") }
                        )
                        PanelDropdownItem(
                            title = "上行测试大小",
                            currentValue = "${autoTestBandwidthUploadSizeMb} MB",
                            options = listOf(1, 10, 25, 50).map { it to "$it MB" },
                            enabled = controlsEnabled && autoTestBandwidthEnabled && autoTestBandwidthUploadEnabled,
                            onSelect = onSetAutoTestBandwidthUploadSizeMb
                        )
                        SettingSwitchRow(
                            checked = autoTestBandwidthWifiOnly,
                            text = "仅 Wi-Fi 执行带宽测试",
                            enabled = controlsEnabled && autoTestBandwidthEnabled,
                            onCheckedChange = onSetAutoTestBandwidthWifiOnly
                        )

                        Divider(modifier = Modifier.padding(vertical = 4.dp))
                        SectionTitle("高级选项")
                        SettingSwitchRow(
                            checked = autoTestEnabled,
                            text = autoRunLabel,
                            subtitle = "此开关为全局设置；APP 启动时只执行当前选中的模式，切换模式会同步更换执行目标，不会同时运行多个模式。",
                            enabled = controlsEnabled,
                            onCheckedChange = onSetAutoTestEnabled
                        )
                        SettingSwitchRow(
                            checked = autoTestUnlockEnabled,
                            text = "启用流媒体等解锁测试",
                            enabled = controlsEnabled,
                            onCheckedChange = onSetAutoTestUnlockEnabled
                        )
                        SettingSwitchRow(
                            checked = autoTestByRegion,
                            text = "按地区分组抽样测试",
                            subtitle = "地区识别依赖节点名称中的国家/地区 Emoji。",
                            enabled = controlsEnabled,
                            onCheckedChange = onSetAutoTestByRegion
                        )
                        SettingSwitchRow(
                            checked = autoTestFilterUnavailable,
                            text = "隐藏不合格节点",
                            subtitle = "没达到各种测试阈值的节点提前淘汰，不会进入后续测试和测试结果。",
                            enabled = controlsEnabled,
                            onCheckedChange = onSetAutoTestFilterUnavailable
                        )
                        OutlinedTextField(
                            value = nodeLimitInput,
                            onValueChange = {
                                nodeLimitInput = it.filter(Char::isDigit).take(3)
                                nodeLimitInput.toIntOrNull()
                                    ?.takeIf { value -> value in 1..200 }
                                    ?.let(onSetAutoTestNodeLimit)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = controlsEnabled,
                            isError = nodeLimitInput.toIntOrNull()?.let { it in 1..200 } != true,
                            supportingText = {
                                Text(if (autoTestByRegion) "每个地区 1–200 个节点" else "有效范围：1–200 个节点")
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            label = { Text(if (autoTestByRegion) "每地区节点上限" else "测试节点上限") }
                        )

                        Divider(modifier = Modifier.padding(vertical = 4.dp))
                        SectionTitle("择优顺序")
                        Text(
                            text = "测试结果里的节点将会按下面顺序逐级比较进行排序。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        activePriorityOrder.forEachIndexed { index, priority ->
                            PriorityOrderItem(
                                index = index,
                                label = priorityLabel(priority, unlockPriorityModeDraft),
                                canMoveUp = controlsEnabled && index > 0,
                                canMoveDown = controlsEnabled && index < activePriorityOrder.lastIndex,
                                onMoveUp = {
                                    priorityOrderDraft = priorityOrderDraft.swapItems(
                                        priority,
                                        activePriorityOrder[index - 1]
                                    )
                                    onUpdateCurrentPreferModePriorityOrder(priorityOrderDraft)
                                },
                                onMoveDown = {
                                    priorityOrderDraft = priorityOrderDraft.swapItems(
                                        priority,
                                        activePriorityOrder[index + 1]
                                    )
                                    onUpdateCurrentPreferModePriorityOrder(priorityOrderDraft)
                                },
                                onConfigure = if (priority == BestNodePriority.UNLOCK_COUNT && controlsEnabled) {
                                    { showUnlockPriorityPage = true }
                                } else {
                                    null
                                }
                            )
                        }

                        Divider(modifier = Modifier.padding(vertical = 4.dp))
                        SectionTitle("执行测试")
                        SettingSwitchRow(
                            checked = autoConnectBest,
                            text = "自动连接最优",
                            enabled = controlsEnabled,
                            onCheckedChange = { checked ->
                                autoConnectBest = checked
                                onUpdateCurrentPreferModeAutoConnect(checked)
                            }
                        )
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "当前测试节点分组：$currentTestNodeGroupName",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                    Text(
                                        text = if (
                                            autoTestProgress.running ||
                                            autoTestProgress.stage != AutoTestStage.IDLE
                                        ) {
                                            "当前阶段：${autoTestStageLabel(autoTestProgress.stage)}\n${autoTestProgress.message}"
                                        } else {
                                            if (autoConnectBest) {
                                                "测试完成后，将按照当前择优顺序自动连接最优节点。"
                                            } else {
                                                "点击下方按钮，按照当前面板配置执行测试。"
                                            }
                                        },
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                if (autoTestProgress.total > 0) {
                                    LinearProgressIndicator(
                                        progress = (autoTestProgress.completed.toFloat() / autoTestProgress.total)
                                            .coerceIn(0f, 1f),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Text(
                                        text = "进度：${autoTestProgress.completed}/${autoTestProgress.total}",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else if (autoTestProgress.running) {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = onShowRecentAutoTestResults,
                            enabled = controlsEnabled && hasRecentAutoTestResults,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("查看最近一次测试结果")
                        }

                        Button(
                            onClick = {
                                if (autoTestProgress.running) {
                                    onCancelAutomatedTest()
                                } else {
                                    onStartAutomatedTest(
                                        currentPanelConfig(),
                                        autoConnectBest,
                                        currentExecutionMode()
                                    )
                                }
                            },
                            enabled = !canceling && (
                                autoTestProgress.running || isPanelConfigValid(
                                    latencyEnabled = autoTestLatencyEnabled,
                                    latencyInput = latencyInput,
                                    bandwidthEnabled = autoTestBandwidthEnabled,
                                    downloadEnabled = autoTestBandwidthDownloadEnabled,
                                    downloadInput = downloadThresholdInput,
                                    uploadEnabled = autoTestBandwidthUploadEnabled,
                                    uploadInput = uploadThresholdInput,
                                    unlockEnabled = autoTestUnlockEnabled,
                                    nodeLimitInput = nodeLimitInput
                                )
                            ),
                            modifier = Modifier.fillMaxWidth(),
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
                            Text(
                                when {
                                    canceling -> "正在取消…"
                                    autoTestProgress.running -> "取消测试"
                                    priorityOrderLabel.isBlank() -> "开始测试"
                                    else -> "开始测试 · $priorityOrderLabel"
                                }
                            )
                        }
                    }
                }
            }
        }
        if (showUnlockPriorityPage) {
            UnlockPrioritySettingsPage(
                mode = unlockPriorityModeDraft,
                selectedSiteIds = unlockTargetSiteIdsDraft,
                onModeChange = { unlockPriorityModeDraft = it },
                onToggleSite = { siteId ->
                    unlockTargetSiteIdsDraft = if (siteId in unlockTargetSiteIdsDraft) {
                        unlockTargetSiteIdsDraft - siteId
                    } else {
                        unlockTargetSiteIdsDraft + siteId
                    }
                },
                onSelectAll = {
                    unlockTargetSiteIdsDraft = AppConfig.UNLOCK_PRIORITY_PRESET_SITES
                        .map { it.id }
                        .toSet()
                },
                onClear = { unlockTargetSiteIdsDraft = emptySet() },
                onBack = closeUnlockPriorityPage,
                onSave = {
                    onUpdateCurrentPreferModeUnlockPriority(
                        unlockPriorityModeDraft,
                        unlockTargetSiteIdsDraft.toList()
                    )
                    showUnlockPriorityPage = false
                }
            )
        }
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("确认删除当前模式") },
            text = { Text("删除后无法找回，是否继续删除？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        onDeleteCurrentPreferTestMode()
                    },
                    modifier = Modifier.defaultMinSize(minHeight = 34.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirmDialog = false },
                    modifier = Modifier.defaultMinSize(minHeight = 34.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                ) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun UnlockPrioritySettingsPage(
    mode: UnlockPriorityMode,
    selectedSiteIds: Set<String>,
    onModeChange: (UnlockPriorityMode) -> Unit,
    onToggleSite: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
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
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                }
                Text(
                    text = "解锁情况设置",
                    modifier = Modifier.weight(1f),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                TextButton(
                    onClick = onSave,
                    enabled = mode != UnlockPriorityMode.TARGET_SITES || selectedSiteIds.isNotEmpty()
                ) {
                    Text("保存")
                }
            }

            Divider()
            Spacer(modifier = Modifier.height(10.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SectionTitle("判定方式")
                PriorityOption("按解锁数", mode == UnlockPriorityMode.COUNT) {
                    onModeChange(UnlockPriorityMode.COUNT)
                }
                PriorityOption("按指定网站（可多选）", mode == UnlockPriorityMode.TARGET_SITES) {
                    onModeChange(UnlockPriorityMode.TARGET_SITES)
                }

                if (mode == UnlockPriorityMode.TARGET_SITES) {
                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                    SectionTitle("指定网站")
                    Text(
                        text = "命中网站越多越优先；数量相同时继续比较后续择优项。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(
                            onClick = onSelectAll,
                            modifier = Modifier.defaultMinSize(minHeight = 32.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                        ) {
                            Text("全选")
                        }
                        TextButton(
                            onClick = onClear,
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
                                .clickable { onToggleSite(site.id) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = site.id in selectedSiteIds,
                                onCheckedChange = { onToggleSite(site.id) }
                            )
                            Text(site.label)
                        }
                    }
                }
            }
        }
    }
}

private fun priorityLabel(
    priority: BestNodePriority,
    unlockMode: UnlockPriorityMode = UnlockPriorityMode.COUNT
): String = when (priority) {
    BestNodePriority.LATENCY -> "延迟"
    BestNodePriority.UPLOAD -> "上行"
    BestNodePriority.DOWNLOAD -> "下行"
    BestNodePriority.UNLOCK_COUNT -> when (unlockMode) {
        UnlockPriorityMode.COUNT -> "解锁数"
        UnlockPriorityMode.TARGET_SITES -> "指定网站"
    }
}

private fun autoTestStageLabel(stage: AutoTestStage): String = when (stage) {
    AutoTestStage.IDLE -> "等待开始"
    AutoTestStage.FETCH_NODES -> "更新节点"
    AutoTestStage.LATENCY_TEST, AutoTestStage.URL_TEST -> "延迟测试"
    AutoTestStage.FILTER_LATENCY -> "延迟筛选"
    AutoTestStage.BANDWIDTH_TEST -> "带宽测试"
    AutoTestStage.FILTER_BANDWIDTH -> "带宽筛选"
    AutoTestStage.UNLOCK_TEST -> "解锁测试"
    AutoTestStage.DONE -> "测试完成"
    AutoTestStage.CANCELED -> "正在取消"
    AutoTestStage.FAILED -> "测试失败"
}

private fun isPanelConfigValid(
    latencyEnabled: Boolean,
    latencyInput: String,
    bandwidthEnabled: Boolean,
    downloadEnabled: Boolean,
    downloadInput: String,
    uploadEnabled: Boolean,
    uploadInput: String,
    unlockEnabled: Boolean,
    nodeLimitInput: String
): Boolean {
    val hasRunnableTest = latencyEnabled || unlockEnabled ||
        (bandwidthEnabled && (downloadEnabled || uploadEnabled))
    val latencyValid = !latencyEnabled || latencyInput.toIntOrNull()?.let { it >= 50 } == true
    val downloadValid = !bandwidthEnabled || !downloadEnabled ||
        downloadInput.toIntOrNull()?.let { it >= 1 } == true
    val uploadValid = !bandwidthEnabled || !uploadEnabled ||
        uploadInput.toIntOrNull()?.let { it >= 1 } == true
    val nodeLimitValid = nodeLimitInput.toIntOrNull()?.let { it in 1..200 } == true
    return hasRunnableTest && latencyValid && downloadValid && uploadValid && nodeLimitValid
}

private fun isPriorityTestEnabled(
    priority: BestNodePriority,
    latencyEnabled: Boolean,
    bandwidthEnabled: Boolean,
    downloadEnabled: Boolean,
    uploadEnabled: Boolean,
    unlockEnabled: Boolean
): Boolean = when (priority) {
    BestNodePriority.LATENCY -> latencyEnabled
    BestNodePriority.UPLOAD -> bandwidthEnabled && uploadEnabled
    BestNodePriority.DOWNLOAD -> bandwidthEnabled && downloadEnabled
    BestNodePriority.UNLOCK_COUNT -> unlockEnabled
}

private fun <T> List<T>.swapItems(first: T, second: T): List<T> {
    val firstIndex = indexOf(first)
    val secondIndex = indexOf(second)
    if (firstIndex !in indices || secondIndex !in indices || firstIndex == secondIndex) return this
    return toMutableList().apply {
        this[firstIndex] = second
        this[secondIndex] = first
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
}

@Composable
private fun PriorityOrderItem(
    index: Int,
    label: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onConfigure: (() -> Unit)?
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    text = "${index + 1}",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(label, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (onConfigure != null) {
                TextButton(
                    onClick = onConfigure,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 32.dp)
                ) {
                    Text("设置", fontSize = 12.sp)
                }
            }
            TextButton(
                onClick = onMoveUp,
                enabled = canMoveUp,
                contentPadding = PaddingValues(horizontal = 7.dp, vertical = 2.dp),
                modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 32.dp)
            ) {
                Text("上移", fontSize = 12.sp)
            }
            TextButton(
                onClick = onMoveDown,
                enabled = canMoveDown,
                contentPadding = PaddingValues(horizontal = 7.dp, vertical = 2.dp),
                modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 32.dp)
            ) {
                Text("下移", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    checked: Boolean,
    text: String,
    subtitle: String? = null,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = text,
                color = contentColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (enabled) 1f else 0.5f
                    )
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = visibleSwitchColors()
        )
    }
}

@Composable
private fun visibleSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
    uncheckedTrackColor = MaterialTheme.colorScheme.surface,
    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
    disabledUncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
    disabledUncheckedTrackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
    disabledUncheckedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
)

private enum class LatencyTestSelection {
    DISABLED,
    TCPING,
    URL_TEST
}

@Composable
private fun <T> PanelDropdownItem(
    title: String,
    currentValue: String,
    options: List<Pair<T, String>>,
    enabled: Boolean,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { expanded = true }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f)
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "当前：$currentValue",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.5f)
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun PriorityOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = selected,
            onCheckedChange = { onClick() },
            colors = visibleSwitchColors()
        )
    }
}
