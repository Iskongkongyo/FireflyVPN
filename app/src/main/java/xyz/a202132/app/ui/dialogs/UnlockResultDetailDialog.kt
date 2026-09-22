package xyz.a202132.app.ui.dialogs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.a202132.app.ui.components.AppScreenScaffold
import xyz.a202132.app.viewmodel.UnlockNodeResult
import xyz.a202132.app.viewmodel.UnlockResultStatus
import java.util.Locale

@Composable
internal fun UnlockResultDetailScreen(
    result: UnlockNodeResult,
    ipVersion: UnlockIpVersion,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val model = remember(result, ipVersion) { UnlockResultUiMapper.map(result, ipVersion) }
    var selectedCategory by remember(result.nodeId, ipVersion) {
        mutableStateOf(UnlockServiceCategory.ALL)
    }
    var expandedIds by remember(result.nodeId, ipVersion) { mutableStateOf(emptySet<String>()) }
    val filteredItems = remember(model.items, selectedCategory) {
        if (selectedCategory == UnlockServiceCategory.ALL) {
            model.items
        } else {
            model.items.filter { it.category == selectedCategory }
        }
    }
    val completeText = remember(result) { buildCompleteResultText(result) }

    AppScreenScaffold(
        title = when (ipVersion) {
            UnlockIpVersion.IPV4 -> "IPv4 详细测试"
            UnlockIpVersion.IPV6 -> "IPv6 详细测试"
        },
        subtitle = model.nodeName,
        onBack = onBack,
        contentPadding = PaddingValues(0.dp),
        actions = {
            MainRegionTopBarIcon(
                region = model.mainRegion,
                modifier = Modifier.padding(end = 14.dp)
            )
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item("status") { TestStatusCard(model) }
                item("node-info") { NodeInfoSection(model) }
                item("statistics") { TestStatisticsSection(model) }
                item("region") { MainRegionCard(model.mainRegion) }
                item("major-platforms") { MainPlatformSection(model.majorItems) }
                item("filters") {
                    CategoryFilterRow(
                        items = model.items,
                        selected = selectedCategory,
                        onSelected = { selectedCategory = it }
                    )
                }
                item("result-header") {
                    Text(
                        text = "完整测试结果",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (filteredItems.isEmpty()) {
                    item("empty") { UnlockResultEmptyState(model.items.isEmpty()) }
                } else {
                    items(filteredItems, key = { it.id }) { item ->
                        UnlockResultItem(
                            item = item,
                            expanded = item.id in expandedIds,
                            onToggleExpanded = {
                                if (item.detail != null) {
                                    expandedIds = if (item.id in expandedIds) {
                                        expandedIds - item.id
                                    } else {
                                        expandedIds + item.id
                                    }
                                }
                            }
                        )
                    }
                }

                item("project") {
                    Text(
                        text = "项目地址：${model.projectUrl}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Divider()
            ResultActionBar(
                onCopy = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("主流站解锁测试结果", completeText))
                    Toast.makeText(context, "测试结果已复制", Toast.LENGTH_SHORT).show()
                },
                onShare = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "${result.nodeName} 主流站解锁测试结果")
                        putExtra(Intent.EXTRA_TEXT, completeText)
                    }
                    context.startActivity(Intent.createChooser(intent, "分享测试结果"))
                }
            )
        }
    }
}

