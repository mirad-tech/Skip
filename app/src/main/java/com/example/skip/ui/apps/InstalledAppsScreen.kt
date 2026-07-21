package com.example.skip.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.skip.data.InstalledAppRepository
import com.example.skip.data.InstalledAppStatus
import com.example.skip.data.LogRepository
import com.example.skip.data.SettingsRepository
import com.example.skip.ui.common.AppIconView
import com.example.skip.ui.common.AutoLoadMoreEffect
import com.example.skip.ui.common.LazyScreenScaffold
import com.example.skip.ui.common.initialVisibleCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val APP_LIST_BATCH_SIZE = 30

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InstalledAppsScreen(
    onBack: () -> Unit,
    onOpenApp: (String) -> Unit
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var refreshKey by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(AppFilter.All) }
    var loading by remember { mutableStateOf(true) }
    var apps by remember { mutableStateOf<List<InstalledAppStatus>>(emptyList()) }

    LaunchedEffect(context, refreshKey) {
        loading = true
        apps = withContext(Dispatchers.IO) {
            LogRepository.getClickLogs(context)
            InstalledAppRepository.loadApps(context)
        }
        loading = false
    }

    val filteredApps = remember(apps, query, filter) {
        apps
            .filter { status ->
                query.isBlank() ||
                    status.app.label.contains(query, ignoreCase = true) ||
                    status.app.packageName.contains(query, ignoreCase = true)
            }
            .filter { status ->
                when (filter) {
                    AppFilter.All -> true
                    AppFilter.DefaultEnabled -> status.appAssistanceEnabled
                    AppFilter.Protected -> status.isProtected
                    AppFilter.Blacklisted -> status.isBlacklisted
                }
            }
    }
    var visibleCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(filteredApps.size, query, filter) {
        listState.scrollToItem(0)
        visibleCount = initialVisibleCount(filteredApps.size, APP_LIST_BATCH_SIZE)
    }
    AutoLoadMoreEffect(
        listState = listState,
        visibleCount = visibleCount,
        totalCount = filteredApps.size,
        batchSize = APP_LIST_BATCH_SIZE,
        onVisibleCountChange = { visibleCount = it }
    )

    LazyScreenScaffold(
        title = "已安装应用",
        onBack = onBack,
        listState = listState
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索应用") },
                    singleLine = true
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppFilter.entries.forEach { item ->
                        FilterChip(
                            selected = filter == item,
                            onClick = { filter = item },
                            label = { Text(item.label) }
                        )
                    }
                }
            }
        }

        when {
            loading -> item { InfoCard("正在加载应用...") }
            apps.isEmpty() -> item { InfoCard("暂无数据") }
            filteredApps.isEmpty() -> item { InfoCard("没有匹配项") }
            else -> {
                items(
                    items = filteredApps.take(visibleCount),
                    key = { it.app.packageName }
                ) { status ->
                    InstalledAppRow(
                        status = status,
                        onOpen = { onOpenApp(status.app.packageName) },
                        onAssistanceEnabledChange = { enabled ->
                            SettingsRepository.setAppAssistanceEnabled(
                                context,
                                status.app.packageName,
                                enabled
                            )
                            refreshKey++
                        }
                    )
                }
                if (visibleCount < filteredApps.size) {
                    item {
                        Text(
                            text = "继续滚动加载 ${filteredApps.size - visibleCount} 个应用",
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
private fun InstalledAppRow(
    status: InstalledAppStatus,
    onOpen: () -> Unit,
    onAssistanceEnabledChange: (Boolean) -> Unit
) {
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIconView(status.app.icon, status.app.label)
                Column(modifier = Modifier.weight(1f)) {
                    Text(status.app.label, fontWeight = FontWeight.Medium)
                    Text(
                        status.app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = status.appAssistanceEnabled,
                    enabled = !status.isProtected && !status.isSelfPackage,
                    onCheckedChange = onAssistanceEnabledChange
                )
            }
            Text(
                text = status.statusText(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InfoCard(text: String) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun InstalledAppStatus.statusText(): String {
    return buildList {
        when {
            isSelfPackage -> add("不可配置")
            isProtected -> add("安全保护")
            appAssistanceEnabled -> add("自动辅助已启用")
            else -> add("自动辅助已关闭")
        }
        if (isBlacklisted) add("默认规则关闭")
        if (!customRulesEnabled) add("自定义规则关闭")
        if (hasCustomRules) add("自定义规则 $customRuleCount")
        if (hitCount > 0) add("命中 $hitCount / 成功 $successCount")
    }.joinToString(" · ")
}

private enum class AppFilter(val label: String) {
    All("全部"),
    DefaultEnabled("自动辅助"),
    Protected("安全保护"),
    Blacklisted("黑名单")
}
