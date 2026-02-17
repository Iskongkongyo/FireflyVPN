package xyz.a202132.app.viewmodel

data class AutoTestConfig(
    val enabled: Boolean = false,
    val filterUnavailable: Boolean = true,
    val latencyThresholdMs: Int = 600,
    val bandwidthEnabled: Boolean = false,
    val bandwidthThresholdMbps: Int = 10,
    val bandwidthWifiOnly: Boolean = true,
    val bandwidthSizeMb: Int = 10,
    val unlockEnabled: Boolean = false,
    val nodeLimit: Int = 20
)

enum class AutoTestStage {
    IDLE,
    FETCH_NODES,
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
