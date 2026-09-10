package xyz.a202132.app.rules.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.a202132.app.data.model.ProxyMode
import xyz.a202132.app.rules.data.RuleRegistry
import xyz.a202132.app.rules.model.RuleRoutingOptions

class RuleConfigBuilderTest {
    @Test
    fun `defaults enable only anti ad blocking`() {
        val rules = RuleConfigBuilder.blockingRules(RuleRoutingOptions())
        assertEquals(1, rules.size())
        assertEquals(
            RuleRegistry.ANTI_AD_ID,
            rules[0].asJsonObject["rule_set"].asJsonArray[0].asString
        )
        assertEquals("reject", rules[0].asJsonObject["action"].asString)
    }

    @Test
    fun `disabled rules are omitted and quic is udp 443 reject`() {
        val disabled = RuleConfigBuilder.blockingRules(
            RuleRoutingOptions(antiAdEnabled = false)
        )
        assertEquals(0, disabled.size())

        val quic = RuleConfigBuilder.blockingRules(
            RuleRoutingOptions(antiAdEnabled = false, blockQuicEnabled = true)
        )[0].asJsonObject
        assertEquals("udp", quic["network"].asString)
        assertEquals(443, quic["port"].asInt)
        assertEquals("reject", quic["action"].asString)
    }

    @Test
    fun `system rules are declared only in smart mode`() {
        val smart = RuleConfigBuilder.activeRuleSetIds(
            ProxyMode.SMART,
            RuleRoutingOptions(antiAdEnabled = false)
        )
        val global = RuleConfigBuilder.activeRuleSetIds(
            ProxyMode.GLOBAL,
            RuleRoutingOptions(antiAdEnabled = false)
        )
        assertTrue(smart.contains(RuleRegistry.GEOSITE_CN_ID))
        assertTrue(smart.contains(RuleRegistry.GEOIP_CN_ID))
        assertFalse(global.contains(RuleRegistry.GEOSITE_CN_ID))
        assertFalse(global.contains(RuleRegistry.GEOIP_CN_ID))
    }

    @Test
    fun `enabled ad rules have unique local declarations`() {
        val declarations = RuleConfigBuilder.declarations(
            ProxyMode.SMART,
            RuleRoutingOptions(antiAdEnabled = true, adRulesEnabled = true)
        )
        val tags = declarations.map { it.asJsonObject["tag"].asString }
        assertEquals(tags.distinct(), tags)
        assertTrue(tags.contains(RuleRegistry.ANTI_AD_ID))
        assertTrue(tags.contains(RuleRegistry.AD_RULES_ID))
        declarations.forEach {
            assertTrue(it.asJsonObject["path"].asString.startsWith("../rule-sets/"))
        }
    }

    @Test
    fun `every rule set reference has exactly one declaration`() {
        listOf(ProxyMode.SMART, ProxyMode.GLOBAL).forEach { mode ->
            val options = RuleRoutingOptions(
                antiAdEnabled = true,
                adRulesEnabled = true,
                blockQuicEnabled = true
            )
            val referencedTags = RuleConfigBuilder.blockingRules(options)
                .filter { it.asJsonObject.has("rule_set") }
                .flatMap { rule ->
                    rule.asJsonObject["rule_set"].asJsonArray.map { it.asString }
                } + if (mode == ProxyMode.SMART) {
                    listOf(RuleRegistry.GEOSITE_CN_ID, RuleRegistry.GEOIP_CN_ID)
                } else {
                    emptyList()
                }
            val declaredTags = RuleConfigBuilder.declarations(mode, options)
                .map { it.asJsonObject["tag"].asString }

            assertEquals(declaredTags.distinct(), declaredTags)
            assertEquals(referencedTags.toSet(), declaredTags.toSet())
        }
    }
}
