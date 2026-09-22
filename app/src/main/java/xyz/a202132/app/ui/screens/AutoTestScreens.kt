package xyz.a202132.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import xyz.a202132.app.ui.components.AppScreenScaffold
import xyz.a202132.app.ui.dialogs.AutoTestDetailScreen
import xyz.a202132.app.ui.dialogs.AutoTestResultScreen
import xyz.a202132.app.viewmodel.BestNodePriority
import xyz.a202132.app.viewmodel.MainViewModel
import xyz.a202132.app.viewmodel.PreferRankingMode
import xyz.a202132.app.viewmodel.TestPreferMode
import xyz.a202132.app.viewmodel.UnlockPriorityMode

@Composable
fun AutoTestResultsPage(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onNodeClick: (String) -> Unit,
    onStartVpn: (action: () -> Unit) -> Unit
) {
    val nodes by viewModel.autoTestResultSnapshot.collectAsState()
    val mode by viewModel.autoTestResultMode.collectAsState()
    val priority by viewModel.autoTestResultPriority.collectAsState()
    val weightedScores by viewModel.autoTestWeightedScores.collectAsState()

    AutoTestResultScreen(
        nodes = nodes,
        priority = priority,
        rankingMode = mode?.rankingMode ?: PreferRankingMode.PRIORITY_ORDER,
        weightedScores = weightedScores,
        onBack = onBack,
        onNodeClick = { onNodeClick(it.id) },
        autoConnectLabel = "连接推荐节点（${priorityDisplayLabel(priority, mode)}）",
        onAutoConnectBest = {
            onStartVpn {
                viewModel.selectBestNodeByPriorityFromSnapshot(priority, true, mode)
            }
        }
    )
}

@Composable
fun AutoTestDetailPage(
    viewModel: MainViewModel,
    nodeId: String,
    onBack: () -> Unit
) {
    val nodes by viewModel.autoTestResultSnapshot.collectAsState()
    val weightedScores by viewModel.autoTestWeightedScores.collectAsState()
    val node = nodes.firstOrNull { it.id == nodeId }

    if (node == null) {
        AppScreenScaffold(
            title = "自动化测试详情",
            subtitle = "结果已失效",
            onBack = onBack
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "未找到该节点的测试结果，请重新执行自动化测试",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    AutoTestDetailScreen(
        node = node,
        weightedScore = weightedScores[node.id],
        onBack = onBack,
        onUseNode = {
            viewModel.selectNode(node)
            onBack()
        }
    )
}

private fun priorityDisplayLabel(priority: BestNodePriority, mode: TestPreferMode?): String {
    if (mode?.rankingMode == PreferRankingMode.WEIGHTED_SCORE) return "综合权重"
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
