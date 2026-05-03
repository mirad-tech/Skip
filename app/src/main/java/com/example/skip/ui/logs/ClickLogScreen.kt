package com.example.skip.ui.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.skip.data.LogRepository
import com.example.skip.model.ClickLog
import com.example.skip.model.RuleLog
import com.example.skip.ui.common.SimpleScreenScaffold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ClickLogScreen(
    showRuleLogs: Boolean = false,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var logs by remember { mutableStateOf(LogRepository.getClickLogs(context)) }
    var ruleLogs by remember { mutableStateOf(LogRepository.getRuleLogs(context)) }

    SimpleScreenScaffold(
        title = if (showRuleLogs) "规则导入日志" else "点击日志",
        onBack = onBack
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (showRuleLogs) {
                        "仅记录规则来源、名称和结果。"
                    } else {
                        "仅记录时间、包名、命中规则和点击结果。"
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    enabled = if (showRuleLogs) ruleLogs.isNotEmpty() else logs.isNotEmpty(),
                    onClick = {
                        if (showRuleLogs) {
                            LogRepository.clearRuleLogs(context)
                            ruleLogs = emptyList()
                        } else {
                            LogRepository.clearClickLogs(context)
                            logs = emptyList()
                        }
                    }
                ) {
                    Text("清空")
                }
            }

            if (showRuleLogs) {
                if (ruleLogs.isEmpty()) {
                    EmptyLogCard("暂无规则日志。")
                } else {
                    ruleLogs.forEach { RuleLogItem(it) }
                }
            } else if (logs.isEmpty()) {
                EmptyLogCard("暂无点击记录。")
            } else {
                logs.forEach { log ->
                    LogItem(log = log)
                }
            }
        }
    }
}

@Composable
private fun EmptyLogCard(text: String) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RuleLogItem(log: RuleLog) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = log.ruleName,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = "${log.source.label} · ${log.targetApp}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (log.success) "成功：${log.reason}" else "失败：${log.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatTime(log.timeMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LogItem(log: ClickLog) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = log.appName.ifBlank { log.packageName },
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = "${log.packageName} · 规则：${log.ruleName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (log.success) "结果：成功" else "结果：失败，${log.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatTime(log.timeMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatTime(timeMillis: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timeMillis))
}
