package xyz.a202132.app.ui.components

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import xyz.a202132.app.data.model.LatencyLevel
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeListCategory
import xyz.a202132.app.data.model.NodeSource
import xyz.a202132.app.data.model.NodeGroup
import xyz.a202132.app.data.model.FAVORITES_NODE_GROUP_ID
import xyz.a202132.app.data.model.sortNodesByLatencyForDisplay
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import xyz.a202132.app.ui.theme.LatencyBad
import xyz.a202132.app.ui.theme.LatencyGood
import xyz.a202132.app.ui.theme.LatencyMedium
import xyz.a202132.app.ui.theme.Primary

private enum class NodeSortMode {
    ORIGINAL,
    LATENCY
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NodeListScreen(
    allNodes: List<Node>,
    groups: List<NodeGroup>,
    selectedNodeId: String?,
    selectedGroupId: String,
    favoriteSourceNodeIds: Set<String>,
    skipFavoriteRemovalConfirmation: Boolean,
    isTesting: Boolean,
    testingLabel: String? = null,
    onNodeSelected: (Node) -> Unit,
    onGroupChange: (String) -> Unit,
    onToggleFavorite: (Node) -> Unit,
    onReorderNodes: (groupId: String, orderedNodeIds: List<String>) -> Unit,
    onSkipFavoriteRemovalConfirmationForSession: () -> Unit,
    onImportFromText: (String) -> Unit,
    onScanQrCode: () -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var showSearch by remember { mutableStateOf(false) }
    var keyword by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(NodeSortMode.ORIGINAL) }
    var sortRequestId by remember { mutableIntStateOf(0) }
    var isClosing by remember { mutableStateOf(false) }
    var favoriteRemovalCandidate by remember { mutableStateOf<Node?>(null) }
    var skipConfirmationForSessionChecked by remember { mutableStateOf(false) }

    val handleBack = {
        if (!isClosing) {
            isClosing = true
            onBack()
        }
    }

    val handleNodeSelected: (Node) -> Unit = { node ->
        if (!isClosing) {
            isClosing = true
            onNodeSelected(node)
        }
    }

    val handleToggleFavorite: (Node, Boolean) -> Unit = { node, isFavorite ->
        when {
            !isFavorite || skipFavoriteRemovalConfirmation -> onToggleFavorite(node)
            else -> {
                skipConfirmationForSessionChecked = false
                favoriteRemovalCandidate = node
            }
        }
    }

    AppScreenScaffold(
        title = "节点列表",
        subtitle = testingLabel,
        onBack = handleBack,
        backEnabled = !isClosing,
        contentPadding = PaddingValues(start = 20.dp, top = 6.dp, end = 20.dp, bottom = 16.dp),
        actions = {
            NodeListTopActions(
                showSearch = showSearch,
                sortMode = sortMode,
                enabled = !isClosing,
                onImportFromClipboard = {
                    if (isClosing) return@NodeListTopActions
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clipText = clipboard.primaryClip
                        ?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)
                        ?.coerceToText(context)
                        ?.toString()
                        ?.trim()
                    if (clipText.isNullOrBlank()) {
                        Toast.makeText(context, "\u526a\u5207\u677f\u4e3a\u7a7a", Toast.LENGTH_SHORT).show()
                    } else {
                        onImportFromText(clipText)
                    }
                },
                onScanQrCode = {
                    if (isClosing) return@NodeListTopActions
                    onScanQrCode()
                },
                onToggleSearch = {
                    if (isClosing) return@NodeListTopActions
                    showSearch = !showSearch
                    if (!showSearch) {
                        keyword = ""
                    }
                },
                onSortModeChange = {
                    sortMode = it
                    sortRequestId++
                },
                onRefresh = onRefresh
            )
        }
    ) {
        NodeListContent(
            allNodes = allNodes,
            groups = groups,
            selectedNodeId = selectedNodeId,
            selectedGroupId = selectedGroupId,
            favoriteSourceNodeIds = favoriteSourceNodeIds,
            isTesting = isTesting,
            showSearch = showSearch,
            keyword = keyword,
            sortMode = sortMode,
            sortRequestId = sortRequestId,
            onKeywordChange = { keyword = it },
            onNodeSelected = handleNodeSelected,
            onGroupChange = onGroupChange,
            onToggleFavorite = handleToggleFavorite,
            onReorderNodes = onReorderNodes,
            interactionEnabled = !isClosing,
        )
    }

    favoriteRemovalCandidate?.let { node ->
        AlertDialog(
            onDismissRequest = {},
            title = {
                Text(text = "取消收藏")
            },
            text = {
                Column {
                    Text(text = "取消收藏的节点会从收藏节点中删除，是否确认删除？")
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                skipConfirmationForSessionChecked =
                                    !skipConfirmationForSessionChecked
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = skipConfirmationForSessionChecked,
                            onCheckedChange = { skipConfirmationForSessionChecked = it }
                        )
                        Text(
                            text = "本次使用 APP 不再提示",
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (skipConfirmationForSessionChecked) {
                            onSkipFavoriteRemovalConfirmationForSession()
                        }
                        favoriteRemovalCandidate = null
                    }
                ) {
                    Text("取消")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (skipConfirmationForSessionChecked) {
                            onSkipFavoriteRemovalConfirmationForSession()
                        }
                        favoriteRemovalCandidate = null
                        onToggleFavorite(node)
                    }
                ) {
                    Text("确认")
                }
            },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            )
        )
    }
}

