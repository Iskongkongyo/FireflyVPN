package xyz.a202132.app.ui.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.a202132.app.AppConfig
import xyz.a202132.app.BuildConfig
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import xyz.a202132.app.data.model.IPv6RoutingMode
import xyz.a202132.app.ui.dialogs.AboutDialog
import xyz.a202132.app.ui.theme.*

@Composable
fun DrawerContent(
    onCheckUpdate: () -> Unit,
    onOpenPerAppProxy: () -> Unit,
    bypassLan: Boolean,
    onToggleBypassLan: (Boolean) -> Unit,
    ipv6RoutingMode: IPv6RoutingMode,
    onIPv6RoutingModeChange: (IPv6RoutingMode) -> Unit,
    notice: xyz.a202132.app.data.model.NoticeInfo?,
    backupNodeEnabled: Boolean,
    onToggleBackupNode: (Boolean) -> Unit,
    autoTestEnabled: Boolean,
    autoTestFilterUnavailable: Boolean,
    autoTestLatencyThresholdMs: Int,
    autoTestBandwidthEnabled: Boolean,
    autoTestBandwidthThresholdMbps: Int,
    autoTestBandwidthWifiOnly: Boolean,
    autoTestBandwidthSizeMb: Int,
    autoTestUnlockEnabled: Boolean,
    autoTestNodeLimit: Int,
    autoTestProgress: xyz.a202132.app.viewmodel.AutoTestProgress,
    onSetAutoTestEnabled: (Boolean) -> Unit,
    onSetAutoTestFilterUnavailable: (Boolean) -> Unit,
    onSetAutoTestLatencyThresholdMs: (Int) -> Unit,
    onSetAutoTestBandwidthEnabled: (Boolean) -> Unit,
    onSetAutoTestBandwidthThresholdMbps: (Int) -> Unit,
    onSetAutoTestBandwidthWifiOnly: (Boolean) -> Unit,
    onSetAutoTestBandwidthSizeMb: (Int) -> Unit,
    onSetAutoTestUnlockEnabled: (Boolean) -> Unit,
    onSetAutoTestNodeLimit: (Int) -> Unit,
    onStartAutomatedTest: () -> Unit,
    onCancelAutomatedTest: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var showIPv6Dialog by remember { mutableStateOf(false) }
    var showBackupNodeConfirmDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showAutoTestDialog by remember { mutableStateOf(false) }
    
    // 检查备用节点是否可用
    val backupNodeInfo = notice?.backupNodes
    val isBackupNodeVisible = backupNodeInfo?.url?.let { 
        it.startsWith("http://") || it.startsWith("https://") 
    } == true
    
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(280.dp)
            .padding(vertical = 24.dp)
    ) {
        // 标题
        Text(
            text = "设置",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
        )
        
        Divider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 8.dp))
        
        // 菜单项
        DrawerMenuItem(
            icon = Icons.Outlined.SystemUpdate,
            title = "检查更新",
            onClick = {
                onCheckUpdate()
                onClose()
            }
        )

        // 备用节点 (仅在有效时显示)
        if (isBackupNodeVisible) {
            DrawerMenuToggle(
                icon = Icons.Outlined.Backup,
                title = "备用节点",
                subtitle = if (backupNodeEnabled) "已开启" else "已关闭",
                checked = backupNodeEnabled,
                onCheckedChange = { isChecked ->
                    if (isChecked) {
                        // 开启时显示确认对话框
                        showBackupNodeConfirmDialog = true
                    } else {
                        // 关闭直接执行
                        onToggleBackupNode(false)
                    }
                }
            )
        }

        // IPv6 路由菜单项
        DrawerMenuItem(
            icon = Icons.Outlined.SettingsEthernet,
            title = "IPv6 路由",
            subtitle = when (ipv6RoutingMode) {
                IPv6RoutingMode.DISABLED -> "禁用"
                IPv6RoutingMode.ENABLED -> "启用"
                IPv6RoutingMode.PREFER -> "优先"
                IPv6RoutingMode.ONLY -> "仅"
            },
            onClick = { showIPv6Dialog = true }
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
        
        DrawerMenuToggle(
            icon = Icons.Outlined.Router,
            title = "绕过局域网",
            subtitle = if (bypassLan) "已开启" else "已关闭",
            checked = bypassLan,
            onCheckedChange = { newValue ->
                onToggleBypassLan(newValue)
                Toast.makeText(
                    context,
                    if (newValue) "已开启绕过局域网" else "已关闭绕过局域网",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )

        DrawerMenuItem(
            icon = Icons.Outlined.Settings,
            title = "自动化测试",
            subtitle = autoTestProgress.message.ifBlank {
                if (autoTestProgress.running) "运行中..." else "未开启"
            },
            onClick = { showAutoTestDialog = true }
        )
        
        DrawerMenuItem(
            icon = Icons.Outlined.Language,
            title = "官方网站",
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(AppConfig.WEBSITE_URL))
                context.startActivity(intent)
                onClose()
            }
        )
        
        // 问题反馈 - 支持邮箱复制 + 链接跳转
        val hasEmail = AppConfig.FEEDBACK_EMAIL.isNotBlank()
        val hasFeedbackUrl = AppConfig.FEEDBACK_URL.isNotBlank()
        
        DrawerMenuItem(
            icon = Icons.Outlined.Email,
            title = "问题反馈",
            subtitle = if (hasEmail) AppConfig.FEEDBACK_EMAIL else null,
            onClick = {
                if (hasEmail) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("email", AppConfig.FEEDBACK_EMAIL)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "邮箱已复制", Toast.LENGTH_SHORT).show()
                }
                if (hasFeedbackUrl) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(AppConfig.FEEDBACK_URL))
                    context.startActivity(intent)
                    onClose()
                }
            }
        )
        
        DrawerMenuItem(
            icon = Icons.Outlined.Info,
            title = "关于",
            onClick = { showAboutDialog = true }
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        // 版本信息
        Text(
            text = "版本 ${BuildConfig.VERSION_NAME}",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
        )
    }
    
    // IPv6 路由选项弹窗
    if (showIPv6Dialog) {
        IPv6RoutingDialog(
            currentMode = ipv6RoutingMode,
            onModeSelected = { mode ->
                onIPv6RoutingModeChange(mode)
                showIPv6Dialog = false
                Toast.makeText(
                    context,
                    "IPv6 路由已设置为: " + when (mode) {
                        IPv6RoutingMode.DISABLED -> "禁用"
                        IPv6RoutingMode.ENABLED -> "启用"
                        IPv6RoutingMode.PREFER -> "优先"
                        IPv6RoutingMode.ONLY -> "仅"
                    },
                    Toast.LENGTH_SHORT
                ).show()
            },
            onDismiss = { showIPv6Dialog = false }
        )
    }
    
    // 备用节点开启确认弹窗
    if (showBackupNodeConfirmDialog && backupNodeInfo != null) {
        AlertDialog(
            onDismissRequest = { showBackupNodeConfirmDialog = false },
            title = { Text("开启备用节点") },
            text = { 
                Text(
                    text = if (!backupNodeInfo.msg.isNullOrBlank()) {
                        backupNodeInfo.msg
                    } else {
                        "开启后，节点列表只会显示备用节点信息！"
                    }
                ) 
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onToggleBackupNode(true)
                        showBackupNodeConfirmDialog = false
                    }
                ) {
                    Text("是")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackupNodeConfirmDialog = false }) {
                    Text("否")
                }
            }
        )
    }
    
    // 关于弹窗
    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }

    if (showAutoTestDialog) {
        AutoTestConfigDialog(
            autoTestEnabled = autoTestEnabled,
            autoTestFilterUnavailable = autoTestFilterUnavailable,
            autoTestLatencyThresholdMs = autoTestLatencyThresholdMs,
            autoTestBandwidthEnabled = autoTestBandwidthEnabled,
            autoTestBandwidthThresholdMbps = autoTestBandwidthThresholdMbps,
            autoTestBandwidthWifiOnly = autoTestBandwidthWifiOnly,
            autoTestBandwidthSizeMb = autoTestBandwidthSizeMb,
            autoTestUnlockEnabled = autoTestUnlockEnabled,
            autoTestNodeLimit = autoTestNodeLimit,
            autoTestProgress = autoTestProgress,
            onSetAutoTestEnabled = onSetAutoTestEnabled,
            onSetAutoTestFilterUnavailable = onSetAutoTestFilterUnavailable,
            onSetAutoTestLatencyThresholdMs = onSetAutoTestLatencyThresholdMs,
            onSetAutoTestBandwidthEnabled = onSetAutoTestBandwidthEnabled,
            onSetAutoTestBandwidthThresholdMbps = onSetAutoTestBandwidthThresholdMbps,
            onSetAutoTestBandwidthWifiOnly = onSetAutoTestBandwidthWifiOnly,
            onSetAutoTestBandwidthSizeMb = onSetAutoTestBandwidthSizeMb,
            onSetAutoTestUnlockEnabled = onSetAutoTestUnlockEnabled,
            onSetAutoTestNodeLimit = onSetAutoTestNodeLimit,
            onStartAutomatedTest = onStartAutomatedTest,
            onCancelAutomatedTest = onCancelAutomatedTest,
            onDismiss = { showAutoTestDialog = false }
        )
    }
}

