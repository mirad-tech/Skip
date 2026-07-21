package com.example.skip.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

private const val BLACKLIST_BATCH_SIZE = 30

@Composable
fun BlacklistScreen(
    onBack: () -> Unit,
    onOpenApp: (String) -> Unit
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var refreshKey by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var showClearConfirm by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var packages by remember { mutableStateOf<List<String>>(emptyList()) }
    var statuses by remember { mutableStateOf<List<InstalledAppStatus>>(emptyList()) }

    LaunchedEffect(context, refreshKey) {
        loading = true
        val loaded = withContext(Dispatchers.IO) {
            LogRepository.getClickLogs(context)
            val blacklistPackages = SettingsRepository.getBlacklistPackages(context)
            blacklistPackages to blacklistPackages.map {
                InstalledAppRepository.resolve(context, it)
            }
        }
        packages = loaded.first
        statuses = loaded.second
        loading = false
    }

    val filteredItems = remember(statuses, query) {
        filterBlacklistStatuses(statuses, query)
    }
    var visibleCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(filteredItems.size, query) {
        listState.scrollToItem(0)
        visibleCount = initialVisibleCount(filteredItems.size, BLACKLIST_BATCH_SIZE)
    }
    AutoLoadMoreEffect(
        listState = listState,
        visibleCount = visibleCount,
        totalCount = filteredItems.size,
        batchSize = BLACKLIST_BATCH_SIZE,
        onVisibleCountChange = { visibleCount = it }
    )

    LazyScreenScaffold(
        title = "黑名单应用",
        onBack = onBack,
        listState = listState
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "黑名单不执行默认开屏跳过。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索黑名单") },
                    singleLine = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        enabled = packages.isNotEmpty(),
                        onClick = { showClearConfirm = true }
                    ) {
                        Text("清空")
                    }
                }
            }
        }

        when {
            loading -> item { InfoCard("正在加载黑名单...") }
            filteredItems.isEmpty() -> item {
                InfoCard(if (packages.isEmpty()) "暂无数据" else "没有匹配项")
            }
            else -> {
                items(
                    items = filteredItems.take(visibleCount),
                    key = { it.app.packageName }
                ) { status ->
                    BlacklistRow(
                        status = status,
                        onOpen = { onOpenApp(status.app.packageName) },
                        onRemove = {
                            SettingsRepository.setBlacklisted(
                                context,
                                status.app.packageName,
                                false
                            )
                            refreshKey++
                        }
                    )
                }
                if (visibleCount < filteredItems.size) {
                    item {
                        Text(
                            text = "继续滚动加载 ${filteredItems.size - visibleCount} 个应用",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空黑名单") },
            text = { Text("清空后，非安全保护 App 将恢复默认跳过。") },
            confirmButton = {
                Button(
                    onClick = {
                        SettingsRepository.clearBlacklist(context)
                        showClearConfirm = false
                        refreshKey++
                    }
                ) {
                    Text("清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun BlacklistRow(
    status: InstalledAppStatus,
    onOpen: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
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
            TextButton(onClick = onRemove) {
                Text("移出")
            }
        }
    }
}

internal fun filterBlacklistStatuses(
    statuses: List<InstalledAppStatus>,
    query: String
): List<InstalledAppStatus> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isBlank()) return statuses
    return statuses.filter { status ->
        status.app.label.contains(normalizedQuery, ignoreCase = true) ||
            status.app.packageName.contains(normalizedQuery, ignoreCase = true)
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
