package xyz.a202132.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.a202132.app.service.ServiceManager
import xyz.a202132.app.ui.theme.*

/**
 * 流量统计显示行
 */
@Composable
fun TrafficStatsRow(
    uploadSpeed: Long,
    downloadSpeed: Long,
    uploadTotal: Long,
    downloadTotal: Long,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // 上传统计
            TrafficStatItem(
                icon = Icons.Default.ArrowUpward,
                speed = ServiceManager.formatSpeed(uploadSpeed),
                total = ServiceManager.formatTraffic(uploadTotal),
                label = "上传",
                tint = AccentGreen
            )
            
            // 分隔线
            Divider(
                modifier = Modifier
                    .height(48.dp)
                    .width(1.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            )
            
            // 下载统计
            TrafficStatItem(
                icon = Icons.Default.ArrowDownward,
                speed = ServiceManager.formatSpeed(downloadSpeed),
                total = ServiceManager.formatTraffic(downloadTotal),
                label = "下载",
                tint = AccentBlue
            )
        }
    }
}

@Composable
private fun TrafficStatItem(
    icon: ImageVector,
    speed: String,
    total: String,
    label: String,
    tint: androidx.compose.ui.graphics.Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = speed,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "总计: $total",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
