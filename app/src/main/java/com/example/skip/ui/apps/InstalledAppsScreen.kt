package com.example.skip.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.skip.data.InstalledAppRepository
import com.example.skip.data.InstalledAppStatus
import com.example.skip.ui.common.AppIconView
import com.example.skip.ui.common.SimpleScreenScaffold

@Composable
fun InstalledAppsScreen(
    onBack: () -> Unit,
    onOpenApp: (String) -> Unit
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(AppFilter.All) }
    val apps = remember(context) { InstalledAppRepository.loadApps(context) }
    val filteredApps = apps
        .filter { status ->
            query.isBlank() ||
                status.app.label.contains(query, ignoreCase = true) ||
                status.app.packageName.contains(query, ignoreCase = true)
        }
        .filter { status ->
            when (filter) {
                AppFilter.All -> true
                AppFilter.DefaultEnabled -> status.defaultSkipEnabled
                AppFilter.Protected -> status.isProtected
                AppFilter.Blacklisted -> status.isBlacklisted
            }
        }

    SimpleScreenScaffold(title = "已安装应用", onBack = onBack) {
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

            if (apps.isEmpty()) {
                InfoCard("暂无数据")
            } else if (filteredApps.isEmpty()) {
                InfoCard("暂无数据")
            } else {
                filteredApps.forEach { status ->
                    InstalledAppRow(
                        status = status,
                        onOpen = { onOpenApp(status.app.packageName) }
                    )
                }
            }
        }
    }
}

@Composable
private fun InstalledAppRow(
    status: InstalledAppStatus,
    onOpen: () -> Unit
) {
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(16.dp),
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
        shape = RoundedCornerShape(16.dp),
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
            isBlacklisted -> add("黑名单")
            defaultSkipEnabled -> add("默认跳过已启用")
            else -> add("默认跳过未启用")
        }
        if (hasCustomRules) add("自定义规则 $customRuleCount")
    }.joinToString(" · ")
}

private enum class AppFilter(val label: String) {
    All("全部"),
    DefaultEnabled("默认跳过"),
    Protected("安全保护"),
    Blacklisted("黑名单")
}
