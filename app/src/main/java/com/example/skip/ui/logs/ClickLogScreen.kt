package com.example.skip.ui.logs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.skip.data.DiagnosticReportRepository
import com.example.skip.data.JsonExportWriter
import com.example.skip.data.LogRepository
import com.example.skip.data.LogStorageState
import com.example.skip.data.SettingsRepository
import com.example.skip.model.ClickLog
import com.example.skip.model.ClickLogStage
import com.example.skip.model.RuleLog
import com.example.skip.ui.common.AutoLoadMoreEffect
import com.example.skip.ui.common.LazyScreenScaffold
import com.example.skip.ui.common.initialVisibleCount
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val CLICK_LOG_DISPLAY_LIMIT = 100

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClickLogScreen(
    showRuleLogs: Boolean = false,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val coroutineScope = rememberCoroutineScope()
    val storageState by LogRepository.storageState.collectAsState()
    val listState = rememberLazyListState()
    val versionName = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }
    var logs by remember { mutableStateOf<List<ClickLog>>(emptyList()) }
    var ruleLogs by remember { mutableStateOf<List<RuleLog>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var stageFilter by remember { mutableStateOf<ClickLogStage?>(null) }
    var resultFilter by remember { mutableStateOf<ResultFilter?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val safetyModeEnabled = remember { SettingsRepository.isSafetyModeEnabled(context) }

    LaunchedEffect(context) {
        loading = true
        val loaded = runCatching { withContext(Dispatchers.IO) {
            LogRepository.getClickLogs(context) to LogRepository.getRuleLogs(context)
        } }
        loaded.onSuccess { value ->
            logs = value.first
            ruleLogs = value.second
        }.onFailure {
            logs = LogRepository.getCachedClickLogs()
            message = "日志读取失败，可稍后重试"
        }
        loading = false
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val filtered = filterLogs(logs, query, stageFilter, resultFilter)
        message = "正在导出..."
        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    JsonExportWriter.writeJson(
                        openOutputStream = { appContext.contentResolver.openOutputStream(uri) },
                        json = LogRepository.exportClickLogsAsJson(appContext, versionName, filtered)
                    )
                }
            }
            message = if (result.isSuccess) "导出成功" else "导出失败，请重试"
        }
    }
    val diagnosticExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        message = "正在导出诊断包..."
        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    JsonExportWriter.writeJson(
                        openOutputStream = { appContext.contentResolver.openOutputStream(uri) },
                        json = DiagnosticReportRepository.exportDiagnosticReportAsJson(appContext, versionName)
                    )
                }
            }
            message = if (result.isSuccess) "诊断包导出成功" else "诊断包导出失败，请重试"
        }
    }

    val filteredLogs = remember(logs, query, stageFilter, resultFilter) {
        filterLogs(logs, query, stageFilter, resultFilter)
    }
    val totalCount = if (showRuleLogs) ruleLogs.size else filteredLogs.size
    var visibleCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(showRuleLogs, totalCount, query, stageFilter, resultFilter) {
        listState.scrollToItem(0)
        visibleCount = initialVisibleCount(totalCount, CLICK_LOG_DISPLAY_LIMIT)
    }
    AutoLoadMoreEffect(
        listState = listState,
        visibleCount = visibleCount,
        totalCount = totalCount,
        batchSize = CLICK_LOG_DISPLAY_LIMIT,
        onVisibleCountChange = { visibleCount = it }
    )

    LazyScreenScaffold(
        title = if (showRuleLogs) "规则日志" else "点击日志",
        onBack = onBack,
        listState = listState
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!showRuleLogs && safetyModeEnabled) {
                    Text(
                        text = "当前为安全模式：仅记录，不会自动点击",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (!showRuleLogs && storageState is LogStorageState.Degraded) {
                    Text(
                        text = "日志存储暂时不可用，当前显示进程缓存；自动跳过功能不受影响。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Button(onClick = {
                        LogRepository.retryStorageNow(appContext)
                        coroutineScope.launch {
                            loading = true
                            logs = withContext(Dispatchers.IO) { LogRepository.getClickLogs(appContext) }
                            loading = false
                        }
                    }) { Text("重试日志存储") }
                } else if (!showRuleLogs &&
                    (storageState as? LogStorageState.Ready)?.legacyDataQuarantined == true
                ) {
                    Text(
                        text = "检测到部分损坏的旧日志，已在本机隔离，其余功能可正常使用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        enabled = if (showRuleLogs) ruleLogs.isNotEmpty() else logs.isNotEmpty(),
                        onClick = {
                            if (showRuleLogs) {
                                LogRepository.clearRuleLogs(context)
                                ruleLogs = emptyList()
                            } else {
                                coroutineScope.launch {
                                    val cleared = runCatching { withContext(Dispatchers.IO) {
                                        LogRepository.clearClickLogs(appContext)
                                    } }
                                    if (cleared.isSuccess) {
                                        logs = emptyList()
                                    } else {
                                        message = "清空失败，请在日志存储恢复后重试"
                                    }
                                }
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
                        Button(
                            onClick = {
                                diagnosticExportLauncher.launch("skip_diagnostic_${formatFileTime()}.json")
                            }
                        ) {
                            Text("导出诊断包")
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
            }
        }

        if (loading) {
            item { EmptyLogCard(if (showRuleLogs) "正在加载规则日志..." else "正在加载点击日志...") }
        } else if (showRuleLogs) {
            if (ruleLogs.isEmpty()) {
                item { EmptyLogCard("暂无规则日志") }
            } else {
                items(ruleLogs.take(visibleCount)) { RuleLogItem(it) }
                if (visibleCount < ruleLogs.size) {
                    item {
                        Text(
                            text = "继续滚动加载 ${ruleLogs.size - visibleCount} 条日志",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                }
            }

            if (filteredLogs.isEmpty()) {
                item { EmptyLogCard("暂无数据") }
            } else {
                item {
                    Text(
                        text = "当前显示 ${visibleCount}/${filteredLogs.size} 条，导出保留完整记录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(filteredLogs.take(visibleCount)) { log ->
                    LogItem(log = log)
                }
                if (visibleCount < filteredLogs.size) {
                    item {
                        Text(
                            text = "继续滚动加载 ${filteredLogs.size - visibleCount} 条日志",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLogCard(text: String) {
    Card(
        shape = RoundedCornerShape(12.dp),
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
        shape = RoundedCornerShape(12.dp),
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
        shape = RoundedCornerShape(12.dp),
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
                ResultFilter.Success -> LogRepository.isSuccessfulHit(log)
                ResultFilter.Failed -> LogRepository.isFailureHit(log)
            }
        }
}

internal fun displayLogsForScreen(logs: List<ClickLog>): List<ClickLog> {
    return logs.take(CLICK_LOG_DISPLAY_LIMIT)
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
