package com.example.skip.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.example.skip.data.SettingsRepository
import com.example.skip.ui.common.AppIconView
import com.example.skip.ui.common.SimpleScreenScaffold

@Composable
fun BlacklistScreen(
    onBack: () -> Unit,
    onOpenApp: (String) -> Unit
) {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var showClearConfirm by remember { mutableStateOf(false) }
    val packages = remember(refreshKey) { SettingsRepository.getBlacklistPackages(context) }
    val items = packages
        .filter { query.isBlank() || it.contains(query, ignoreCase = true) }
        .map { InstalledAppRepository.resolve(context, it) }
        .filter { status ->
            query.isBlank() ||
                status.app.label.contains(query, ignoreCase = true) ||
                status.app.packageName.contains(query, ignoreCase = true)
        }

    SimpleScreenScaffold(title = "黑名单应用", onBack = onBack) {
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

            if (items.isEmpty()) {
                InfoCard(if (packages.isEmpty()) "暂无数据" else "没有匹配项")
            } else {
                items.forEach { status ->
                    Card(
                        onClick = { onOpenApp(status.app.packageName) },
                        shape = RoundedCornerShape(16.dp),
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
                            TextButton(
                                onClick = {
                                    SettingsRepository.setBlacklisted(
                                        context,
                                        status.app.packageName,
                                        false
                                    )
                                    refreshKey++
                                }
                            ) {
                                Text("移出")
                            }
                        }
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
