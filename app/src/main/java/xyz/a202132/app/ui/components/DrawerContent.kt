package xyz.a202132.app.ui.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.a202132.app.AppConfig
import xyz.a202132.app.BuildConfig
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import xyz.a202132.app.data.model.IPv6RoutingMode
import xyz.a202132.app.ui.theme.*

@Composable
fun DrawerContent(
    onCheckUpdate: () -> Unit,
    onOpenPerAppProxy: () -> Unit,
    bypassLan: Boolean,
    onToggleBypassLan: (Boolean) -> Unit,
    ipv6RoutingMode: IPv6RoutingMode,
    onIPv6RoutingModeChange: (IPv6RoutingMode) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var showIPv6Dialog by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(280.dp)
            .padding(vertical = 24.dp)
    ) {
        // 标题
        Text(
            text = "设置",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
        )
        
        Divider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 8.dp))
        
        // 菜单项
        DrawerMenuItem(
            icon = Icons.Outlined.SystemUpdate,
            title = "检查更新",
            onClick = {
                onCheckUpdate()
                onClose()
            }
        )
        
        DrawerMenuItem(
            icon = Icons.Outlined.Apps,
            title = "分应用代理",
            subtitle = "选择代理应用",
            onClick = {
                onOpenPerAppProxy()
                onClose()
            }
        )
        
        // IPv6 路由菜单项
        DrawerMenuItem(
            icon = Icons.Outlined.SettingsEthernet,
            title = "IPv6 路由",
            subtitle = when (ipv6RoutingMode) {
                IPv6RoutingMode.DISABLED -> "禁用"
                IPv6RoutingMode.ENABLED -> "启用"
                IPv6RoutingMode.PREFER -> "优先"
                IPv6RoutingMode.ONLY -> "仅"
            },
            onClick = { showIPv6Dialog = true }
        )
        
        DrawerMenuToggle(
            icon = Icons.Outlined.Router,
            title = "绕过局域网",
            subtitle = if (bypassLan) "已开启" else "已关闭",
            checked = bypassLan,
            onCheckedChange = { newValue ->
                onToggleBypassLan(newValue)
                Toast.makeText(
                    context,
                    if (newValue) "已开启绕过局域网" else "已关闭绕过局域网",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
        
        DrawerMenuItem(
            icon = Icons.Outlined.Language,
            title = "官方网站",
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(AppConfig.WEBSITE_URL))
                context.startActivity(intent)
                onClose()
            }
        )
        
        DrawerMenuItem(
            icon = Icons.Outlined.Email,
            title = "问题反馈",
            subtitle = AppConfig.FEEDBACK_EMAIL,
            onClick = {
                // 复制邮箱到剪贴板
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("email", AppConfig.FEEDBACK_EMAIL)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "邮箱已复制", Toast.LENGTH_SHORT).show()
            }
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        // 版本信息
        Text(
            text = "版本 ${BuildConfig.VERSION_NAME}",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
        )
    }
    
    // IPv6 路由选项弹窗
    if (showIPv6Dialog) {
        IPv6RoutingDialog(
            currentMode = ipv6RoutingMode,
            onModeSelected = { mode ->
                onIPv6RoutingModeChange(mode)
                showIPv6Dialog = false
                Toast.makeText(
                    context,
                    "IPv6 路由已设置为: " + when (mode) {
                        IPv6RoutingMode.DISABLED -> "禁用"
                        IPv6RoutingMode.ENABLED -> "启用"
                        IPv6RoutingMode.PREFER -> "优先"
                        IPv6RoutingMode.ONLY -> "仅"
                    },
                    Toast.LENGTH_SHORT
                ).show()
            },
            onDismiss = { showIPv6Dialog = false }
        )
    }
}

@Composable
private fun DrawerMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        color = androidx.compose.ui.graphics.Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerMenuToggle(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        color = androidx.compose.ui.graphics.Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // Switch 移除，点击整行即可切换，更美观
        }
    }
}

/**
 * IPv6 路由选项弹窗
 */
@Composable
private fun IPv6RoutingDialog(
    currentMode: IPv6RoutingMode,
    onModeSelected: (IPv6RoutingMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "IPv6 路由",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = "选择 IPv6 路由模式：",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                val options = listOf(
                    IPv6RoutingMode.ONLY to "仅" to "仅使用 IPv6 (实验性)",
                    IPv6RoutingMode.PREFER to "优先" to "优先使用 IPv6",
                    IPv6RoutingMode.ENABLED to "启用" to "同时支持 IPv4 和 IPv6",
                    IPv6RoutingMode.DISABLED to "禁用" to "不使用 IPv6 (默认)"
                )
                
                options.forEach { (modePair, description) ->
                    val (mode, label) = modePair
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onModeSelected(mode) },
                        color = if (currentMode == mode) 
                            MaterialTheme.colorScheme.primaryContainer 
                        else 
                            androidx.compose.ui.graphics.Color.Transparent,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Text(
                                text = label,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (currentMode == mode)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = description,
                                fontSize = 12.sp,
                                color = if (currentMode == mode)
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}
