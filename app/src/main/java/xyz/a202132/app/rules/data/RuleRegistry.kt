package xyz.a202132.app.rules.data

import xyz.a202132.app.R
import xyz.a202132.app.rules.model.ManagedRule
import xyz.a202132.app.rules.model.RuleAction
import xyz.a202132.app.rules.model.RuleCategory
import xyz.a202132.app.rules.model.RuleType

object RuleRegistry {
    const val GEOSITE_CN_ID = "geosite-cn"
    const val GEOIP_CN_ID = "geoip-cn"
    const val ANTI_AD_ID = "anti-ad"
    const val AD_RULES_ID = "adrules"
    const val BLOCK_QUIC_ID = "block-quic"

    val rules: List<ManagedRule> = listOf(
        ManagedRule(
            id = GEOSITE_CN_ID,
            nameRes = R.string.rule_geosite_cn_name,
            descriptionRes = R.string.rule_geosite_cn_summary,
            category = RuleCategory.SYSTEM,
            type = RuleType.DOMAIN,
            fileName = "geosite-cn.srs",
            remoteUrl = "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-cn.srs",
            sourceRes = R.string.rule_geosite_cn_source,
            purposeRes = R.string.rule_geosite_cn_purpose,
            defaultEnabled = true,
            switchable = false,
            updateable = true,
            builtIn = true,
            action = RuleAction.DIRECT
        ),
        ManagedRule(
            id = GEOIP_CN_ID,
            nameRes = R.string.rule_geoip_cn_name,
            descriptionRes = R.string.rule_geoip_cn_summary,
            category = RuleCategory.SYSTEM,
            type = RuleType.IP,
            fileName = "geoip-cn.srs",
            remoteUrl = "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/geoip-cn.srs",
            sourceRes = R.string.rule_geoip_cn_source,
            purposeRes = R.string.rule_geoip_cn_purpose,
            defaultEnabled = true,
            switchable = false,
            updateable = true,
            builtIn = true,
            action = RuleAction.DIRECT
        ),
        ManagedRule(
            id = ANTI_AD_ID,
            nameRes = R.string.rule_anti_ad_name,
            descriptionRes = R.string.rule_anti_ad_summary,
            category = RuleCategory.AD_BLOCK,
            type = RuleType.RULE_SET,
            fileName = "anti-ad.srs",
            remoteUrl = "https://anti-ad.net/anti-ad-sing-box.srs",
            sourceRes = R.string.rule_anti_ad_source,
            purposeRes = R.string.rule_anti_ad_purpose,
            defaultEnabled = true,
            switchable = true,
            updateable = true,
            builtIn = true,
            action = RuleAction.REJECT
        ),
        ManagedRule(
            id = AD_RULES_ID,
            nameRes = R.string.rule_adrules_name,
            descriptionRes = R.string.rule_adrules_summary,
            category = RuleCategory.AD_BLOCK,
            type = RuleType.RULE_SET,
            fileName = "adrules.srs",
            remoteUrl = "https://gitlab.com/cats-team/adrules/-/raw/main/adrules-singbox.srs",
            sourceRes = R.string.rule_adrules_source,
            purposeRes = R.string.rule_adrules_purpose,
            defaultEnabled = false,
            switchable = true,
            updateable = true,
            builtIn = true,
            action = RuleAction.REJECT
        ),
        ManagedRule(
            id = BLOCK_QUIC_ID,
            nameRes = R.string.rule_block_quic_name,
            descriptionRes = R.string.rule_block_quic_summary,
            category = RuleCategory.NETWORK,
            type = RuleType.NETWORK_RULE,
            fileName = null,
            remoteUrl = null,
            sourceRes = R.string.rule_block_quic_source,
            purposeRes = R.string.rule_block_quic_purpose,
            defaultEnabled = false,
            switchable = true,
            updateable = false,
            builtIn = true,
            action = RuleAction.REJECT
        )
    )

    val fileRules: List<ManagedRule> = rules.filter { it.fileName != null }

    fun find(id: String): ManagedRule? = rules.firstOrNull { it.id == id }
}
