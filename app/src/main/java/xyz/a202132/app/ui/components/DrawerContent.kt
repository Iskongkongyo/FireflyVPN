package xyz.a202132.app.ui.components

import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.a202132.app.BuildConfig
import xyz.a202132.app.R
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import xyz.a202132.app.data.model.IPv6RoutingMode
import xyz.a202132.app.data.model.RemoteAppSettings
import xyz.a202132.app.viewmodel.AutoTestLatencyMode
import xyz.a202132.app.viewmodel.BestNodePriority
import xyz.a202132.app.viewmodel.TestPreferMode

@Composable
fun DrawerContent(
    remoteAppSettings: RemoteAppSettings,
    onOpenAbout: () -> Unit,
    onOpenPerAppProxy: () -> Unit,
    onOpenSubscriptionManagement: () -> Unit,
    onOpenRuleManagement: () -> Unit,
    onOpenOtherConfig: () -> Unit,
    onOpenLanProxy: () -> Unit,
    onOpenRuntimeLog: () -> Unit,
    onOpenTestPreferPanel: () -> Unit,
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
    onSaveCurrentPreferTestMode: (String) -> Unit,
    onDeleteCurrentPreferTestMode: () -> Unit,
    onHideUnqualifiedAutoTestNodes: () -> Unit,
    onSelectBestNodeByPriority: (BestNodePriority, Boolean) -> Unit,
    onUpdateCurrentPreferModePriority: (BestNodePriority) -> Unit,
    onStartAutomatedTest: () -> Unit,
    onCancelAutomatedTest: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(280.dp)
            .padding(bottom = 24.dp)
    ) {
        Image(
            painter = painterResource(id = R.drawable.drawer_wallpaper),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
        )
        
        Divider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(bottom = 8.dp))
        
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 8.dp)
        ) {
            // 菜单项

            DrawerMenuItem(
                iconRes = R.drawable.ic_group_management,
                title = "分组管理",
                subtitle = "管理节点分组",
                onClick = {
                    onOpenSubscriptionManagement()
                    onClose()
                }
            )

            DrawerMenuItem(
                icon = Icons.Outlined.Rule,
                title = stringResource(R.string.rule_management_title),
                onClick = {
                    onOpenRuleManagement()
                    onClose()
                }
            )

            DrawerMenuItem(
                iconRes = R.drawable.ic_test_prefer,
                title = "择优面板",
                subtitle = autoTestProgress.message.ifBlank {
                    if (autoTestProgress.running) "运行中..." else "未运行"
                },
                onClick = {
                    onOpenTestPreferPanel()
                    onClose()
                }
            )

             DrawerMenuItem(
                icon = Icons.Outlined.Tune,
                title = "其他配置",
                onClick = {
                    onOpenOtherConfig()
                    onClose()
                }
            )

            DrawerMenuItem(
                icon = Icons.Outlined.Apps,
                title = "分应用代理",
                subtitle = "选择代理应用",
                onClick = {
                    onOpenPerAppProxy()
                    onClose()
                }
            )

            DrawerMenuItem(
                icon = Icons.Outlined.WifiTethering,
                title = "局域网代理",
                subtitle = "代理其他设备",
                onClick = {
                    onOpenLanProxy()
                    onClose()
                }
            )

            DrawerMenuItem(
                icon = Icons.Outlined.Article,
                title = "运行日志",
                onClick = {
                    onOpenRuntimeLog()
                    onClose()
                }
            )
            
            val websiteUrl = remoteAppSettings.websiteUrl.trim()
            if (websiteUrl.isNotBlank()) {
                DrawerMenuItem(
                    icon = Icons.Outlined.Language,
                    title = "官方网站",
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(websiteUrl))
                        context.startActivity(intent)
                        onClose()
                    }
                )
            }
            
            // 问题反馈 - 支持邮箱复制 + 链接跳转
            val feedbackEmail = remoteAppSettings.feedbackEmail.trim()
            val feedbackUrl = remoteAppSettings.feedbackUrl.trim()
            val hasEmail = feedbackEmail.isNotBlank()
            val hasFeedbackUrl = feedbackUrl.isNotBlank()
            
            if (hasEmail || hasFeedbackUrl) {
                DrawerMenuItem(
                    icon = Icons.Outlined.Email,
                    title = "问题反馈",
                    subtitle = feedbackEmail.takeIf { hasEmail },
                    onClick = {
                        if (hasEmail) {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("email", feedbackEmail)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "邮箱已复制", Toast.LENGTH_SHORT).show()
                        }
                        if (hasFeedbackUrl) {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(feedbackUrl))
                            context.startActivity(intent)
                            onClose()
                        }
                    }
                )
            }
            
            DrawerMenuItem(
                icon = Icons.Outlined.Info,
                title = "关于应用",
                onClick = onOpenAbout
            )
            
            Spacer(modifier = Modifier.height(12.dp))
        }

        Divider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 8.dp))
        
        // 版本信息
        Text(
            text = "版本 ${BuildConfig.VERSION_NAME}",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 20.dp)
        )
    }
    
}

