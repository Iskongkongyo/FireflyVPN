package xyz.a202132.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.a202132.app.ui.components.AppScreenScaffold
import xyz.a202132.app.ui.dialogs.UnlockIpVersion
import xyz.a202132.app.ui.dialogs.UnlockResultDetailScreen
import xyz.a202132.app.ui.dialogs.UnlockResultPane
import xyz.a202132.app.viewmodel.UnlockTestViewModel

@Composable
internal fun UnlockTestResultScreen(
    onBack: () -> Unit,
    onShowDetail: (nodeId: String, ipVersion: UnlockIpVersion) -> Unit,
    viewModel: UnlockTestViewModel
) {
    val context = LocalContext.current
    val results by viewModel.results.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val progressText by viewModel.progressText.collectAsState()
    val error by viewModel.error.collectAsState()

    LaunchedEffect(error) {
        error?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearError()
        }
    }

    AppScreenScaffold(
        title = "测试结果",
        subtitle = if (isRunning) "主流站解锁测试正在进行" else "最近一次主流站解锁测试",
        onBack = onBack
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (isRunning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                progressText?.let { progress ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = progress,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            UnlockResultPane(
                modifier = Modifier.weight(1f),
                results = results,
                retryEnabled = !isRunning,
                onShowDetail = { result, ipVersion ->
                    onShowDetail(result.nodeId, ipVersion)
                },
                onRetry = { nodeId -> viewModel.retryTest(nodeId) }
            )

            if (isRunning) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = viewModel::stopTests,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Outlined.Stop, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("停止测试")
                }
            }
        }
    }
}

@Composable
internal fun UnlockTestDetailPage(
    nodeId: String,
    ipVersion: UnlockIpVersion,
    onBack: () -> Unit,
    viewModel: UnlockTestViewModel
) {
    val results by viewModel.results.collectAsState()
    val result = results.firstOrNull { it.nodeId == nodeId }

    if (result == null) {
        AppScreenScaffold(
            title = when (ipVersion) {
                UnlockIpVersion.IPV4 -> "IPv4 详细测试"
                UnlockIpVersion.IPV6 -> "IPv6 详细测试"
            },
            subtitle = "结果已失效",
            onBack = onBack
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "未找到该节点的测试结果，请重新执行主流站解锁测试",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        UnlockResultDetailScreen(
            result = result,
            ipVersion = ipVersion,
            onBack = onBack
        )
    }
}
