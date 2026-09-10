package xyz.a202132.app.util

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.a202132.app.data.model.ProxyMode
import xyz.a202132.app.data.model.TunStackMode

class TunStackConfigTest {
    private val generator = SingBoxConfigGenerator()

    @Test
    fun `gvisor is the default tun stack`() {
        assertEquals("gvisor", generatedStack())
    }

    @Test
    fun `all selectable tun stacks reach sing-box config`() {
        TunStackMode.entries.forEach { mode ->
            assertEquals(mode.configValue, generatedStack(mode))
        }
    }

    private fun generatedStack(mode: TunStackMode? = null): String {
        val config = if (mode == null) {
            generator.generateConfig(emptyList(), null, ProxyMode.GLOBAL)
        } else {
            generator.generateConfig(
                nodes = emptyList(),
                selectedNodeId = null,
                proxyMode = ProxyMode.GLOBAL,
                tunStackMode = mode
            )
        }
        return JsonParser.parseString(config)
            .asJsonObject["inbounds"]
            .asJsonArray[0]
            .asJsonObject["stack"]
            .asString
    }
}
