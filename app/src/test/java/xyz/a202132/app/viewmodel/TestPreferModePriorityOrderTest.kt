package xyz.a202132.app.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class TestPreferModePriorityOrderTest {

    @Test
    fun normalizePriorityOrder_removesDuplicatesAndAppendsMissingMetrics() {
        val mode = TestPreferMode(
            id = "custom",
            name = "Custom",
            defaultPriority = BestNodePriority.DOWNLOAD,
            priorityOrder = listOf(
                BestNodePriority.DOWNLOAD,
                BestNodePriority.LATENCY,
                BestNodePriority.DOWNLOAD
            )
        )

        assertEquals(
            listOf(
                BestNodePriority.DOWNLOAD,
                BestNodePriority.LATENCY,
                BestNodePriority.UPLOAD,
                BestNodePriority.UNLOCK_COUNT
            ),
            mode.normalizePriorityOrder().priorityOrder
        )
    }

    @Test
    fun activePriorityOrder_keepsConfiguredOrderAndSkipsDisabledTests() {
        val mode = TestPreferMode(
            id = "custom",
            name = "Custom",
            latencyEnabled = true,
            bandwidthEnabled = true,
            bandwidthDownloadEnabled = true,
            bandwidthUploadEnabled = false,
            unlockEnabled = true,
            defaultPriority = BestNodePriority.UNLOCK_COUNT,
            priorityOrder = listOf(
                BestNodePriority.UNLOCK_COUNT,
                BestNodePriority.UPLOAD,
                BestNodePriority.DOWNLOAD,
                BestNodePriority.LATENCY
            )
        )

        assertEquals(
            listOf(
                BestNodePriority.UNLOCK_COUNT,
                BestNodePriority.DOWNLOAD,
                BestNodePriority.LATENCY
            ),
            mode.activePriorityOrder()
        )
    }

    @Test
    fun builtInDownloadMode_startsWithDownloadPriority() {
        val downloadMode = builtInPreferTestModes().first { it.id == BUILTIN_PREFER_MODE_DOWNLOAD }

        assertEquals(BestNodePriority.DOWNLOAD, downloadMode.normalizePriorityOrder().priorityOrder.first())
        assertEquals(PreferRankingMode.PRIORITY_ORDER, downloadMode.rankingMode)
    }

    @Test
    fun weights_allowZeroAndClampValuesToSupportedRange() {
        val weights = BestNodeWeights(
            latency = -1,
            upload = 0,
            download = 101,
            unlock = 25
        ).normalized()

        assertEquals(0, weights.latency)
        assertEquals(0, weights.upload)
        assertEquals(100, weights.download)
        assertEquals(25, weights.unlock)
    }

    @Test
    fun weights_canUpdateOneMetricWithoutChangingOthers() {
        val weights = BestNodeWeights().with(BestNodePriority.UPLOAD, 55)

        assertEquals(40, weights.latency)
        assertEquals(55, weights.upload)
        assertEquals(30, weights.download)
        assertEquals(20, weights.unlock)
    }
}