@Composable
private fun NodeListTopActions(
    showSearch: Boolean,
    sortMode: NodeSortMode,
    enabled: Boolean,
    onImportFromClipboard: () -> Unit,
    onScanQrCode: () -> Unit,
    onToggleSearch: () -> Unit,
    onSortModeChange: (NodeSortMode) -> Unit,
    onRefresh: () -> Unit
) {
    var showImportMenu by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }

    Box {
        IconButton(
            onClick = { showImportMenu = true },
            enabled = enabled
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "\u5bfc\u5165\u8282\u70b9",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(
            expanded = showImportMenu,
            onDismissRequest = { showImportMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("\u4ece\u526a\u5207\u677f\u5bfc\u5165") },
                onClick = {
                    showImportMenu = false
                    onImportFromClipboard()
                }
            )
            DropdownMenuItem(
                text = { Text("\u626b\u63cf\u4e8c\u7ef4\u7801") },
                onClick = {
                    showImportMenu = false
                    onScanQrCode()
                }
            )
        }
    }

    IconButton(
        onClick = onToggleSearch,
        enabled = enabled
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = "搜索",
            tint = if (showSearch) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
    Box {
        IconButton(
            onClick = { showSortMenu = true },
            enabled = enabled
        ) {
            Icon(
                imageVector = Icons.Default.Sort,
                contentDescription = "排序方式",
                tint = if (sortMode == NodeSortMode.LATENCY) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        DropdownMenu(
            expanded = showSortMenu,
            onDismissRequest = { showSortMenu = false }
        ) {
            NodeSortMenuItem(
                text = "按原始排序",
                selected = sortMode == NodeSortMode.ORIGINAL,
                onClick = {
                    showSortMenu = false
                    onSortModeChange(NodeSortMode.ORIGINAL)
                }
            )
            NodeSortMenuItem(
                text = "按延迟排序",
                selected = sortMode == NodeSortMode.LATENCY,
                onClick = {
                    showSortMenu = false
                    onSortModeChange(NodeSortMode.LATENCY)
                }
            )
        }
    }
    IconButton(
        onClick = onRefresh,
        enabled = enabled
    ) {
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = "刷新",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NodeSortMenuItem(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = { Text(text) },
        onClick = onClick,
        leadingIcon = {
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            } else {
                Spacer(modifier = Modifier.size(24.dp))
            }
        }
    )
}

private data class NodeListPage(
    val id: String,
    val label: String,
    val hasUnreadNodes: Boolean = false
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NodeListContent(
    allNodes: List<Node>,
    groups: List<NodeGroup>,
    selectedNodeId: String?,
    selectedGroupId: String,
    favoriteSourceNodeIds: Set<String>,
    isTesting: Boolean,
    showSearch: Boolean,
    keyword: String,
    sortMode: NodeSortMode,
    sortRequestId: Int,
    onKeywordChange: (String) -> Unit,
    onNodeSelected: (Node) -> Unit,
    onGroupChange: (String) -> Unit,
    onToggleFavorite: (Node, Boolean) -> Unit,
    onReorderNodes: (groupId: String, orderedNodeIds: List<String>) -> Unit,
    interactionEnabled: Boolean
) {
    val pages = remember(groups) {
        groups.map {
            NodeListPage(
                id = it.id,
                label = it.name,
                hasUnreadNodes = it.hasUnreadNodes
            )
        }
    }
    val initialPage = pages.indexOfFirst { it.id == selectedGroupId }.coerceAtLeast(0)
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { pages.size }
    )
    val groupTabListState = rememberLazyListState(initialFirstVisibleItemIndex = initialPage)
    val scope = rememberCoroutineScope()
    val latestPages by rememberUpdatedState(pages)
    val latestSelectedGroupId by rememberUpdatedState(selectedGroupId)

    // 一次完成分组，避免 HorizontalPager 的每个可见页面都在节点刷新时
    // 重新扫描整份节点列表。
    val nodesByGroup = remember(allNodes) {
        allNodes.groupBy { node ->
            if (node.source == NodeSource.FAVORITE) {
                FAVORITES_NODE_GROUP_ID
            } else {
                node.subscriptionGroupId.orEmpty()
            }
        }
    }

    LaunchedEffect(selectedGroupId, pages) {
        if (pages.isEmpty()) return@LaunchedEffect
        val selectedIndex = pages.indexOfFirst { it.id == selectedGroupId }
        val currentPageIsValid = pagerState.currentPage in pages.indices
        val target = if (selectedIndex >= 0) {
            selectedIndex
        } else {
            pagerState.currentPage.coerceIn(pages.indices)
        }
        if ((!currentPageIsValid || !pagerState.isScrollInProgress) &&
            target != pagerState.settledPage
        ) {
            pagerState.scrollToPage(target)
        }
    }
    LaunchedEffect(pagerState) {
        var observedScroll = false
        snapshotFlow { pagerState.isScrollInProgress to pagerState.settledPage }
            .collect { (isScrolling, settledPage) ->
                if (isScrolling) {
                    observedScroll = true
                } else if (observedScroll) {
                    observedScroll = false
                    latestPages.getOrNull(settledPage)?.id
                        ?.takeIf { it != latestSelectedGroupId }
                        ?.let(onGroupChange)
                }
            }
    }
    LaunchedEffect(pagerState.currentPage, pages.size) {
        val selectedIndex = pagerState.currentPage.coerceIn(0, (pages.lastIndex).coerceAtLeast(0))
        val layoutInfo = groupTabListState.layoutInfo
        val selectedItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == selectedIndex }
        val fullyVisible = selectedItem != null &&
            selectedItem.offset >= layoutInfo.viewportStartOffset &&
            selectedItem.offset + selectedItem.size <= layoutInfo.viewportEndOffset
        if (!fullyVisible && pages.isNotEmpty()) {
            groupTabListState.animateScrollToItem(selectedIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (isTesting) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp)
        ) {
            LazyRow(
                state = groupTabListState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                itemsIndexed(pages, key = { _, page -> page.id }) { index, page ->
                    if (index > 0) {
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(22.dp)
                                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                        )
                    }
                    val selected = pages.getOrNull(pagerState.currentPage)?.id == page.id
                    Box {
                        Surface(
                            modifier = Modifier
                                .widthIn(min = 96.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = interactionEnabled && !isTesting) {
                                    scope.launch { pagerState.animateScrollToPage(index) }
                                },
                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = page.label,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                        if (page.hasUnreadNodes) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = (-5).dp, y = 4.dp)
                                    .size(7.dp)
                                    .background(MaterialTheme.colorScheme.error, CircleShape)
                            )
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        if (showSearch) {
            OutlinedTextField(
                value = keyword,
                onValueChange = onKeywordChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("输入关键词搜索节点") },
                trailingIcon = {
                    if (keyword.isNotEmpty()) {
                        IconButton(onClick = { onKeywordChange("") }, enabled = interactionEnabled) {
                            Icon(Icons.Default.Close, contentDescription = "清空")
                        }
                    }
                }
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        Text(
            text = when {
                isTesting -> "节点测试期间暂不可排序"
                sortMode == NodeSortMode.LATENCY -> "切换到“按原始”后可长按节点卡片排序"
                showSearch && keyword.isNotBlank() -> "清空搜索后可长按节点卡片排序"
                else -> "长按节点卡片并上下拖动，可调整其在当前分组的顺序"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))

        if (pages.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "正在获取节点分组...",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 16.sp
                )
            }
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = interactionEnabled && !isTesting,
                key = { pageIndex ->
                    pages.getOrNull(pageIndex)?.id ?: "missing_node_group_$pageIndex"
                }
            ) { pageIndex ->
                val page = pages.getOrNull(pageIndex) ?: return@HorizontalPager
                val pageNodes = nodesByGroup[page.id].orEmpty()
                NodeListPageContent(
                    nodes = pageNodes,
                    selectedNodeId = selectedNodeId,
                    favoriteSourceNodeIds = favoriteSourceNodeIds,
                    isTesting = isTesting,
                    keyword = keyword,
                    sortMode = sortMode,
                    sortRequestId = sortRequestId,
                    interactionEnabled = interactionEnabled,
                    onNodeSelected = onNodeSelected,
                    onToggleFavorite = onToggleFavorite,
                    onReorderNodes = { orderedNodeIds -> onReorderNodes(page.id, orderedNodeIds) }
                )
            }
        }
    }
}

@Composable
private fun NodeListPageContent(
    nodes: List<Node>,
    selectedNodeId: String?,
    favoriteSourceNodeIds: Set<String>,
    isTesting: Boolean,
    keyword: String,
    sortMode: NodeSortMode,
    sortRequestId: Int,
    interactionEnabled: Boolean,
    onNodeSelected: (Node) -> Unit,
    onToggleFavorite: (Node, Boolean) -> Unit,
    onReorderNodes: (orderedNodeIds: List<String>) -> Unit
) {
    var frozenNodeOrderIds by remember { mutableStateOf<List<String>?>(null) }
    var wasTesting by remember { mutableStateOf(isTesting) }
    var pendingScrollToTop by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val autoScrollEdgePx = with(LocalDensity.current) { 80.dp.toPx() }
    val orderedIdsState = remember { mutableStateOf(nodes.map { it.id }) }
    var orderedIds by orderedIdsState
    var draggingId by remember { mutableStateOf<String?>(null) }
    var draggedVisualTopY by remember { mutableFloatStateOf(0f) }
    var draggedItemHeight by remember { mutableFloatStateOf(0f) }
    var autoScrollAmount by remember { mutableFloatStateOf(0f) }
    var pendingOrder by remember { mutableStateOf<List<String>?>(null) }
    val backendIds = nodes.map { it.id }
    val latestBackendIds by rememberUpdatedState(backendIds)
    val latestOnReorderNodes by rememberUpdatedState(onReorderNodes)

    LaunchedEffect(backendIds, draggingId, pendingOrder) {
        if (draggingId == null && (pendingOrder == null || backendIds == pendingOrder)) {
            orderedIds = backendIds
            if (backendIds == pendingOrder) pendingOrder = null
        }
    }

    LaunchedEffect(pendingOrder) {
        val expectedOrder = pendingOrder ?: return@LaunchedEffect
        delay(1_500)
        if (pendingOrder == expectedOrder) pendingOrder = null
    }

    val orderedNodes = remember(nodes, orderedIds) {
        val nodesById = nodes.associateBy { it.id }
        val orderedIdSet = orderedIds.toHashSet()
        orderedIds.mapNotNull(nodesById::get) + nodes.filterNot { it.id in orderedIdSet }
    }

    val sortedNodes = remember(orderedNodes, sortMode) {
        when (sortMode) {
            NodeSortMode.ORIGINAL -> orderedNodes
            NodeSortMode.LATENCY -> sortNodesByLatencyForDisplay(orderedNodes)
        }
    }

    LaunchedEffect(isTesting, sortMode) {
        if (isTesting && sortMode == NodeSortMode.LATENCY && frozenNodeOrderIds == null) {
            frozenNodeOrderIds = sortedNodes.map { it.id }
        } else if (!isTesting || sortMode == NodeSortMode.ORIGINAL) {
            if (wasTesting && sortMode == NodeSortMode.LATENCY) pendingScrollToTop = true
            frozenNodeOrderIds = null
        }
        wasTesting = isTesting
    }

    val displayNodes = remember(sortedNodes, isTesting, frozenNodeOrderIds) {
        if (!isTesting || frozenNodeOrderIds == null) sortedNodes else {
            val nodeMap = sortedNodes.associateBy { it.id }
            frozenNodeOrderIds.orEmpty().mapNotNull(nodeMap::get) +
                sortedNodes.filterNot { it.id in frozenNodeOrderIds.orEmpty() }
        }
    }
    val filteredNodes = remember(displayNodes, keyword) {
        val query = keyword.trim()
        if (query.isBlank()) displayNodes else displayNodes.filter { node ->
            node.getDisplayName().contains(query, ignoreCase = true) ||
                node.name.contains(query, ignoreCase = true) ||
                node.country?.contains(query, ignoreCase = true) == true
        }
    }
    val reorderEnabled = interactionEnabled && !isTesting &&
        sortMode == NodeSortMode.ORIGINAL && keyword.isBlank() && filteredNodes.size > 1

    fun moveDraggedNode(direction: Int) {
        val draggedId = draggingId ?: return
        val currentOrder = orderedIdsState.value
        val from = currentOrder.indexOf(draggedId)
        val layout = listState.layoutInfo
        if (from < 0 || draggedItemHeight <= 0f || direction == 0) return

        val draggedCenter = draggedVisualTopY + draggedItemHeight / 2f
        val visibleByIndex = layout.visibleItemsInfo.associateBy { it.index }
        val targetIndex = if (direction > 0) {
            ((from + 1) until currentOrder.size).lastOrNull { index ->
                visibleByIndex[index]?.let { target ->
                    draggedCenter >= target.offset + target.size / 2f
                } == true
            }
        } else {
            (0 until from).firstOrNull { index ->
                visibleByIndex[index]?.let { target ->
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

    fun finishNodeDrag() {
        if (draggingId == null) return
        val currentOrder = orderedIdsState.value
        val currentIdSet = currentOrder.toHashSet()
        val currentBackendIds = latestBackendIds
        val backendIdSet = currentBackendIds.toHashSet()
        val finalOrder = currentOrder.filter { it in backendIdSet } +
            currentBackendIds.filterNot { it in currentIdSet }
        orderedIds = finalOrder
        pendingOrder = finalOrder
        draggingId = null
        draggedVisualTopY = 0f
        draggedItemHeight = 0f
        autoScrollAmount = 0f
        latestOnReorderNodes(finalOrder)
    }

    LaunchedEffect(draggingId, autoScrollAmount) {
        while (draggingId != null && autoScrollAmount != 0f) {
            // 等到下一帧再滚动，避免指针事件频繁更新滚动量时反复重启协程，
            // 在同一帧内累计多次 scrollBy 导致长列表突然冲到底部。
            withFrameNanos { }
            val requested = autoScrollAmount
            if (requested == 0f) break
            val consumed = listState.scrollBy(requested)
            if (consumed == 0f) {
                autoScrollAmount = 0f
                break
            }
            moveDraggedNode(if (requested > 0f) 1 else -1)
        }
    }

    LaunchedEffect(pendingScrollToTop, isTesting, filteredNodes.size) {
        if (pendingScrollToTop && !isTesting && filteredNodes.isNotEmpty()) {
            withFrameNanos { }
            listState.scrollToItem(0)
            pendingScrollToTop = false
        }
    }

    LaunchedEffect(sortRequestId) {
        if (sortRequestId > 0 && filteredNodes.isNotEmpty()) {
            withFrameNanos { }
            listState.scrollToItem(0)
        }
    }

    when {
        nodes.isEmpty() -> Box(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isTesting) "正在获取节点..." else "暂无可用节点",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 16.sp
            )
        }
        filteredNodes.isEmpty() -> Box(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("未找到匹配节点", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
        }
        else -> Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(listState, reorderEnabled) {
                        if (!reorderEnabled) return@pointerInput
                        detectDragGesturesAfterLongPress(
                            onDragStart = { pointer ->
                                val touchedItem = listState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { item ->
                                        item.index in orderedIdsState.value.indices &&
                                            pointer.y >= item.offset &&
                                            pointer.y <= item.offset + item.size
                                    }
                                draggingId = touchedItem?.index
                                    ?.let { orderedIdsState.value.getOrNull(it) }
                                draggedVisualTopY = touchedItem?.offset?.toFloat() ?: 0f
                                draggedItemHeight = touchedItem?.size?.toFloat() ?: 0f
                                autoScrollAmount = 0f
                            },
                            onDragCancel = { finishNodeDrag() },
                            onDragEnd = { finishNodeDrag() },
                            onDrag = { change, dragAmount ->
                                if (draggingId != null) {
                                    change.consume()
                                    draggedVisualTopY += dragAmount.y
                                    if (dragAmount.y != 0f) {
                                        moveDraggedNode(if (dragAmount.y > 0f) 1 else -1)
                                    }

                                    val layout = listState.layoutInfo
                                    autoScrollAmount = if (draggedItemHeight <= 0f) {
                                        0f
                                    } else {
                                        val draggedTop = draggedVisualTopY
                                        val draggedBottom = draggedVisualTopY + draggedItemHeight
                                        val accelerationDistance =
                                            (autoScrollEdgePx + draggedItemHeight / 2f).coerceAtLeast(1f)
                                        when {
                                            draggedTop <= layout.viewportStartOffset + autoScrollEdgePx -> {
                                                val overflowRatio =
                                                    ((layout.viewportStartOffset + autoScrollEdgePx - draggedTop) /
                                                        accelerationDistance).coerceIn(0f, 1f)
                                                -(8f + 28f * overflowRatio)
                                            }
                                            draggedBottom >= layout.viewportEndOffset - autoScrollEdgePx -> {
                                                val overflowRatio =
                                                    ((draggedBottom -
                                                        (layout.viewportEndOffset - autoScrollEdgePx)) /
                                                        accelerationDistance).coerceIn(0f, 1f)
                                                8f + 28f * overflowRatio
                                            }
                                            else -> 0f
                                        }
                                    }
                                }
                            }
                        )
                    },
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(filteredNodes) { _, node ->
                    val isFavorite = node.source == NodeSource.FAVORITE || favoriteSourceNodeIds.contains(node.id)
                    val isDragging = draggingId == node.id
                    NodeListItem(
                        modifier = Modifier.graphicsLayer { alpha = if (isDragging) 0f else 1f },
                        node = node,
                        isSelected = node.id == selectedNodeId,
                        isFavorite = isFavorite,
                        isTesting = isTesting,
                        enabled = interactionEnabled && draggingId == null,
                        onClick = { onNodeSelected(node) },
                        onToggleFavorite = { onToggleFavorite(node, isFavorite) }
                    )
                }
            }

            val draggedNode = draggingId?.let { id -> nodes.firstOrNull { it.id == id } }
            if (draggedNode != null && draggedItemHeight > 0f) {
                val isFavorite = draggedNode.source == NodeSource.FAVORITE ||
                    favoriteSourceNodeIds.contains(draggedNode.id)
                NodeListItem(
                    modifier = Modifier
                        .offset { IntOffset(0, draggedVisualTopY.roundToInt()) }
                        .zIndex(2f)
                        .graphicsLayer {
                            scaleX = 1.015f
                            scaleY = 1.015f
                        },
                    node = draggedNode,
                    isSelected = draggedNode.id == selectedNodeId,
                    isFavorite = isFavorite,
                    isTesting = false,
                    enabled = false,
                    onClick = {},
                    onToggleFavorite = {}
                )
            }
        }
    }
}

@Composable
private fun LegacyNodeListContent(
    nodes: List<Node>,
    selectedNodeId: String?,
    category: NodeListCategory,
    favoriteSourceNodeIds: Set<String>,
    isTesting: Boolean,
    showSearch: Boolean,
    keyword: String,
    onKeywordChange: (String) -> Unit,
    onNodeSelected: (Node) -> Unit,
    onCategoryChange: (NodeListCategory) -> Unit,
    onToggleFavorite: (Node, Boolean) -> Unit,
    interactionEnabled: Boolean,
) {
    var frozenNodeOrderIds by remember { mutableStateOf<List<String>?>(null) }
    var wasTesting by remember { mutableStateOf(isTesting) }
    var pendingScrollToTop by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(isTesting) {
        if (isTesting) {
            if (frozenNodeOrderIds == null) {
                frozenNodeOrderIds = nodes.map { it.id }
            }
        } else {
            if (wasTesting) {
                pendingScrollToTop = true
            }
            frozenNodeOrderIds = null
        }
        wasTesting = isTesting
    }

    val displayNodes = remember(nodes, isTesting, frozenNodeOrderIds) {
        if (!isTesting || frozenNodeOrderIds == null) {
            nodes
        } else {
            val nodeMap = nodes.associateBy { it.id }
            val frozenNodes = frozenNodeOrderIds.orEmpty().mapNotNull(nodeMap::get)
            val newNodes = nodes.filterNot { it.id in frozenNodeOrderIds.orEmpty() }
            frozenNodes + newNodes
        }
    }

    val filteredNodes = remember(displayNodes, keyword) {
        val query = keyword.trim()
        if (query.isBlank()) {
            displayNodes
        } else {
            displayNodes.filter { node ->
                node.getDisplayName().contains(query, ignoreCase = true) ||
                    node.name.contains(query, ignoreCase = true) ||
                    node.country?.contains(query, ignoreCase = true) == true
            }
        }
    }

    LaunchedEffect(pendingScrollToTop, isTesting, frozenNodeOrderIds, filteredNodes.size) {
        if (pendingScrollToTop && !isTesting && frozenNodeOrderIds == null && filteredNodes.isNotEmpty()) {
            withFrameNanos { }
            listState.scrollToItem(0)
            pendingScrollToTop = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (isTesting) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        NodeListCategorySwitch(
            category = category,
            primaryLabel = "\u4e3b\u8282\u70b9",
            enabled = interactionEnabled && !isTesting,
            onCategoryChange = onCategoryChange
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (showSearch) {
            OutlinedTextField(
                value = keyword,
                onValueChange = onKeywordChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("输入关键词搜索节点") },
                trailingIcon = {
                    if (keyword.isNotEmpty()) {
                        IconButton(
                            onClick = { onKeywordChange("") },
                            enabled = interactionEnabled
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "清空")
                        }
                    }
                }
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        when {
            nodes.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isTesting) "正在获取节点..." else "暂无可用节点",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 16.sp
                    )
                }
            }

            filteredNodes.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "未找到匹配节点",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 16.sp
                    )
                }
            }

            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = filteredNodes,
                        key = { it.id }
                    ) { node ->
                        val isFavorite =
                            node.source == NodeSource.FAVORITE ||
                                favoriteSourceNodeIds.contains(node.id)
                        NodeListItem(
                            node = node,
                            isSelected = node.id == selectedNodeId,
                            isFavorite = isFavorite,
                            isTesting = isTesting,
                            enabled = interactionEnabled,
                            onClick = { onNodeSelected(node) },
                            onToggleFavorite = { onToggleFavorite(node, isFavorite) }
                        )
                    }
                }
            }
        }

        if (!interactionEnabled) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { }
            )
        }
    }
}

