package xyz.a202132.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import xyz.a202132.app.data.model.PerAppProxyMode
import xyz.a202132.app.ui.theme.Primary
import xyz.a202132.app.viewmodel.AppInfo
import xyz.a202132.app.viewmodel.PerAppProxyViewModel

import androidx.activity.compose.BackHandler

/**
 * 分应用代理设置界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerAppProxyScreen(
    onBack: (Boolean) -> Unit,
    viewModel: PerAppProxyViewModel = viewModel()
) {
    // 监听系统返回键 (包括手势返回)
    // 返回是否发生了更改，以便主界面决定是否重启 VPN
    val hasChanges by viewModel.hasChanges.collectAsState()
    BackHandler { onBack(hasChanges) }

    val isEnabled by viewModel.isEnabled.collectAsState()
    val mode by viewModel.mode.collectAsState()
    val selectedPackages by viewModel.selectedPackages.collectAsState()
    val filteredApps by viewModel.filteredApps.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val selectedCount by viewModel.selectedCount.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val showSystemApps by viewModel.showSystemApps.collectAsState()
    
    // 监听生命周期，在 onResume 时刷新应用列表 (解决用户授权后返回不刷新问题)
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.refreshApps()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // 退出时清空搜索框
            viewModel.searchQuery.value = ""
        }
    }
    
    // 权限拒绝状态 (针对定制 ROM)
    val isPermissionDenied by viewModel.isPermissionDenied.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("分应用代理") },
                navigationIcon = {
                    IconButton(onClick = { onBack(viewModel.hasChanges.value) }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 全选按钮
                    IconButton(onClick = { viewModel.selectAll() }) {
                        Icon(Icons.Outlined.SelectAll, contentDescription = "全选")
                    }
                    // 取消全选按钮
                    IconButton(onClick = { viewModel.deselectAll() }) {
                        Icon(Icons.Filled.Clear, contentDescription = "取消全选")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            
            // 权限提示卡片 (仅当检测到权限受限时显示)
            if (isPermissionDenied) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "无法获取应用列表",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "您的系统可能限制了读取应用列表权限。请前往设置授予权限，或手动搜索应用。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                try {
                                    val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    intent.data = android.net.Uri.parse("package:${context.packageName}")
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    // ignore
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("前往授权")
                        }
                    }
                }
            }

            // 设置区域
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    // 启用开关
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "启用分应用代理",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Switch(
                            checked = isEnabled,
                            onCheckedChange = { viewModel.setEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Primary
                            )
                        )
                    }
                    
                    // 分隔线
                    Divider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )
                    
                    // 模式选择
                    Text(
                        text = "代理模式",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 代理模式按钮
                        ModeButton(
                            title = "代理模式",
                            subtitle = "仅代理选中",
                            isSelected = mode == PerAppProxyMode.WHITELIST,
                            enabled = isEnabled,
                            onClick = { viewModel.setMode(PerAppProxyMode.WHITELIST) },
                            modifier = Modifier.weight(1f)
                        )
                        
                        // 绕过模式按钮
                        ModeButton(
                            title = "绕过模式",
                            subtitle = "绕过选中",
                            isSelected = mode == PerAppProxyMode.BLACKLIST,
                            enabled = isEnabled,
                            onClick = { viewModel.setMode(PerAppProxyMode.BLACKLIST) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            
            // 过滤选项
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 显示系统应用
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { viewModel.showSystemApps.value = !showSystemApps }
                ) {
                    Checkbox(
                        checked = showSystemApps,
                        onCheckedChange = { viewModel.showSystemApps.value = it },
                        enabled = isEnabled,
                        colors = CheckboxDefaults.colors(
                            checkedColor = Primary
                        )
                    )
                    Text(
                        text = "显示系统应用",
                        fontSize = 14.sp,
                        color = if (isEnabled) MaterialTheme.colorScheme.onSurface 
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // 已选择数量
                Text(
                    text = "已选择 $selectedCount 个应用",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // 搜索框
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.searchQuery.value = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("搜索应用...") },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                singleLine = true,
                enabled = isEnabled,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    cursorColor = Primary
                )
            )
            
            // 应用列表
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Primary)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = filteredApps,
                        key = { it.packageName }
                    ) { app ->
                        AppListItem(
                            app = app,
                            isSelected = app.packageName in selectedPackages,
                            enabled = isEnabled,
                            onToggle = { viewModel.togglePackage(app.packageName) }
                        )
                    }
                }
            }
            
            // 提示信息 (如果没显示权限警告，则显示普通提示)
            if (isEnabled && !isPermissionDenied) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Primary.copy(alpha = 0.1f)
                    )
                ) {
                    Text(
                        text = if (mode == PerAppProxyMode.WHITELIST) {
                            "💡 代理模式：只有选中的应用流量会经过 VPN"
                        } else {
                            "💡 绕过模式：选中的应用流量会绕过 VPN 直连"
                        },
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

/**
 * 模式选择按钮
 */
@Composable
private fun ModeButton(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected && enabled) Primary else MaterialTheme.colorScheme.surface,
        border = if (!isSelected && enabled) {
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        } else null
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                    isSelected -> Color.White
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    isSelected -> Color.White.copy(alpha = 0.8f)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

/**
 * 应用列表项
 */
@Composable
private fun AppListItem(
    app: AppInfo,
    isSelected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onToggle() },
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected && enabled) {
            Primary.copy(alpha = 0.1f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
        border = if (isSelected && enabled) {
            androidx.compose.foundation.BorderStroke(1.dp, Primary)
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 应用图标
            if (app.icon != null) {
                Image(
                    bitmap = app.icon.toBitmap(48, 48).asImageBitmap(),
                    contentDescription = app.appName,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.outline),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = app.appName.take(1).uppercase(),
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // 应用信息
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = app.appName,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface 
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = app.packageName,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            // 选中状态
            if (isSelected && enabled) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(Primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "已选择",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            } else {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = null,
                    enabled = enabled,
                    colors = CheckboxDefaults.colors(
                        checkedColor = Primary
                    )
                )
            }
        }
    }
}
