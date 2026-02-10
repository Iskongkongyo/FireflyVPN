package xyz.a202132.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.a202132.app.R
import xyz.a202132.app.data.model.VpnState

@Composable
fun ConnectButton(
    vpnState: VpnState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    customLabel: String? = null
) {
    val isConnecting = vpnState == VpnState.CONNECTING || vpnState == VpnState.DISCONNECTING

    // 自适应按钮大小：取屏幕宽度的 65%，但不超过 250dp
    val configuration = LocalConfiguration.current
    val buttonSize = minOf(250, (configuration.screenWidthDp * 0.65).toInt())

    // 连接中的脉冲动画
    val infiniteTransition = rememberInfiniteTransition(label = "loading")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val scale = if (isConnecting) pulseScale else 1f

    // 根据状态选择图片
    val imageRes = when (vpnState) {
        VpnState.DISCONNECTED -> R.drawable.btn_disconnected
        VpnState.CONNECTING, VpnState.DISCONNECTING -> R.drawable.btn_connecting
        VpnState.CONNECTED -> R.drawable.btn_connected
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        // 按钮图片 - 使用 Crossfade 实现平滑过渡，去除涟漪效果
        Crossfade(
            targetState = imageRes,
            animationSpec = tween(400),
            label = "buttonImage"
        ) { currentImage ->
            Image(
                painter = painterResource(id = currentImage),
                contentDescription = when (vpnState) {
                    VpnState.CONNECTED -> "已连接"
                    VpnState.CONNECTING -> "连接中"
                    VpnState.DISCONNECTING -> "断开中"
                    VpnState.DISCONNECTED -> "点击连接"
                },
                modifier = Modifier
                    .size((buttonSize * scale).dp)
                    .clickable(
                        enabled = !isConnecting,
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    ) { onClick() }
            )
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
