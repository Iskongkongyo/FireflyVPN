package xyz.a202132.app.ui.dialogs

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import xyz.a202132.app.BuildConfig
import xyz.a202132.app.AppConfig
import xyz.a202132.app.ui.theme.Primary

@Composable
fun AboutDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val githubUrl = AppConfig.GITHUB_URL
    val releasesUrl = if (githubUrl.isNotBlank()) "$githubUrl/releases" else ""

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.75f),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // App Icon
                val context = LocalContext.current
                val iconDrawable = remember {
                    context.packageManager.getApplicationIcon(context.packageName)
                }
                val iconBitmap = remember(iconDrawable) {
                    val bitmap = android.graphics.Bitmap.createBitmap(
                        iconDrawable.intrinsicWidth.coerceAtLeast(1),
                        iconDrawable.intrinsicHeight.coerceAtLeast(1),
                        android.graphics.Bitmap.Config.ARGB_8888
                    )
                    val canvas = android.graphics.Canvas(bitmap)
                    iconDrawable.setBounds(0, 0, canvas.width, canvas.height)
                    iconDrawable.draw(canvas)
                    bitmap.asImageBitmap()
                }
                Image(
                    bitmap = iconBitmap,
                    contentDescription = "App Icon",
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // App Name
                Text(
                    text = "FireflyVPN",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Tagline
                Text(
                    text = "流萤加速器",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Description
                Text(
                    text = "一款基于 sing-box 核心，支持多种代理协议和智能分流的 Android VPN 客户端。",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp
                )

                Spacer(modifier = Modifier.height(24.dp))

                Divider(color = MaterialTheme.colorScheme.outlineVariant)

                Spacer(modifier = Modifier.height(24.dp))

                // Disclaimer CN
                Text(
                    text = "免责声明",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "本项目为个人开源作品，与米哈游 (HoYoverse) 无关。本项目不盈利、不接受捐赠。所有涉及的游戏角色名称及设计版权归米哈游所有。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 19.sp,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Disclaimer EN
                Text(
                    text = "Disclaimers",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "This project is an open-source creation and is not related to miHoYo (HoYoverse). This project is non-profit and not for sale. All game character names and design copyrights belong to miHoYo.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 19.sp,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(24.dp))

                Divider(color = MaterialTheme.colorScheme.outlineVariant)

                Spacer(modifier = Modifier.height(16.dp))

                // Version - clickable to releases (only if GitHub URL is set)
                if (releasesUrl.isNotBlank()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(releasesUrl))
                                context.startActivity(intent)
                            },
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.NewReleases,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "版本 ${BuildConfig.VERSION_NAME}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "查看更新 →",
                                fontSize = 12.sp,
                                color = Primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                }

                // Source Code - clickable to GitHub (only if GitHub URL is set)
                if (githubUrl.isNotBlank()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl))
                                context.startActivity(intent)
                            },
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Code,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "项目源代码",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "GitHub →",
                                fontSize = 12.sp,
                                color = Primary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Close button
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
