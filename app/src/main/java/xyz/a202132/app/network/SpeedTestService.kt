package xyz.a202132.app.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import xyz.a202132.app.AppConfig
import android.util.Log
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val TAG = "SpeedTestService"

data class SpeedTestResult(
    val avgSpeedMbps: Float,
    val peakSpeedMbps: Float,
    val totalBytes: Long,
    val durationMs: Long
)

class SpeedTestService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var isCancelled = false

    fun cancel() {
        isCancelled = true
    }

    fun startDownloadTest(
        size: Long,
        onProgress: (currentSpeedMbps: Float, progress: Float) -> Unit
    ): SpeedTestResult {
        isCancelled = false
        // Use a random query param to avoid caching
        val url = "${AppConfig.SPEED_TEST_DOWNLOAD_URL}?bytes=$size&r=${System.currentTimeMillis()}"
        val request = Request.Builder().url(url).build()

        Log.d(TAG, "Starting download test: url=$url, size=$size")
        val startTime = System.currentTimeMillis()
        var peakSpeed = 0f
        var totalBytesRead = 0L

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Unexpected code $response")

                val body = response.body ?: throw IOException("ResponseBody is null")
                val source = body.source()
                val buffer = ByteArray(8192)
                
                var lastBytesRead = 0L
                var lastUpdate = startTime
                val updateInterval = 100 // ms
                var bytesRead: Int = 0

                while (!isCancelled && source.read(buffer).also { bytesRead = it } != -1) {
                    totalBytesRead += bytesRead

                    val now = System.currentTimeMillis()
                    val timeDelta = now - lastUpdate
                    if (timeDelta >= updateInterval) {
                        if (timeDelta > 0) {
                            val bytesDelta = totalBytesRead - lastBytesRead
                            // Calculate instantaneous speed
                            val currentSpeedMbps = (bytesDelta * 8f / 1_000_000f) / (timeDelta / 1000f)
                            if (currentSpeedMbps > peakSpeed) peakSpeed = currentSpeedMbps
                            
                            val progress = totalBytesRead.toFloat() / size.toFloat()
                            onProgress(currentSpeedMbps, progress.coerceIn(0f, 1f))
                        }
                        lastUpdate = now
                        lastBytesRead = totalBytesRead
                    }
                }
            }
        } catch (e: Exception) {
            // If cancelled, we might still want to return partial result or rethrow?
            // For now, if cancelled, we just stop. If error, rethrow.
            if (!isCancelled) throw e
        }

        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime
        val avgSpeed = if (duration > 0) (totalBytesRead * 8f / 1_000_000f) / (duration / 1000f) else 0f
        
        Log.d(TAG, "Download test finished: avg=${avgSpeed}Mbps, peak=${peakSpeed}Mbps, duration=${duration}ms")
        return SpeedTestResult(avgSpeed, peakSpeed.coerceAtLeast(avgSpeed), totalBytesRead, duration)
    }

    fun startUploadTest(
        size: Long,
        onProgress: (currentSpeedMbps: Float, progress: Float) -> Unit
    ): SpeedTestResult {
        isCancelled = false
        val startTime = System.currentTimeMillis()
        var peakSpeed = 0f
        var finalBytesUploaded = 0L
        
        val requestBody = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()

            override fun contentLength() = size

            override fun writeTo(sink: BufferedSink) {
                // Generate chunk of data to write
                val buffer = ByteArray(8192) { 1 } // Dummy data
                var uploaded = 0L
                var lastUpdate = System.currentTimeMillis()
                val updateInterval = 100 // ms

                var lastUploadedBytes = 0L

                while (!isCancelled && uploaded < size) {
                    val remaining = size - uploaded
                    val toWrite = if (remaining < buffer.size) remaining.toInt() else buffer.size
                    sink.write(buffer, 0, toWrite)
                    uploaded += toWrite

                    val now = System.currentTimeMillis()
                    val timeDelta = now - lastUpdate
                    if (timeDelta >= updateInterval) {
                        if (timeDelta > 0) {
                            val bytesDelta = uploaded - lastUploadedBytes
                            val currentSpeedMbps = (bytesDelta * 8f / 1_000_000f) / (timeDelta / 1000f)
                            if (currentSpeedMbps > peakSpeed) peakSpeed = currentSpeedMbps

                            val progress = uploaded.toFloat() / size.toFloat()
                            onProgress(currentSpeedMbps, progress.coerceIn(0f, 1f))
                        }
                        lastUpdate = now
                        lastUploadedBytes = uploaded
                    }
                }
                finalBytesUploaded = uploaded
            }
        }

        val request = Request.Builder()
            .url(AppConfig.SPEED_TEST_UPLOAD_URL)
            .post(requestBody)
            .build()
            
        Log.d(TAG, "Starting upload test: url=${AppConfig.SPEED_TEST_UPLOAD_URL}, size=$size")
            
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Unexpected code $response")
            }
        } catch (e: Exception) {
             if (!isCancelled && e.message != "Socket closed") throw e
        }

        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime
        val realBytes = if (finalBytesUploaded > 0) finalBytesUploaded else size // Fallback if writeTo completed
        val avgSpeed = if (duration > 0) (realBytes * 8f / 1_000_000f) / (duration / 1000f) else 0f

        Log.d(TAG, "Upload test finished: avg=${avgSpeed}Mbps, peak=${peakSpeed}Mbps, duration=${duration}ms")
        return SpeedTestResult(avgSpeed, peakSpeed.coerceAtLeast(avgSpeed), realBytes, duration)
    }
}
