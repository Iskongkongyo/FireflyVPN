package xyz.a202132.app.ui.dialogs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.a202132.app.viewmodel.UnlockNodeResult
import xyz.a202132.app.viewmodel.UnlockResultStatus

class UnlockResultUiMapperTest {

    @Test
    fun `maps status counts regions categories and expandable detail`() {
        val result = UnlockNodeResult(
            nodeId = "node-1",
            nodeName = "法国 01",
            status = UnlockResultStatus.SUCCESS,
            rawOutput = "退出码: 0",
            testedAt = 1_000L,
            fullOutput = """
                Apple YES Region: FRA
                Netflix YES (Region: FR)
                YouTube NO
                Disney+ YES Region: FR
                ChatGPT Available - Region: FR
                Gemini NO
                Wikipedia Editability Unknown:
                get zh.wikipedia.org failed with code: 403
                Google Global CacheCDN - ISP Cooperation Main Service Unavailable
            """.trimIndent()
        )

        val model = UnlockResultUiMapper.map(result)

        assertEquals(8, model.items.size)
        assertEquals(4, model.availableCount)
        assertEquals(3, model.unavailableCount)
        assertEquals(1, model.otherCount)
        assertEquals("FR", model.mainRegion)
        assertEquals(0, model.exitCode)

        val wikipedia = model.items.first { it.name == "Wikipedia Editability" }
        assertEquals(UnlockServiceStatus.UNKNOWN, wikipedia.status)
        assertTrue(wikipedia.detail.orEmpty().contains("HTTP", ignoreCase = true) ||
            wikipedia.detail.orEmpty().contains("403"))
        assertEquals(UnlockServiceCategory.OTHER, wikipedia.category)

        val chatGpt = model.items.first { it.name == "ChatGPT" }
        assertEquals(UnlockServiceCategory.AI, chatGpt.category)
        assertEquals("FR", chatGpt.region)
    }

    @Test
    fun `major platforms stay visible and not available is unavailable`() {
        val result = UnlockNodeResult(
            nodeId = "node-2",
            nodeName = "测试节点",
            status = UnlockResultStatus.SUCCESS,
            fullOutput = "Netflix Not Available\nChatGPT YES Region: US"
        )

        val model = UnlockResultUiMapper.map(result)

        assertEquals(6, model.majorItems.size)
        assertEquals(
            UnlockServiceStatus.UNAVAILABLE,
            model.majorItems.first { it.name == "Netflix" }.status
        )
        assertEquals(
            UnlockServiceStatus.UNKNOWN,
            model.majorItems.first { it.name == "Disney+" }.status
        )
        assertEquals(
            UnlockServiceStatus.UNKNOWN,
            model.majorItems.first { it.name == "Claude" }.status
        )
    }

    @Test
    fun `copy text preserves formatted and complete original output`() {
        val result = UnlockNodeResult(
            nodeId = "node-3",
            nodeName = "节点",
            rawOutput = "\u001B[32m摘要内容\u001B[0m [01m",
            fullOutput = "[31m不可用[0m [35m完整原始内容"
        )

        val text = buildCompleteResultText(result)

        assertTrue(text.contains("摘要内容"))
        assertTrue(text.contains("完整原始内容"))
        assertFalse(text.contains('\u001B'))
        assertFalse(Regex("\\[[0-9;]{1,20}m").containsMatchIn(text))
    }

    @Test
    fun `project url excludes ansi suffix`() {
        val result = UnlockNodeResult(
            nodeId = "node-4",
            nodeName = "节点",
            fullOutput = "[35mhttps://github.com/oneclickvirt/UnlockTests[0m\nNetflix YES"
        )

        val model = UnlockResultUiMapper.map(result)

        assertEquals("https://github.com/oneclickvirt/UnlockTests", model.projectUrl)
    }