@Composable
private fun DrawerMenuItem(
    icon: ImageVector,
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
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            
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
private fun DrawerMenuToggle(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        color = androidx.compose.ui.graphics.Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
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
            
            // Switch 移除，点击整行即可切换，更美观
        }
    }
}

/**
 * IPv6 路由选项弹窗
 */
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
                    IPv6RoutingMode.ONLY to "仅" to "仅使用 IPv6 (实验性)",
                    IPv6RoutingMode.PREFER to "优先" to "优先使用 IPv6",
                    IPv6RoutingMode.ENABLED to "启用" to "同时支持 IPv4 和 IPv6",
                    IPv6RoutingMode.DISABLED to "禁用" to "不使用 IPv6 (默认)"
                )
                
                options.forEach { (modePair, description) ->
                    val (mode, label) = modePair
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
private fun AutoTestConfigDialog(
    autoTestEnabled: Boolean,
    autoTestFilterUnavailable: Boolean,
    autoTestLatencyThresholdMs: Int,
    autoTestBandwidthEnabled: Boolean,
    autoTestBandwidthThresholdMbps: Int,
    autoTestBandwidthWifiOnly: Boolean,
    autoTestBandwidthSizeMb: Int,
    autoTestUnlockEnabled: Boolean,
    autoTestNodeLimit: Int,
    autoTestProgress: xyz.a202132.app.viewmodel.AutoTestProgress,
    onSetAutoTestEnabled: (Boolean) -> Unit,
    onSetAutoTestFilterUnavailable: (Boolean) -> Unit,
    onSetAutoTestLatencyThresholdMs: (Int) -> Unit,
    onSetAutoTestBandwidthEnabled: (Boolean) -> Unit,
    onSetAutoTestBandwidthThresholdMbps: (Int) -> Unit,
    onSetAutoTestBandwidthWifiOnly: (Boolean) -> Unit,
    onSetAutoTestBandwidthSizeMb: (Int) -> Unit,
    onSetAutoTestUnlockEnabled: (Boolean) -> Unit,
    onSetAutoTestNodeLimit: (Int) -> Unit,
    onStartAutomatedTest: () -> Unit,
    onCancelAutomatedTest: () -> Unit,
    onDismiss: () -> Unit
) {
    var latencyInput by remember { mutableStateOf(autoTestLatencyThresholdMs.toString()) }
    var bandwidthInput by remember { mutableStateOf(autoTestBandwidthThresholdMbps.toString()) }
    var nodeLimitInput by remember { mutableStateOf(autoTestNodeLimit.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自动化测试") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (autoTestProgress.running) {
                        "当前阶段: ${autoTestProgress.stage}\n${autoTestProgress.message}"
                    } else {
                        "拉节点 -> URL Test -> 带宽测试 -> 解锁测试"
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
                    Text("自动进行下行带宽测试")
                }

                OutlinedTextField(
                    value = bandwidthInput,
                    onValueChange = {
                        bandwidthInput = it.filter { ch -> ch.isDigit() }
                        bandwidthInput.toIntOrNull()?.let(onSetAutoTestBandwidthThresholdMbps)
                    },
                    label = { Text("带宽阈值(Mbps)") },
                    singleLine = true,
                    enabled = autoTestBandwidthEnabled,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = autoTestBandwidthWifiOnly,
                        onCheckedChange = onSetAutoTestBandwidthWifiOnly,
                        enabled = autoTestBandwidthEnabled
                    )
                    Text("仅 Wi-Fi 执行下行带宽测试")
                }

                Text(
                    text = "下载测试流量大小",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    listOf(1, 10, 25, 50).forEach { mb ->
                        FilterChip(
                            selected = autoTestBandwidthSizeMb == mb,
                            onClick = { onSetAutoTestBandwidthSizeMb(mb) },
                            label = { Text("${mb}MB") },
                            enabled = autoTestBandwidthEnabled
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = autoTestUnlockEnabled, onCheckedChange = onSetAutoTestUnlockEnabled)
                    Text("自动测试流媒体解锁")
                }

                OutlinedTextField(
                    value = nodeLimitInput,
                    onValueChange = {
                        nodeLimitInput = it.filter { ch -> ch.isDigit() }
                        nodeLimitInput.toIntOrNull()?.let(onSetAutoTestNodeLimit)
                    },
                    label = { Text("测试节点上限") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
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
