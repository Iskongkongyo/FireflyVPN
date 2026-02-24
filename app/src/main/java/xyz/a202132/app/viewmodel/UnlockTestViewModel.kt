package xyz.a202132.app.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import xyz.a202132.app.AppConfig
import xyz.a202132.app.data.local.AppDatabase
import xyz.a202132.app.network.UnlockTestManager
import xyz.a202132.app.util.UnlockTestsRunner
import java.net.ServerSocket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class UnlockResultStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELED
}

data class UnlockNodeResult(
    val nodeId: String,
    val nodeName: String,
    val status: UnlockResultStatus = UnlockResultStatus.PENDING,
    val summary: String = "等待测试",
    val rawOutput: String = "",
    val testedAt: Long = 0L
)

class UnlockTestViewModel(application: Application) : AndroidViewModel(application) {

    private val tag = "UnlockTestViewModel"
    private val resultTag = "UnlockTestResult"
    private val projectUrl = "https://github.com/oneclickvirt/UnlockTests"
    private val nodeDao = AppDatabase.getInstance(application).nodeDao()

    val nodes = nodeDao.getAllNodes()
        .map { list -> list.sortedBy { it.sortOrder } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _selectedNodeIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedNodeIds = _selectedNodeIds.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()

    private val _progressText = MutableStateFlow<String?>(null)
    val progressText = _progressText.asStateFlow()

    private val _results = MutableStateFlow<List<UnlockNodeResult>>(emptyList())
    val results = _results.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private var runningJob: Job? = null
    private val activeSessions = ConcurrentHashMap<String, UnlockTestManager.Session>()

    fun clearError() {
        _error.value = null
    }

    fun setAllSelected(selected: Boolean) {
        _selectedNodeIds.value = if (selected) {
            nodes.value.map { it.id }.toSet()
        } else {
            emptySet()
        }
    }

    fun toggleNode(nodeId: String) {
        _selectedNodeIds.update { selected ->
            if (selected.contains(nodeId)) selected - nodeId else selected + nodeId
        }
    }

    fun startTests() {
        if (_isRunning.value) return

        val selectedNodes = nodes.value.filter { _selectedNodeIds.value.contains(it.id) }
        if (selectedNodes.isEmpty()) {
            _error.value = "请先选择要测试的节点"
            return
        }

        _isRunning.value = true
        _results.value = selectedNodes.map { node ->
            UnlockNodeResult(nodeId = node.id, nodeName = node.getDisplayName())
        }
        Log.i(tag, "Start unlock tests for ${selectedNodes.size} nodes")

        runningJob = viewModelScope.launch {
            val concurrency = AppConfig.AUTO_TEST_UNLOCK_CONCURRENCY.coerceAtLeast(1)
            val completed = AtomicInteger(0)
            val total = selectedNodes.size
            try {
                _progressText.value = "流媒体测试中 (0/$total, 并发=$concurrency)"
                coroutineScope {
                    val semaphore = Semaphore(concurrency)
                    selectedNodes.map { node ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                if (!isActive) return@withPermit

                                updateResult(node.id, UnlockResultStatus.RUNNING, "测试中...", "", 0L)
                                Log.d(tag, "Testing node: ${node.getDisplayName()}")

                                val port = pickFreePort()
                                val session = UnlockTestManager.createSession(getApplication(), node, port)
                                activeSessions[node.id] = session

                                val started = session.start()
                                if (!started) {
                                    Log.e(tag, "Failed to start proxy for node: ${node.getDisplayName()}")
                                    val now = System.currentTimeMillis()
                                    updateResult(node.id, UnlockResultStatus.FAILED, "启动测试代理失败", "", now)
                                    activeSessions.remove(node.id)
                                    val done = completed.incrementAndGet()
                                    _progressText.value = "流媒体测试中 ($done/$total, 并发=$concurrency)"
                                    return@withPermit
                                }

                                try {
                                    val result = try {
                                        UnlockTestsRunner.run(
                                            context = getApplication(),
                                            args = listOf(
                                                "-socks-proxy", "socks5://127.0.0.1:$port",
                                                "-f", "0",
                                                "-L", "zh",
                                                "-b=false",
                                                "-s=false"
                                            ),
                                            timeoutSeconds = 120
                                        )
                                    } catch (e: Exception) {
                                        Log.e(tag, "Unlock runner crashed: ${e.message}", e)
                                        UnlockTestsRunner.Result(
                                            exitCode = -3,
                                            stdout = "runner exception: ${e.message}"
                                        )
                                    }

                                    val status = if (result.exitCode == 0) UnlockResultStatus.SUCCESS else UnlockResultStatus.FAILED
                                    val highlights = extractHighlights(result.stdout)
                                    val summary = summarizeOutput(result.exitCode, highlights)
                                    val now = System.currentTimeMillis()
                                    logStructuredResult(
                                        nodeName = node.getDisplayName(),
                                        exitCode = result.exitCode,
                                        status = status,
                                        summary = summary,
                                        highlights = highlights,
                                        raw = result.stdout
                                    )
                                    Log.d(tag, "Node done: ${node.getDisplayName()}, code=${result.exitCode}, summary=$summary")
                                    updateResult(
                                        node.id,
                                        status,
                                        summary,
                                        buildDisplayOutput(node.getDisplayName(), result.exitCode, summary, highlights, now, result.stdout),
                                        now
                                    )
                                } finally {
                                    session.stop()
                                    activeSessions.remove(node.id)
                                    val done = completed.incrementAndGet()
                                    _progressText.value = "流媒体测试中 ($done/$total, 并发=$concurrency)"
                                }
                            }
                        }
                    }.awaitAll()
                }
            } finally {
                activeSessions.values.forEach { session ->
                    runCatching { session.stop() }
                }
                activeSessions.clear()
                _progressText.value = null
                _isRunning.value = false
                runningJob = null
                Log.i(tag, "Unlock tests finished")
            }
        }
    }

    fun stopTests() {
        runningJob?.cancel()
        runningJob = null
        _progressText.value = "已取消"
        _isRunning.value = false

        viewModelScope.launch(Dispatchers.IO) {
            activeSessions.values.forEach { session ->
                runCatching { session.stop() }
            }
            activeSessions.clear()
        }

        _results.update { list ->
            list.map { item ->
                if (item.status == UnlockResultStatus.PENDING || item.status == UnlockResultStatus.RUNNING) {
                    item.copy(status = UnlockResultStatus.CANCELED, summary = "已取消")
                } else {
                    item
                }
            }
        }
    }

    private fun updateResult(
        nodeId: String,
        status: UnlockResultStatus,
        summary: String,
        rawOutput: String,
        testedAt: Long
    ) {
        _results.update { list ->
            list.map { item ->
                if (item.nodeId == nodeId) {
                    item.copy(
                        status = status,
                        summary = summary,
                        rawOutput = if (rawOutput.isBlank()) item.rawOutput else rawOutput,
                        testedAt = if (testedAt > 0L) testedAt else item.testedAt
                    )
                } else {
                    item
                }
            }
        }
    }

    private enum class LineStatus { YES, NO, OTHER }

    private fun summarizeOutput(exitCode: Int, highlights: List<String>): String {
        if (exitCode != 0) return "执行失败 (code=$exitCode)"
        if (highlights.isEmpty()) return "测试完成，未识别到平台结果"

        var yes = 0
        var no = 0
        var other = 0
        highlights.forEach { line ->
            when (classifyLine(line)) {
                LineStatus.YES -> yes++
                LineStatus.NO -> no++
                LineStatus.OTHER -> other++
            }
        }

        val major = buildMajorPlatformSummary(highlights)
        val base = "测试完成，共 ${highlights.size} 项：YES $yes / NO $no / 其他 $other"
        return if (major.isBlank()) base else "$base；主要平台：$major"
    }

    private fun classifyLine(line: String): LineStatus {
        val lower = line.lowercase()
        return when {
            Regex("\\b(yes|available|unlocked|full unlock)\\b", RegexOption.IGNORE_CASE).containsMatchIn(line) -> LineStatus.YES
            Regex("\\b(no|blocked|fail|unavailable|not\\s+available)\\b", RegexOption.IGNORE_CASE).containsMatchIn(line) -> LineStatus.NO
            lower.contains("仅自制") || lower.contains("originals only") || lower.contains("coming soon") -> LineStatus.OTHER
            else -> LineStatus.OTHER
        }
    }

    private fun buildMajorPlatformSummary(highlights: List<String>): String {
        val majorNames = listOf("Netflix", "YouTube", "Disney+", "ChatGPT", "Gemini", "TikTok", "Prime", "Spotify")
        val found = mutableListOf<String>()
        for (name in majorNames) {
            val line = highlights.firstOrNull { it.contains(name, ignoreCase = true) } ?: continue
            val status = when (classifyLine(line)) {
                LineStatus.YES -> "YES"
                LineStatus.NO -> "NO"
                LineStatus.OTHER -> "其他"
            }
            found.add("$name=$status")
        }
        return found.take(5).joinToString(", ")
    }

    private fun extractHighlights(output: String): List<String> {
        if (output.isBlank()) return emptyList()
        val keywords = listOf(
            "Netflix", "YouTube", "Disney", "Prime", "HBO", "TikTok", "ChatGPT", "OpenAI",
            "Dazn", "DAZN", "Spotify", "TVB", "Abema", "Bilibili", "区域", "Region", "解锁",
            "Unlocked", "Available", "No", "Yes"
        )
        val list = output.lineSequence()
            .map { cleanUtLine(it) }
            .filter { it.isNotEmpty() }
            .filter { line -> keywords.any { key -> line.contains(key, ignoreCase = true) } }
            .distinct()
            .take(40)
            .toList()
            .toMutableList()

        var i = 1
        while (i < list.size) {
            val line = list[i]
            if (line.startsWith("(Region", ignoreCase = true) || line.startsWith("(Community", ignoreCase = true)) {
                list[i - 1] = "${list[i - 1]} $line"
                list.removeAt(i)
            } else {
                i++
            }
        }
        return list
    }

    private fun buildDisplayOutput(
        nodeName: String,
        exitCode: Int,
        summary: String,
        highlights: List<String>,
        testedAt: Long,
        raw: String
    ): String {
        val project = extractProjectLine(raw) ?: projectUrl
        val testTime = "测试时间: ${formatTime(testedAt)}"
        val header = buildString {
            appendLine(project)
            appendLine(testTime)
            appendLine("节点: $nodeName")
            appendLine("退出码: $exitCode")
            appendLine("摘要: $summary")
            if (highlights.isNotEmpty()) {
                appendLine("测试结果:")
                highlights.forEach { appendLine("- $it") }
            }
        }.trim()

        return header
    }

    private fun logStructuredResult(
        nodeName: String,
        exitCode: Int,
        status: UnlockResultStatus,
        summary: String,
        highlights: List<String>,
        raw: String
    ) {
        Log.i(resultTag, "===== UT RESULT START =====")
        Log.i(resultTag, "node=$nodeName")
        Log.i(resultTag, "status=$status, exitCode=$exitCode")
        Log.i(resultTag, "summary=$summary")
        if (highlights.isNotEmpty()) {
            Log.i(resultTag, "highlights(${highlights.size}):")
            highlights.forEach { Log.i(resultTag, it) }
        } else {
            Log.i(resultTag, "highlights(0)")
        }
        Log.i(resultTag, "raw_length=${raw.length}")
        if (raw.isNotBlank()) {
            val normalized = raw.replace("\r\n", "\n").trim()
            val chunkSize = 3000
            val total = (normalized.length + chunkSize - 1) / chunkSize
            for (i in 0 until total) {
                val start = i * chunkSize
                val end = minOf(start + chunkSize, normalized.length)
                Log.i(resultTag, "raw_chunk_${i + 1}/$total:\n${normalized.substring(start, end)}")
            }
        }
        Log.i(resultTag, "===== UT RESULT END =====")
    }

    private fun extractProjectLine(raw: String): String? {
        return raw.lineSequence()
            .map { cleanUtLine(it) }
            .firstOrNull { it.contains("github.com/oneclickvirt/UnlockTests", ignoreCase = true) }
            ?.ifBlank { null }
    }

    private fun formatTime(timestamp: Long): String {
        val safeTs = if (timestamp > 0L) timestamp else System.currentTimeMillis()
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(safeTs))
    }

    private fun cleanUtLine(input: String): String {
        return input
            .replace(Regex("\\u001B\\[[;\\d]*[ -/]*[@-~]"), "")
            .replace(Regex("\\[[0-9;]*m"), "")
            .replace("[0m", "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun pickFreePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }
}
