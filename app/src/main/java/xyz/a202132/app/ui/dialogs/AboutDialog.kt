package xyz.a202132.app.ui.dialogs

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.a202132.app.BuildConfig
import xyz.a202132.app.R
import xyz.a202132.app.ui.theme.Primary

@Composable
fun AboutDialog(
    githubUrl: String,
    onCheckUpdate: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val releasesUrl = if (githubUrl.isNotBlank()) "$githubUrl/releases" else ""

    BackHandler(onBack = onDismiss)

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val verticalSafePadding = maxOf(statusBarTop, navigationBarBottom) + 12.dp

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
            .padding(
                start = 14.dp,
                top = verticalSafePadding,
                end = 14.dp,
                bottom = verticalSafePadding
            ),
        contentAlignment = Alignment.Center
    ) {
        val isLargeScreen = maxWidth >= 600.dp
        val dialogWidthFraction = if (isLargeScreen) 0.58f else 1f
        // Wrap short content, but allow long content to use the entire safe area before
        // falling back to internal scrolling. This keeps the action buttons visible whenever
        // the device has enough vertical space.
        val dialogMaxHeight = maxHeight
        val contentHorizontalPadding = if (isLargeScreen) 28.dp else 18.dp
        val contentVerticalPadding = if (isLargeScreen) 26.dp else 16.dp
        val iconSize = if (isLargeScreen) 88.dp else 64.dp
        val sectionSpacer = if (isLargeScreen) 24.dp else 14.dp
        val itemSpacer = if (isLargeScreen) 16.dp else 10.dp

            Surface(
                modifier = Modifier
                    .fillMaxWidth(dialogWidthFraction)
                    .widthIn(max = 540.dp)
                    .wrapContentHeight()
                    .heightIn(max = dialogMaxHeight)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    ),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(max = dialogMaxHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = contentHorizontalPadding, vertical = contentVerticalPadding),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // App Icon
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
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(iconSize)
                    )

                    Spacer(modifier = Modifier.height(if (isLargeScreen) 16.dp else 10.dp))

                    // App名字
                    Text(
                        text = "FireflyVPN",
                        fontSize = if (isLargeScreen) 24.sp else 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // 标语
                    Text(
                        text = "流萤加速器",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(if (isLargeScreen) 20.dp else 12.dp))

                    // 描述
                    Text(
                        text = "一款基于 sing-box 核心，支持多种代理协议和智能分流的 Android VPN 客户端。",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp
                    )

                    Spacer(modifier = Modifier.height(sectionSpacer))

                    Divider(color = MaterialTheme.colorScheme.outlineVariant)

                    Spacer(modifier = Modifier.height(sectionSpacer))

                    // 免责声明
                    Text(
                        text = "免责声明",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "本项目为个人开源作品，与米哈游 (HoYoverse) 无关。本项目不盈利、不接受捐赠。所有涉及的游戏角色名称及设计版权归米哈游所有。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 19.sp,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(itemSpacer))

                    // 免责声明
                    Text(
                        text = "Disclaimers",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "This project is an open-source creation and is not related to miHoYo (HoYoverse). This project is non-profit and not for sale. All game character names and design copyrights belong to miHoYo.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 19.sp,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(sectionSpacer))

                    Divider(color = MaterialTheme.colorScheme.outlineVariant)

                    Spacer(modifier = Modifier.height(itemSpacer))

                    // 版本 - 可点击查看发布版本（仅当设置了 GitHub URL 时）
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

                        Spacer(modifier = Modifier.height(if (isLargeScreen) 10.dp else 8.dp))
                    }

                    // 源代码 - 可点击跳转至 GitHub（仅当已设置 GitHub URL 时）
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

                    Spacer(modifier = Modifier.height(sectionSpacer))

                    OutlinedButton(
                        onClick = onCheckUpdate,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.SystemUpdate,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.check_update),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

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
