package xyz.a202132.app.data.model

object SubscriptionAutoUpdatePolicy {
    const val STARTUP_ONCE_INTERVAL_MINUTES = 0
    const val DEFAULT_INTERVAL_MINUTES = STARTUP_ONCE_INTERVAL_MINUTES
    const val MIN_PERIODIC_INTERVAL_MINUTES = 1
    const val MAX_PERIODIC_INTERVAL_MINUTES = 7 * 24 * 60
    const val STARTUP_STABILITY_DELAY_MS = 10_000L
    const val PERIODIC_CHECK_INTERVAL_MS = 60_000L

    fun normalizeInterval(minutes: Int): Int = when {
        minutes <= STARTUP_ONCE_INTERVAL_MINUTES -> STARTUP_ONCE_INTERVAL_MINUTES
        minutes < MIN_PERIODIC_INTERVAL_MINUTES -> MIN_PERIODIC_INTERVAL_MINUTES
        else -> minutes.coerceAtMost(MAX_PERIODIC_INTERVAL_MINUTES)
    }

    fun isStartupOnce(intervalMinutes: Int): Boolean =
        normalizeInterval(intervalMinutes) == STARTUP_ONCE_INTERVAL_MINUTES

    fun isPeriodicDue(intervalMinutes: Int, lastUpdatedAt: Long, now: Long): Boolean {
        val normalized = normalizeInterval(intervalMinutes)
        if (normalized == STARTUP_ONCE_INTERVAL_MINUTES) return false
        return now - lastUpdatedAt >= normalized * 60_000L
    }
}
