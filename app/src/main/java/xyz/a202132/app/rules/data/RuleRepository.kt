package xyz.a202132.app.rules.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import xyz.a202132.app.data.repository.SettingsRepository
import xyz.a202132.app.rules.model.ManagedRuleState
import xyz.a202132.app.rules.model.RuleRoutingOptions
import xyz.a202132.app.rules.model.RuleUpdateStage
import xyz.a202132.app.util.RuleManager
import java.io.File

class RuleRepository(context: Context) {
    private val appContext = context.applicationContext
    private val settings = SettingsRepository(appContext)

    val routingOptions: Flow<RuleRoutingOptions> = settings.ruleRoutingOptions

    suspend fun ensureFiles() = RuleManager.ensureRuleSets(appContext)

    fun states(options: RuleRoutingOptions): List<ManagedRuleState> = RuleRegistry.rules.map { rule ->
        val file = RuleManager.ruleFile(appContext, rule)?.takeIf(RuleManager::isValidRuleFile)
        ManagedRuleState(
            rule = rule,
            enabled = when (rule.id) {
                RuleRegistry.ANTI_AD_ID -> options.antiAdEnabled
                RuleRegistry.AD_RULES_ID -> options.adRulesEnabled
                RuleRegistry.BLOCK_QUIC_ID -> options.blockQuicEnabled
                else -> true
            },
            fileSize = file?.length(),
            lastModified = file?.lastModified()
        )
    }

    suspend fun setEnabled(ruleId: String, enabled: Boolean) {
        val rule = requireNotNull(RuleRegistry.find(ruleId))
        require(rule.switchable)
        when (ruleId) {
            RuleRegistry.ANTI_AD_ID -> settings.setAntiAdEnabled(enabled)
            RuleRegistry.AD_RULES_ID -> settings.setAdRulesEnabled(enabled)
            RuleRegistry.BLOCK_QUIC_ID -> settings.setBlockQuicEnabled(enabled)
            else -> error("Rule is not switchable: $ruleId")
        }
    }

    suspend fun update(
        ruleId: String,
        onStage: (RuleUpdateStage) -> Unit
    ): Result<File> = RuleManager.updateRule(appContext, ruleId, onStage)

    suspend fun restore(
        ruleId: String,
        onStage: (RuleUpdateStage) -> Unit
    ): Result<File> = RuleManager.restoreBuiltIn(appContext, ruleId, onStage)
}
