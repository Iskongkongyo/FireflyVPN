package xyz.a202132.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeType

class ReconnectNodeMatcherTest {
    @Test
    fun `matches the same protocol address and port`() {
        val original = node("old", "香港", "1.2.3.4", 443)
        val refreshed = node("new", "香港新入口", "1.2.3.4", 443)

        assertEquals(refreshed, ReconnectNodeMatcher.findMatch(original, listOf(refreshed)))
    }

    @Test
    fun `does not match when only the address is the same`() {
        val original = node("old", "香港", "1.2.3.4", 443)
        val refreshed = node("new", "香港", "1.2.3.4", 8443)

        assertNull(ReconnectNodeMatcher.findMatch(original, listOf(refreshed)))
    }

    @Test
    fun `prefers same name when one server has multiple nodes`() {
        val original = node("old", "香港 A", "example.com", 443)
        val otherName = node("other", "香港 B", "EXAMPLE.COM", 443)
        val sameName = node("same", "香港 A", "example.com", 443)

        assertEquals(
            sameName,
            ReconnectNodeMatcher.findMatch(original, listOf(otherName, sameName))
        )
    }

    @Test
    fun `does not fall back to display name across endpoints`() {
        val original = node("old", "🇭🇰 香港 A", "old.example.com", 443)
        val refreshed = node("new", "香港 A", "new.example.com", 8443)

        assertNull(ReconnectNodeMatcher.findMatch(original, listOf(refreshed)))
    }

    @Test
    fun `returns null without server or name match`() {
        val original = node("old", "香港 A", "old.example.com", 443)
        val refreshed = node("new", "日本 B", "new.example.com", 8443)

        assertNull(ReconnectNodeMatcher.findMatch(original, listOf(refreshed)))
    }

    private fun node(id: String, name: String, server: String, port: Int) = Node(
        id = id,
        name = name,
        type = NodeType.VLESS,
        server = server,
        port = port,
        rawLink = "vless://$id@$server:$port#$name"
    )
}