@Composable
internal fun MainRegionTopBarIcon(region: String?, modifier: Modifier = Modifier) {
    val flag = remember(region) { regionFlagEmojiOrNull(region) }
    Box(
        modifier = modifier.size(36.dp),
        contentAlignment = Alignment.Center
    ) {
        if (flag != null) {
            Text(
                text = flag,
                fontSize = 26.sp,
                maxLines = 1
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.Language,
                contentDescription = "主要地区未知",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
private fun TestStatusCard(model: UnlockResultUiModel) {
    val successful = model.status == UnlockResultStatus.SUCCESS && (model.exitCode ?: 0) == 0
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
    val title = when (model.status) {
        UnlockResultStatus.SUCCESS -> if (successful) "测试完成" else "测试异常"
        UnlockResultStatus.FAILED -> "测试失败"
        UnlockResultStatus.CANCELED -> "测试已取消"
        UnlockResultStatus.RUNNING -> "正在测试"
        UnlockResultStatus.PENDING -> "等待测试"
    }

    Card(colors = CardDefaults.cardColors(containerColor = container)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (successful) Icons.Outlined.CheckCircle else Icons.Outlined.HelpOutline,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(36.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, color = content)
                Text(
                    text = buildString {
                        append("共 ${model.items.size} 项")
                        model.durationMillis?.let { append("，耗时 ${formatDuration(it)}") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = content
                )
            }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
            ) {
                Text(
                    text = "退出码：${model.exitCode?.toString() ?: "—"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = content,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun NodeInfoSection(model: UnlockResultUiModel) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val compact = maxWidth < 360.dp
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoCard(Icons.Outlined.Dns, "节点", model.nodeName, Modifier.fillMaxWidth())
                InfoCard(
                    Icons.Outlined.Schedule,
                    "测试时间",
                    model.testedAt.takeIf { it > 0L }?.let(::formatResultTime) ?: "—",
                    Modifier.fillMaxWidth()
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoCard(Icons.Outlined.Dns, "节点", model.nodeName, Modifier.weight(1f))
                InfoCard(
                    Icons.Outlined.Schedule,
                    "测试时间",
                    model.testedAt.takeIf { it > 0L }?.let(::formatResultTime) ?: "—",
                    Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun InfoCard(icon: ImageVector, label: String, value: String, modifier: Modifier = Modifier) {
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
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun TestStatisticsSection(model: UnlockResultUiModel) {
    val total = model.items.size
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatisticCard(
            symbol = "✓",
            count = model.availableCount,
            label = "可用",
            percent = percentage(model.availableCount, total),
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f)
        )
        StatisticCard(
            symbol = "×",
            count = model.unavailableCount,
            label = "不可用",
            percent = percentage(model.unavailableCount, total),
            container = MaterialTheme.colorScheme.errorContainer,
            content = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f)
        )
        StatisticCard(
            symbol = "?",
            count = model.otherCount,
            label = "其他",
            percent = percentage(model.otherCount, total),
            container = MaterialTheme.colorScheme.secondaryContainer,
            content = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StatisticCard(
    symbol: String,
    count: Int,
    label: String,
    percent: String,
    container: Color,
    content: Color,
    modifier: Modifier
) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = container)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(symbol, fontWeight = FontWeight.Bold, color = content)
            Text(count.toString(), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = content)
            Text(label, style = MaterialTheme.typography.labelMedium, color = content)
            Text(percent, style = MaterialTheme.typography.labelSmall, color = content)
        }
    }
}

@Composable
internal fun MainRegionCard(region: String?) {
    InfoCard(
        icon = Icons.Outlined.Language,
        label = "主要地区",
        value = region?.let(::formatRegion) ?: "未知",
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
internal fun MainPlatformSection(items: List<UnlockServiceItemUi>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "主要平台状态",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val columns = if (maxWidth >= 520.dp) 3 else 2
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.chunked(columns).forEach { rowItems ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowItems.forEach { item ->
                            PlatformStatusCard(item, Modifier.weight(1f))
                        }
                        repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlatformStatusCard(item: UnlockServiceItemUi, modifier: Modifier) {
    val palette = statusPalette(item.status)
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = palette.container, modifier = Modifier.size(30.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(item.name.take(1), fontWeight = FontWeight.Bold, color = palette.content)
                    }
                }
                Spacer(Modifier.width(7.dp))
                Text(
                    item.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item.region?.let {
                    RegionBadge(it)
                }
                Spacer(Modifier.weight(1f))
                StatusBadge(item.status)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun CategoryFilterRow(
    items: List<UnlockServiceItemUi>,
    selected: UnlockServiceCategory,
    onSelected: (UnlockServiceCategory) -> Unit
) {
    val categories = UnlockServiceCategory.entries
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        categories.forEach { category ->
            val count = if (category == UnlockServiceCategory.ALL) {
                items.size
            } else {
                items.count { it.category == category }
            }
            FilterChip(
                selected = selected == category,
                onClick = { onSelected(category) },
                label = { Text("${categoryLabel(category)}（$count）") }
            )
        }
    }
}

@Composable
internal fun UnlockResultItem(
    item: UnlockServiceItemUi,
    expanded: Boolean,
    onToggleExpanded: () -> Unit
) {
    val palette = statusPalette(item.status)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = item.detail != null, onClick = onToggleExpanded),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = palette.container, modifier = Modifier.size(32.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = statusIcon(item.status),
                            contentDescription = null,
                            tint = palette.content,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = item.name,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.width(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    item.region?.let {
                        RegionBadge(it)
                        Spacer(Modifier.width(6.dp))
                    }
                    StatusBadge(item.status)
                }
                if (item.detail != null) {
                    Icon(
                        imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (expanded) "收起详情" else "展开详情",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }

            AnimatedVisibility(visible = expanded && item.detail != null) {
                Column {
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        text = "详细信息",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = item.detail.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun RegionBadge(region: String) {
    ResultBadge(
        text = region.uppercase(),
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    )
}

@Composable
private fun StatusBadge(status: UnlockServiceStatus) {
    val containerColor = when (status) {
        UnlockServiceStatus.AVAILABLE -> MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
        UnlockServiceStatus.UNAVAILABLE -> MaterialTheme.colorScheme.errorContainer
        UnlockServiceStatus.UNKNOWN -> MaterialTheme.colorScheme.secondaryContainer
        UnlockServiceStatus.NOT_APPLICABLE -> MaterialTheme.colorScheme.surfaceVariant
    }
    ResultBadge(
        text = statusLabel(status),
        containerColor = containerColor,
        contentColor = statusTextColor(status),
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun ResultBadge(
    text: String,
    containerColor: Color,
    contentColor: Color,
    fontWeight: FontWeight? = null
) {
    Surface(
        modifier = Modifier.height(22.dp),
        shape = RoundedCornerShape(6.dp),
        color = containerColor
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                fontWeight = fontWeight,
                maxLines = 1
            )
        }
    }
}

@Composable
internal fun UnlockResultEmptyState(noParsedResults: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Outlined.HelpOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (noParsedResults) "未识别到结构化测试项目" else "此分类暂无结果",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (noParsedResults) {
                Text(
                    text = "完整原始内容仍可复制或分享",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ResultActionBar(onCopy: () -> Unit, onShare: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f)) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("复制结果")
        }
        Button(onClick = onShare, modifier = Modifier.weight(1f)) {
            Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("分享结果")
        }
    }
}

private data class StatusPalette(val container: Color, val content: Color)

@Composable
private fun statusPalette(status: UnlockServiceStatus): StatusPalette = when (status) {
    UnlockServiceStatus.AVAILABLE -> StatusPalette(
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.onPrimaryContainer
    )
    UnlockServiceStatus.UNAVAILABLE -> StatusPalette(
        MaterialTheme.colorScheme.errorContainer,
        MaterialTheme.colorScheme.onErrorContainer
    )
    UnlockServiceStatus.UNKNOWN -> StatusPalette(
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.onSecondaryContainer
    )
    UnlockServiceStatus.NOT_APPLICABLE -> StatusPalette(
        MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun statusTextColor(status: UnlockServiceStatus): Color = when (status) {
    UnlockServiceStatus.AVAILABLE -> MaterialTheme.colorScheme.onSurface
    UnlockServiceStatus.UNAVAILABLE -> MaterialTheme.colorScheme.error
    UnlockServiceStatus.UNKNOWN,
    UnlockServiceStatus.NOT_APPLICABLE -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun statusIcon(status: UnlockServiceStatus): ImageVector = when (status) {
    UnlockServiceStatus.AVAILABLE -> Icons.Outlined.CheckCircle
    UnlockServiceStatus.UNAVAILABLE -> Icons.Outlined.Cancel
    UnlockServiceStatus.UNKNOWN, UnlockServiceStatus.NOT_APPLICABLE -> Icons.Outlined.HelpOutline
}

private fun statusLabel(status: UnlockServiceStatus): String = when (status) {
    UnlockServiceStatus.AVAILABLE -> "可用"
    UnlockServiceStatus.UNAVAILABLE -> "不可用"
    UnlockServiceStatus.UNKNOWN -> "未知"
    UnlockServiceStatus.NOT_APPLICABLE -> "不适用"
}

private fun categoryLabel(category: UnlockServiceCategory): String = when (category) {
    UnlockServiceCategory.ALL -> "全部"
    UnlockServiceCategory.STREAMING -> "流媒体"
    UnlockServiceCategory.AI -> "AI"
    UnlockServiceCategory.SEARCH -> "搜索"
    UnlockServiceCategory.SOCIAL -> "社交"
    UnlockServiceCategory.OTHER -> "其他"
}

private fun percentage(count: Int, total: Int): String = if (total <= 0) {
    "0%"
} else {
    String.format(Locale.getDefault(), "%.1f%%", count * 100.0 / total)
}

private fun formatDuration(durationMillis: Long): String {
    val seconds = (durationMillis.coerceAtLeast(0L) + 500L) / 1_000L
    return if (seconds < 60L) "${seconds}秒" else "${seconds / 60}分${seconds % 60}秒"
}

private fun formatRegion(code: String): String {
    val normalized = code.uppercase()
    if (normalized.length != 2) return normalized
    val country = Locale("", normalized).getDisplayCountry(Locale.getDefault())
    return if (country.isBlank() || country.equals(normalized, ignoreCase = true)) {
        normalized
    } else {
        "$country（$normalized）"
    }
}

internal fun buildCompleteResultText(result: UnlockNodeResult): String {
    val display = sanitizeUnlockOutput(result.rawOutput.ifBlank { result.summary }).trim()
    val full = sanitizeUnlockOutput(result.fullOutput).trim()
    return when {
        full.isBlank() -> display
        display.isBlank() -> full
        display.contains(full) -> display
        else -> "$display\n\n完整原始输出：\n$full"
    }
}
