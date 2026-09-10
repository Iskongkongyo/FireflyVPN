package xyz.a202132.app.ui.dialogs

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.a202132.app.AppConfig
import xyz.a202132.app.SpeedTestSize
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.network.NetworkClient
import xyz.a202132.app.network.SpeedTestResult
import xyz.a202132.app.network.SpeedTestService
import xyz.a202132.app.network.UnlockTestManager
import xyz.a202132.app.service.BoxVpnService
import xyz.a202132.app.service.ServiceManager
import xyz.a202132.app.ui.theme.Primary
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket

enum class SpeedTestPhase {
    CONFIG,
    TESTING,
    RESULT
}

enum class SpeedTestDirection(val label: String) {
    DOWNLOAD("下载测速"),
    UPLOAD("上传测速"),
    BOTH("双向测速（先下后上）")
}

private enum class SpeedTestTargetMode(val label: String) {
    DIRECT("直连带宽"),
    NODE("节点带宽")
}

@Composable
fun SpeedTestDialog(
    node: Node? = null,
    useNodeBandwidth: Boolean = false,
    downloadTimeoutMs: Long = AppConfig.AUTO_TEST_BANDWIDTH_DOWNLOAD_TIMEOUT_MS,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val targetMode = if (useNodeBandwidth && node != null) SpeedTestTargetMode.NODE else SpeedTestTargetMode.DIRECT

    var phase by remember { mutableStateOf(SpeedTestPhase.CONFIG) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var activeService by remember { mutableStateOf<SpeedTestService?>(null) }

    val sizes = remember { AppConfig.getSpeedTestSizes() }
    var selectedSize by remember {
        mutableStateOf(
            sizes.firstOrNull { it.bytes == 25_000_000L }
                ?: sizes.lastOrNull()
                ?: SpeedTestSize("25MB", 25_000_000)
        )
    }
    var selectedDirection by remember { mutableStateOf(SpeedTestDirection.DOWNLOAD) }

    var currentSpeed by remember { mutableFloatStateOf(0f) }
    var progress by remember { mutableFloatStateOf(0f) }
    var currentOperation by remember { mutableStateOf("") }

    var downloadResult by remember { mutableStateOf<SpeedTestResult?>(null) }
    var uploadResult by remember { mutableStateOf<SpeedTestResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val animatedProgress by animateFloatAsState(targetValue = progress, label = "progress")

    fun dismissDialog() {
        activeService?.cancel()
        UnlockTestManager.stop()
        onDismiss()
    }

    fun startTest() {
        phase = SpeedTestPhase.TESTING
        error = null
        downloadResult = null
        uploadResult = null
        currentSpeed = 0f
        progress = 0f

        scope.launch(Dispatchers.IO) {
            var service: SpeedTestService? = null
            var sessionStarted = false
            try {
                val createdService = if (targetMode == SpeedTestTargetMode.NODE) {
                    val targetNode = node ?: throw IllegalStateException("当前没有可用节点")
                    val activeVpnPort = BoxVpnService.getInternalSocksPort()
                    val currentVpnProxyPort = activeVpnPort?.takeIf {
                        ServiceManager.currentNode.value?.id == targetNode.id
                    }
                    val port = currentVpnProxyPort ?: run {
                        pickFreePort().also { temporaryPort ->
                            if (!UnlockTestManager.start(context, targetNode, temporaryPort)) {
                                throw IllegalStateException("启动节点测速代理失败")
                            }
                            sessionStarted = true
                        }
                    }
                    SpeedTestService(
                        downloadTimeoutMs = downloadTimeoutMs,
                        proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
                    )
                } else {
                    val directClient = NetworkClient.createUnderlyingNetworkClient(
                        context,
                        maxOf(downloadTimeoutMs, AppConfig.AUTO_TEST_BANDWIDTH_UPLOAD_TIMEOUT_MS)
                    ) ?: throw IllegalStateException("当前没有可用的 Wi-Fi 或移动网络")
                    SpeedTestService(
                        downloadTimeoutMs = downloadTimeoutMs,
                        baseClient = directClient
                    )
                }
                service = createdService
                withContext(Dispatchers.Main) {
                    activeService = createdService
                }

                if (selectedDirection == SpeedTestDirection.DOWNLOAD || selectedDirection == SpeedTestDirection.BOTH) {
                    withContext(Dispatchers.Main) {
                        currentOperation = "正在下载..."
                        progress = 0f
                    }
                    val result = createdService.startDownloadTest(selectedSize.bytes) { speed, prog ->
                        scope.launch {
                            currentSpeed = speed
                            progress = prog
                        }
                    }
                    withContext(Dispatchers.Main) {
                        downloadResult = result
                    }
                }

                if (selectedDirection == SpeedTestDirection.UPLOAD || selectedDirection == SpeedTestDirection.BOTH) {
                    withContext(Dispatchers.Main) {
                        currentOperation = "正在上传..."
                        progress = 0f
                        currentSpeed = 0f
                    }
                    kotlinx.coroutines.delay(500)

                    val result = createdService.startUploadTest(selectedSize.bytes) { speed, prog ->
                        scope.launch {
                            currentSpeed = speed
                            progress = prog
                        }
                    }
                    withContext(Dispatchers.Main) {
                        uploadResult = result
                    }
                }

                withContext(Dispatchers.Main) {
                    phase = SpeedTestPhase.RESULT
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    error = "测速失败: ${e.message}"
                    phase = SpeedTestPhase.RESULT
                }
            } finally {
                service?.cancel()
                if (sessionStarted) {
                    UnlockTestManager.stop()
                }
                withContext(Dispatchers.Main) {
                    if (activeService === service) {
                        activeService = null
                    }
                }
            }
        }
    }

    Dialog(
        onDismissRequest = ::dismissDialog,
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Speed,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "网速测试",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(targetMode.label) }
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (phase != SpeedTestPhase.TESTING) {
                        IconButton(onClick = ::dismissDialog) {
                            Icon(Icons.Outlined.Close, null)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                when (phase) {
                    SpeedTestPhase.CONFIG -> {
                        ConfigView(
                            targetMode = targetMode,
                            node = node,
                            sizes = sizes,
                            selectedSize = selectedSize,
                            onSizeSelected = { selectedSize = it },
                            selectedDirection = selectedDirection,
                            onDirectionSelected = { selectedDirection = it },
                            onStart = { showConfirmDialog = true }
                        )
                    }

                    SpeedTestPhase.TESTING -> {
                        TestingView(
                            targetMode = targetMode,
                            operation = currentOperation,
                            speed = currentSpeed,
                            progress = animatedProgress,
                            onCancel = ::dismissDialog
                        )
                    }

                    SpeedTestPhase.RESULT -> {
                        ResultView(
                            downloadResult = downloadResult,
                            uploadResult = uploadResult,
                            error = error,
                            onRetry = { phase = SpeedTestPhase.CONFIG },
                            onClose = ::dismissDialog
                        )
                    }
                }
            }
        }
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("流量消耗提醒") },
            text = {
                val totalBytes = selectedSize.bytes * (if (selectedDirection == SpeedTestDirection.BOTH) 2 else 1)
                val totalMb = totalBytes / 1_000_000
                val targetLabel = if (targetMode == SpeedTestTargetMode.NODE) "节点带宽" else "直连带宽"
                Text("本次将测试$targetLabel，预计消耗约 ${totalMb}MB 流量，另有每个方向约 0.1MB 预热流量。\n高速网络建议选择 25MB 或 50MB，以减少短样本误差。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmDialog = false
                        startTest()
                    }
                ) {
                    Text("开始测速")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigView(
    targetMode: SpeedTestTargetMode,
    node: Node?,
    sizes: List<SpeedTestSize>,
    selectedSize: SpeedTestSize,
    onSizeSelected: (SpeedTestSize) -> Unit,
    selectedDirection: SpeedTestDirection,
    onDirectionSelected: (SpeedTestDirection) -> Unit,
    onStart: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (targetMode == SpeedTestTargetMode.NODE && node != null) {
                "当前将通过 ${node.getDisplayName()} 测试节点带宽"
            } else {
                "当前将测试本机直连出口带宽（Wi-Fi/流量）"
            },
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Text(
            text = "测试大小",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            sizes.forEach { size ->
                FilterChip(
                    selected = size == selectedSize,
                    onClick = { onSizeSelected(size) },
                    label = { Text(size.label) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "测试方向",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Column {
            SpeedTestDirection.values().forEach { direction ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onDirectionSelected(direction) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = direction == selectedDirection,
                        onClick = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = direction.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Primary)
        ) {
            Text(
                text = "下一步",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun TestingView(
    targetMode: SpeedTestTargetMode,
    operation: String,
    speed: Float,
    progress: Float,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (targetMode == SpeedTestTargetMode.NODE) "正在测试节点带宽" else "正在测试直连带宽",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = operation,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "%.1f".format(speed),
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            color = Primary
        )
        Text(
            text = "Mbps",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        LinearProgressIndicator(
            progress = progress,
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = Primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("取消测速")
        }
    }
}

@Composable
private fun ResultView(
    downloadResult: SpeedTestResult?,
    uploadResult: SpeedTestResult?,
    error: String?,
    onRetry: () -> Unit,
    onClose: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (error != null) {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        if (downloadResult != null) {
            ResultCard(
                title = "下载测试",
                icon = Icons.Outlined.Download,
                result = downloadResult
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (uploadResult != null) {
            ResultCard(
                title = "上传测试",
                icon = Icons.Outlined.Upload,
                result = uploadResult
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("重新测速")
            }

            Button(
                onClick = onClose,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) {
                Text("完成")
            }
        }
    }
}

@Composable
private fun ResultCard(
    title: String,
    icon: ImageVector,
    result: SpeedTestResult
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("平均速率", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "%.1f Mbps".format(result.avgSpeedMbps),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = Primary
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("峰值速率", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "%.1f Mbps".format(result.peakSpeedMbps),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("消耗流量", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "%.1f MB".format(result.totalBytes / 1_000_000f),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("耗时", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "%.1f s".format(result.durationMs / 1000f),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

private fun pickFreePort(): Int {
    return ServerSocket(0).use { it.localPort }
}
