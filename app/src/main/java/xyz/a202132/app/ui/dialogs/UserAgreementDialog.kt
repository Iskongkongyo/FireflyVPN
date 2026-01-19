package xyz.a202132.app.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import xyz.a202132.app.ui.theme.BackgroundDarkVariant
import xyz.a202132.app.ui.theme.Primary
import xyz.a202132.app.ui.theme.TextPrimary
import xyz.a202132.app.ui.theme.TextSecondary

@Composable
fun UserAgreementDialog(
    onAgree: () -> Unit,
    onDisagree: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {}, // 不允许点击外部关闭
        title = {
            Text(
                text = "使用协议",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "本应用提供网络加速代理服务，旨在保护用户隐私。请在遵守所在地法律法规的前提下使用。\n\n" +
                            "本应用仅限于学习、娱乐等合法合规用途。用户因使用本应用而产生的任何行为及后果均由用户自行承担。\n\n" +
                            "如因违反当地法律法规或用于任何违法违规用途，本应用不承担任何责任。\n\n" +
                            "安装并使用本应用即视为您已阅读、理解并同意本协议；如不同意，请立即停止使用并从设备中删除本应用。",
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 22.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onAgree,
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) {
                Text("同意协议", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDisagree
            ) {
                Text("不同意", color = Color.Red)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    )
}
