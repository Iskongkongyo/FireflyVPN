package xyz.a202132.app.ui.components

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import xyz.a202132.app.data.model.LatencyLevel
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeListCategory
import xyz.a202132.app.data.model.NodeSource
import xyz.a202132.app.data.model.NodeGroup
import xyz.a202132.app.data.model.FAVORITES_NODE_GROUP_ID
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import xyz.a202132.app.ui.theme.LatencyBad
import xyz.a202132.app.ui.theme.LatencyGood
import xyz.a202132.app.ui.theme.LatencyMedium
import xyz.a202132.app.ui.theme.Primary

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
    onSkipFavoriteRemovalConfirmationForSession: () -> Unit,
    onImportFromText: (String) -> Unit,
    onScanQrCode: () -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var showSearch by remember { mutableStateOf(false) }
    var keyword by remember { mutableStateOf("") }
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
            onKeywordChange = { keyword = it },
            onNodeSelected = handleNodeSelected,
            onGroupChange = onGroupChange,
            onToggleFavorite = handleToggleFavorite,
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
    enabled: Boolean,
    onImportFromClipboard: () -> Unit,
    onScanQrCode: () -> Unit,
    onToggleSearch: () -> Unit,
    onRefresh: () -> Unit
) {
    var showImportMenu by remember { mutableStateOf(false) }

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

private data class NodeListPage(
    val id: String,
    val label: String,
    val favorites: Boolean = false,
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
    onKeywordChange: (String) -> Unit,
    onNodeSelected: (Node) -> Unit,
    onGroupChange: (String) -> Unit,
    onToggleFavorite: (Node, Boolean) -> Unit,
    interactionEnabled: Boolean
) {
    val pages = remember(groups) {
        groups.map {
            NodeListPage(
                id = it.id,
                label = it.name,
                favorites = it.isFavorites,
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

    LaunchedEffect(selectedGroupId, pages) {
        val target = pages.indexOfFirst { it.id == selectedGroupId }
        if (target >= 0 && target != pagerState.currentPage) {
            pagerState.scrollToPage(target)
        }
    }
    LaunchedEffect(pagerState, pages) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { pageIndex ->
                pages.getOrNull(pageIndex)?.id?.let(onGroupChange)
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

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = interactionEnabled && !isTesting
        ) { pageIndex ->
            val page = pages[pageIndex]
            val pageNodes = remember(allNodes, page.id) {
                if (page.favorites) {
                    allNodes.filter { it.source == NodeSource.FAVORITE }
                } else {
                    allNodes.filter {
                        it.source == NodeSource.SUBSCRIPTION && it.subscriptionGroupId == page.id
                    }
                }
            }
            NodeListPageContent(
                nodes = pageNodes,
                selectedNodeId = selectedNodeId,
                favoriteSourceNodeIds = favoriteSourceNodeIds,
                isTesting = isTesting,
                keyword = keyword,
                interactionEnabled = interactionEnabled,
                onNodeSelected = onNodeSelected,
                onToggleFavorite = onToggleFavorite
            )
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
    interactionEnabled: Boolean,
    onNodeSelected: (Node) -> Unit,
    onToggleFavorite: (Node, Boolean) -> Unit
) {
    var frozenNodeOrderIds by remember { mutableStateOf<List<String>?>(null) }
    var wasTesting by remember { mutableStateOf(isTesting) }
    var pendingScrollToTop by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(isTesting) {
        if (isTesting && frozenNodeOrderIds == null) {
            frozenNodeOrderIds = nodes.map { it.id }
        } else if (!isTesting) {
            if (wasTesting) pendingScrollToTop = true
            frozenNodeOrderIds = null
        }
        wasTesting = isTesting
    }

    val displayNodes = remember(nodes, isTesting, frozenNodeOrderIds) {
        if (!isTesting || frozenNodeOrderIds == null) nodes else {
            val nodeMap = nodes.associateBy { it.id }
            frozenNodeOrderIds.orEmpty().mapNotNull(nodeMap::get) +
                nodes.filterNot { it.id in frozenNodeOrderIds.orEmpty() }
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

    LaunchedEffect(pendingScrollToTop, isTesting, filteredNodes.size) {
        if (pendingScrollToTop && !isTesting && filteredNodes.isNotEmpty()) {
            withFrameNanos { }
            listState.scrollToItem(0)
            pendingScrollToTop = false
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
        else -> LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filteredNodes, key = { it.id }) { node ->
                val isFavorite = node.source == NodeSource.FAVORITE || favoriteSourceNodeIds.contains(node.id)
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
        modifier = Modifier
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
