package xyz.a202132.app.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.ui.components.AppScreenScaffold
import xyz.a202132.app.ui.components.NodeIcon
import xyz.a202132.app.viewmodel.BestNodePriority
import xyz.a202132.app.viewmodel.PreferRankingMode
import xyz.a202132.app.viewmodel.UnlockNodeResult
import xyz.a202132.app.viewmodel.UnlockResultStatus
import xyz.a202132.app.viewmodel.WeightedNodeScore

@Composable
fun AutoTestResultScreen(
    nodes: List<Node>,
    priority: BestNodePriority = BestNodePriority.LATENCY,
    rankingMode: PreferRankingMode = PreferRankingMode.PRIORITY_ORDER,
    weightedScores: Map<String, WeightedNodeScore> = emptyMap(),
    onBack: () -> Unit,
    onNodeClick: (Node) -> Unit,
    autoConnectLabel: String = "自动连接最优",
    onAutoConnectBest: (() -> Unit)? = null
) {
    var showSearch by remember { mutableStateOf(false) }
    var keyword by remember { mutableStateOf("") }
    val rankById = remember(nodes) {
        nodes.mapIndexed { index, node -> node.id to index + 1 }.toMap()
    }
    val filteredNodes = remember(nodes, keyword) {
        val q = keyword.trim()
        if (q.isBlank()) nodes else nodes.filter {
            it.getDisplayName().contains(q, ignoreCase = true) ||
                it.name.contains(q, ignoreCase = true) ||
                (it.countryName?.contains(q, ignoreCase = true) == true) ||
                (it.country?.contains(q, ignoreCase = true) == true)
        }
    }
    val subtitle = if (keyword.isBlank()) {
        "${resultPriorityLabel(priority, rankingMode)}排序 · ${nodes.size} 个节点"
    } else {
        "${resultPriorityLabel(priority, rankingMode)}排序 · 搜索匹配 ${filteredNodes.size}/${nodes.size}"
    }

    AppScreenScaffold(
        title = "自动化测试完成",
        subtitle = subtitle,
        onBack = onBack,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        actions = {
            IconButton(onClick = { showSearch = !showSearch }) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = if (showSearch) "关闭搜索" else "搜索"
                )
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (showSearch) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("节点名称关键字匹配") }
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            if (filteredNodes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (nodes.isEmpty()) "没有符合要求的节点" else "没有匹配关键字的节点",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(filteredNodes, key = { _, node -> node.id }) { _, node ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNodeClick(node) },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${rankById[node.id] ?: "-"}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                NodeIcon(
                                    node = node,
                                    size = 24.dp,
                                    flagFontSize = 20.sp,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = node.getDisplayName(),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = resultMetricText(
                                            node = node,
                                            priority = priority,
                                            rankingMode = rankingMode,
                                            weightedScore = weightedScores[node.id]
                                        ),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = "详情",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            if (onAutoConnectBest != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Divider()
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onAutoConnectBest,
                    enabled = nodes.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(autoConnectLabel)
                }
            }
        }
    }
}

private fun resultMetricText(
    node: Node,
    priority: BestNodePriority,
    rankingMode: PreferRankingMode,
    weightedScore: WeightedNodeScore?
): String {
    if (rankingMode == PreferRankingMode.WEIGHTED_SCORE) {
        return if (weightedScore != null) {
            "综合 ${formatScore(weightedScore.total)} · 延迟 ${node.getLatencyText()} · " +
                "下行 ${formatDownload(node)} · 上行 ${formatUpload(node)} · 解锁 ${formatUnlock(node)}"
        } else {
            "综合得分暂无 · 延迟 ${node.getLatencyText()} · 下行 ${formatDownload(node)} · " +
                "上行 ${formatUpload(node)} · 解锁 ${formatUnlock(node)}"
        }
    }
    val latency = "延迟 ${node.getLatencyText()}"
    val download = "下行 ${formatDownload(node)}"
    val upload = "上行 ${formatUpload(node)}"
    val unlock = "解锁 ${formatUnlock(node)}"
    return when (priority) {
        BestNodePriority.LATENCY -> listOf(latency, download, upload, unlock)
        BestNodePriority.DOWNLOAD -> listOf(download, latency, upload, unlock)
        BestNodePriority.UPLOAD -> listOf(upload, latency, download, unlock)
        BestNodePriority.UNLOCK_COUNT -> listOf(unlock, latency, download, upload)
    }
        .joinToString(" · ")
}

