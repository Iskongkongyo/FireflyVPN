package xyz.a202132.app.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTestQualificationPolicyTest {
    @Test
    fun `disabled filtering keeps failed measurements in results`() {
        assertTrue(AutoTestQualificationPolicy.keepAfterLatency(false, false, -2, 600))
        assertTrue(AutoTestQualificationPolicy.keepAfterRule(false, false))
    }

    @Test
    fun `enabled filtering applies latency and bandwidth thresholds`() {
        assertTrue(AutoTestQualificationPolicy.keepAfterLatency(true, true, 599, 600))
        assertFalse(AutoTestQualificationPolicy.keepAfterLatency(true, true, 601, 600))
        assertTrue(
            AutoTestQualificationPolicy.bandwidthPassed(
                downloadEnabled = true,
                uploadEnabled = true,
                downloadMbps = 50f,
                uploadMbps = 20f,
                downloadThresholdMbps = 50,
                uploadThresholdMbps = 20
            )
        )
        assertFalse(
            AutoTestQualificationPolicy.bandwidthPassed(
                downloadEnabled = true,
                uploadEnabled = true,
                downloadMbps = 49.9f,
                uploadMbps = 20f,
                downloadThresholdMbps = 50,
                uploadThresholdMbps = 20
            )
        )
    }

    @Test
    fun `rule failure never turns a reachable node permanently unavailable`() {
        assertTrue(AutoTestQualificationPolicy.connectivityAfterSuccessfulTest(true, false))
        assertTrue(AutoTestQualificationPolicy.connectivityAfterSuccessfulTest(false, true))
        assertFalse(AutoTestQualificationPolicy.connectivityAfterSuccessfulTest(false, false))
    }
}
