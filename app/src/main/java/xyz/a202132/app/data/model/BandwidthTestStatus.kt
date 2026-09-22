package xyz.a202132.app.data.model

/** 单个带宽测试方向的执行结果，避免用 0 Mbps 混淆“未测试”和“测试失败”。 */
enum class BandwidthTestStatus {
    NOT_TESTED,
    SUCCESS,
    SKIPPED,
    PROXY_START_FAILED,
    TIMEOUT,
    REQUEST_FAILED
}
