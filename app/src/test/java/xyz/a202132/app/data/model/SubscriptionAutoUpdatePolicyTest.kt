package xyz.a202132.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionAutoUpdatePolicyTest {
    @Test
    fun `zero is reserved for one startup update`() {
        assertEquals(0, SubscriptionAutoUpdatePolicy.normalizeInterval(0))
        assertTrue(SubscriptionAutoUpdatePolicy.isStartupOnce(0))
        assertFalse(SubscriptionAutoUpdatePolicy.isPeriodicDue(0, 0L, Long.MAX_VALUE))
    }

    @Test
    fun `periodic intervals stay inside supported range`() {
        assertEquals(1, SubscriptionAutoUpdatePolicy.normalizeInterval(1))
        assertEquals(15, SubscriptionAutoUpdatePolicy.normalizeInterval(15))
        assertEquals(10_080, SubscriptionAutoUpdatePolicy.normalizeInterval(20_000))
    }

    @Test
    fun `periodic update becomes due only after its interval`() {
        val lastUpdatedAt = 1_000L
        assertFalse(
            SubscriptionAutoUpdatePolicy.isPeriodicDue(
                intervalMinutes = 1,
                lastUpdatedAt = lastUpdatedAt,
                now = lastUpdatedAt + 60_000L - 1
            )
        )
        assertTrue(
            SubscriptionAutoUpdatePolicy.isPeriodicDue(
                intervalMinutes = 1,
                lastUpdatedAt = lastUpdatedAt,
                now = lastUpdatedAt + 60_000L
            )
        )
    }
}
