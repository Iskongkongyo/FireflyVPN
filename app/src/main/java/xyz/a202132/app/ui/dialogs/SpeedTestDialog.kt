package xyz.a202132.app.ui.dialogs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.a202132.app.AppConfig
import xyz.a202132.app.SpeedTestSize
import xyz.a202132.app.network.SpeedTestResult
import xyz.a202132.app.network.SpeedTestService
import xyz.a202132.app.ui.theme.Primary

enum class SpeedTestPhase {
    CONFIG,
    TESTING,
    RESULT
}

enum class SpeedTestDirection(val label: String) {
    DOWNLOAD("下载测试"),
    UPLOAD("上传测试"),
    BOTH("双向测试 (先下后上)")
}

@Composable
fun SpeedTestDialog(
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val service = remember { SpeedTestService() }
    
    var phase by remember { mutableStateOf(SpeedTestPhase.CONFIG) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    
    // Config State
    val sizes = remember { AppConfig.getSpeedTestSizes() }
    var selectedSize by remember { mutableStateOf(sizes.firstOrNull() ?: SpeedTestSize("10MB", 10000000)) }
    var selectedDirection by remember { mutableStateOf(SpeedTestDirection.DOWNLOAD) }
    
    // Test State
    var currentSpeed by remember { mutableFloatStateOf(0f) }
    var progress by remember { mutableFloatStateOf(0f) } // 0.0 - 1.0 (per test)
    var currentOperation by remember { mutableStateOf("") } // "正在下载..."
    
    // Results
    var downloadResult by remember { mutableStateOf<SpeedTestResult?>(null) }
    var uploadResult by remember { mutableStateOf<SpeedTestResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    
    // Animation
    val animatedProgress by animateFloatAsState(targetValue = progress, label = "progress")

    fun startTest() {
        phase = SpeedTestPhase.TESTING
        error = null
        downloadResult = null
        uploadResult = null
        currentSpeed = 0f
        progress = 0f
        
        scope.launch(Dispatchers.IO) {
            try {
                if (selectedDirection == SpeedTestDirection.DOWNLOAD || selectedDirection == SpeedTestDirection.BOTH) {
                    withContext(Dispatchers.Main) { 
                        currentOperation = "正在下载..." 
                        progress = 0f
                    }
                    val result = service.startDownloadTest(selectedSize.bytes) { speed, prog ->
                        // Switch to main thread for UI updates? Compose state is thread-safe for reading but modifying from BG thread is okay for SnapshotState? 
                        // Actually better to use withContext or keep it minimal.
                        // SnapshotState can be updated from any thread, but recomposition happens on UI thread.
                        currentSpeed = speed
                        progress = prog
                    }
                    downloadResult = result
                }
                
                if (selectedDirection == SpeedTestDirection.UPLOAD || selectedDirection == SpeedTestDirection.BOTH) {
                    withContext(Dispatchers.Main) { 
                        currentOperation = "正在上传..." 
                        progress = 0f
                        currentSpeed = 0f
                    }
                    // Small delay between tests
                    kotlinx.coroutines.delay(500)
                    
                    val result = service.startUploadTest(selectedSize.bytes) { speed, prog ->
                        currentSpeed = speed
                        progress = prog
                    }
                    uploadResult = result
                }
                
                withContext(Dispatchers.Main) {
                    phase = SpeedTestPhase.RESULT
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    error = "测试失败: ${e.message}"
                    phase = SpeedTestPhase.RESULT
                }
            }
        }
    }

    Dialog(
        onDismissRequest = {
            service.cancel()
            onDismiss()
        },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)
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
                // Title
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
                    Spacer(modifier = Modifier.weight(1f))
                    if (phase != SpeedTestPhase.TESTING) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Outlined.Close, null)
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                when (phase) {
                    SpeedTestPhase.CONFIG -> {
                        ConfigView(
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
                            operation = currentOperation,
                            speed = currentSpeed,
                            progress = animatedProgress,
                            onCancel = {
                                service.cancel()
                                onDismiss()
                            }
                        )
                    }
                    SpeedTestPhase.RESULT -> {
                        ResultView(
                            downloadResult = downloadResult,
                            uploadResult = uploadResult,
                            error = error,
                            onRetry = { phase = SpeedTestPhase.CONFIG },
                            onClose = onDismiss
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
                Text("本次测速预计消耗约 ${totalMb}MB 流量。\n如果您正在使用流量数据，请确剩余流量充足。")
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
    sizes: List<SpeedTestSize>,
    selectedSize: SpeedTestSize,
    onSizeSelected: (SpeedTestSize) -> Unit,
    selectedDirection: SpeedTestDirection,
    onDirectionSelected: (SpeedTestDirection) -> Unit,
    onStart: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
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
                        onClick = null // null because row is clickable
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
            text = operation,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Speed Display
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
            Text("取消测试")
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
                Text("重新测试")
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
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    result: SpeedTestResult
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
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
                        "%.1f Mbps".format(result.avgSpeedMbps),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = Primary
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("峰值速率", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "%.1f Mbps".format(result.peakSpeedMbps),
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
                        "%.1f MB".format(result.totalBytes / 1_000_000f),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("耗时", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "%.1f s".format(result.durationMs / 1000f),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