@Composable
private fun NodeListCategorySwitch(
    category: NodeListCategory,
    primaryLabel: String,
    enabled: Boolean,
    onCategoryChange: (NodeListCategory) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            NodeListCategorySegment(
                text = primaryLabel,
                selected = category == NodeListCategory.PRIMARY,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = { onCategoryChange(NodeListCategory.PRIMARY) }
            )
            NodeListCategorySegment(
                text = "\u6536\u85cf\u8282\u70b9",
                selected = category == NodeListCategory.FAVORITES,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = { onCategoryChange(NodeListCategory.FAVORITES) }
            )
        }
    }
}

@Composable
private fun NodeListCategorySegment(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onClick() },
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(vertical = 9.dp),
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 14.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun NodeListItem(
    modifier: Modifier = Modifier,
    node: Node,
    isSelected: Boolean,
    isFavorite: Boolean,
    isTesting: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val backgroundColor =
        if (isSelected) Primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
    val borderColor = if (isSelected) Primary else Color.Transparent

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onClick() },
        color = backgroundColor,
        shape = RoundedCornerShape(12.dp),
        border = if (isSelected) BorderStroke(2.dp, borderColor) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                NodeIcon(
                    node = node,
                    size = 28.dp,
                    flagFontSize = 24.sp,
                    modifier = Modifier.padding(end = 12.dp)
                )

                Column {
                    Text(
                        text = node.getDisplayName(),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (node.isAvailable) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        text = node.type.protocol.uppercase(),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = onToggleFavorite,
                    enabled = enabled,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "\u6536\u85cf\u8282\u70b9",
                        tint = if (isFavorite) Primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                LatencyBadge(node = node, isTesting = isTesting)
            }
        }
    }
}

@Composable
fun LatencyBadge(node: Node, isTesting: Boolean = false) {
    val latencyColor = when (node.getLatencyLevel()) {
        LatencyLevel.GOOD -> LatencyGood
        LatencyLevel.MEDIUM -> LatencyMedium
        LatencyLevel.BAD -> LatencyBad
    }

    Surface(
        color = latencyColor.copy(alpha = 0.15f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = if (isTesting && node.latency == -1) "测试中" else node.getLatencyText(),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = latencyColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
