package xyz.a202132.app.viewmodel

enum class AutoTestLatencyMode {
    URL_TEST,
    TCPING
}

enum class BestNodePriority {
    LATENCY,
    UPLOAD,
    DOWNLOAD,
    UNLOCK_COUNT
}

enum class PreferRankingMode {
    PRIORITY_ORDER,
    WEIGHTED_SCORE
}

data class BestNodeWeights(
    val latency: Int = 40,
    val upload: Int = 10,
    val download: Int = 30,
    val unlock: Int = 20
) {
    fun normalized(): BestNodeWeights = copy(
        latency = latency.coerceIn(0, 100),
        upload = upload.coerceIn(0, 100),
        download = download.coerceIn(0, 100),
        unlock = unlock.coerceIn(0, 100)
    )

    fun get(priority: BestNodePriority): Int = when (priority) {
        BestNodePriority.LATENCY -> latency
        BestNodePriority.UPLOAD -> upload
        BestNodePriority.DOWNLOAD -> download
        BestNodePriority.UNLOCK_COUNT -> unlock
    }

    fun with(priority: BestNodePriority, value: Int): BestNodeWeights = when (priority) {
        BestNodePriority.LATENCY -> copy(latency = value.coerceIn(0, 100))
        BestNodePriority.UPLOAD -> copy(upload = value.coerceIn(0, 100))
        BestNodePriority.DOWNLOAD -> copy(download = value.coerceIn(0, 100))
        BestNodePriority.UNLOCK_COUNT -> copy(unlock = value.coerceIn(0, 100))
    }
}

data class WeightedNodeScore(
    val total: Double,
    val latency: Double? = null,
    val upload: Double? = null,
    val download: Double? = null,
    val unlock: Double? = null
) {
    fun get(priority: BestNodePriority): Double? = when (priority) {
        BestNodePriority.LATENCY -> latency
        BestNodePriority.UPLOAD -> upload
        BestNodePriority.DOWNLOAD -> download
        BestNodePriority.UNLOCK_COUNT -> unlock
    }
}

enum class UnlockPriorityMode {
    COUNT,
    TARGET_SITES
}

data class AutoTestConfig(
    val enabled: Boolean = false,
    val filterUnavailable: Boolean = true,
    val latencyEnabled: Boolean = true,
    val latencyMode: AutoTestLatencyMode = AutoTestLatencyMode.URL_TEST,
    val latencyThresholdMs: Int = 600,
    val bandwidthEnabled: Boolean = false,
    val bandwidthDownloadEnabled: Boolean = true,
    val bandwidthUploadEnabled: Boolean = false,
    val bandwidthDownloadThresholdMbps: Int = 10,
    val bandwidthUploadThresholdMbps: Int = 10,
    val bandwidthWifiOnly: Boolean = true,
    val bandwidthDownloadSizeMb: Int = 10,
    val bandwidthUploadSizeMb: Int = 10,
    val unlockEnabled: Boolean = false,
    val byRegion: Boolean = false,
    val nodeLimit: Int = 20
)

data class TestPreferMode(
    val id: String,
    val name: String,
    val builtIn: Boolean = false,
    val filterUnavailable: Boolean = true,
    val latencyEnabled: Boolean = true,
    val latencyMode: AutoTestLatencyMode = AutoTestLatencyMode.URL_TEST,
    val latencyThresholdMs: Int = 600,
    val bandwidthEnabled: Boolean = false,
    val bandwidthDownloadEnabled: Boolean = true,
    val bandwidthUploadEnabled: Boolean = false,
    val bandwidthDownloadThresholdMbps: Int = 10,
    val bandwidthUploadThresholdMbps: Int = 10,
    val bandwidthWifiOnly: Boolean = true,
    val bandwidthDownloadSizeMb: Int = 10,
    val bandwidthUploadSizeMb: Int = 10,
    val unlockEnabled: Boolean = false,
    val byRegion: Boolean = false,
    val nodeLimit: Int = 20,
    val defaultPriority: BestNodePriority = BestNodePriority.LATENCY,
    val priorityOrder: List<BestNodePriority> = BestNodePriority.entries.toList(),
    val rankingMode: PreferRankingMode = PreferRankingMode.PRIORITY_ORDER,
    val priorityWeights: BestNodeWeights = BestNodeWeights(),
    val unlockPriorityMode: UnlockPriorityMode = UnlockPriorityMode.COUNT,
    val unlockPriorityTargetSiteIds: List<String> = emptyList(),
    val autoConnectBest: Boolean = false
) {
    fun toAutoTestConfig(autoRunEnabled: Boolean): AutoTestConfig = AutoTestConfig(
        enabled = autoRunEnabled,
        filterUnavailable = filterUnavailable,
        latencyEnabled = latencyEnabled,
        latencyMode = latencyMode,
        latencyThresholdMs = latencyThresholdMs,
        bandwidthEnabled = bandwidthEnabled,
        bandwidthDownloadEnabled = bandwidthDownloadEnabled,
        bandwidthUploadEnabled = bandwidthUploadEnabled,
        bandwidthDownloadThresholdMbps = bandwidthDownloadThresholdMbps,
        bandwidthUploadThresholdMbps = bandwidthUploadThresholdMbps,
        bandwidthWifiOnly = bandwidthWifiOnly,
        bandwidthDownloadSizeMb = bandwidthDownloadSizeMb,
        bandwidthUploadSizeMb = bandwidthUploadSizeMb,
        unlockEnabled = unlockEnabled,
        byRegion = byRegion,
        nodeLimit = nodeLimit
    )
}

