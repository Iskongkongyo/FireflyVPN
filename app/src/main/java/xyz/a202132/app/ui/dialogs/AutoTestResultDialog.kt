package xyz.a202132.app.ui.dialogs

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.ui.components.NodeIcon
import xyz.a202132.app.viewmodel.BestNodePriority

@Composable
fun AutoTestResultDialog(
    nodes: List<Node>,
    priority: BestNodePriority = BestNodePriority.LATENCY,
    onDismiss: () -> Unit,
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
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.86f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("自动化测试完成", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(
                            text = if (keyword.isBlank()) {
                                "${resultPriorityLabel(priority)}排序 · ${nodes.size} 个节点"
                            } else {
                                "${resultPriorityLabel(priority)}排序 · 搜索匹配 ${filteredNodes.size}/${nodes.size}"
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row {
                        IconButton(onClick = { showSearch = !showSearch }) {
                            Icon(Icons.Outlined.Search, contentDescription = "搜索")
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Outlined.Close, contentDescription = "关闭")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))

                if (showSearch) {
                    OutlinedTextField(
                        value = keyword,
                        onValueChange = { keyword = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("节点名称关键字匹配") }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (filteredNodes.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(if (nodes.isEmpty()) "没有符合要求的节点" else "没有匹配关键字的节点", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                            text = resultMetricText(node, priority),
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
                    Spacer(modifier = Modifier.height(8.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(8.dp))
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
}

private fun resultMetricText(node: Node, priority: BestNodePriority): String = when (priority) {
    BestNodePriority.LATENCY ->
        "延迟 ${node.getLatencyText()} · 下行 ${formatMbps(node.downloadMbps)}"
    BestNodePriority.DOWNLOAD ->
        "下行 ${formatMbps(node.downloadMbps)} · 延迟 ${node.getLatencyText()}"
    BestNodePriority.UPLOAD ->
        "上行 ${formatMbps(node.uploadMbps)} · 延迟 ${node.getLatencyText()}"
    BestNodePriority.UNLOCK_COUNT -> {
        val count = Regex("""\bYES\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(node.unlockSummary)
            ?.groupValues
            ?.getOrNull(1)
            ?: if (node.unlockPassed) "已通过" else "未通过"
        "解锁 $count · 延迟 ${node.getLatencyText()}"
    }
}

private fun resultPriorityLabel(priority: BestNodePriority): String = when (priority) {
    BestNodePriority.LATENCY -> "延迟优先"
    BestNodePriority.DOWNLOAD -> "下行优先"
    BestNodePriority.UPLOAD -> "上行优先"
    BestNodePriority.UNLOCK_COUNT -> "解锁优先"
}

private fun formatMbps(value: Float): String =
    if (value > 0f) "%.1f Mbps".format(value) else "暂无"

@Composable
fun AutoTestDetailDialog(
    node: Node,
    onDismiss: () -> Unit,
    onUseNode: (Node) -> Unit
) {
    val content = buildDetailText(node)
    val scroll = rememberScrollState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.86f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${node.getDisplayName()} - 自动化测试详情",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭")
                    }
                }

                Divider()
                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scroll)
                ) {
                    Text(
                        text = content,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("关闭")
                    }
                }
            }
        }
    }
}

private fun buildDetailText(node: Node): String {
    val testedTime = if (node.autoTestedAt > 0) {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(node.autoTestedAt))
    } else {
        "未知"
    }

    return buildString {
        appendLine("节点: ${node.getDisplayName()}")
        appendLine("延迟: ${node.getLatencyText()}")
        appendLine("下载带宽: ${if (node.downloadMbps > 0f) "%.2f Mbps".format(node.downloadMbps) else "暂无"}")
        appendLine("上传带宽: ${if (node.uploadMbps > 0f) "%.2f Mbps".format(node.uploadMbps) else "暂无"}")
        appendLine("自动化状态: ${node.autoTestStatus.ifBlank { "暂无" }}")
        appendLine("可用性: ${if (node.isAvailable) "可用" else "不可用"}")
        appendLine("自动化测试时间: $testedTime")
        appendLine()
        appendLine("测试结果:")
        appendLine(node.unlockSummary.ifBlank { "暂无数据" })
    }.trim()
}