@Composable
private fun DrawerMenuItem(
    icon: ImageVector? = null,
    @DrawableRes iconRes: Int? = null,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        color = androidx.compose.ui.graphics.Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                iconRes != null -> Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                icon != null -> Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun IPv6RoutingDialog(
    currentMode: IPv6RoutingMode,
    onModeSelected: (IPv6RoutingMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "IPv6 路由",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = "选择 IPv6 路由模式：",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                val options = listOf(
                    Triple(IPv6RoutingMode.ONLY, "仅", "仅使用 IPv6（实验）"),
                    Triple(IPv6RoutingMode.PREFER, "优先", "优先使用 IPv6"),
                    Triple(IPv6RoutingMode.ENABLED, "启用", "同时支持 IPv4 和 IPv6"),
                    Triple(IPv6RoutingMode.DISABLED, "禁用", "不使用 IPv6（默认）")
                )

                options.forEach { (mode, label, description) ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onModeSelected(mode) },
                        color = if (currentMode == mode)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            androidx.compose.ui.graphics.Color.Transparent,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Text(
                                text = label,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (currentMode == mode)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = description,
                                fontSize = 12.sp,
                                color = if (currentMode == mode)
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AutoTestConfigDialogV2(
    autoTestEnabled: Boolean,
    autoTestFilterUnavailable: Boolean,
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
    onSetAutoTestEnabled: (Boolean) -> Unit,
    onSetAutoTestFilterUnavailable: (Boolean) -> Unit,
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
    onStartAutomatedTest: () -> Unit,
    onCancelAutomatedTest: () -> Unit,
    onDismiss: () -> Unit
) {
    var latencyInput by remember { mutableStateOf(autoTestLatencyThresholdMs.toString()) }
    var downloadBandwidthThresholdInput by remember { mutableStateOf(autoTestBandwidthDownloadThresholdMbps.toString()) }
    var uploadBandwidthThresholdInput by remember { mutableStateOf(autoTestBandwidthUploadThresholdMbps.toString()) }
    var nodeLimitInput by remember { mutableStateOf(autoTestNodeLimit.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自动化测试") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = if (autoTestProgress.running) {
                        "当前阶段: ${autoTestProgress.stage}\n${autoTestProgress.message}"
                    } else {
                        "测试流程：拉节点 -> 延迟测试(TCPing/URL Test) -> 带宽测试(上下行) -> 流媒体解锁测试"
                    },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = autoTestEnabled, onCheckedChange = onSetAutoTestEnabled)
                    Text("启用自动化测试")
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = autoTestFilterUnavailable, onCheckedChange = onSetAutoTestFilterUnavailable)
                    Text("自动排除无效/高延迟节点")
                }

                Text(
                    text = "延迟测试方式",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = autoTestLatencyMode == AutoTestLatencyMode.URL_TEST,
                        onClick = { onSetAutoTestLatencyMode(AutoTestLatencyMode.URL_TEST) },
                        label = { Text("URL Test") }
                    )
                    FilterChip(
                        selected = autoTestLatencyMode == AutoTestLatencyMode.TCPING,
                        onClick = { onSetAutoTestLatencyMode(AutoTestLatencyMode.TCPING) },
                        label = { Text("TCPing") }
                    )
                }

                OutlinedTextField(
                    value = latencyInput,
                    onValueChange = {
                        latencyInput = it.filter { ch -> ch.isDigit() }
                        latencyInput.toIntOrNull()?.let(onSetAutoTestLatencyThresholdMs)
                    },
                    label = { Text("延迟阈值(ms)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = autoTestBandwidthEnabled, onCheckedChange = onSetAutoTestBandwidthEnabled)
                    Text("自动进行带宽测试")
                }

                OutlinedTextField(
                    value = downloadBandwidthThresholdInput,
                    onValueChange = {
                        downloadBandwidthThresholdInput = it.filter { ch -> ch.isDigit() }
                        downloadBandwidthThresholdInput.toIntOrNull()?.let(onSetAutoTestBandwidthDownloadThresholdMbps)
                    },
                    label = { Text("下载带宽阈值(Mbps)") },
                    singleLine = true,
                    enabled = autoTestBandwidthEnabled && autoTestBandwidthDownloadEnabled,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = uploadBandwidthThresholdInput,
                    onValueChange = {
                        uploadBandwidthThresholdInput = it.filter { ch -> ch.isDigit() }
                        uploadBandwidthThresholdInput.toIntOrNull()?.let(onSetAutoTestBandwidthUploadThresholdMbps)
                    },
                    label = { Text("上传带宽阈值(Mbps)") },
                    singleLine = true,
                    enabled = autoTestBandwidthEnabled && autoTestBandwidthUploadEnabled,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = autoTestBandwidthDownloadEnabled,
                        onCheckedChange = onSetAutoTestBandwidthDownloadEnabled,
                        enabled = autoTestBandwidthEnabled
                    )
                    Text("测试下载带宽")
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = autoTestBandwidthUploadEnabled,
                        onCheckedChange = onSetAutoTestBandwidthUploadEnabled,
                        enabled = autoTestBandwidthEnabled
                    )
                    Text("测试上传带宽")
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = autoTestBandwidthWifiOnly,
                        onCheckedChange = onSetAutoTestBandwidthWifiOnly,
                        enabled = autoTestBandwidthEnabled
                    )
                    Text("仅 Wi-Fi 下执行带宽测试")
                }

                Text(
                    text = "下载测试流量大小",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    listOf(1, 10, 25, 50).forEach { mb ->
                        FilterChip(
                            selected = autoTestBandwidthDownloadSizeMb == mb,
                            onClick = { onSetAutoTestBandwidthDownloadSizeMb(mb) },
                            label = { Text("${mb}MB") },
                            enabled = autoTestBandwidthEnabled && autoTestBandwidthDownloadEnabled
                        )
                    }
                }

                Text(
                    text = "上传测试流量大小",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    listOf(1, 10, 25, 50).forEach { mb ->
                        FilterChip(
                            selected = autoTestBandwidthUploadSizeMb == mb,
                            onClick = { onSetAutoTestBandwidthUploadSizeMb(mb) },
                            label = { Text("${mb}MB") },
                            enabled = autoTestBandwidthEnabled && autoTestBandwidthUploadEnabled
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = autoTestUnlockEnabled, onCheckedChange = onSetAutoTestUnlockEnabled)
                    Text("自动测试流媒体解锁")
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = autoTestByRegion, onCheckedChange = onSetAutoTestByRegion)
                    Text("分地区进行测试")
                }

                OutlinedTextField(
                    value = nodeLimitInput,
                    onValueChange = {
                        nodeLimitInput = it.filter { ch -> ch.isDigit() }
                        nodeLimitInput.toIntOrNull()?.let(onSetAutoTestNodeLimit)
                    },
                    label = { Text(if (autoTestByRegion) "每个地区测试节点上限" else "测试节点上限") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (autoTestProgress.running) {
                    onCancelAutomatedTest()
                } else {
                    onStartAutomatedTest()
                }
            }) {
                Text(if (autoTestProgress.running) "取消测试" else "开始测试")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}