private fun resultPriorityLabel(
    priority: BestNodePriority,
    rankingMode: PreferRankingMode
): String = if (rankingMode == PreferRankingMode.WEIGHTED_SCORE) {
    "综合权重"
} else when (priority) {
    BestNodePriority.LATENCY -> "延迟优先"
    BestNodePriority.DOWNLOAD -> "下行优先"
    BestNodePriority.UPLOAD -> "上行优先"
    BestNodePriority.UNLOCK_COUNT -> "解锁优先"
}

private fun formatDownload(node: Node): String =
    BandwidthResultUiMapper.summary(node.downloadMbps, node.downloadTestStatus)

private fun formatUpload(node: Node): String =
    BandwidthResultUiMapper.summary(node.uploadMbps, node.uploadTestStatus)

private fun formatUnlock(node: Node): String {
    val yesCount = Regex("""\bYES\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)
        .find(node.unlockSummary)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
    val noCount = Regex("""\bNO\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)
        .find(node.unlockSummary)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
    if (yesCount != null) {
        return if (noCount != null) "$yesCount/${yesCount + noCount} 可用" else "$yesCount 项可用"
    }
    if (node.unlockSummary.isBlank() && !node.autoTestStatus.startsWith("UNLOCK_")) return "未测试"
    return if (node.unlockPassed) "已通过" else "未通过"
}

private fun formatScore(value: Double): String = "%.1f".format(value)

