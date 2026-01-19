package xyz.a202132.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.a202132.app.data.model.VpnState
import xyz.a202132.app.ui.theme.*

@Composable
fun ConnectButton(
    vpnState: VpnState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    customLabel: String? = null
) {
    val isConnecting = vpnState == VpnState.CONNECTING || vpnState == VpnState.DISCONNECTING
    val isConnected = vpnState == VpnState.CONNECTED
    
    // 动画颜色
    val buttonColor by animateColorAsState(
        targetValue = when (vpnState) {
            VpnState.CONNECTED -> ConnectedGreen
            VpnState.CONNECTING -> ConnectingYellow
            VpnState.DISCONNECTING -> ConnectingYellow
            VpnState.DISCONNECTED -> Primary
        },
        animationSpec = tween(300),
        label = "buttonColor"
    )
    
    val glowColor by animateColorAsState(
        targetValue = when (vpnState) {
            VpnState.CONNECTED -> ConnectedGreenGlow.copy(alpha = 0.3f)
            VpnState.CONNECTING, VpnState.DISCONNECTING -> ConnectingYellow.copy(alpha = 0.3f)
            VpnState.DISCONNECTED -> Primary.copy(alpha = 0.2f)
        },
        animationSpec = tween(300),
        label = "glowColor"
    )
    
    // 加载动画
    val infiniteTransition = rememberInfiniteTransition(label = "loading")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    
    val scale = if (isConnecting) pulseScale else 1f
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        // 外圈发光效果
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size((140 * scale).dp)
                .shadow(
                    elevation = 20.dp,
                    shape = CircleShape,
                    ambientColor = glowColor,
                    spotColor = glowColor
                )
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            glowColor,
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        ) {
            // 主按钮
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                buttonColor,
                                buttonColor.copy(alpha = 0.8f)
                            )
                        )
                    )
                    .clickable(enabled = !isConnecting) { onClick() }
            ) {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = "Connect",
                    tint = Color.White,
                    modifier = Modifier.size(56.dp)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // 状态文本
        Text(
            text = customLabel ?: when (vpnState) {
                VpnState.CONNECTED -> "已连接"
                VpnState.CONNECTING -> "连接中..."
                VpnState.DISCONNECTING -> "断开中..."
                VpnState.DISCONNECTED -> "点击连接"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
