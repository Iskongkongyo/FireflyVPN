package xyz.a202132.app.rules.ui

import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.a202132.app.R
import xyz.a202132.app.rules.data.RuleRegistry
import xyz.a202132.app.rules.model.ManagedRuleState
import xyz.a202132.app.rules.model.RuleAction
import xyz.a202132.app.rules.model.RuleCategory
import xyz.a202132.app.rules.model.RuleType
import xyz.a202132.app.rules.model.RuleUpdateStage
import xyz.a202132.app.ui.components.AppScreenScaffold
import java.text.DateFormat
import java.util.Date

@Composable
fun RuleManagementScreen(
    viewModel: RuleManagementViewModel,
    onBack: (Boolean) -> Unit,
    onRuleClick: (String) -> Unit
) {
    val context = LocalContext.current
    val rules by viewModel.rules.collectAsState()
    val stages by viewModel.updateStages.collectAsState()
    val pendingOperationCount by viewModel.pendingOperationCount.collectAsState()

    RuleEventToasts(viewModel)

    AppScreenScaffold(
        title = stringResource(R.string.rule_management_title),
        subtitle = stringResource(R.string.rule_management_subtitle),
        onBack = { onBack(viewModel.consumeChanges()) },
        backEnabled = pendingOperationCount == 0,
        onBackBlocked = {
            Toast.makeText(context, R.string.rule_operation_in_progress, Toast.LENGTH_SHORT).show()
        }
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RuleCategory.entries.forEach { category ->
                val categoryRules = rules.filter { it.rule.category == category }
                if (categoryRules.isNotEmpty()) {
                    item(key = "header-$category") {
                        Text(
                            text = categoryTitle(category),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    items(categoryRules, key = { it.rule.id }) { state ->
                        RuleCard(
                            state = state,
                            stage = stages[state.rule.id],
                            onClick = { onRuleClick(state.rule.id) },
                            onToggle = { viewModel.setEnabled(state.rule.id, it) },
                            onUpdate = { viewModel.update(state.rule.id) }
                        )
                    }
                }
            }

            if (rules.firstOrNull { it.rule.id == RuleRegistry.ANTI_AD_ID }?.enabled == true &&
                rules.firstOrNull { it.rule.id == RuleRegistry.AD_RULES_ID }?.enabled == true
            ) {
                item {
                    Text(
                        text = stringResource(R.string.rule_overlap_warning),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
fun RuleDetailScreen(
    viewModel: RuleManagementViewModel,
    ruleId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val rules by viewModel.rules.collectAsState()
    val stages by viewModel.updateStages.collectAsState()
    val pendingOperationCount by viewModel.pendingOperationCount.collectAsState()
    val state = rules.firstOrNull { it.rule.id == ruleId }
    var showRestoreConfirmation by remember { mutableStateOf(false) }

    RuleEventToasts(viewModel)

    AppScreenScaffold(
        title = state?.let { stringResource(it.rule.nameRes) }
            ?: stringResource(R.string.rule_detail_title),
        subtitle = stringResource(R.string.rule_detail_title),
        onBack = onBack,
        backEnabled = pendingOperationCount == 0,
        onBackBlocked = {
            Toast.makeText(context, R.string.rule_operation_in_progress, Toast.LENGTH_SHORT).show()
        }
    ) {
        if (state != null) {
            val stage = stages[state.rule.id]
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = stringResource(state.rule.descriptionRes),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            DetailRow(
                                stringResource(R.string.rule_detail_type),
                                ruleTypeLabel(state.rule.type)
                            )
                            DetailRow(
                                stringResource(R.string.rule_detail_action),
                                if (state.rule.action == RuleAction.REJECT) {
                                    stringResource(R.string.rule_action_reject)
                                } else {
                                    stringResource(R.string.rule_action_direct)
                                }
                            )
                            DetailRow(
                                stringResource(R.string.rule_detail_format),
                                stringResource(
                                    if (state.rule.fileName != null) R.string.rule_format_srs
                                    else R.string.rule_format_builtin
                                )
                            )
                            DetailRow(
                                stringResource(R.string.rule_detail_file_size),
                                state.fileSize?.let { Formatter.formatShortFileSize(context, it) }
                                    ?: stringResource(R.string.rule_not_applicable)
                            )
                            DetailRow(
                                stringResource(R.string.rule_detail_updated_at),
                                state.lastModified?.takeIf { it > 0L }?.let {
                                    DateFormat.getDateTimeInstance().format(Date(it))
                                } ?: stringResource(R.string.rule_not_applicable)
                            )
                            DetailRow(
                                stringResource(R.string.rule_detail_source),
                                stringResource(state.rule.sourceRes)
                            )
                            DetailRow(
                                stringResource(R.string.rule_detail_purpose),
                                stringResource(state.rule.purposeRes)
                            )
                        }
                    }
                }

                if (state.rule.switchable) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                if (state.enabled) stringResource(R.string.rule_enabled)
                                else stringResource(R.string.rule_disabled)
                            )
                            Switch(
                                checked = state.enabled,
                                onCheckedChange = { viewModel.setEnabled(state.rule.id, it) }
                            )
                        }
                    }
                }

                if (state.rule.updateable) {
                    item {
                        Button(
                            onClick = { viewModel.update(state.rule.id) },
                            enabled = stage == null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (stage != null) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.size(8.dp))
                                Text(stageLabel(stage))
                            } else {
                                Icon(Icons.Outlined.Refresh, contentDescription = null)
                                Spacer(Modifier.size(8.dp))
                                Text(stringResource(R.string.rule_update))
                            }
                        }
                    }
                }

                if (state.rule.fileName != null && state.rule.builtIn) {
                    item {
                        OutlinedButton(
                            onClick = { showRestoreConfirmation = true },
                            enabled = stage == null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.rule_restore))
                        }
                    }
                }
            }
        }
    }

    if (showRestoreConfirmation && state != null) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirmation = false },
            title = { Text(stringResource(R.string.rule_restore_confirm_title)) },
            text = { Text(stringResource(R.string.rule_restore_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreConfirmation = false
                    viewModel.restore(state.rule.id)
                }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun RuleCard(
    state: ManagedRuleState,
    stage: RuleUpdateStage?,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onUpdate: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(state.rule.nameRes),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (state.rule.switchable) {
                    Switch(
                        checked = state.enabled,
                        onCheckedChange = onToggle
                    )
                } else {
                    Switch(
                        checked = true,
                        onCheckedChange = null,
                        enabled = false
                    )
                }
            }

            Text(
                text = stringResource(state.rule.descriptionRes),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (!state.rule.switchable) {
                        stringResource(R.string.rule_system_always_enabled)
                    } else if (state.enabled) {
                        stringResource(R.string.rule_enabled)
                    } else {
                        stringResource(R.string.rule_disabled)
                    },
                    color = if (state.enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )

                if (state.rule.updateable) {
                    TextButton(
                        onClick = onUpdate,
                        enabled = stage == null
                    ) {
                        if (stage != null) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(stageLabel(stage))
                        } else {
                            Text(stringResource(R.string.rule_update))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
        Divider(modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun RuleEventToasts(viewModel: RuleManagementViewModel) {
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val message = when (event.operation) {
                RuleOperation.UPDATE -> if (event.success) {
                    R.string.rule_update_success
                } else {
                    R.string.rule_update_failed
                }
                RuleOperation.RESTORE -> if (event.success) {
                    R.string.rule_restore_success
                } else {
                    R.string.rule_restore_failed
                }
                RuleOperation.TOGGLE -> if (event.success) {
                    R.string.rule_reconnect_hint
                } else {
                    R.string.rule_toggle_failed
                }
                RuleOperation.INITIALIZE -> R.string.rule_initialize_failed
            }
            Toast.makeText(context, context.getString(message), Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun categoryTitle(category: RuleCategory): String = stringResource(
    when (category) {
        RuleCategory.SYSTEM -> R.string.rule_category_system
        RuleCategory.AD_BLOCK -> R.string.rule_category_ad_block
        RuleCategory.NETWORK -> R.string.rule_category_network
    }
)

@Composable
private fun ruleTypeLabel(type: RuleType): String = stringResource(
    when (type) {
        RuleType.DOMAIN -> R.string.rule_type_domain
        RuleType.IP -> R.string.rule_type_ip
        RuleType.RULE_SET -> R.string.rule_type_rule_set
        RuleType.NETWORK_RULE -> R.string.rule_type_network
    }
)

@Composable
private fun stageLabel(stage: RuleUpdateStage): String = stringResource(
    when (stage) {
        RuleUpdateStage.IDLE -> R.string.rule_update
        RuleUpdateStage.CHECKING -> R.string.rule_stage_checking
        RuleUpdateStage.DOWNLOADING -> R.string.rule_stage_downloading
        RuleUpdateStage.VERIFYING -> R.string.rule_stage_verifying
        RuleUpdateStage.RESTORING -> R.string.rule_stage_restoring
    }
)
