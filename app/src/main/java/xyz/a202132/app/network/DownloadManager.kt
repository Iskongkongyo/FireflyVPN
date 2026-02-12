package xyz.a202132.app.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Download Status Enum
 */
enum class DownloadStatus {
    IDLE,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    ERROR,
    CANCELED
}

/**
 * Download State Data Class
 */
data class DownloadState(
    val status: DownloadStatus = DownloadStatus.IDLE,
    val progress: Int = 0,               // 0-100
    val speed: String = "0 KB/s",
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null,
    val file: File? = null,
    val consecutiveFailures: Int = 0      // 连续失败次数（不含无网络）
)

object DownloadManager {
    private const val TAG = "DownloadManager"
    private val client = OkHttpClient()
    
    // StateFlow for UI observation
    private val _downloadState = MutableStateFlow(DownloadState())
    val downloadState = _downloadState.asStateFlow()
    
    private var downloadUrl: String = ""
    private var targetFile: File? = null
    
    // Control flags
    private var isPaused = false
    private var isCancelled = false
    
    // 连续失败计数器（排除无网络错误）
    private var consecutiveFailures = 0
    
    /**
     * Start or Resume download
     */
    /**
     * Start or Resume download
     */
    /**
     * Start or Resume download
     */
    suspend fun startDownload(url: String, context: Context, versionName: String) {
        withContext(Dispatchers.IO) {
            try {
                val finalFileName = "update_$versionName.apk"
                val tempFileName = "update_$versionName.temp"
                val finalFile = File(context.externalCacheDir, finalFileName)
                val tempFile = File(context.externalCacheDir, tempFileName)
                
                // 1. Check if final file exists and is valid
                if (finalFile.exists() && finalFile.length() > 0) {
                    Log.d(TAG, "Final file exists, skipping download")
                    if (_downloadState.value.status != DownloadStatus.COMPLETED) {
                         _downloadState.value = DownloadState(
                            status = DownloadStatus.COMPLETED,
                            progress = 100,
                            file = finalFile,
                            totalBytes = finalFile.length(),
                            downloadedBytes = finalFile.length()
                        )
                    }
                    return@withContext
                }
                
                // Initialize checks
                if (downloadUrl != url) {
                    // New download request
                    downloadUrl = url
                    targetFile = tempFile // Work on temp file
                    
                    // Clear old temp files
                    if (tempFile.exists()) {
                         // Decide if we want to resume or restart. Protocol assumes resume if same url.
                         // But here we are treating URL change as new download.
                         // For simplicity, let's keep resume logic below check.
                    }
                    
                    // Clear old files from other versions
                    context.externalCacheDir?.listFiles()?.forEach { file ->
                        if (file.name.startsWith("update_") && file.name != finalFileName && file.name != tempFileName) {
                            file.delete()
                        }
                    }
                    
                    _downloadState.value = DownloadState(status = DownloadStatus.DOWNLOADING)
                } else {
                    // Resume logic
                    if (_downloadState.value.status == DownloadStatus.PAUSED || 
                        _downloadState.value.status == DownloadStatus.ERROR) {
                        targetFile = tempFile // Ensure we point to temp
                         _downloadState.value = _downloadState.value.copy(status = DownloadStatus.DOWNLOADING, error = null)
                    } else if (_downloadState.value.status == DownloadStatus.COMPLETED) {
                        return@withContext
                    }
                    // If targetFile was null (e.g. app restart), reset it
                    if (targetFile == null) targetFile = tempFile
                }
                
                isPaused = false
                isCancelled = false
                
                val downloadedLength = if (targetFile!!.exists()) targetFile!!.length() else 0L
                val requestBuilder = Request.Builder().url(url)
                
                if (downloadedLength > 0) {
                    Log.d(TAG, "Resuming download from bytes=$downloadedLength")
                    requestBuilder.header("Range", "bytes=$downloadedLength-")
                }
                
                val response = client.newCall(requestBuilder.build()).execute()
                
                if (!response.isSuccessful) {
                    // If range not satisfiable (e.g. file complete or server incompatible), error or delete and retry
                     if (response.code == 416) {
                        targetFile!!.delete()
                        // Retry recursively once
                        startDownload(url, context, versionName)
                        return@withContext
                    }
                    throw Exception("Download failed: ${response.code}")
                }
                
                val body = response.body ?: throw Exception("Response body is null")
                val totalLength = body.contentLength() + downloadedLength
                
                var inputStream: InputStream? = null
                var outputStream: FileOutputStream? = null
                
                try {
                    inputStream = body.byteStream()
                    outputStream = FileOutputStream(targetFile!!, true) // Append mode
                    
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead = downloadedLength
                    var lastUpdateTime = System.currentTimeMillis()
                    var lastBytesRead = totalBytesRead
                    
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        if (isCancelled) {
                             // User cancelled, delete temp file
                            targetFile?.delete()
                            _downloadState.value = DownloadState(status = DownloadStatus.CANCELED)
                            return@withContext
                        }
                        
                        if (isPaused) {
                            _downloadState.value = _downloadState.value.copy(status = DownloadStatus.PAUSED)
                            return@withContext
                        }
                        
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        
                        // Calculate Speed & Progress every 500ms
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastUpdateTime >= 500) {
                            val timeDiff = currentTime - lastUpdateTime
                            val bytesDiff = totalBytesRead - lastBytesRead
                            val speed = calculateSpeed(bytesDiff, timeDiff)
                            val progress = if (totalLength > 0) ((totalBytesRead * 100) / totalLength).toInt() else 0
                            
                            _downloadState.value = DownloadState(
                                status = DownloadStatus.DOWNLOADING,
                                progress = progress.coerceIn(0, 100),
                                speed = speed,
                                downloadedBytes = totalBytesRead,
                                totalBytes = totalLength
                            )
                            
                            lastUpdateTime = currentTime
                            lastBytesRead = totalBytesRead
                        }
                    }
                    
                    // Download Complete - Rename to final
                    if (targetFile!!.renameTo(finalFile)) {
                         consecutiveFailures = 0 // 下载成功，重置失败计数
                         _downloadState.value = DownloadState(
                            status = DownloadStatus.COMPLETED,
                            progress = 100,
                            speed = "0 KB/s",
                            downloadedBytes = totalBytesRead,
                            totalBytes = totalLength,
                            file = finalFile // Point to final file
                        )
                    } else {
                        throw Exception("Failed to rename temp file to apk")
                    }
                    
                } finally {
                    inputStream?.close()
                    outputStream?.close()
                    body.close()
                }
                
            } catch (e: Exception) {
                if (!isCancelled && !isPaused) {
                    Log.e(TAG, "Download error", e)
                    
                    // 判断是否为网络不可用错误
                    val isNetworkError = e is java.net.UnknownHostException ||
                            e is java.net.ConnectException ||
                            e is java.net.NoRouteToHostException ||
                            e is java.net.SocketTimeoutException ||
                            (e.message?.contains("Unable to resolve host", ignoreCase = true) == true) ||
                            (e.message?.contains("No address associated", ignoreCase = true) == true)
                    
                    // 非网络错误才计入连续失败次数
                    if (!isNetworkError) {
                        consecutiveFailures++
                    }
                    
                    _downloadState.value = _downloadState.value.copy(
                        status = DownloadStatus.ERROR,
                        error = e.message,
                        consecutiveFailures = consecutiveFailures
                    )
                }
            }
        }
    }
    
    fun pauseDownload() {
        if (_downloadState.value.status == DownloadStatus.DOWNLOADING) {
            isPaused = true
        }
    }
    
    fun cancelDownload() {
        isCancelled = true
        // Delete temp file if it exists
        targetFile?.delete() 
        consecutiveFailures = 0 // 取消时重置失败计数
        _downloadState.value = DownloadState(status = DownloadStatus.CANCELED)
        downloadUrl = "" // Reset url to force fresh start next time
    }
    
    fun resetState() {
        _downloadState.value = DownloadState()
        downloadUrl = ""
        targetFile = null
        isPaused = false
        isCancelled = false
        consecutiveFailures = 0
    }
    
    // Check if valid APK exists
    fun isApkReady(context: Context, versionName: String): File? {
        val file = File(context.externalCacheDir, "update_$versionName.apk")
        return if (file.exists() && file.length() > 0) file else null
    }

    private fun calculateSpeed(bytesDiff: Long, timeMs: Long): String {
        if (timeMs == 0L) return "0 KB/s"
        val bytesPerSec = (bytesDiff * 1000) / timeMs
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
            bytesPerSec >= 1024 -> String.format("%.1f KB/s", bytesPerSec / 1024.0)
            else -> "$bytesPerSec B/s"
        }
    }
}
