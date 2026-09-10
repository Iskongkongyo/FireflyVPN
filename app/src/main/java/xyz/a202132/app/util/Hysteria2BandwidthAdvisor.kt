package xyz.a202132.app.util

import xyz.a202132.app.AppConfig

internal object Hysteria2BandwidthAdvisor {
    fun recommend(measuredMbps: Float): Int {
        require(measuredMbps.isFinite() && measuredMbps > 0f) { "带宽测试结果无效" }
        return (measuredMbps * AppConfig.HYSTERIA2_ADAPTIVE_SAFETY_RATIO)
            .toInt()
            .coerceIn(
                AppConfig.HYSTERIA2_MIN_BANDWIDTH_MBPS,
                AppConfig.HYSTERIA2_MAX_BANDWIDTH_MBPS
            )
    }
}