const val BUILTIN_PREFER_MODE_CHAT = "builtin_chat"
const val BUILTIN_PREFER_MODE_DOWNLOAD = "builtin_download"

fun builtInPreferTestModes(): List<TestPreferMode> = listOf(
    TestPreferMode(
        id = BUILTIN_PREFER_MODE_CHAT,
        name = "日常模式",
        builtIn = true,
        filterUnavailable = true,
        latencyEnabled = true,
        latencyMode = AutoTestLatencyMode.URL_TEST,
        latencyThresholdMs = 3000,
        bandwidthEnabled = false,
        unlockEnabled = false,
        defaultPriority = BestNodePriority.LATENCY,
        priorityOrder = listOf(
            BestNodePriority.LATENCY,
            BestNodePriority.UPLOAD,
            BestNodePriority.DOWNLOAD,
            BestNodePriority.UNLOCK_COUNT
        ),
        nodeLimit = 50
    ),
    TestPreferMode(
        id = BUILTIN_PREFER_MODE_DOWNLOAD,
        name = "下载模式",
        builtIn = true,
        filterUnavailable = true,
        latencyEnabled = false,
        bandwidthEnabled = true,
        bandwidthDownloadEnabled = true,
        bandwidthUploadEnabled = false,
        bandwidthDownloadThresholdMbps = 10,
        bandwidthUploadThresholdMbps = 10,
        bandwidthWifiOnly = true,
        bandwidthDownloadSizeMb = 10,
        bandwidthUploadSizeMb = 10,
        unlockEnabled = false,
        defaultPriority = BestNodePriority.DOWNLOAD,
        priorityOrder = listOf(
            BestNodePriority.DOWNLOAD,
            BestNodePriority.LATENCY,
            BestNodePriority.UPLOAD,
            BestNodePriority.UNLOCK_COUNT
        ),
        nodeLimit = 30
    )
)

fun normalizePreferTestModes(raw: List<TestPreferMode>): List<TestPreferMode> {
    val builtIns = builtInPreferTestModes()
    val custom = raw.filterNot { it.builtIn || it.id == BUILTIN_PREFER_MODE_CHAT || it.id == BUILTIN_PREFER_MODE_DOWNLOAD }
    val mergedBuiltIns = builtIns.map { builtin ->
        raw.firstOrNull { it.id == builtin.id }
            ?.normalizePriorityOrder()
            ?.copy(builtIn = true, name = builtin.name)
            ?: builtin
    }
    return mergedBuiltIns + custom.map(TestPreferMode::normalizePriorityOrder)
}

fun TestPreferMode.normalizePriorityOrder(): TestPreferMode {
    val normalized = buildList {
        priorityOrder.distinct().forEach(::add)
        if (defaultPriority !in this) add(0, defaultPriority)
        BestNodePriority.entries.filterNot { it in this }.forEach(::add)
    }
    return copy(
        defaultPriority = normalized.first(),
        priorityOrder = normalized,
        priorityWeights = priorityWeights.normalized()
    )
}

fun TestPreferMode.supportsPriority(priority: BestNodePriority): Boolean = when (priority) {
    BestNodePriority.LATENCY -> latencyEnabled
    BestNodePriority.UPLOAD -> bandwidthEnabled && bandwidthUploadEnabled
    BestNodePriority.DOWNLOAD -> bandwidthEnabled && bandwidthDownloadEnabled
    BestNodePriority.UNLOCK_COUNT -> unlockEnabled
}

fun TestPreferMode.activePriorityOrder(): List<BestNodePriority> =
    normalizePriorityOrder().priorityOrder.filter(::supportsPriority)

enum class AutoTestStage {
    IDLE,
    FETCH_NODES,
    LATENCY_TEST,
    URL_TEST,
    FILTER_LATENCY,
    BANDWIDTH_TEST,
    FILTER_BANDWIDTH,
    UNLOCK_TEST,
    DONE,
    CANCELED,
    FAILED
}

data class AutoTestProgress(
    val running: Boolean = false,
    val stage: AutoTestStage = AutoTestStage.IDLE,
    val message: String = "",
    val completed: Int = 0,
    val total: Int = 0
)
