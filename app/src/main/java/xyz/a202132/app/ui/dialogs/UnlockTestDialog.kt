package xyz.a202132.app.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.ui.components.NodeIcon
import xyz.a202132.app.viewmodel.UnlockNodeResult
import xyz.a202132.app.viewmodel.UnlockResultStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun UnlockNodeSelectionPane(
    modifier: Modifier,
    filteredDisplayNodes: List<Node>,
    selected: Set<String>,
    isRunning: Boolean,
    nodeListState: LazyListState,
    onToggleNode: (String) -> Unit
) {
    Column(modifier = modifier) {
        Text(
            text = "节点选择",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(6.dp))
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                state = nodeListState
            ) {
                items(filteredDisplayNodes, key = { it.id }) { node ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isRunning) { onToggleNode(node.id) }
                            .padding(vertical = 0.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = selected.contains(node.id),
                            onCheckedChange = { onToggleNode(node.id) },
                            enabled = !isRunning
                        )
                        NodeIcon(
                            node = node,
                            size = 22.dp,
                            flagFontSize = 18.sp,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        Text(
                            text = node.getDisplayName(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun UnlockResultPane(
    modifier: Modifier,
    results: List<UnlockNodeResult>,
    retryEnabled: Boolean,
    onShowDetail: (UnlockNodeResult, UnlockIpVersion) -> Unit,
    onRetry: (String) -> Unit
) {
    Column(modifier = modifier) {
        Text(
            text = "测试结果",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(6.dp))
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
        ) {
            if (results.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无结果",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(results, key = { it.nodeId }) { result ->
                        UnlockResultCard(
                            result = result,
                            retryEnabled = retryEnabled,
                            onShowDetail = onShowDetail,
                            onRetry = onRetry
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun UnlockResultCard(
    result: UnlockNodeResult,
    retryEnabled: Boolean,
    onShowDetail: (UnlockNodeResult, UnlockIpVersion) -> Unit,
    onRetry: (String) -> Unit
) {
    val statusColor = when (result.status) {
        UnlockResultStatus.SUCCESS -> MaterialTheme.colorScheme.primary
        UnlockResultStatus.FAILED -> MaterialTheme.colorScheme.error
        UnlockResultStatus.RUNNING -> MaterialTheme.colorScheme.tertiary
        UnlockResultStatus.CANCELED -> MaterialTheme.colorScheme.onSurfaceVariant
        UnlockResultStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = result.nodeName,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = resultStatusLabel(result.status),
                        color = statusColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (result.status == UnlockResultStatus.FAILED) {
                        TextButton(
                            onClick = { onRetry(result.nodeId) },
                            enabled = retryEnabled,
                            modifier = Modifier
                                .defaultMinSize(minWidth = 1.dp)
                                .height(32.dp),
                            contentPadding = PaddingValues(
                                start = 8.dp,
                                end = 0.dp,
                                top = 0.dp,
                                bottom = 0.dp
                            )
                        ) {
                            Text("刷新", fontSize = 12.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = result.summary,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (result.testedAt > 0L) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "测试时间: ${formatResultTime(result.testedAt)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (result.rawOutput.isNotBlank() || result.fullOutput.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "查看IPV4详细测试",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable {
                            onShowDetail(result, UnlockIpVersion.IPV4)
                        }
                    )
                    Text(
                        text = "查看IPV6详细测试",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable {
                            onShowDetail(result, UnlockIpVersion.IPV6)
                        }
                    )
                }
            }
        }
    }
}

internal fun formatResultTime(timestamp: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

private fun resultStatusLabel(status: UnlockResultStatus): String = when (status) {
    UnlockResultStatus.PENDING -> "等待测试"
    UnlockResultStatus.RUNNING -> "测试中"
    UnlockResultStatus.SUCCESS -> "已完成"
    UnlockResultStatus.FAILED -> "失败"
    UnlockResultStatus.CANCELED -> "已取消"
}