@Composable
fun AutoTestDetailScreen(
    node: Node,
    weightedScore: WeightedNodeScore? = null,
    onBack: () -> Unit,
    onUseNode: (Node) -> Unit
) {
    val unlockModel = remember(node.id, node.unlockSummary, node.autoTestedAt) {
        UnlockResultUiMapper.map(
            result = UnlockNodeResult(
                nodeId = node.id,
                nodeName = node.getDisplayName(),
                status = UnlockResultStatus.SUCCESS,
                summary = node.unlockSummary,
                rawOutput = node.unlockSummary,
                testedAt = node.autoTestedAt,
                exitCode = 0
            )
        )
    }
    var selectedUnlockCategory by remember(node.id) {
        mutableStateOf(UnlockServiceCategory.ALL)
    }
    var expandedUnlockIds by remember(node.id) { mutableStateOf(emptySet<String>()) }
    val filteredUnlockItems = remember(unlockModel.items, selectedUnlockCategory) {
        if (selectedUnlockCategory == UnlockServiceCategory.ALL) {
            unlockModel.items
        } else {
            unlockModel.items.filter { it.category == selectedUnlockCategory }
        }
    }

    AppScreenScaffold(
        title = "自动化测试详情",
        subtitle = node.getDisplayName(),
        onBack = onBack,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        actions = {
            MainRegionTopBarIcon(
                region = unlockModel.mainRegion,
                modifier = Modifier.padding(end = 14.dp)
            )
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item("auto-status") { AutoTestStatusCard(node) }
                item("metrics") { AutoTestMetricsSection(node, unlockModel) }
                weightedScore?.let { score ->
                    item("weighted-score") { WeightedScoreCard(score) }
                }
                item("test-time") { AutoTestTimeCard(node.autoTestedAt) }
                item("unlock-title") { SectionTitle("主流站解锁结果") }

                if (unlockModel.items.isEmpty()) {
                    item("unlock-empty") {
                        if (node.unlockSummary.isBlank()) {
                            AutoTestUnlockNotRunCard()
                        } else {
                            UnlockResultEmptyState(noParsedResults = true)
                        }
                    }
                } else {
                    item("unlock-statistics") { TestStatisticsSection(unlockModel) }
                    item("unlock-region") { MainRegionCard(unlockModel.mainRegion) }
                    item("unlock-major") { MainPlatformSection(unlockModel.majorItems) }
                    item("unlock-filters") {
                        CategoryFilterRow(
                            items = unlockModel.items,
                            selected = selectedUnlockCategory,
                            onSelected = { selectedUnlockCategory = it }
                        )
                    }
                    item("unlock-result-title") { SectionTitle("完整测试结果") }
                    if (filteredUnlockItems.isEmpty()) {
                        item("unlock-filter-empty") {
                            UnlockResultEmptyState(noParsedResults = false)
                        }
                    } else {
                        items(filteredUnlockItems, key = { "auto-${it.id}" }) { item ->
                            UnlockResultItem(
                                item = item,
                                expanded = item.id in expandedUnlockIds,
                                onToggleExpanded = {
                                    if (item.detail != null) {
                                        expandedUnlockIds = if (item.id in expandedUnlockIds) {
                                            expandedUnlockIds - item.id
                                        } else {
                                            expandedUnlockIds + item.id
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TextButton(
                    onClick = { onUseNode(node) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("使用该节点")
                }
                Button(
                    onClick = onBack,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("关闭")
                }
            }
        }
    }
}

@Composable
private fun AutoTestStatusCard(node: Node) {
    val successful = node.isAvailable &&
        !node.autoTestStatus.contains("FAILED", ignoreCase = true) &&
        !node.autoTestStatus.contains("FILTERED", ignoreCase = true)
    val container = if (successful) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val content = if (successful) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }

    Card(colors = CardDefaults.cardColors(containerColor = container)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (successful) Icons.Outlined.CheckCircle else Icons.Outlined.Cancel,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(34.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (successful) "节点可用" else "节点不可用",
                    fontWeight = FontWeight.Bold,
                    color = content
                )
                Text(
                    text = autoTestStatusLabel(node.autoTestStatus),
                    style = MaterialTheme.typography.bodySmall,
                    color = content
                )
            }
        }
    }
}

@Composable
private fun AutoTestMetricsSection(node: Node, unlockModel: UnlockResultUiModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("测试概览")
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val compact = maxWidth < 330.dp
            val metrics = listOf(
                AutoTestMetric(
                    icon = Icons.Outlined.Speed,
                    label = "延迟",
                    value = node.getLatencyText(),
                    supporting = "连接响应"
                ),
                AutoTestMetric(
                    icon = Icons.Outlined.Language,
                    label = "解锁",
                    value = if (unlockModel.items.isEmpty()) "未测试" else {
                        "${unlockModel.availableCount}/${unlockModel.items.size} 可用"
                    },
                    supporting = if (unlockModel.items.isEmpty()) null else "主流站检测"
                ),
                AutoTestMetric(
                    icon = Icons.Outlined.Upload,
                    label = "上行带宽",
                    value = BandwidthResultUiMapper.summary(node.uploadMbps, node.uploadTestStatus),
                    supporting = node.uploadTestMessage
                ),
                AutoTestMetric(
                    icon = Icons.Outlined.Download,
                    label = "下行带宽",
                    value = BandwidthResultUiMapper.summary(node.downloadMbps, node.downloadTestStatus),
                    supporting = node.downloadTestMessage
                )
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                metrics.chunked(if (compact) 1 else 2).forEach { rowMetrics ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowMetrics.forEach { metric ->
                            AutoTestMetricCard(metric, Modifier.weight(1f))
                        }
                        if (!compact && rowMetrics.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private data class AutoTestMetric(
    val icon: ImageVector,
    val label: String,
    val value: String,
    val supporting: String?
)

@Composable
private fun AutoTestMetricCard(metric: AutoTestMetric, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = metric.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(9.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = metric.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = metric.value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                metric.supporting?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun WeightedScoreCard(score: WeightedNodeScore) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "综合权重得分",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = formatScore(score.total),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Text(
                    text = "各项为相对得分",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Divider(color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.18f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScorePart("延迟", score.latency, Modifier.weight(1f))
                ScorePart("下行", score.download, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScorePart("上行", score.upload, Modifier.weight(1f))
                ScorePart("解锁", score.unlock, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ScorePart(label: String, score: Double?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(9.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                text = score?.let(::formatScore) ?: "未参与",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun AutoTestTimeCard(testedAt: Long) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = "自动化测试时间",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatAutoTestTime(testedAt),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun AutoTestUnlockNotRunCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.HelpOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "本次自动化测试未执行主流站解锁测试",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
}

private fun autoTestStatusLabel(status: String): String = when (status.uppercase()) {
    "LATENCY_PASSED" -> "延迟测试通过"
    "LATENCY_SKIPPED" -> "已跳过延迟测试"
    "LATENCY_FILTERED" -> "延迟超过筛选阈值"
    "BANDWIDTH_PASSED" -> "带宽测试通过"
    "BANDWIDTH_FAILED" -> "带宽未达到筛选阈值"
    "BANDWIDTH_FILTERED" -> "带宽未达标，已被筛选"
    "BANDWIDTH_SKIPPED" -> "已跳过带宽测试"
    "UNLOCK_PASSED" -> "主流站解锁测试通过"
    "UNLOCK_FAILED" -> "主流站解锁测试未通过"
    "" -> "自动化测试已完成"
    else -> "自动化测试已完成"
}

private fun formatAutoTestTime(testedAt: Long): String = if (testedAt > 0L) {
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        .format(java.util.Date(testedAt))
} else {
    "未知"
}
