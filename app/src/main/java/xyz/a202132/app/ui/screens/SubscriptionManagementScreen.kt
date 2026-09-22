package xyz.a202132.app.ui.screens

import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import xyz.a202132.app.data.model.SubscriptionGroupSummary
import xyz.a202132.app.data.model.NodeGroup
import xyz.a202132.app.data.model.SubscriptionAutoUpdatePolicy
import xyz.a202132.app.AppConfig
import xyz.a202132.app.ui.components.AppScreenScaffold
import xyz.a202132.app.viewmodel.MainViewModel
import java.util.Date
import kotlin.math.roundToInt

@Composable
fun SubscriptionManagementScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onAddGroup: () -> Unit,
    onEditGroup: (String) -> Unit
) {
    val context = LocalContext.current
    val summaries by viewModel.subscriptionGroupSummaries.collectAsState()
    val nodeGroups by viewModel.nodeGroups.collectAsState()
    val favoriteNodeCount by viewModel.favoriteNodeCount.collectAsState()
    val refreshingIds by viewModel.refreshingSubscriptionGroupIds.collectAsState()
    var deleting by remember { mutableStateOf<SubscriptionGroupSummary?>(null) }
    val listState = rememberLazyListState()
    val autoScrollEdgePx = with(LocalDensity.current) { 80.dp.toPx() }
    val orderedIdsState = remember { mutableStateOf(nodeGroups.map { it.id }) }
    var orderedIds by orderedIdsState
    var draggingId by remember { mutableStateOf<String?>(null) }
    var draggedVisualTopY by remember { mutableFloatStateOf(0f) }
    var draggedItemHeight by remember { mutableFloatStateOf(0f) }
    var autoScrollAmount by remember { mutableFloatStateOf(0f) }
    var pendingOrder by remember { mutableStateOf<List<String>?>(null) }
    val backendIds = nodeGroups.map { it.id }

    LaunchedEffect(backendIds, draggingId, pendingOrder) {
        if (draggingId == null && (pendingOrder == null || backendIds == pendingOrder)) {
            orderedIds = backendIds
            if (backendIds == pendingOrder) pendingOrder = null
        }
    }

    fun moveDraggedGroup(direction: Int) {
        val draggedId = draggingId ?: return
        val currentOrder = orderedIdsState.value
        val from = currentOrder.indexOf(draggedId)
        val layout = listState.layoutInfo
        if (from < 0 || draggedItemHeight <= 0f || direction == 0) return

        val draggedCenter = draggedVisualTopY + draggedItemHeight / 2f
        val visibleById = layout.visibleItemsInfo.associateBy { it.key }
        val targetIndex = if (direction > 0) {
            ((from + 1) until currentOrder.size).lastOrNull { index ->
                visibleById[currentOrder[index]]?.let { target ->
                    draggedCenter >= target.offset + target.size / 2f
                } == true
            }
        } else {
            (0 until from).firstOrNull { index ->
                visibleById[currentOrder[index]]?.let { target ->
                    draggedCenter <= target.offset + target.size / 2f
                } == true
            }
        }
        if (targetIndex != null) {
            orderedIds = currentOrder.toMutableList().also { ids ->
                val moved = ids.removeAt(from)
                ids.add(targetIndex, moved)
            }
        }
    }

    fun finishGroupDrag() {
        if (draggingId == null) return
        val finalOrder = orderedIdsState.value
        pendingOrder = finalOrder
        draggingId = null
        draggedVisualTopY = 0f
        draggedItemHeight = 0f
        autoScrollAmount = 0f
        viewModel.setNodeGroupOrder(finalOrder)
    }

    LaunchedEffect(draggingId, autoScrollAmount) {
        while (draggingId != null && autoScrollAmount != 0f) {
            val requested = autoScrollAmount
            val consumed = listState.scrollBy(requested)
            if (consumed == 0f) {
                autoScrollAmount = 0f
                break
            }

            withFrameNanos { }
            moveDraggedGroup(if (requested > 0f) 1 else -1)
        }
    }

    val orderedGroups = orderedIds.mapNotNull { id -> nodeGroups.firstOrNull { it.id == id } } +
        nodeGroups.filterNot { it.id in orderedIds }

    AppScreenScaffold(
        title = "分组管理",
        subtitle = "管理节点分组",
        onBack = onBack,
        actions = {
            IconButton(
                onClick = { viewModel.refreshAllSubscriptionGroups() },
                enabled = refreshingIds.isEmpty()
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = "更新全部分组")
            }
            IconButton(onClick = onAddGroup) {
                Icon(Icons.Default.Add, contentDescription = "新增订阅节点分组")
            }
        }
    ) {
        if (nodeGroups.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("正在准备订阅分组…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                    // 手势绑定在列表容器上。拖拽卡片跨越大量项目并被 LazyColumn
                    // 重新布局或回收时，容器仍持续接收同一条指针事件流。
                    .pointerInput(listState) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { pointer ->
                                val touchedItem = listState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { item ->
                                        item.key in orderedIdsState.value &&
                                            pointer.y >= item.offset &&
                                            pointer.y <= item.offset + item.size
                                    }
                                draggingId = touchedItem?.key as? String
                                draggedVisualTopY = touchedItem?.offset?.toFloat() ?: 0f
                                draggedItemHeight = touchedItem?.size?.toFloat() ?: 0f
                                autoScrollAmount = 0f
                            },
                            onDragCancel = { finishGroupDrag() },
                            onDragEnd = { finishGroupDrag() },
                            onDrag = { change, dragAmount ->
                                val draggedId = draggingId
                                if (draggedId != null) {
                                    change.consume()
                                    draggedVisualTopY += dragAmount.y
                                    val layout = listState.layoutInfo
                                    if (dragAmount.y != 0f) {
                                        moveDraggedGroup(if (dragAmount.y > 0f) 1 else -1)
                                    }

                                    autoScrollAmount = if (draggedItemHeight <= 0f) {
                                        0f
                                    } else {
                                        val draggedTop = draggedVisualTopY
                                        val draggedBottom = draggedVisualTopY + draggedItemHeight
                                        val accelerationDistance =
                                            (autoScrollEdgePx + draggedItemHeight / 2f).coerceAtLeast(1f)
                                        when {
                                            draggedTop <=
                                                layout.viewportStartOffset + autoScrollEdgePx -> {
                                                val overflowRatio =
                                                    ((layout.viewportStartOffset + autoScrollEdgePx -
                                                        draggedTop) / accelerationDistance)
                                                        .coerceIn(0f, 1f)
                                                -(8f + 28f * overflowRatio)
                                            }
                                            draggedBottom >=
                                                layout.viewportEndOffset - autoScrollEdgePx -> {
                                                val overflowRatio =
                                                    ((draggedBottom -
                                                        (layout.viewportEndOffset - autoScrollEdgePx)) /
                                                        accelerationDistance)
                                                        .coerceIn(0f, 1f)
                                                8f + 28f * overflowRatio
                                            }
                                            else -> 0f
                                        }
                                    }
                                }
                            }
                        )
                        },
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                item {
                    Text(
                        text = "长按分组卡片并上下拖动即可排序；节点列表会按此顺序显示。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
                items(orderedGroups, key = { it.id }) { nodeGroup ->
                    val summary = summaries.firstOrNull { it.group.id == nodeGroup.id }
                    val isDragging = draggingId == nodeGroup.id
                    SubscriptionGroupCard(
                        modifier = Modifier
                            .graphicsLayer {
                                alpha = if (isDragging) 0f else 1f
                            },
                        nodeGroup = nodeGroup,
                        summary = summary,
                        favoriteNodeCount = favoriteNodeCount,
                        refreshing = nodeGroup.id in refreshingIds,
                        onRefresh = { viewModel.refreshSubscriptionGroup(nodeGroup.id) },
                        onEdit = {
                            if (summary != null && !nodeGroup.isBuiltIn && !nodeGroup.isFavorites) {
                                onEditGroup(summary.group.id)
                            }
                        },
                        onDelete = {
                            if (summary != null && !nodeGroup.isBuiltIn && !nodeGroup.isFavorites) {
                                deleting = summary
                            }
                        }
                    )
                }
                item { Spacer(modifier = Modifier.height(8.dp)) }
                }

                val draggedGroup = draggingId?.let { id ->
                    nodeGroups.firstOrNull { it.id == id }
                }
                if (draggedGroup != null && draggedItemHeight > 0f) {
                    val draggedSummary = summaries.firstOrNull {
                        it.group.id == draggedGroup.id
                    }
                    SubscriptionGroupCard(
                        modifier = Modifier
                            .offset { IntOffset(0, draggedVisualTopY.roundToInt()) }
                            .zIndex(2f)
                            .graphicsLayer {
                                scaleX = 1.015f
                                scaleY = 1.015f
                                shadowElevation = 12.dp.toPx()
                            },
                        nodeGroup = draggedGroup,
                        summary = draggedSummary,
                        favoriteNodeCount = favoriteNodeCount,
                        refreshing = draggedGroup.id in refreshingIds,
                        onRefresh = {},
                        onEdit = {},
                        onDelete = {}
                    )
                }
            }
        }
    }

    deleting?.let { summary ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除订阅分组") },
            text = { Text("将删除“${summary.group.name}”及其订阅节点，收藏节点不会受到影响。") },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSubscriptionGroup(summary.group.id) { success, message ->
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        if (success) deleting = null
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            }
        )
    }
}

