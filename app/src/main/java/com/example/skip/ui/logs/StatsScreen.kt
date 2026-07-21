package com.example.skip.ui.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.skip.data.StatsRepository
import com.example.skip.data.LogRepository
import com.example.skip.data.LogStorageState
import com.example.skip.model.AppHitStats
import com.example.skip.model.HitStats
import com.example.skip.model.RuleHitStats
import com.example.skip.model.StatsWindow
import com.example.skip.ui.common.AutoLoadMoreEffect
import com.example.skip.ui.common.LazyScreenScaffold
import com.example.skip.ui.common.initialVisibleCount
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val STATS_BATCH_SIZE = 30

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val storageState by LogRepository.storageState.collectAsState()
    val listState = rememberLazyListState()
    var window by remember { mutableStateOf(StatsWindow.All) }
    var loading by remember { mutableStateOf(true) }
    var stats by remember { mutableStateOf(emptyStats()) }

    LaunchedEffect(context, window) {
        loading = true
        runCatching { withContext(Dispatchers.IO) {
            StatsRepository.loadStats(context, window)
        } }.onSuccess { stats = it }
        loading = false
    }

    val detailEntries = remember(stats) {
        buildList {
            add(StatsListEntry.Header("按应用"))
            if (stats.appStats.isEmpty()) {
                add(StatsListEntry.Empty("暂无数据", "点击日志为空时不会产生统计。"))
            } else {
                stats.appStats.forEach { add(StatsListEntry.AppEntry(it)) }
            }
            add(StatsListEntry.Header("按规则"))
            if (stats.ruleStats.isEmpty()) {
                add(StatsListEntry.Empty("暂无数据", "规则相关事件会在这里汇总。"))
            } else {
                stats.ruleStats.forEach { add(StatsListEntry.RuleEntry(it)) }
            }
        }
    }
    var visibleCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(detailEntries.size, window) {
        listState.scrollToItem(0)
        visibleCount = initialVisibleCount(detailEntries.size, STATS_BATCH_SIZE)
    }
    AutoLoadMoreEffect(
        listState = listState,
        visibleCount = visibleCount,
        totalCount = detailEntries.size,
        batchSize = STATS_BATCH_SIZE,
        onVisibleCountChange = { visibleCount = it }
    )

    LazyScreenScaffold(
        title = "事件统计",
        onBack = onBack,
        listState = listState
    ) {
        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatsWindow.entries.forEach { item ->
                    FilterChip(
                        selected = window == item,
                        onClick = { window = item },
                        label = { Text(item.label) }
                    )
                }
            }
        }

        item {
            if (storageState is LogStorageState.Degraded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "日志存储暂时不可用，统计仅包含当前可读取的数据。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(onClick = { LogRepository.retryStorageNow(context.applicationContext) }) {
                        Text("重试日志存储")
                    }
                }
            }
        }

        item {
            StatSummaryCard(
                title = if (loading) "正在计算统计..." else "总览",
                body = if (loading) {
                    "正在读取本地日志。"
                } else {
                    "应用 ${stats.appStats.size} · 规则 ${stats.ruleStats.size} · 事件 ${stats.stageStats.values.sum()}\n安全阻止 ${stats.safetyBlockedCount} · 坐标兜底 ${stats.coordinateFallbackCount}"
                }
            )
        }

        if (!loading) {
            items(
                items = detailEntries.take(visibleCount),
                key = { it.key }
            ) { entry ->
                when (entry) {
                    is StatsListEntry.Header -> Text(
                        entry.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    is StatsListEntry.Empty -> StatSummaryCard(entry.title, entry.body)
                    is StatsListEntry.AppEntry -> AppStatCard(entry.stat)
                    is StatsListEntry.RuleEntry -> RuleStatCard(entry.stat)
                }
            }
            if (visibleCount < detailEntries.size) {
                item {
                    Text(
                        text = "继续滚动加载 ${detailEntries.size - visibleCount} 条统计",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private sealed interface StatsListEntry {
    val key: String

    data class Header(val title: String) : StatsListEntry {
        override val key: String = "header:$title"
    }

    data class Empty(val title: String, val body: String) : StatsListEntry {
        override val key: String = "empty:$title:$body"
    }

    data class AppEntry(val stat: AppHitStats) : StatsListEntry {
        override val key: String = "app:${stat.packageName}"
    }

    data class RuleEntry(val stat: RuleHitStats) : StatsListEntry {
        override val key: String = "rule:${stat.ruleId}:${stat.packageName}"
    }
}

@Composable
private fun AppStatCard(stat: AppHitStats) {
    StatSummaryCard(
        title = stat.appName.ifBlank { stat.packageName },
        body = "${stat.packageName}\n事件 ${stat.totalCount} · 成功 ${stat.successCount} · 失败 ${stat.failureCount}\n安全阻止 ${stat.safetyBlockedCount} · 坐标兜底 ${stat.coordinateFallbackCount}\n最近 ${formatTime(stat.lastHitTimeMillis)}"
    )
}

@Composable
private fun RuleStatCard(stat: RuleHitStats) {
    StatSummaryCard(
        title = stat.ruleName,
        body = "${stat.packageName}\n事件 ${stat.totalCount} · 成功 ${stat.successCount} · 失败 ${stat.failureCount}\n安全阻止 ${stat.safetyBlockedCount} · 坐标兜底 ${stat.coordinateFallbackCount}\n最近 ${formatTime(stat.lastHitTimeMillis)}"
    )
}

@Composable
private fun StatSummaryCard(title: String, body: String) {
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
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun emptyStats(): HitStats {
    return HitStats(
        appStats = emptyList(),
        ruleStats = emptyList(),
        stageStats = emptyMap()
    )
}

private fun formatTime(timeMillis: Long): String {
    if (timeMillis <= 0L) return "-"
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timeMillis))
}
