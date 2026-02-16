package xyz.a202132.app.ui.dialogs

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import xyz.a202132.app.AppConfig
import xyz.a202132.app.NetworkTool
import xyz.a202132.app.ui.theme.Primary

/**
 * 网络工具箱对话框
 */
@Composable
fun NetworkToolboxDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val tools = remember { AppConfig.getNetworkTools() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.78f),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 28.dp)
            ) {
                // 标题区域
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Construction,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "网络工具箱",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "常用网络检测与查询工具",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                Divider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(16.dp))

                // 工具网格
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(tools) { tool ->
                        NetworkToolCard(
                            tool = tool,
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(tool.url))
                                context.startActivity(intent)
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 关闭按钮
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    Text(
                        text = "关闭",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * 单个网络工具卡片
 */
@Composable
private fun NetworkToolCard(
    tool: NetworkTool,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() },
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = getToolIcon(tool.icon),
                contentDescription = tool.name,
                tint = Primary,
                modifier = Modifier.size(32.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = tool.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            // 显示简化的域名
            Text(
                text = Uri.parse(tool.url).host ?: tool.url,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 根据图标标识获取 Material Icon
 */
private fun getToolIcon(iconKey: String): ImageVector {
    return when (iconKey) {
        "outbound" -> Icons.Outlined.TravelExplore
        "ip" -> Icons.Outlined.LocationSearching
        "webrtc" -> Icons.Outlined.Videocam
        "dns" -> Icons.Outlined.Dns
        "check" -> Icons.Outlined.VerifiedUser
        "precision" -> Icons.Outlined.GpsFixed
        "disguise" -> Icons.Outlined.Masks
        "bgp" -> Icons.Outlined.AccountTree
        "speed" -> Icons.Outlined.Speed
        else -> Icons.Outlined.Language
    }
}
