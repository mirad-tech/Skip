package com.example.skip.ui.logs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.example.skip.data.SettingsRepository
import com.example.skip.model.ClickLog
import com.example.skip.model.ClickLogStage
import com.example.skip.model.RuleLog
import com.example.skip.ui.common.SimpleScreenScaffold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClickLogScreen(
    showRuleLogs: Boolean = false,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val versionName = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }
    var logs by remember { mutableStateOf(LogRepository.getClickLogs(context)) }
    var ruleLogs by remember { mutableStateOf(LogRepository.getRuleLogs(context)) }
    var query by remember { mutableStateOf("") }
    var stageFilter by remember { mutableStateOf<ClickLogStage?>(null) }
    var resultFilter by remember { mutableStateOf<ResultFilter?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val safetyModeEnabled = remember { SettingsRepository.isSafetyModeEnabled(context) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val filtered = filterLogs(logs, query, stageFilter, resultFilter)
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(
                    LogRepository.exportClickLogsAsJson(context, versionName, filtered)
                        .toByteArray(Charsets.UTF_8)
                )
            }
            message = "导出成功"
        }.onFailure {
            message = "导出失败，请重试"
        }
    }

    SimpleScreenScaffold(
        title = if (showRuleLogs) "规则日志" else "点击日志",
        onBack = onBack
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!showRuleLogs && safetyModeEnabled) {
                Text(
                    text = "当前为安全模式：仅记录，不会自动点击",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                if (!showRuleLogs) {
                    Button(
                        enabled = logs.isNotEmpty(),
                        onClick = {
                            exportLauncher.launch("skip_click_logs_${formatFileTime()}.json")
                        }
                    ) {
                        Text("导出日志")
                    }
                }
            }

            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (showRuleLogs) {
                if (ruleLogs.isEmpty()) {
                    EmptyLogCard("暂无规则日志")
                } else {
                    ruleLogs.forEach { RuleLogItem(it) }
                }
            } else {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索包名") },
                    singleLine = true
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = stageFilter == null,
                        onClick = { stageFilter = null },
                        label = { Text("全部阶段") }
                    )
                    listOf(
                        ClickLogStage.RuleMatched,
                        ClickLogStage.ClickFailed,
                        ClickLogStage.ClickEffectConfirmed,
                        ClickLogStage.ClickEffectUnknown,
                        ClickLogStage.ClickSkippedBySafetyMode,
                        ClickLogStage.SkippedBySafety,
                        ClickLogStage.SkippedByLowScore
                    ).forEach { stage ->
                        FilterChip(
                            selected = stageFilter == stage,
                            onClick = { stageFilter = stage },
                            label = { Text(stage.label) }
                        )
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = resultFilter == null,
                        onClick = { resultFilter = null },
                        label = { Text("全部结果") }
                    )
                    ResultFilter.entries.forEach { item ->
                        FilterChip(
                            selected = resultFilter == item,
                            onClick = { resultFilter = item },
                            label = { Text(item.label) }
                        )
                    }
                }

                val filteredLogs = filterLogs(logs, query, stageFilter, resultFilter)
                if (filteredLogs.isEmpty()) {
                    EmptyLogCard("暂无数据")
                } else {
                    filteredLogs.forEach { log ->
                        LogItem(log = log)
                    }
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(log.ruleName, style = MaterialTheme.typography.titleSmall)
            Text("${log.source.label} · ${log.targetApp}", style = MaterialTheme.typography.bodySmall)
            Text(if (log.success) "成功：${log.reason}" else "失败：${log.reason}", style = MaterialTheme.typography.bodySmall)
            Text(formatTime(log.timeMillis), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LogItem(log: ClickLog) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
                text = "${log.packageName} · ${log.ruleName.ifBlank { "-" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${log.stage.label} · ${log.clickMethod.label}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val reason = log.failureReason.ifBlank { log.reason }
            if (reason.isNotBlank()) {
                Text(
                    text = "原因：$reason",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = buildList {
                    log.score?.let { add("score $it") }
                    log.minScore?.let { add("min ${it}") }
                    if (log.boundsInScreen.isNotBlank()) add(log.boundsInScreen)
                    if (log.candidateCount != null) add("候选 ${log.candidateCount}")
                    if (log.actionReturnValue != null) add("返回 ${log.actionReturnValue}")
                    if (log.effectConfirmed != null) add("确认 ${log.effectConfirmed}")
                }.joinToString(" · ").ifBlank { "无详情" },
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

private fun filterLogs(
    logs: List<ClickLog>,
    query: String,
    stageFilter: ClickLogStage?,
    resultFilter: ResultFilter?
): List<ClickLog> {
    return logs
        .filter { query.isBlank() || it.packageName.contains(query, ignoreCase = true) }
        .filter { stageFilter == null || it.stage == stageFilter }
        .filter { log ->
            when (resultFilter) {
                null -> true
                ResultFilter.Success -> log.success == true || log.stage == ClickLogStage.ClickEffectConfirmed
                ResultFilter.Failed -> log.success == false ||
                    log.stage == ClickLogStage.ClickFailed ||
                    log.stage == ClickLogStage.ClickEffectUnknown
            }
        }
}

private fun formatTime(timeMillis: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timeMillis))
}

private fun formatFileTime(): String {
    return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
}

private enum class ResultFilter(val label: String) {
    Success("成功"),
    Failed("失败")
}
