package xyz.a202132.app.viewmodel

internal object AutoTestQualificationPolicy {
    fun keepAfterLatency(
        filterUnqualified: Boolean,
        isAvailable: Boolean,
        latencyMs: Int,
        thresholdMs: Int
    ): Boolean = !filterUnqualified || (isAvailable && latencyMs > 0 && latencyMs <= thresholdMs)

    fun bandwidthPassed(
        downloadEnabled: Boolean,
        uploadEnabled: Boolean,
        downloadMbps: Float,
        uploadMbps: Float,
        downloadThresholdMbps: Int,
        uploadThresholdMbps: Int
    ): Boolean {
        val downloadPassed = !downloadEnabled ||
            (downloadMbps > 0f && downloadMbps >= downloadThresholdMbps)
        val uploadPassed = !uploadEnabled ||
            (uploadMbps > 0f && uploadMbps >= uploadThresholdMbps)
        return downloadPassed && uploadPassed
    }

    fun keepAfterRule(filterUnqualified: Boolean, passed: Boolean): Boolean =
        !filterUnqualified || passed

    fun connectivityAfterSuccessfulTest(wasAvailable: Boolean, testPassed: Boolean): Boolean =
        wasAvailable || testPassed
}
