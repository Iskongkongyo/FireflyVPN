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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import xyz.a202132.app.AppConfig
import xyz.a202132.app.data.local.AppDatabase
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.repository.SettingsRepository
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
    val testedAt: Long = 0L,
    val exitCode: Int? = null,
    val durationMillis: Long? = null,
    val fullOutput: String = ""
)

class UnlockTestViewModel(application: Application) : AndroidViewModel(application) {

    private val tag = "UnlockTestViewModel"
    private val resultTag = "UnlockTestResult"
    private val projectUrl = "https://github.com/oneclickvirt/UnlockTests"
    private val nodeDao = AppDatabase.getInstance(application).nodeDao()
    private val settingsRepository = SettingsRepository(application)
    private val visibleNodeIds = MutableStateFlow<Set<String>?>(null)

    val nodes = combine(
        nodeDao.getAllNodes(),
        visibleNodeIds
    ) { list, visibleIds ->
        val filtered = if (visibleIds == null) {
            list
        } else {
            list.filter { it.id in visibleIds }
        }
        filtered.sortedBy { it.sortOrder }
    }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val currentSelectedNodeId = settingsRepository.selectedNodeId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val unlockTestConcurrency = settingsRepository.unlockTestConcurrency
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig.AUTO_TEST_UNLOCK_CONCURRENCY)

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

    fun updateVisibleNodes(visibleNodes: List<Node>) {
        val ids = visibleNodes.map { it.id }.toSet()
        visibleNodeIds.value = ids
        _selectedNodeIds.update { selected -> selected.intersect(ids) }
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

    fun selectCurrentNodeOnly(): Boolean {
        val currentId = currentSelectedNodeId.value
        if (currentId.isNullOrBlank()) {
            _error.value = "请先在主页选择节点"
            return false
        }
        val exists = nodes.value.any { it.id == currentId }
        if (!exists) {
            _error.value = "当前节点不在可测列表中"
            return false
        }
        _selectedNodeIds.value = setOf(currentId)
        return true
    }

    fun selectRandomNodes(count: Int) {
        val allNodes = nodes.value
        if (allNodes.isEmpty()) {
            _selectedNodeIds.value = emptySet()
            return
        }
        val target = count.coerceIn(0, allNodes.size)
        if (target == 0) {
            _selectedNodeIds.value = emptySet()
            return
        }
        _selectedNodeIds.value = allNodes
            .shuffled()
            .take(target)
            .map { it.id }
            .toSet()
    }

    fun startTests(): Boolean {
        if (_isRunning.value) return false

        val selectedNodes = nodes.value.filter { _selectedNodeIds.value.contains(it.id) }
        if (selectedNodes.isEmpty()) {
            _error.value = "请先选择要测试的节点"
            return false
        }
        if (GlobalTestExecution.isFetching()) {
            _error.value = GlobalTestExecution.fetchingHint()
            return false
        }
        if (!GlobalTestExecution.tryStart("主流站解锁测试")) {
            _error.value = GlobalTestExecution.busyHint()
            return false
        }

        _isRunning.value = true
        _results.value = selectedNodes.map { node ->
            UnlockNodeResult(nodeId = node.id, nodeName = node.getDisplayName())
        }
        Log.i(tag, "Start unlock tests for ${selectedNodes.size} nodes")

        runningJob = viewModelScope.launch {
            val concurrency = unlockTestConcurrency.value.coerceAtLeast(1)
            val completed = AtomicInteger(0)
            val total = selectedNodes.size
            try {
                _progressText.value = "主流站解锁测试中 (0/$total, 并发=$concurrency)"
                coroutineScope {
                    val semaphore = Semaphore(concurrency)
                    selectedNodes.map { node ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                if (!isActive) return@withPermit
                                try {
                                    executeNodeTest(node, "测试中...")
                                } finally {
                                    val done = completed.incrementAndGet()
                                    _progressText.value = "主流站解锁测试中 ($done/$total, 并发=$concurrency)"
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
                GlobalTestExecution.finish()
            }
        }
        return true
    }

    fun retryTest(nodeId: String): Boolean {
        if (_isRunning.value) {
            _error.value = "当前主流站解锁测试尚未结束，请稍后重试"
            return false
        }
        val failedResult = _results.value.firstOrNull { it.nodeId == nodeId }
        if (failedResult?.status != UnlockResultStatus.FAILED) return false

        val node = nodes.value.firstOrNull { it.id == nodeId }
        if (node == null) {
            _error.value = "该节点已不存在，无法重新测试"
            return false
        }
        if (GlobalTestExecution.isFetching()) {
            _error.value = GlobalTestExecution.fetchingHint()
            return false
        }
        if (!GlobalTestExecution.tryStart("主流站解锁重试")) {
            _error.value = GlobalTestExecution.busyHint()
            return false
        }

        _isRunning.value = true
        _progressText.value = "正在重新测试：${node.getDisplayName()}"
        runningJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                executeNodeTest(node, "重新测试中...")
            } finally {
                activeSessions.remove(node.id)?.let { session ->
                    runCatching { session.stop() }
                }
                _progressText.value = null
                _isRunning.value = false
                runningJob = null
                GlobalTestExecution.finish()
            }
        }
        return true
    }

    private suspend fun executeNodeTest(node: Node, runningSummary: String) {
        prepareResultForRun(node.id, runningSummary)
        Log.d(tag, "Testing node: ${node.getDisplayName()}")

        activeSessions.remove(node.id)?.let { staleSession ->
            runCatching { staleSession.stop() }
        }

        val port = try {
            pickFreePort()
        } catch (e: Exception) {
            Log.e(tag, "Failed to allocate proxy port for node: ${node.getDisplayName()}", e)
            updateResult(
                node.id,
                UnlockResultStatus.FAILED,
                "启动测试代理失败：无法分配本地端口",
                "",
                System.currentTimeMillis()
            )
            return
        }
        val session = UnlockTestManager.createSession(getApplication(), node, port)
        activeSessions[node.id] = session

        try {
            if (!session.start()) {
                Log.e(tag, "Failed to start proxy for node: ${node.getDisplayName()}")
                updateResult(
                    node.id,
                    UnlockResultStatus.FAILED,
                    "启动测试代理失败",
                    "",
                    System.currentTimeMillis()
                )
                return
            }

            val testStartedAt = System.currentTimeMillis()
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

            val status = if (result.exitCode == 0) {
                UnlockResultStatus.SUCCESS
            } else {
                UnlockResultStatus.FAILED
            }
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
                buildDisplayOutput(
                    node.getDisplayName(),
                    result.exitCode,
                    summary,
                    highlights,
                    now,
                    result.stdout
                ),
                now,
                exitCode = result.exitCode,
                durationMillis = now - testStartedAt,
                fullOutput = result.stdout
            )
        } finally {
            runCatching { session.stop() }
            activeSessions.remove(node.id, session)
        }
    }

    private fun prepareResultForRun(nodeId: String, summary: String) {
        _results.update { list ->
            list.map { item ->
                if (item.nodeId == nodeId) {
                    item.copy(
                        status = UnlockResultStatus.RUNNING,
                        summary = summary,
                        rawOutput = "",
                        testedAt = 0L,
                        exitCode = null,
                        durationMillis = null,
                        fullOutput = ""
                    )
                } else {
                    item
                }
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
        testedAt: Long,
        exitCode: Int? = null,
        durationMillis: Long? = null,
        fullOutput: String = ""
    ) {
        _results.update { list ->
            list.map { item ->
                if (item.nodeId == nodeId) {
                    item.copy(
                        status = status,
                        summary = summary,
                        rawOutput = if (rawOutput.isBlank()) item.rawOutput else rawOutput,
                        testedAt = if (testedAt > 0L) testedAt else item.testedAt,
                        exitCode = exitCode ?: item.exitCode,
                        durationMillis = durationMillis ?: item.durationMillis,
                        fullOutput = if (fullOutput.isBlank()) item.fullOutput else fullOutput
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
        val ipv4Output = ipv4OnlyOutput(output)
        val keywords = listOf(
            "Netflix", "YouTube", "Disney", "Prime", "HBO", "TikTok", "ChatGPT", "OpenAI",
            "Dazn", "DAZN", "Spotify", "TVB", "Abema", "Bilibili", "区域", "Region", "解锁",
            "Unlocked", "Available", "No", "Yes"
        )
        val list = ipv4Output.lineSequence()
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

    private fun ipv4OnlyOutput(output: String): String {
        val lines = output.lines()
        val ipv4Start = lines.indexOfFirst {
            cleanUtLine(it).matches(Regex("^IPv4\\s*:?$", RegexOption.IGNORE_CASE))
        }
        if (ipv4Start < 0) return output
        val ipv6Start = lines.withIndex()
            .firstOrNull { (index, line) ->
                index > ipv4Start &&
                    cleanUtLine(line).matches(Regex("^IPv6\\s*:?$", RegexOption.IGNORE_CASE))
            }
            ?.index
            ?: lines.size
        return lines.subList(ipv4Start + 1, ipv6Start).joinToString("\n")
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
