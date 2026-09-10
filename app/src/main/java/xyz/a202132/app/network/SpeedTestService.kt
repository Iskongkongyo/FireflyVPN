package xyz.a202132.app.network

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import xyz.a202132.app.AppConfig
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

private const val TAG = "SpeedTestService"
private const val WARM_UP_BYTES = 100_000L
private const val TRANSFER_BUFFER_BYTES = 64 * 1024
private const val PROGRESS_INTERVAL_NS = 150_000_000L

data class SpeedTestResult(
    val avgSpeedMbps: Float,
    val peakSpeedMbps: Float,
    val totalBytes: Long,
    val durationMs: Long
)

/**
 * Cloudflare 带宽测试。
 *
 * 连接建立、TLS 和服务端响应等待都不属于链路的稳定传输吞吐，因此先做一次小流量
 * 预热，并只统计响应体读取（下载）或请求体写入（上传）阶段。这样也避免高速上传在
 * 数据早已发完后仍等待服务端回包，导致结果被明显压低。
 */
class SpeedTestService(
    downloadTimeoutMs: Long = AppConfig.AUTO_TEST_BANDWIDTH_DOWNLOAD_TIMEOUT_MS,
    uploadTimeoutMs: Long = AppConfig.AUTO_TEST_BANDWIDTH_UPLOAD_TIMEOUT_MS,
    proxy: Proxy? = null,
    baseClient: OkHttpClient? = null
) {

    private val sharedClient = baseClient ?: NetworkClient.withUserAgent(OkHttpClient.Builder())
        .apply {
            if (proxy != null) proxy(proxy)
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val downloadClient = sharedClient.newBuilder()
        .apply {
            if (proxy != null) proxy(proxy)
            callTimeout(downloadTimeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val uploadClient = sharedClient.newBuilder()
        .apply {
            if (proxy != null) proxy(proxy)
            callTimeout(uploadTimeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var isCancelled = false

    private var downloadWarmedUp = false
    private var uploadWarmedUp = false

    fun cancel() {
        isCancelled = true
        downloadClient.dispatcher.cancelAll()
        uploadClient.dispatcher.cancelAll()
    }

    fun startDownloadTest(
        size: Long,
        onProgress: (currentSpeedMbps: Float, progress: Float) -> Unit
    ): SpeedTestResult {
        require(size > 0L) { "Download test size must be positive" }
        isCancelled = false
        if (!downloadWarmedUp) {
            warmUpDownload()
            downloadWarmedUp = true
        }

        val request = speedRequest(downloadUrl(size))
        val windows = mutableListOf<Float>()
        var totalBytesRead = 0L
        var transferStartedNs = 0L
        var transferEndedNs = 0L

        Log.d(TAG, "Starting download transfer: size=$size")
        try {
            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Unexpected code $response")
                val source = response.body?.source() ?: throw IOException("ResponseBody is null")
                val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
                transferStartedNs = System.nanoTime()
                var lastUpdateNs = transferStartedNs
                var lastBytesRead = 0L

                while (!isCancelled) {
                    val bytesRead = source.read(buffer)
                    if (bytesRead == -1) break
                    totalBytesRead += bytesRead
                    val nowNs = System.nanoTime()
                    if (nowNs - lastUpdateNs >= PROGRESS_INTERVAL_NS) {
                        val speed = mbps(totalBytesRead - lastBytesRead, nowNs - lastUpdateNs)
                        windows += speed
                        onProgress(speed, (totalBytesRead.toFloat() / size).coerceIn(0f, 1f))
                        lastUpdateNs = nowNs
                        lastBytesRead = totalBytesRead
                    }
                }
                transferEndedNs = System.nanoTime()
            }
        } catch (error: Exception) {
            if (!isCancelled) throw error
            if (transferEndedNs == 0L) transferEndedNs = System.nanoTime()
        }

        val durationNs = (transferEndedNs - transferStartedNs).coerceAtLeast(1L)
        val average = mbps(totalBytesRead, durationNs)
        if (!isCancelled) onProgress(average, 1f)
        return buildResult(average, windows, totalBytesRead, durationNs, "Download")
    }

    fun startUploadTest(
        size: Long,
        onProgress: (currentSpeedMbps: Float, progress: Float) -> Unit
    ): SpeedTestResult {
        require(size > 0L) { "Upload test size must be positive" }
        isCancelled = false
        if (!uploadWarmedUp) {
            warmUpUpload()
            uploadWarmedUp = true
        }

        val windows = mutableListOf<Float>()
        var finalBytesUploaded = 0L
        var transferStartedNs = 0L
        var transferEndedNs = 0L
        val requestBody = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = size

            override fun writeTo(sink: BufferedSink) {
                val buffer = ByteArray(TRANSFER_BUFFER_BYTES) { 0x5A.toByte() }
                var uploaded = 0L
                transferStartedNs = System.nanoTime()
                var lastUpdateNs = transferStartedNs
                var lastUploadedBytes = 0L

                while (uploaded < size) {
                    if (isCancelled) throw IOException("Speed test cancelled")
                    val toWrite = minOf(size - uploaded, buffer.size.toLong()).toInt()
                    sink.write(buffer, 0, toWrite)
                    uploaded += toWrite
                    finalBytesUploaded = uploaded

                    val nowNs = System.nanoTime()
                    if (nowNs - lastUpdateNs >= PROGRESS_INTERVAL_NS) {
                        val speed = mbps(uploaded - lastUploadedBytes, nowNs - lastUpdateNs)
                        windows += speed
                        onProgress(speed, (uploaded.toFloat() / size).coerceIn(0f, 1f))
                        lastUpdateNs = nowNs
                        lastUploadedBytes = uploaded
                    }
                }
                // 将 Okio 缓冲区真正交给网络层后停止计时，不把服务端响应等待算入上传速度。
                sink.flush()
                transferEndedNs = System.nanoTime()
            }
        }

        val request = speedRequest(AppConfig.SPEED_TEST_UPLOAD_URL)
            .newBuilder()
            .post(requestBody)
            .build()

        Log.d(TAG, "Starting upload transfer: size=$size")
        try {
            uploadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Unexpected code $response")
            }
        } catch (error: Exception) {
            if (!isCancelled) throw error
            if (transferEndedNs == 0L) transferEndedNs = System.nanoTime()
        }

        val durationNs = (transferEndedNs - transferStartedNs).coerceAtLeast(1L)
        val average = mbps(finalBytesUploaded, durationNs)
        if (!isCancelled) onProgress(average, 1f)
        return buildResult(average, windows, finalBytesUploaded, durationNs, "Upload")
    }

    private fun warmUpDownload() {
        if (isCancelled) return
        downloadClient.newCall(speedRequest(downloadUrl(WARM_UP_BYTES))).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download warm-up failed: $response")
            val source = response.body?.source() ?: throw IOException("Download warm-up body is null")
            val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
            while (!isCancelled && source.read(buffer) != -1) Unit
        }
    }

    private fun warmUpUpload() {
        if (isCancelled) return
        val body = fixedBody(WARM_UP_BYTES)
        val request = speedRequest(AppConfig.SPEED_TEST_UPLOAD_URL).newBuilder().post(body).build()
        uploadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Upload warm-up failed: $response")
        }
    }

    private fun fixedBody(size: Long) = object : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength() = size

        override fun writeTo(sink: BufferedSink) {
            val buffer = ByteArray(TRANSFER_BUFFER_BYTES) { 0x5A.toByte() }
            var written = 0L
            while (written < size) {
                if (isCancelled) throw IOException("Speed test cancelled")
                val count = minOf(size - written, buffer.size.toLong()).toInt()
                sink.write(buffer, 0, count)
                written += count
            }
        }
    }

    private fun downloadUrl(size: Long): String =
        "${AppConfig.SPEED_TEST_DOWNLOAD_URL}?bytes=$size&r=${System.nanoTime()}"

    private fun speedRequest(url: String): Request = Request.Builder()
        .url(url)
        .header("Cache-Control", "no-store")
        .build()

    private fun buildResult(
        average: Float,
        windows: List<Float>,
        bytes: Long,
        durationNs: Long,
        direction: String
    ): SpeedTestResult {
        val stablePeak = percentile90(windows).coerceAtLeast(average)
        val durationMs = (durationNs / 1_000_000.0).roundToLong().coerceAtLeast(1L)
        Log.d(TAG, "$direction test finished: avg=${average}Mbps, peak=${stablePeak}Mbps, duration=${durationMs}ms")
        return SpeedTestResult(average, stablePeak, bytes, durationMs)
    }

    private fun mbps(bytes: Long, durationNs: Long): Float {
        if (bytes <= 0L || durationNs <= 0L) return 0f
        return (bytes.toDouble() * 8_000.0 / durationNs.toDouble()).toFloat()
    }

    private fun percentile90(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val index = (sorted.lastIndex * 0.9).roundToLong().toInt()
        return sorted[index]
    }
}
