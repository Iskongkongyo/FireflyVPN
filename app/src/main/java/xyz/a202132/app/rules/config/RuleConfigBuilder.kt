package xyz.a202132.app.rules.config

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import xyz.a202132.app.data.model.ProxyMode
import xyz.a202132.app.rules.data.RuleRegistry
import xyz.a202132.app.rules.model.RuleRoutingOptions
import xyz.a202132.app.util.RuleManager

object RuleConfigBuilder {
    fun blockingRules(options: RuleRoutingOptions): JsonArray = JsonArray().apply {
        if (options.antiAdEnabled) add(rejectRuleSet(RuleRegistry.ANTI_AD_ID))
        if (options.adRulesEnabled) add(rejectRuleSet(RuleRegistry.AD_RULES_ID))
        if (options.blockQuicEnabled) {
            add(JsonObject().apply {
                addProperty("network", "udp")
                addProperty("port", 443)
                addProperty("action", "reject")
            })
        }
    }

    fun declarations(proxyMode: ProxyMode, options: RuleRoutingOptions): JsonArray =
        JsonArray().apply {
            activeRuleSetIds(proxyMode, options).forEach { ruleId ->
                val managedRule = requireNotNull(RuleRegistry.find(ruleId))
                val fileName = requireNotNull(managedRule.fileName)
                add(JsonObject().apply {
                    addProperty("tag", managedRule.id)
                    addProperty("type", "local")
                    addProperty("format", "binary")
                    addProperty("path", RuleManager.configPath(fileName))
                })
            }
        }

    fun activeRuleSetIds(proxyMode: ProxyMode, options: RuleRoutingOptions): List<String> =
        buildList {
            if (proxyMode == ProxyMode.SMART) {
                add(RuleRegistry.GEOSITE_CN_ID)
                add(RuleRegistry.GEOIP_CN_ID)
            }
            if (options.antiAdEnabled) add(RuleRegistry.ANTI_AD_ID)
            if (options.adRulesEnabled) add(RuleRegistry.AD_RULES_ID)
        }.distinct()

    private fun rejectRuleSet(ruleId: String): JsonObject = JsonObject().apply {
        add("rule_set", JsonArray().apply { add(ruleId) })
        addProperty("action", "reject")
    }
}