    @Test
    fun `parses ipv4 section search services regions and details from real output shape`() {
        val result = UnlockNodeResult(
            nodeId = "node-5",
            nodeName = "新加坡节点",
            status = UnlockResultStatus.SUCCESS,
            fullOutput = """
                项目地址: https://github.com/oneclickvirt/UnlockTests
                IPV4:
                ============[ 跨国平台 ]============
                Apple                     YES (Region: SGP)
                BingSearch                YES (Region: WW)
                Claude                    YES (Region: SG)
                Dazn                      YES (Region: SG)
                Disney+                   NO (forbidden-location)
                Gemini                    YES (Region: SG)
                GoogleSearch              YES
                Google Play Store         YES (Region: SG)
                IQiYi                     YES (Region: SG)
                Instagram Licensed Audio  YES
                KOCOWA                    NO
                MetaAI                    YES
                Netflix                   NO
                Netflix CDN               NO (Main Service Unavailable) (Region: JP)
                OneTrust                  YES (Region: SG)
                ChatGPT                   YES (Region: SG)
                Paramount+                YES
                Amazon Prime Video        YES (Region: SG)
                Reddit                    NO
                SonyLiv                   Error
                Sora                      YES (Region: SG)
                Spotify Registration      NO
                Steam Store               YES (Community Available) (Region: SG)
                TVBAnywhere+              YES (Region: SG)
                TikTok                    YES (Region: ALISG)
                Viu.com                   YES
                Wikipedia Editability     Unknown: get zh.wikipedia.org failed with code: 403
                YouTube Region            YES (Region: SG)
                YouTube CDN               SIN
                IPV6:
                BingSearch                NO (Region: US)
                Netflix CDN               NO (Main Service Unavailable) (Region: SG)
                YouTube Region            NO (Region: US)
            """.trimIndent()
        )

        val model = UnlockResultUiMapper.map(result)

        assertEquals(28, model.items.size)
        assertEquals(20, model.availableCount)
        assertEquals(6, model.unavailableCount)
        assertEquals(2, model.otherCount)
        assertEquals(
            UnlockServiceCategory.SEARCH,
            model.items.first { it.name == "BingSearch" }.category
        )
        assertEquals(
            UnlockServiceCategory.SEARCH,
            model.items.first { it.name == "GoogleSearch" }.category
        )

        val youtube = model.items.first { it.name == "YouTube Region" }
        assertEquals(UnlockServiceStatus.AVAILABLE, youtube.status)
        assertEquals("SG", youtube.region)
        assertEquals("CDN：SIN", youtube.detail)

        val netflixCdn = model.items.first { it.name == "Netflix CDN" }
        assertEquals("JP", netflixCdn.region)
        assertEquals("Main Service Unavailable", netflixCdn.detail)

        assertEquals("ALISG", model.items.first { it.name == "TikTok" }.region)
        assertEquals(
            UnlockServiceCategory.STREAMING,
            model.items.first { it.name == "SonyLiv" }.category
        )
        assertFalse(model.items.any { it.detail.orEmpty().contains("跨国平台") })
        assertFalse(model.items.any { it.region == "US" })

        val ipv6Model = UnlockResultUiMapper.map(result, UnlockIpVersion.IPV6)
        assertEquals(3, ipv6Model.items.size)
        assertEquals("US", ipv6Model.items.first { it.name == "BingSearch" }.region)
        assertEquals("SG", ipv6Model.items.first { it.name == "Netflix CDN" }.region)
        assertEquals(
            UnlockServiceStatus.UNAVAILABLE,
            ipv6Model.items.first { it.name == "YouTube Region" }.status
        )
    }

    @Test
    fun `empty ipv6 section never falls back to ipv4 summary`() {
        val result = UnlockNodeResult(
            nodeId = "node-6",
            nodeName = "仅 IPv4 节点",
            rawOutput = "Netflix YES (Region: SG)",
            fullOutput = "IPV4:\nNetflix YES (Region: SG)\nIPV6:\n"
        )

        val model = UnlockResultUiMapper.map(result, UnlockIpVersion.IPV6)

        assertTrue(model.items.isEmpty())
    }

    @Test
    fun `region flags support alpha2 alpha3 and taiwan uses china flag`() {
        assertEquals("🇯🇵", regionFlagEmojiOrNull("JP"))
        assertEquals("🇯🇵", regionFlagEmojiOrNull("JPN"))
        assertEquals("🇨🇳", regionFlagEmojiOrNull("TW"))
        assertEquals("🇨🇳", regionFlagEmojiOrNull("TWN"))
    }

    @Test
    fun `unknown or global regions have no flag`() {
        assertEquals(null, regionFlagEmojiOrNull(null))
        assertEquals(null, regionFlagEmojiOrNull(""))
        assertEquals(null, regionFlagEmojiOrNull("WW"))
        assertEquals(null, regionFlagEmojiOrNull("ALISG"))
    }
}
