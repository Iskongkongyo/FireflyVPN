package xyz.a202132.app.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeIpInfo
import xyz.a202132.app.ui.components.NodeIcon

@Composable
fun NodeIpInfoDialog(
    node: Node,
    onDismiss: () -> Unit,
    fetchIpInfo: suspend (Node) -> Result<NodeIpInfo>
) {
    var loading by remember(node.id) { mutableStateOf(true) }
    var error by remember(node.id) { mutableStateOf<String?>(null) }
    var info by remember(node.id) { mutableStateOf<NodeIpInfo?>(null) }
    var reloadToken by remember(node.id) { mutableIntStateOf(0) }

    LaunchedEffect(node.id, reloadToken) {
        loading = true
        error = null
        info = null
        val result = fetchIpInfo(node)
        result.onSuccess {
            info = it
        }.onFailure {
            error = it.message ?: "查询失败"
        }
        loading = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "节点IP信息",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NodeIcon(node = node, size = 20.dp, flagFontSize = 18.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = node.getDisplayName(),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "IP信息主要来源于 https://my.ippure.com/v1/info，数据仅供参考，本APP不对数据真实性提供保证！",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))

                when {
                    loading -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    error != null -> {
                        Text(
                            text = error.orEmpty(),
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { reloadToken++ },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("重试")
                        }
                    }

                    info != null -> {
                        val data = info!!
                        val score = (data.fraudScore ?: 0).coerceIn(0, 100)
                        val risk = riskLevel(score)

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(420.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            InfoRow("IP", data.ip.ifBlank { "-" })
                            InfoRow("ASN", data.asn?.toString() ?: "-")
                            InfoRow("组织", data.asOrganization.orEmpty().ifBlank { "-" })
                            InfoRow("国家/地区", listOfNotNull(data.country, data.countryCode).joinToString(" / ").ifBlank { "-" })
                            InfoRow("省/州", listOfNotNull(data.region, data.regionCode).joinToString(" / ").ifBlank { "-" })
                            InfoRow("城市", data.city.orEmpty().ifBlank { "-" })
                            InfoRow("邮编", data.postalCode.orEmpty().ifBlank { "-" })
                            InfoRow("时区", data.timezone.orEmpty().ifBlank { "-" })
                            InfoRow("经纬度", listOfNotNull(data.latitude, data.longitude).joinToString(", ").ifBlank { "-" })
                            InfoRow("住宅IP", yesNoText(data.isResidential))
                            InfoRow("原生IP", yesNoText(data.isBroadcast?.not()))
                            InfoRow("广播IP", yesNoText(data.isBroadcast))

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "风险评分: $score / 100 (${risk.label})",
                                fontWeight = FontWeight.SemiBold,
                                color = risk.color
                            )
                            LinearProgressIndicator(
                                progress = score / 100f,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp),
                                color = risk.color,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                            if (!data.userAgent.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "UA: ${data.userAgent}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("关闭")
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp
        )
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp
        )
    }
}

private fun yesNoText(value: Boolean?): String = when (value) {
    true -> "是"
    false -> "否"
    null -> "-"
}

private data class RiskStyle(
    val label: String,
    val color: Color
)

private fun riskLevel(score: Int): RiskStyle {
    return when (score) {
        in 0..15 -> RiskStyle("极度纯净", Color(0xFF1B5E20))
        in 16..25 -> RiskStyle("纯净", Color(0xFF2E7D32))
        in 26..40 -> RiskStyle("中性", Color(0xFF66BB6A))
        in 41..50 -> RiskStyle("轻度风险", Color(0xFFFFB74D))
        in 51..70 -> RiskStyle("中度风险", Color(0xFFF57C00))
        else -> RiskStyle("极度风险", Color(0xFFC62828))
    }
}
