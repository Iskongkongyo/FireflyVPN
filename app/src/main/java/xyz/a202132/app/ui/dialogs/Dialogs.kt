package xyz.a202132.app.ui.dialogs

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.Html
import android.text.method.LinkMovementMethod
import android.util.Base64
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.core.text.HtmlCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.a202132.app.AppConfig
import xyz.a202132.app.data.model.NoticeInfo
import xyz.a202132.app.network.NetworkClient
import xyz.a202132.app.ui.theme.*
import xyz.a202132.app.R
import java.util.concurrent.Executors
import kotlin.math.roundToInt

private val htmlImageExecutor = Executors.newCachedThreadPool()
private val htmlImageClient: OkHttpClient by lazy {
    NetworkClient.withUserAgent(OkHttpClient.Builder()).build()
}

@Composable
fun NoticeDialog(
    notice: NoticeInfo,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = notice.title.ifEmpty { "公告" },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Spacer(modifier = Modifier.height(16.dp))

                HtmlContentText(
                    html = notice.content,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    linkColor = Primary,
                    textSizeSp = 15f
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    Text(
                        text = "我知道了",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun UpdateDialog(
    version: String,
    changelog: String,
    isForce: Boolean,
    onUpdate: () -> Unit,
    onIgnore: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = {
            if (!isForce) {
                onDismiss()
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = !isForce,
            dismissOnClickOutside = !isForce
        )
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(id = R.string.update_title),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = stringResource(id = R.string.version) + ": $version",
                    fontSize = 16.sp,
                    color = Primary,
                    fontWeight = FontWeight.Medium
                )
                
                if (changelog.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))

                    HtmlContentText(
                        html = changelog,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        linkColor = Primary,
                        textSizeSp = 14f
                    )
                }
                
                Spacer(modifier = Modifier.height(24.dp))

                if (!isForce) {
                    OutlinedButton(
                        onClick = onIgnore,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text(
                            text = stringResource(id = R.string.ignore_current_update),
                            fontSize = 15.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (!isForce) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                        ) {
                            Text(
                                text = stringResource(id = R.string.update_later),
                                fontSize = 15.sp,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                    
                    Button(
                        onClick = onUpdate,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        Text(
                            text = stringResource(id = R.string.update_now),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HtmlContentText(
    html: String,
    modifier: Modifier = Modifier,
    textColor: Color,
    linkColor: Color,
    textSizeSp: Float
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextView(context).apply {
                movementMethod = LinkMovementMethod.getInstance()
                linksClickable = true
                setTextIsSelectable(false)
                setLineSpacing(0f, 1.2f)
            }
        },
        update = { textView ->
            textView.textSize = textSizeSp
            textView.setTextColor(textColor.toArgb())
            textView.setLinkTextColor(linkColor.toArgb())
            val parsed = HtmlCompat.fromHtml(
                normalizeHtml(html),
                HtmlCompat.FROM_HTML_MODE_LEGACY,
                HtmlImageGetter(textView),
                null
            )
            textView.text = parsed
        }
    )
}

private fun normalizeHtml(raw: String): String {
    if (raw.isBlank()) return ""
    val looksLikeHtml = Regex("""<\s*/?\s*[a-zA-Z][^>]*>""").containsMatchIn(raw)
    return if (looksLikeHtml) {
        raw
    } else {
        Html.escapeHtml(raw).replace("\n", "<br/>")
    }
}

private class HtmlImageGetter(
    private val textView: TextView
) : Html.ImageGetter {

    override fun getDrawable(source: String?): Drawable {
        val drawable = UrlDrawable(textView)
        val availableWidth = (textView.width - textView.paddingLeft - textView.paddingRight)
            .takeIf { it > 0 }
            ?: (textView.resources.displayMetrics.widthPixels * 0.72f).roundToInt()
        val placeholderHeight = (64f * textView.resources.displayMetrics.density).roundToInt()
        drawable.setBounds(0, 0, availableWidth.coerceAtLeast(1), placeholderHeight.coerceAtLeast(1))

        if (source.isNullOrBlank()) {
            drawable.markFailed()
            return drawable
        }

        htmlImageExecutor.execute {
            val loaded = runCatching { loadDrawable(source) }.getOrNull()
            textView.post {
                if (loaded == null) {
                    drawable.markFailed()
                } else {
                    val maxWidth = (textView.width - textView.paddingLeft - textView.paddingRight)
                        .takeIf { it > 0 }
                        ?: (textView.resources.displayMetrics.widthPixels * 0.8f).toInt()
                    val targetWidth = loaded.intrinsicWidth.coerceAtLeast(1).coerceAtMost(maxWidth)
                    val aspectRatio = loaded.intrinsicHeight.toFloat() / loaded.intrinsicWidth.coerceAtLeast(1)
                    val targetHeight = (targetWidth * aspectRatio).toInt().coerceAtLeast(1)
                    loaded.setBounds(0, 0, targetWidth, targetHeight)
                    drawable.show(loaded, targetWidth, targetHeight)
                }
                textView.text = textView.text
                textView.invalidate()
            }
        }

        return drawable
    }

    private fun loadDrawable(source: String): Drawable? {
        val resolvedSource = when {
            source.startsWith("//") -> "https:$source"
            source.startsWith("/") -> AppConfig.API_BASE_URL + source
            else -> source
        }
        return if (source.startsWith("data:image", ignoreCase = true)) {
            loadDataUriDrawable(source)
        } else {
            val request = Request.Builder().url(resolvedSource).build()
            htmlImageClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.bytes() ?: return null
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                BitmapDrawable(textView.resources, bitmap)
            }
        }
    }

    private fun loadDataUriDrawable(source: String): Drawable? {
        val payload = source.substringAfter("base64,", missingDelimiterValue = "")
        if (payload.isBlank()) return null
        val bytes = Base64.decode(payload, Base64.DEFAULT)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return BitmapDrawable(textView.resources, bitmap)
    }
}

private class UrlDrawable(
    private val textView: TextView
) : Drawable() {
    private var wrapped: Drawable? = null
    private var failed = false
    private var animationScheduled = false
    private val density = textView.resources.displayMetrics.density
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            14f,
            textView.resources.displayMetrics
        )
    }

    fun show(drawable: Drawable, width: Int, height: Int) {
        wrapped = drawable
        failed = false
        setBounds(0, 0, width, height)
        invalidateSelf()
    }

    fun markFailed() {
        wrapped = null
        failed = true
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        wrapped?.let {
            it.draw(canvas)
            return
        }

        backgroundPaint.color = textView.currentTextColor
        backgroundPaint.alpha = 14
        canvas.drawRoundRect(RectF(bounds), 10f * density, 10f * density, backgroundPaint)

        val label = if (failed) "图片加载失败" else "图片加载中……"
        textPaint.color = textView.currentTextColor
        textPaint.alpha = if (failed) 180 else 210
        val baseline = bounds.exactCenterY() - (textPaint.ascent() + textPaint.descent()) / 2f
        val textWidth = textPaint.measureText(label)

        if (failed) {
            canvas.drawText(label, bounds.exactCenterX() - textWidth / 2f, baseline, textPaint)
            return
        }

        val progressSize = 18f * density
        val gap = 9f * density
        val contentWidth = progressSize + gap + textWidth
        val startX = bounds.exactCenterX() - contentWidth / 2f
        val progressBounds = RectF(
            startX,
            bounds.exactCenterY() - progressSize / 2f,
            startX + progressSize,
            bounds.exactCenterY() + progressSize / 2f
        )
        progressPaint.color = textView.linkTextColors.defaultColor
        progressPaint.alpha = 255
        val rotation = (SystemClock.uptimeMillis() % PROGRESS_PERIOD_MS) * 360f / PROGRESS_PERIOD_MS
        canvas.drawArc(progressBounds, rotation, 270f, false, progressPaint)
        canvas.drawText(label, startX + progressSize + gap, baseline, textPaint)
        scheduleNextFrame()
    }

    override fun setAlpha(alpha: Int) {
        wrapped?.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        wrapped?.colorFilter = colorFilter
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = wrapped?.intrinsicWidth ?: 1

    override fun getIntrinsicHeight(): Int = wrapped?.intrinsicHeight ?: 1

    private fun scheduleNextFrame() {
        if (animationScheduled) return
        animationScheduled = true
        textView.postDelayed({
            animationScheduled = false
            if (wrapped == null && !failed) textView.invalidate()
        }, PROGRESS_FRAME_DELAY_MS)
    }

    private companion object {
        const val PROGRESS_PERIOD_MS = 900L
        const val PROGRESS_FRAME_DELAY_MS = 40L
    }
}

@Composable
fun LoadingDialog(
    message: String = "加载中..."
) {
    Dialog(onDismissRequest = {}) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Row(
                modifier = Modifier.padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = Primary,
                    strokeWidth = 2.dp
                )
                
                Text(
                    text = message,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun DownloadDialog(
    progress: Int,
    speed: String,
    status: xyz.a202132.app.network.DownloadStatus,
    errorMessage: String?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit
) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (status == xyz.a202132.app.network.DownloadStatus.ERROR) "下载失败" else "正在下载更新",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                if (status == xyz.a202132.app.network.DownloadStatus.ERROR) {
                     Text(
                        text = errorMessage ?: "发生未知错误",
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                } else {
                    LinearProgressIndicator(
                        progress = progress / 100f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = Primary,
                        trackColor = Primary.copy(alpha = 0.2f)
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "$progress%",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = speed,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("取消")
                    }
                    
                    if (status == xyz.a202132.app.network.DownloadStatus.ERROR) {
                         Button(
                            onClick = onRetry,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Primary)
                        ) {
                            Text("重试")
                        }
                    } else {
                        val isPaused = status == xyz.a202132.app.network.DownloadStatus.PAUSED
                        Button(
                            onClick = if (isPaused) onResume else onPause,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Primary)
                        ) {
                            Text(if (isPaused) "继续" else "暂停")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionDialog(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "需要安装权限",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "为了更新应用，请授予\"安装未知应用\"权限。\n\n请点击下方按钮前往设置页面开启权限。",
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    ) {
                        Text("取消")
                    }
                    
                    Button(
                        onClick = onOpenSettings,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        Text("去设置")
                    }
                }
            }
        }
    }
}
