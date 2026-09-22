package xyz.a202132.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.a202132.app.AppConfig
import xyz.a202132.app.data.model.AppThemeMode
import xyz.a202132.app.data.model.IPv6RoutingMode
import xyz.a202132.app.data.model.TunStackMode
import xyz.a202132.app.data.model.VpnState
import xyz.a202132.app.ui.components.AppScreenScaffold
import xyz.a202132.app.ui.theme.Primary
import xyz.a202132.app.viewmodel.MainViewModel
import xyz.a202132.app.viewmodel.StartupDefaultTestMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtherConfigScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val startupDefaultTestMode by viewModel.startupDefaultTestMode.collectAsState()
    val nodeIpInfoTestOnVpnStart by viewModel.nodeIpInfoTestOnVpnStart.collectAsState()
    val vpnConnectivityRecoveryEnabled by viewModel.vpnConnectivityRecoveryEnabled.collectAsState()
    val hysteria2UploadMbps by viewModel.hysteria2UploadMbps.collectAsState()
    val hysteria2DownloadMbps by viewModel.hysteria2DownloadMbps.collectAsState()
    val hysteria2BandwidthAdaptationState by viewModel.hysteria2BandwidthAdaptationState.collectAsState()
    val tcpingTestTimeoutMs by viewModel.tcpingTestTimeoutMs.collectAsState()
    val urlTestTimeoutMs by viewModel.urlTestTimeoutMs.collectAsState()
    val nodeIpInfoTimeoutMs by viewModel.nodeIpInfoTimeoutMs.collectAsState()
    val speedTestDownloadTimeoutMs by viewModel.speedTestDownloadTimeoutMs.collectAsState()
    val tcpingConcurrency by viewModel.tcpingConcurrency.collectAsState()
    val urlTestConcurrency by viewModel.urlTestConcurrency.collectAsState()
    val bandwidthTestConcurrency by viewModel.bandwidthTestConcurrency.collectAsState()
    val unlockTestConcurrency by viewModel.unlockTestConcurrency.collectAsState()
    val vpnMtu by viewModel.vpnMtu.collectAsState()
    val vpnState by viewModel.vpnState.collectAsState()
    val rememberLastSelectedNodeEnabled by viewModel.rememberLastSelectedNodeEnabled.collectAsState()
    val appThemeMode by viewModel.appThemeMode.collectAsState()
    val bypassLan by viewModel.bypassLan.collectAsState()
    val ipv6RoutingMode by viewModel.ipv6RoutingMode.collectAsState()
    val tunStackMode by viewModel.tunStackMode.collectAsState()

    var appThemeModeDraft by remember(appThemeMode) { mutableStateOf(appThemeMode) }
    var startupModeDraft by remember(startupDefaultTestMode) { mutableStateOf(startupDefaultTestMode) }
    var rememberLastSelectedNodeDraft by remember(rememberLastSelectedNodeEnabled) {
        mutableStateOf(rememberLastSelectedNodeEnabled)
    }
    var nodeIpInfoAutoRunDraft by remember(nodeIpInfoTestOnVpnStart) {
        mutableStateOf(nodeIpInfoTestOnVpnStart)
    }
    var vpnConnectivityRecoveryDraft by remember(vpnConnectivityRecoveryEnabled) {
        mutableStateOf(vpnConnectivityRecoveryEnabled)
    }
    var hysteria2UploadInput by remember(hysteria2UploadMbps) {
        mutableStateOf(hysteria2UploadMbps.toString())
    }
    var hysteria2DownloadInput by remember(hysteria2DownloadMbps) {
        mutableStateOf(hysteria2DownloadMbps.toString())
    }
    var tcpingTimeoutInput by remember(tcpingTestTimeoutMs) { mutableStateOf(tcpingTestTimeoutMs.toString()) }
    var urlTestTimeoutInput by remember(urlTestTimeoutMs) { mutableStateOf(urlTestTimeoutMs.toString()) }
    var nodeIpInfoTimeoutInput by remember(nodeIpInfoTimeoutMs) { mutableStateOf(nodeIpInfoTimeoutMs.toString()) }
    var speedTestDownloadTimeoutInput by remember(speedTestDownloadTimeoutMs) {
        mutableStateOf(speedTestDownloadTimeoutMs.toString())
    }
    var tcpingConcurrencyInput by remember(tcpingConcurrency) { mutableStateOf(tcpingConcurrency.toString()) }
    var urlTestConcurrencyInput by remember(urlTestConcurrency) { mutableStateOf(urlTestConcurrency.toString()) }
    var bandwidthTestConcurrencyInput by remember(bandwidthTestConcurrency) {
        mutableStateOf(bandwidthTestConcurrency.toString())
    }
    var unlockTestConcurrencyInput by remember(unlockTestConcurrency) {
        mutableStateOf(unlockTestConcurrency.toString())
    }
    var mtuInput by remember(vpnMtu) { mutableStateOf(vpnMtu.toString()) }

    LaunchedEffect(
        appThemeMode,
        startupDefaultTestMode,
        rememberLastSelectedNodeEnabled,
        nodeIpInfoTestOnVpnStart,
        vpnConnectivityRecoveryEnabled,
        hysteria2UploadMbps,
        hysteria2DownloadMbps,
        tcpingTestTimeoutMs,
        urlTestTimeoutMs,
        nodeIpInfoTimeoutMs,
        speedTestDownloadTimeoutMs,
        tcpingConcurrency,
        urlTestConcurrency,
        bandwidthTestConcurrency,
        unlockTestConcurrency,
        vpnMtu
    ) {
        appThemeModeDraft = appThemeMode
        startupModeDraft = startupDefaultTestMode
        rememberLastSelectedNodeDraft = rememberLastSelectedNodeEnabled
        nodeIpInfoAutoRunDraft = nodeIpInfoTestOnVpnStart
        vpnConnectivityRecoveryDraft = vpnConnectivityRecoveryEnabled
        hysteria2UploadInput = hysteria2UploadMbps.toString()
        hysteria2DownloadInput = hysteria2DownloadMbps.toString()
        tcpingTimeoutInput = tcpingTestTimeoutMs.toString()
        urlTestTimeoutInput = urlTestTimeoutMs.toString()
        nodeIpInfoTimeoutInput = nodeIpInfoTimeoutMs.toString()
        speedTestDownloadTimeoutInput = speedTestDownloadTimeoutMs.toString()
        tcpingConcurrencyInput = tcpingConcurrency.toString()
        urlTestConcurrencyInput = urlTestConcurrency.toString()
        bandwidthTestConcurrencyInput = bandwidthTestConcurrency.toString()
        unlockTestConcurrencyInput = unlockTestConcurrency.toString()
        mtuInput = vpnMtu.toString()
    }

    LaunchedEffect(hysteria2BandwidthAdaptationState.resultGeneration) {
        if (hysteria2BandwidthAdaptationState.resultGeneration > 0L) {
            hysteria2BandwidthAdaptationState.uploadMbps?.let { hysteria2UploadInput = it.toString() }
            hysteria2BandwidthAdaptationState.downloadMbps?.let { hysteria2DownloadInput = it.toString() }
        }
    }

    AppScreenScaffold(
        title = "其他配置",
        onBack = onBack,
        actions = {
            TextButton(
                onClick = {
                    val savedMtu = mtuInput.toIntOrNull()?.coerceIn(576, 9000) ?: vpnMtu
                    val savedHysteria2Upload = hysteria2UploadInput.toIntOrNull()
                        ?.coerceIn(
                            AppConfig.HYSTERIA2_MIN_BANDWIDTH_MBPS,
                            AppConfig.HYSTERIA2_MAX_BANDWIDTH_MBPS
                        )
                        ?: hysteria2UploadMbps
                    val savedHysteria2Download = hysteria2DownloadInput.toIntOrNull()
                        ?.coerceIn(
                            AppConfig.HYSTERIA2_MIN_BANDWIDTH_MBPS,
                            AppConfig.HYSTERIA2_MAX_BANDWIDTH_MBPS
                        )
                        ?: hysteria2DownloadMbps

                    viewModel.setAppThemeMode(appThemeModeDraft)
                    viewModel.setStartupDefaultTestMode(startupModeDraft)
                    viewModel.setRememberLastSelectedNodeEnabled(rememberLastSelectedNodeDraft)
                    viewModel.setNodeIpInfoTestOnVpnStart(nodeIpInfoAutoRunDraft)
                    viewModel.setVpnConnectivityRecoveryEnabled(vpnConnectivityRecoveryDraft)
                    viewModel.setHysteria2Bandwidth(savedHysteria2Upload, savedHysteria2Download)
                    viewModel.setTcpingTestTimeoutMs(
                        tcpingTimeoutInput.toLongOrNull()?.coerceAtLeast(500L) ?: tcpingTestTimeoutMs
                    )
                    viewModel.setUrlTestTimeoutMs(
                        urlTestTimeoutInput.toLongOrNull()?.coerceAtLeast(500L) ?: urlTestTimeoutMs
                    )
                    viewModel.setNodeIpInfoTimeoutMs(
                        nodeIpInfoTimeoutInput.toLongOrNull()?.coerceAtLeast(1000L) ?: nodeIpInfoTimeoutMs
                    )
                    viewModel.setSpeedTestDownloadTimeoutMs(
                        speedTestDownloadTimeoutInput.toLongOrNull()?.coerceAtLeast(0L)
                            ?: speedTestDownloadTimeoutMs
                    )
                    viewModel.setTcpingConcurrency(
                        tcpingConcurrencyInput.toIntOrNull()?.coerceIn(1, 128) ?: tcpingConcurrency
                    )
                    viewModel.setUrlTestConcurrency(
                        urlTestConcurrencyInput.toIntOrNull()?.coerceIn(1, 128) ?: urlTestConcurrency
                    )
                    viewModel.setBandwidthTestConcurrency(
                        bandwidthTestConcurrencyInput.toIntOrNull()?.coerceIn(1, 3)
                            ?: bandwidthTestConcurrency
                    )
                    viewModel.setUnlockTestConcurrency(
                        unlockTestConcurrencyInput.toIntOrNull()?.coerceIn(1, 32) ?: unlockTestConcurrency
                    )
                    viewModel.setVpnMtu(savedMtu)
                    Toast.makeText(
                        context,
                        if (vpnState == VpnState.CONNECTED && (
                                savedMtu != vpnMtu ||
                                    savedHysteria2Upload != hysteria2UploadMbps ||
                                    savedHysteria2Download != hysteria2DownloadMbps
                                )
                        ) {
                            "设置已保存，MTU/带宽需断开并重连 VPN 后生效"
                        } else {
                            "设置已保存"
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                }
            ) {
                Text("保存")
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            OtherConfigSection(
                title = "网络配置",
                description = "点击对应功能即可修改；VPN 连接中切换会自动重连并应用。"
            ) {
                ConfigDropdownItem(
                    title = "TUN 实现",
                    currentValue = tunStackModeLabel(tunStackMode),
                    options = listOf(
                        TunStackMode.GVISOR to "gVisor（兼容优先）",
                        TunStackMode.MIXED to "Mixed（均衡）",
                        TunStackMode.SYSTEM to "System（性能优先）"
                    ),
                    onSelect = viewModel::setTunStackMode
                )
                ConfigDropdownItem(
                    title = "IPv6 路由",
                    currentValue = ipv6ModeLabel(ipv6RoutingMode),
                    options = listOf(
                        IPv6RoutingMode.ONLY to "仅 IPv6",
                        IPv6RoutingMode.PREFER to "优先 IPv6",
                        IPv6RoutingMode.ENABLED to "启用",
                        IPv6RoutingMode.DISABLED to "禁用"
                    ),
                    onSelect = viewModel::setIPv6RoutingMode
                )
                ConfigToggleItem(
                    title = "绕过局域网",
                    subtitle = enabledLabel(bypassLan),
                    checked = bypassLan,
                    onCheckedChange = viewModel::setBypassLan
                )
            }

            SectionDivider()

            OtherConfigSection(title = "夜间模式") {
                ConfigDropdownItem(
                    title = "显示模式",
                    currentValue = appThemeModeLabel(appThemeModeDraft),
                    options = listOf(
                        AppThemeMode.SYSTEM to "跟随系统",
                        AppThemeMode.LIGHT to "浅色",
                        AppThemeMode.DARK to "深色"
                    ),
                    onSelect = { appThemeModeDraft = it }
                )
            }

            SectionDivider()

            OtherConfigSection(
                title = "自动执行",
                description = "修改后点击右上角“保存”生效。"
            ) {
                ConfigDropdownItem(
                    title = "APP 启动默认测试",
                    currentValue = startupModeLabel(startupModeDraft),
                    options = listOf(
                        StartupDefaultTestMode.NONE to "不执行",
                        StartupDefaultTestMode.TCPING to "TCPing",
                        StartupDefaultTestMode.URL_TEST to "URL Test"
                    ),
                    onSelect = { startupModeDraft = it }
                )
                ConfigToggleItem(
                    title = "记住上次选择的节点",
                    subtitle = if (rememberLastSelectedNodeDraft) {
                        "当前：已开启 · 启动后恢复上次选择的节点"
                    } else {
                        "当前：已关闭"
                    },
                    checked = rememberLastSelectedNodeDraft,
                    onCheckedChange = { rememberLastSelectedNodeDraft = it }
                )
                ConfigToggleItem(
                    title = "VPN 连接后获取节点 IP 信息",
                    subtitle = enabledLabel(nodeIpInfoAutoRunDraft),
                    checked = nodeIpInfoAutoRunDraft,
                    onCheckedChange = { nodeIpInfoAutoRunDraft = it }
                )
                ConfigToggleItem(
                    title = "VPN 连接异常自动恢复",
                    subtitle = if (vpnConnectivityRecoveryDraft) {
                        "当前：已开启 · 每 6 秒检测，首次失败并重试两次后尝试恢复"
                    } else {
                        "当前：已关闭"
                    },
                    checked = vpnConnectivityRecoveryDraft,
                    onCheckedChange = { vpnConnectivityRecoveryDraft = it }
                )
            }

            SectionDivider()

            OtherConfigSection(
                title = "Hysteria2 带宽",
                description = "单位 Mbps，仅影响 Hysteria2。自适应会直连预热并分别测试 25MB 上下行，保留 20% 余量，约消耗 50.2MB 流量。"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OtherConfigNumberField(
                        label = "上行 Mbps",
                        value = hysteria2UploadInput,
                        onValueChange = { hysteria2UploadInput = it.filter(Char::isDigit) },
                        modifier = Modifier.weight(1f),
                        enabled = !hysteria2BandwidthAdaptationState.isRunning
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OtherConfigNumberField(
                        label = "下行 Mbps",
                        value = hysteria2DownloadInput,
                        onValueChange = { hysteria2DownloadInput = it.filter(Char::isDigit) },
                        modifier = Modifier.weight(1f),
                        enabled = !hysteria2BandwidthAdaptationState.isRunning
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    TextButton(
                        onClick = viewModel::adaptHysteria2Bandwidth,
                        enabled = !hysteria2BandwidthAdaptationState.isRunning,
                        modifier = Modifier.padding(bottom = 10.dp)
                    ) {
                        Text(if (hysteria2BandwidthAdaptationState.isRunning) "测速中…" else "自适应")
                    }
                }
                Text(
                    text = "有效范围：${AppConfig.HYSTERIA2_MIN_BANDWIDTH_MBPS}~${AppConfig.HYSTERIA2_MAX_BANDWIDTH_MBPS} Mbps；低于下限会自动按 ${AppConfig.HYSTERIA2_MIN_BANDWIDTH_MBPS} Mbps 保存，修改后重新连接 VPN 生效。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionDivider()

            OtherConfigSection(
                title = "测试超时",
                description = "单位均为毫秒。下载测速超时设为 0 表示不限制。"
            ) {
                OtherConfigNumberField(
                    label = "TCPing 超时",
                    value = tcpingTimeoutInput,
                    onValueChange = { tcpingTimeoutInput = it.filter(Char::isDigit) }
                )
                OtherConfigNumberField(
                    label = "URL Test 超时",
                    value = urlTestTimeoutInput,
                    onValueChange = { urlTestTimeoutInput = it.filter(Char::isDigit) }
                )
                OtherConfigNumberField(
                    label = "节点 IP 信息超时",
                    value = nodeIpInfoTimeoutInput,
                    onValueChange = { nodeIpInfoTimeoutInput = it.filter(Char::isDigit) }
                )
                OtherConfigNumberField(
                    label = "单次下载测速超时",
                    value = speedTestDownloadTimeoutInput,
                    onValueChange = { speedTestDownloadTimeoutInput = it.filter(Char::isDigit) }
                )
            }

            SectionDivider()

            OtherConfigSection(
                title = "并发与网络",
                description = "并发配置对下一次测试生效；MTU 需断开并重连 VPN 生效。"
            ) {
                OtherConfigNumberField(
                    label = "TCPing 并发数",
                    value = tcpingConcurrencyInput,
                    onValueChange = { tcpingConcurrencyInput = it.filter(Char::isDigit) }
                )
                OtherConfigNumberField(
                    label = "URL Test 并发数",
                    value = urlTestConcurrencyInput,
                    onValueChange = { urlTestConcurrencyInput = it.filter(Char::isDigit) }
                )
                OtherConfigNumberField(
                    label = "带宽测试并发数",
                    value = bandwidthTestConcurrencyInput,
                    onValueChange = { bandwidthTestConcurrencyInput = it.filter(Char::isDigit) }
                )
                OtherConfigNumberField(
                    label = "主流站解锁测试并发数",
                    value = unlockTestConcurrencyInput,
                    onValueChange = { unlockTestConcurrencyInput = it.filter(Char::isDigit) }
                )
                OtherConfigNumberField(
                    label = "VPN MTU",
                    value = mtuInput,
                    onValueChange = { mtuInput = it.filter(Char::isDigit) }
                )
                if (vpnState == VpnState.CONNECTED) {
                    Text(
                        text = "当前 VPN 已连接，修改 MTU 后请断开并重新连接。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

}

@Composable
private fun OtherConfigSection(
    title: String,
    description: String = "",
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (description.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun <T> ConfigDropdownItem(
    title: String,
    currentValue: String,
    options: List<Pair<T, String>>,
    enabled: Boolean = true,
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
            ConfigItemText(
                title = title,
                subtitle = "当前：$currentValue",
                enabled = enabled,
                modifier = Modifier.weight(1f)
            )
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
private fun ConfigToggleItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ConfigItemText(
            title = title,
            subtitle = subtitle,
            enabled = enabled,
            modifier = Modifier.weight(1f)
        )
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
private fun ConfigItemText(
    title: String,
    subtitle: String,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f)
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = subtitle,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.5f)
        )
    }
}

@Composable
private fun SectionDivider() {
    Divider(
        modifier = Modifier.padding(vertical = 18.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outline
    )
}

@Composable
private fun OtherConfigNumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.padding(bottom = 10.dp),
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

@Composable
private fun visibleSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = Primary,
    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
    uncheckedTrackColor = MaterialTheme.colorScheme.surface,
    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
    disabledUncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
    disabledUncheckedTrackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
    disabledUncheckedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
)

private fun ipv6ModeLabel(mode: IPv6RoutingMode): String = when (mode) {
    IPv6RoutingMode.ONLY -> "仅 IPv6"
    IPv6RoutingMode.PREFER -> "优先 IPv6"
    IPv6RoutingMode.ENABLED -> "启用"
    IPv6RoutingMode.DISABLED -> "禁用"
}

private fun tunStackModeLabel(mode: TunStackMode): String = when (mode) {
    TunStackMode.GVISOR -> "gVisor（兼容优先）"
    TunStackMode.MIXED -> "Mixed（均衡）"
    TunStackMode.SYSTEM -> "System（性能优先）"
}

private fun appThemeModeLabel(mode: AppThemeMode): String = when (mode) {
    AppThemeMode.SYSTEM -> "跟随系统"
    AppThemeMode.LIGHT -> "浅色"
    AppThemeMode.DARK -> "深色"
}

private fun startupModeLabel(mode: StartupDefaultTestMode): String = when (mode) {
    StartupDefaultTestMode.NONE -> "不执行"
    StartupDefaultTestMode.TCPING -> "TCPing"
    StartupDefaultTestMode.URL_TEST -> "URL Test"
}

private fun enabledLabel(enabled: Boolean): String = if (enabled) "当前：已开启" else "当前：已关闭"