@Composable
private fun SubscriptionGroupCard(
    modifier: Modifier = Modifier,
    nodeGroup: NodeGroup,
    summary: SubscriptionGroupSummary?,
    favoriteNodeCount: Int,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val group = summary?.group
    val protected = nodeGroup.isBuiltIn || nodeGroup.isFavorites
    val nodeCount = if (nodeGroup.isFavorites) favoriteNodeCount else group?.lastNodeCount ?: 0
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(nodeGroup.name, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        if (protected) {
                            Icon(
                                Icons.Outlined.Lock,
                                contentDescription = "受保护分组",
                                modifier = Modifier.padding(start = 7.dp).size(15.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        when {
                            nodeGroup.isFavorites -> "本地收藏 · $nodeCount 个节点"
                            nodeGroup.isBuiltIn -> "APP 内置节点 · $nodeCount 个节点"
                            else -> "${summary?.links?.size ?: 0} 个链接 · $nodeCount 个节点"
                        },
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (refreshing && !nodeGroup.isFavorites) {
                    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                } else if (!nodeGroup.isFavorites) {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "更新 ${nodeGroup.name}")
                    }
                }
                if (!protected) {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Outlined.Edit, contentDescription = "编辑 ${nodeGroup.name}")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Outlined.Delete, contentDescription = "删除 ${nodeGroup.name}")
                    }
                }
            }

            val updateText = when {
                nodeGroup.isFavorites -> "受保护分组 · 收藏内容仅由用户管理"
                nodeGroup.isBuiltIn -> "受保护分组 · 内置节点源已隐藏"
                group?.autoUpdateEnabled == true -> "自动更新 · ${formatInterval(group.updateIntervalMinutes)}"
                else -> "自动更新已关闭"
            }
            Text(
                text = if (protected) updateText else {
                    "$updateText · ${if (group?.deduplicateEnabled != false) "自动去重" else "保留重复节点"}"
                },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp)
            )
            if (group != null && group.lastUpdatedAt > 0L) {
                Text(
                    text = "上次更新：${DateFormat.format("yyyy-MM-dd HH:mm", Date(group.lastUpdatedAt))}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            group?.lastError?.takeIf { it.isNotBlank() }?.let { error ->
                Text(
                    text = error,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 5.dp)
                )
            }
        }
    }
}

