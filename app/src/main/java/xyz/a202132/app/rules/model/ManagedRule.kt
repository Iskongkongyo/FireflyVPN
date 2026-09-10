package xyz.a202132.app.rules.model

import androidx.annotation.StringRes

enum class RuleCategory {
    SYSTEM,
    AD_BLOCK,
    NETWORK
}

enum class RuleType {
    DOMAIN,
    IP,
    RULE_SET,
    NETWORK_RULE
}

enum class RuleAction {
    DIRECT,
    REJECT
}

enum class RuleUpdateStage {
    IDLE,
    CHECKING,
    DOWNLOADING,
    VERIFYING,
    RESTORING
}

data class ManagedRule(
    val id: String,
    @StringRes val nameRes: Int,
    @StringRes val descriptionRes: Int,
    val category: RuleCategory,
    val type: RuleType,
    val fileName: String?,
    val remoteUrl: String?,
    @StringRes val sourceRes: Int,
    @StringRes val purposeRes: Int,
    val defaultEnabled: Boolean,
    val switchable: Boolean,
    val updateable: Boolean,
    val builtIn: Boolean,
    val action: RuleAction
)

data class ManagedRuleState(
    val rule: ManagedRule,
    val enabled: Boolean,
    val fileSize: Long? = null,
    val lastModified: Long? = null
)

data class RuleRoutingOptions(
    val antiAdEnabled: Boolean = true,
    val adRulesEnabled: Boolean = false,
    val blockQuicEnabled: Boolean = false
)
