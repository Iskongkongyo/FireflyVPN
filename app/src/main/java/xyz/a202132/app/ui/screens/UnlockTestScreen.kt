package xyz.a202132.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.ui.components.AppScreenScaffold
import xyz.a202132.app.ui.dialogs.UnlockNodeSelectionPane
import xyz.a202132.app.viewmodel.UnlockTestViewModel

private enum class UnlockNodeSelectionMode {
    ALL,
    CURRENT,
    RANDOM
}

@Composable
fun UnlockTestScreen(
    visibleNodes: List<Node>,
    onBack: () -> Unit,
    onShowResults: () -> Unit,
    viewModel: UnlockTestViewModel
) {
    val context = LocalContext.current
    val nodes by viewModel.nodes.collectAsState()
    val selected by viewModel.selectedNodeIds.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val progressText by viewModel.progressText.collectAsState()
    val results by viewModel.results.collectAsState()
    val error by viewModel.error.collectAsState()
    var selectionMode by remember { mutableStateOf<UnlockNodeSelectionMode?>(null) }
    var randomNodeCountInput by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var keyword by remember { mutableStateOf("") }
    val filteredDisplayNodes = remember(nodes, keyword) {
        val query = keyword.trim()
        if (query.isBlank()) {
            nodes
        } else {
            nodes.filter { node ->
                node.getDisplayName().contains(query, ignoreCase = true) ||
                    node.name.contains(query, ignoreCase = true) ||
                    node.country?.contains(query, ignoreCase = true) == true
            }
        }
    }
    val nodeListState = rememberLazyListState()

    LaunchedEffect(visibleNodes) {
        viewModel.updateVisibleNodes(visibleNodes)
    }

    LaunchedEffect(nodes) {
        val maxCount = nodes.size
        val parsed = randomNodeCountInput.toIntOrNull()
        if (parsed != null && parsed > maxCount) {
            randomNodeCountInput = maxCount.toString()
            if (selectionMode == UnlockNodeSelectionMode.RANDOM) {
                viewModel.selectRandomNodes(maxCount)
            }
        }
    }

    LaunchedEffect(error) {
        error?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearError()
        }
    }

    LaunchedEffect(filteredDisplayNodes) {
        if (filteredDisplayNodes.isNotEmpty()) {
            nodeListState.scrollToItem(0)
        }
    }

    AppScreenScaffold(
        title = "主流站解锁测试",
        subtitle = "通过临时本地代理逐个测试所选节点",
        onBack = onBack,
        backEnabled = !isRunning,
        onBackBlocked = {
            Toast.makeText(context, "请先停止测试再退出", Toast.LENGTH_SHORT).show()
        },
        actions = {
            IconButton(
                onClick = {
                    showSearch = !showSearch
                    if (!showSearch) {
                        keyword = ""
                    }
                }
            ) {
                Icon(Icons.Outlined.Search, contentDescription = "筛选节点")
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
                    label = { Text("过滤节点关键词") },
                    placeholder = { Text("名称 / 地区等") },
                    enabled = !isRunning
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "范围选择",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "已选择 ${selected.size}/${nodes.size}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(6.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clickable(enabled = !isRunning && nodes.isNotEmpty()) {
                                    val selectAll = selectionMode != UnlockNodeSelectionMode.ALL
                                    selectionMode = if (selectAll) {
                                        UnlockNodeSelectionMode.ALL
                                    } else {
                                        null
                                    }
                                    viewModel.setAllSelected(selectAll)
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectionMode == UnlockNodeSelectionMode.ALL,
                                onClick = null,
                                enabled = !isRunning && nodes.isNotEmpty(),
                                modifier = Modifier.size(48.dp)
                            )
                            Text("全部节点")
                        }

                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectionMode == UnlockNodeSelectionMode.CURRENT,
                                onClick = {
                                    if (viewModel.selectCurrentNodeOnly()) {
                                        selectionMode = UnlockNodeSelectionMode.CURRENT
                                    }
                                },
                                enabled = !isRunning,
                                modifier = Modifier.size(48.dp)
                            )
                            Text("当前节点")
                        }
                    }

                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val halfWidth = maxWidth / 2
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectionMode == UnlockNodeSelectionMode.RANDOM,
                                onClick = {
                                    selectionMode = UnlockNodeSelectionMode.RANDOM
                                    val count = randomNodeCountInput.toIntOrNull()
                                    if (count == null) {
                                        viewModel.setAllSelected(false)
                                    } else {
                                        viewModel.selectRandomNodes(count)
                                    }
                                },
                                enabled = !isRunning && nodes.isNotEmpty(),
                                modifier = Modifier.size(48.dp)
                            )

                            OutlinedTextField(
                                value = randomNodeCountInput,
                                onValueChange = { value ->
                                    val digits = value.filter { it.isDigit() }
                                    if (digits.isEmpty()) {
                                        randomNodeCountInput = ""
                                        if (selectionMode == UnlockNodeSelectionMode.RANDOM) {
                                            viewModel.selectRandomNodes(0)
                                        }
                                        return@OutlinedTextField
                                    }
                                    val parsed = digits.toIntOrNull() ?: return@OutlinedTextField
                                    val clamped = parsed.coerceAtMost(nodes.size)
                                    randomNodeCountInput = clamped.toString()
                                    if (selectionMode == UnlockNodeSelectionMode.RANDOM) {
                                        viewModel.selectRandomNodes(clamped)
                                    }
                                },
                                modifier = Modifier
                                    .width(halfWidth - 54.dp)
                                    .height(60.dp),
                                label = { Text("随机节点数", fontSize = 12.sp) },
                                singleLine = true,
                                enabled = !isRunning &&
                                    nodes.isNotEmpty() &&
                                    selectionMode == UnlockNodeSelectionMode.RANDOM,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )

                            Spacer(modifier = Modifier.width(20.dp))

                            Button(
                                onClick = {
                                    if (isRunning) {
                                        viewModel.stopTests()
                                    } else if (viewModel.startTests()) {
                                        onShowResults()
                                    }
                                },
                                modifier = Modifier
                                    .width(halfWidth - 25.dp)
                                    .height(54.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isRunning) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    }
                                )
                            ) {
                                Text(if (isRunning) "停止测试" else "开始测试")
                            }
                        }
                    }
                }
            }

            if (isRunning) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            progressText?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }

            Spacer(modifier = Modifier.height(12.dp))

            UnlockNodeSelectionPane(
                modifier = Modifier.weight(1f),
                filteredDisplayNodes = filteredDisplayNodes,
                selected = selected,
                isRunning = isRunning,
                nodeListState = nodeListState,
                onToggleNode = { nodeId ->
                    selectionMode = null
                    viewModel.toggleNode(nodeId)
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onShowResults,
                enabled = results.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isRunning) "查看测试结果" else "查看最近一次测试结果")
            }
        }
    }
}
