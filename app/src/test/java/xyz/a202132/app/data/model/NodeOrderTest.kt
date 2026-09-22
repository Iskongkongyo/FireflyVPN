package xyz.a202132.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class NodeOrderTest {

    @Test
    fun `reorders visible nodes while preserving hidden node slots`() {
        val existing = listOf(
            node("a", 0),
            node("hidden", 1),
            node("b", 2),
            node("c", 3)
        )

        val reordered = applyVisibleNodeOrder(existing, listOf("c", "a", "b"))

        assertEquals(listOf("c", "hidden", "a", "b"), reordered.map { it.id })
        assertEquals(listOf(0, 1, 2, 3), reordered.map { it.sortOrder })
    }

    @Test
    fun `latency display sort does not change manual order values`() {
        val nodes = listOf(
            node("slow", 0).copy(latency = 300),
            node("unavailable", 1).copy(latency = 20, isAvailable = false),
            node("fast", 2).copy(latency = 50),
            node("untested", 3).copy(latency = -1)
        )

        val sorted = sortNodesByLatencyForDisplay(nodes)

        assertEquals(listOf("fast", "slow", "unavailable", "untested"), sorted.map { it.id })
        assertEquals(listOf(2, 0, 1, 3), sorted.map { it.sortOrder })
        assertEquals(listOf(0, 1, 2, 3), nodes.map { it.sortOrder })
    }

    private fun node(id: String, sortOrder: Int) = Node(
        id = id,
        name = id,
        type = NodeType.VLESS,
        server = "example.com",
        port = 443,
        rawLink = "vless://$id",
        sortOrder = sortOrder
    )
}
