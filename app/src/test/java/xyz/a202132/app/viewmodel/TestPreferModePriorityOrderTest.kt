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
    }
}
