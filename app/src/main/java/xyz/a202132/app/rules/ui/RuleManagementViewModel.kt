package xyz.a202132.app.rules.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.a202132.app.rules.data.RuleRegistry
import xyz.a202132.app.rules.data.RuleRepository
import xyz.a202132.app.rules.model.ManagedRuleState
import xyz.a202132.app.rules.model.RuleRoutingOptions
import xyz.a202132.app.rules.model.RuleUpdateStage

enum class RuleOperation {
    UPDATE,
    RESTORE,
    TOGGLE,
    INITIALIZE
}

data class RuleOperationEvent(
    val operation: RuleOperation,
    val success: Boolean
)

class RuleManagementViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RuleRepository(application)
    private val revision = MutableStateFlow(0L)
    private val _updateStages = MutableStateFlow<Map<String, RuleUpdateStage>>(emptyMap())
    val updateStages = _updateStages.asStateFlow()
    private val _events = MutableSharedFlow<RuleOperationEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    val rules = combine(repository.routingOptions, revision) { options, _ ->
        withContext(Dispatchers.IO) { repository.states(options) }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        initialStates()
    )

    init {
        viewModelScope.launch {
            runCatching { repository.ensureFiles() }
                .onSuccess { revision.value++ }
                .onFailure { _events.emit(RuleOperationEvent(RuleOperation.INITIALIZE, false)) }
        }
    }

    fun setEnabled(ruleId: String, enabled: Boolean) {
        viewModelScope.launch {
            runCatching { repository.setEnabled(ruleId, enabled) }
                .onSuccess { _events.emit(RuleOperationEvent(RuleOperation.TOGGLE, true)) }
                .onFailure { _events.emit(RuleOperationEvent(RuleOperation.TOGGLE, false)) }
        }
    }

    fun update(ruleId: String) {
        if (_updateStages.value[ruleId] != null) return
        viewModelScope.launch {
            setStage(ruleId, RuleUpdateStage.CHECKING)
            val result = repository.update(ruleId) { stage -> setStage(ruleId, stage) }
            setStage(ruleId, null)
            if (result.isSuccess) revision.value++
            _events.emit(RuleOperationEvent(RuleOperation.UPDATE, result.isSuccess))
        }
    }

    fun restore(ruleId: String) {
        if (_updateStages.value[ruleId] != null) return
        viewModelScope.launch {
            setStage(ruleId, RuleUpdateStage.RESTORING)
            val result = repository.restore(ruleId) { stage -> setStage(ruleId, stage) }
            setStage(ruleId, null)
            if (result.isSuccess) revision.value++
            _events.emit(RuleOperationEvent(RuleOperation.RESTORE, result.isSuccess))
        }
    }

    private fun setStage(ruleId: String, stage: RuleUpdateStage?) {
        _updateStages.value = _updateStages.value.toMutableMap().apply {
            if (stage == null || stage == RuleUpdateStage.IDLE) remove(ruleId) else put(ruleId, stage)
        }
    }

    private fun initialStates(): List<ManagedRuleState> {
        val defaults = RuleRoutingOptions()
        return RuleRegistry.rules.map { rule ->
            ManagedRuleState(
                rule = rule,
                enabled = when (rule.id) {
                    RuleRegistry.ANTI_AD_ID -> defaults.antiAdEnabled
                    RuleRegistry.AD_RULES_ID -> defaults.adRulesEnabled
                    RuleRegistry.BLOCK_QUIC_ID -> defaults.blockQuicEnabled
                    else -> true
                }
            )
        }
    }
}