@Composable
fun AddSubscriptionGroupScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    SubscriptionGroupEditorPage(
        viewModel = viewModel,
        summary = null,
        title = "新增订阅节点分组",
        onBack = onBack
    )
}

@Composable
fun EditSubscriptionGroupScreen(
    viewModel: MainViewModel,
    groupId: String,
    onBack: () -> Unit
) {
    val summaries by viewModel.subscriptionGroupSummaries.collectAsState()
    val summary = summaries.firstOrNull { it.group.id == groupId }

    if (summary == null) {
        AppScreenScaffold(title = "编辑订阅节点分组", onBack = onBack) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
    } else {
        SubscriptionGroupEditorPage(
            viewModel = viewModel,
            summary = summary,
            title = "编辑订阅节点分组",
            onBack = onBack
        )
    }
}

@Composable
private fun SubscriptionGroupEditorPage(
    viewModel: MainViewModel,
    summary: SubscriptionGroupSummary?,
    title: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val editor = rememberSubscriptionGroupEditorState(summary)
    var saving by remember(summary?.group?.id) { mutableStateOf(false) }

    val save = {
        if (!saving) {
            saving = true
            editor.submit { name, urls, userAgent, autoUpdate, intervalMinutes, deduplicate ->
                viewModel.saveSubscriptionGroup(
                    groupId = summary?.group?.id,
                    name = name,
                    urls = urls,
                    userAgent = userAgent,
                    autoUpdateEnabled = autoUpdate,
                    updateIntervalMinutes = intervalMinutes,
                    deduplicateEnabled = deduplicate
                ) { success, message ->
                    saving = false
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    if (success) onBack()
                }
            }
        }
    }

    AppScreenScaffold(
        title = title,
        subtitle = "配置订阅来源与更新方式",
        onBack = onBack,
        backEnabled = !saving,
        actions = {
            TextButton(onClick = save, enabled = !saving) {
                Text(if (saving) "保存中…" else "保存")
            }
        }
    ) {
        SubscriptionGroupEditorFields(
            editor = editor,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        )
    }
}

