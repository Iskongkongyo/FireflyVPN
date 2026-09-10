package xyz.a202132.app.network

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import xyz.a202132.app.AppConfig
import xyz.a202132.app.util.RuntimeLog
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 将 VPN 会话流量作为幂等记录上报。先持久化再发送，进程退出或网络失败时会在下次重试。
 */
class FireflyUsageReporter(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val client = FireflySubscriptionClient(appContext)
    private val flushing = AtomicBoolean(false)

    fun newSessionId(): String = "android-${UUID.randomUUID()}"

    fun enqueueAndFlush(sessionId: String, uploadBytes: Long, downloadBytes: Long) {
        val upload = uploadBytes.coerceIn(0L, MAX_REPORT_BYTES)
        val download = downloadBytes.coerceIn(0L, MAX_REPORT_BYTES)
        if (upload == 0L && download == 0L) return
        enqueue(PendingUsage(sessionId, upload, download))
        flushAsync()
    }

    fun flushAsync() {
        if (!flushing.compareAndSet(false, true)) return
        backgroundScope.launch {
            try {
                while (true) {
                    val pending = snapshot().firstOrNull() ?: break
                    val result = runCatching {
                        client.reportUsage(
                            sessionId = pending.sessionId,
                            uploadBytes = pending.uploadBytes,
                            downloadBytes = pending.downloadBytes,
                            userAgent = AppConfig.HTTP_USER_AGENT
                        )
                    }
                    if (result.isFailure) {
                        RuntimeLog.warn(TAG, "Usage report deferred", result.exceptionOrNull())
                        break
                    }
                    remove(pending.sessionId)
                }
            } finally {
                flushing.set(false)
            }
        }
    }

    @Synchronized
    private fun enqueue(report: PendingUsage) {
        val reports = snapshot().filterNot { it.sessionId == report.sessionId }
            .plus(report)
            .takeLast(MAX_PENDING_REPORTS)
        save(reports)
    }

    @Synchronized
    private fun remove(sessionId: String) {
        save(snapshot().filterNot { it.sessionId == sessionId })
    }

    @Synchronized
    private fun snapshot(): List<PendingUsage> = runCatching {
        val array = JSONArray(preferences.getString(PENDING_REPORTS, "[]"))
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val sessionId = item.optString("sessionId")
                val upload = item.optLong("uploadBytes", -1L)
                val download = item.optLong("downloadBytes", -1L)
                if (sessionId.matches(SESSION_ID_PATTERN) && upload >= 0L && download >= 0L) {
                    add(PendingUsage(sessionId, upload, download))
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun save(reports: List<PendingUsage>) {
        val array = JSONArray().apply {
            reports.forEach { report ->
                put(
                    JSONObject()
                        .put("sessionId", report.sessionId)
                        .put("uploadBytes", report.uploadBytes)
                        .put("downloadBytes", report.downloadBytes)
                )
            }
        }
        preferences.edit().putString(PENDING_REPORTS, array.toString()).apply()
    }

    private data class PendingUsage(
        val sessionId: String,
        val uploadBytes: Long,
        val downloadBytes: Long
    )

    private companion object {
        const val TAG = "FireflyUsage"
        const val PREFERENCES_NAME = "firefly_usage_reports"
        const val PENDING_REPORTS = "pending_reports"
        const val MAX_PENDING_REPORTS = 32
        const val MAX_REPORT_BYTES = 10L * 1024 * 1024 * 1024 * 1024
        val SESSION_ID_PATTERN = Regex("^[A-Za-z0-9._:-]{1,128}$")
        val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