@Composable
private fun SubscriptionGroupEditorFields(
    editor: SubscriptionGroupEditorState,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("分组名称", fontWeight = FontWeight.Medium)
        OutlinedTextField(
            value = editor.name,
            onValueChange = { editor.name = it },
            placeholder = { Text("请输入分组名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Text("订阅链接", fontWeight = FontWeight.Medium)
        editor.urls.forEachIndexed { index, url ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { value ->
                        editor.urls = editor.urls.toMutableList().also { it[index] = value }
                    },
                    label = { Text("链接 ${index + 1}") },
                    leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                if (editor.urls.size > 1) {
                    IconButton(onClick = {
                        editor.urls = editor.urls.toMutableList().also { it.removeAt(index) }
                    }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "删除此链接")
                    }
                }
            }
        }
        TextButton(onClick = { editor.urls = editor.urls + "" }) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(" 添加链接")
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = editor.userAgent,
                onValueChange = { editor.userAgent = it.replace("\r", "").replace("\n", "") },
                label = { Text("用户代理") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "默认：${AppConfig.HTTP_USER_AGENT}；留空可恢复默认",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SettingSwitchRow(
            title = "自动去重",
            subtitle = "移除订阅中的相同节点",
            checked = editor.deduplicate,
            onCheckedChange = { editor.deduplicate = it }
        )
        SettingSwitchRow(
            title = "自动更新",
            subtitle = "启动后更新一次，或按间隔定时更新",
            checked = editor.autoUpdate,
            onCheckedChange = { editor.autoUpdate = it }
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = editor.interval,
                onValueChange = { editor.interval = it.filter(Char::isDigit) },
                label = { Text("更新间隔（分钟）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = editor.autoUpdate,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "更新间隔有效范围为0-10080分钟！0分钟：APP 启动稳定后仅更新一次；1-10080分钟：APP 会定时更新",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (editor.autoUpdate) 1f else 0.38f
                )
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun rememberSubscriptionGroupEditorState(
    summary: SubscriptionGroupSummary?
): SubscriptionGroupEditorState = remember(summary?.group?.id) {
    SubscriptionGroupEditorState(summary)
}

private class SubscriptionGroupEditorState(summary: SubscriptionGroupSummary?) {
    var name by mutableStateOf(summary?.group?.name.orEmpty())
    var urls by mutableStateOf(summary?.links?.map { it.url }?.ifEmpty { listOf("") } ?: listOf(""))
    var userAgent by mutableStateOf(
        summary?.group?.userAgent?.takeIf { it.isNotBlank() } ?: AppConfig.HTTP_USER_AGENT
    )
    var deduplicate by mutableStateOf(summary?.group?.deduplicateEnabled ?: true)
    var autoUpdate by mutableStateOf(summary?.group?.autoUpdateEnabled ?: false)
    var interval by mutableStateOf(
        (summary?.group?.updateIntervalMinutes
            ?: SubscriptionAutoUpdatePolicy.DEFAULT_INTERVAL_MINUTES).toString()
    )

    fun submit(onSave: (String, List<String>, String, Boolean, Int, Boolean) -> Unit) {
        onSave(
            name,
            urls,
            userAgent,
            autoUpdate,
            interval.toIntOrNull() ?: SubscriptionAutoUpdatePolicy.DEFAULT_INTERVAL_MINUTES,
            deduplicate
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun formatInterval(minutes: Int): String = when {
    SubscriptionAutoUpdatePolicy.isStartupOnce(minutes) -> "启动后更新一次"
    minutes % (24 * 60) == 0 -> "${minutes / (24 * 60)} 天"
    minutes % 60 == 0 -> "${minutes / 60} 小时"
    else -> "$minutes 分钟"
}
