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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.example.skip.data.LogRepository
import com.example.skip.data.LogStorageState
import com.example.skip.data.RuleRepository
import com.example.skip.data.SettingsRepository
import com.example.skip.model.RuleSource
import com.example.skip.model.SkipRule
import com.example.skip.ui.common.AppIconView
import com.example.skip.ui.common.SimpleScreenScaffold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AppDetailScreen(
    packageName: String,
    onBack: () -> Unit,
    onAddRule: (String) -> Unit,
    onAddPreciseRule: (String, String, String, String, String, String) -> Unit,
    onImportJsonRule: (String) -> Unit,
    onDefaultRuleSettings: () -> Unit,
    onEditRule: (String) -> Unit
) {
    val context = LocalContext.current
    val storageState by LogRepository.storageState.collectAsState()
    var refreshKey by remember { mutableIntStateOf(0) }
    var status by remember(packageName) {
        mutableStateOf(InstalledAppRepository.resolve(context, packageName))
    }
    val rules = remember(packageName, refreshKey) {
        RuleRepository.getCustomRulesForPackage(context, packageName)
    }
    val builtInPreciseRules = remember(packageName) {
        RuleRepository.getBuiltInPreciseRulesForPackage(packageName)
    }
    var logs by remember(packageName) { mutableStateOf(emptyList<com.example.skip.model.ClickLog>()) }
    LaunchedEffect(packageName, refreshKey) {
        logs = runCatching { withContext(Dispatchers.IO) {
            LogRepository.getClickLogs(context).filter { it.packageName == packageName }.take(5)
        } }.getOrElse {
            LogRepository.getCachedClickLogs().filter { it.packageName == packageName }.take(5)
        }
        status = InstalledAppRepository.resolve(context, packageName)
    }
    val defaultRuleConfig = remember(refreshKey) {
        RuleRepository.getDefaultRuleConfig(context)
    }
    var showAddRuleOptions by remember { mutableStateOf(false) }

    SimpleScreenScaffold(title = "应用详情", onBack = onBack) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIconView(status.app.icon, status.app.label, size = 52.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(status.app.label, fontWeight = FontWeight.SemiBold)
                            Text(
                                status.app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        text = when {
                            status.isSelfPackage -> "当前状态：不可配置"
                            status.isProtected -> "当前状态：安全保护"
                            status.defaultSkipEnabled -> "当前状态：默认跳过已启用"
                            else -> "当前状态：默认跳过已关闭"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "自定义规则：${rules.size} · 命中：${status.hitCount} · 成功：${status.successCount}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (logs.firstOrNull { it.planScope.isNotBlank() }?.planScope == "precise_takeover") {
                            "当前执行模式：精确规则接管"
                        } else {
                            "当前执行模式：通用规则回退"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (storageState is LogStorageState.Degraded) {
                        Text(
                            text = "最近日志暂时不可用；规则配置和自动跳过不受影响。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    PolicySwitchRow(
                        title = "本应用自动辅助",
                        subtitle = "关闭后本应用的默认规则和自定义规则都暂停执行。",
                        checked = status.appAssistanceEnabled,
                        enabled = !status.isProtected && !status.isSelfPackage,
                        onCheckedChange = { enabled ->
                            SettingsRepository.setAppAssistanceEnabled(context, packageName, enabled)
                            refreshKey++
                        }
                    )
                    PolicySwitchRow(
                        title = "默认开屏规则",
                        subtitle = "关闭后不再使用内置通用跳过规则。",
                        checked = status.defaultSkipEnabled,
                        enabled = !status.isProtected && !status.isSelfPackage,
                        onCheckedChange = { enabled ->
                            SettingsRepository.setDefaultRuleEnabled(context, packageName, enabled)
                            refreshKey++
                        }
                    )
                    PolicySwitchRow(
                        title = "自定义规则",
                        subtitle = "关闭后本应用的本地/导入规则都暂停执行。",
                        checked = status.customRulesEnabled,
                        enabled = !status.isProtected && !status.isSelfPackage,
                        onCheckedChange = { enabled ->
                            SettingsRepository.setCustomRulesEnabled(context, packageName, enabled)
                            refreshKey++
                        }
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !status.isProtected && !status.isSelfPackage,
                        onClick = { showAddRuleOptions = true }
                    ) {
                        Text("添加规则")
                    }
                }
            }

            SectionTitle("默认规则")
            InfoCard(
                when {
                    status.isSelfPackage -> "Skip 自身不会被扫描、匹配或自动点击。"
                    status.isProtected -> "该应用属于安全保护范围，默认不自动点击。"
                    !status.defaultSkipEnabled -> "已关闭本应用的默认开屏跳过，可继续保留自定义规则。"
                    else -> "默认模板：${formatDuration(defaultRuleConfig.validDurationMs)} · 最低分 ${defaultRuleConfig.minScore} · ${defaultRuleConfig.area.label} · 间隔 ${defaultRuleConfig.cooldownMs}ms。"
                }
            )
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !status.isProtected && !status.isSelfPackage,
                onClick = onDefaultRuleSettings
            ) {
                Text("编辑默认规则设置")
            }

            SectionTitle("自定义规则")
            if (rules.isEmpty()) {
                InfoCard("暂无数据")
            } else {
                rules.forEach { rule ->
                    RuleCard(
                        rule = rule,
                        editable = rule.source == RuleSource.UserSimple,
                        onEnabledChange = { enabled ->
                            RuleRepository.setRuleEnabled(context, rule.id, enabled)
                            refreshKey++
                        },
                        onEdit = { onEditRule(rule.id) },
                        onDelete = {
                            RuleRepository.deleteRule(context, rule.id)
                            refreshKey++
                        }
                    )
                }
            }

            if (builtInPreciseRules.isNotEmpty()) {
                SectionTitle("内置精确规则")
                builtInPreciseRules.forEach { builtInRule ->
                    RuleCard(
                        rule = builtInRule,
                        editable = false,
                        copyable = true,
                        deletable = false,
                        toggleable = false,
                        onEnabledChange = {},
                        onEdit = {
                            RuleRepository.upsertRule(
                                context,
                                builtInRule.copy(
                                    id = "user_copy_${builtInRule.id}_${System.currentTimeMillis()}",
                                    source = RuleSource.UserSimple,
                                    priority = 300,
                                    packageId = "local",
                                    createdAt = System.currentTimeMillis()
                                )
                            )
                            refreshKey++
                        },
                        onDelete = {}
                    )
                }
            }

            SectionTitle("最近命中日志")
            if (logs.isEmpty()) {
                InfoCard("暂无数据")
            } else {
                logs.forEach { log ->
                    InfoCard(
                        buildString {
                            append(formatTime(log.timeMillis))
                            append("\n")
                            append(log.ruleType.ifBlank { "规则" })
                            append(" · ")
                            append(log.ruleName)
                            append("\n")
                            append(log.stage.label)
                            val failure = log.failureReason.ifBlank { log.reason }
                            if (failure.isNotBlank()) append("（").append(failure).append("）")
                            if (log.detail.isNotBlank()) {
                                append("\n")
                                append(log.detail)
                            }
                        }
                    )
                    val hasStableSignal = log.activityName.isNotBlank() &&
                        (log.viewIdResourceName.isNotBlank() || log.nodeText.isNotBlank() || log.contentDescription.isNotBlank())
                    if (hasStableSignal && !(log.stage.value == "no_candidate_found" &&
                            log.viewIdResourceName.isBlank() && log.nodeText.isBlank() && log.contentDescription.isBlank())) {
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                onAddPreciseRule(
                                    packageName,
                                    log.activityName,
                                    log.nodeText,
                                    log.contentDescription,
                                    log.viewIdResourceName,
                                    log.area
                                )
                            }
                        ) { Text("创建精确规则") }
                    }
                }
            }
        }
    }

    if (showAddRuleOptions) {
        AddRuleOptionsDialog(
            onDismiss = { showAddRuleOptions = false },
            onCreateSimpleRule = {
                showAddRuleOptions = false
                onAddRule(packageName)
            },
            onCreatePreciseRule = {
                showAddRuleOptions = false
                onAddPreciseRule(packageName, "", "", "", "", "")
            },
            onImportJson = {
                showAddRuleOptions = false
                onImportJsonRule(packageName)
            }
        )
    }
}

@Composable
private fun AddRuleOptionsDialog(
    onDismiss: () -> Unit,
    onCreateSimpleRule: () -> Unit,
    onCreatePreciseRule: () -> Unit,
    onImportJson: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加规则") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onCreateSimpleRule
                ) {
                    Text("创建普通规则")
                }
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onCreatePreciseRule
                ) {
                    Text("创建精确规则")
                }
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onImportJson
                ) {
                    Text("导入 JSON 文件")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun PolicySwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

@Composable
private fun RuleCard(
    rule: SkipRule,
    editable: Boolean,
    copyable: Boolean = false,
    deletable: Boolean = true,
    toggleable: Boolean = true,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(rule.name, fontWeight = FontWeight.Medium)
                    Text(
                        "${rule.kind.label} · ${rule.activityName} · ${rule.area.label} · ${formatDuration(rule.validDurationMs)} · ${rule.textMatchMode.value}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = rule.enabled, enabled = toggleable, onCheckedChange = onEnabledChange)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (editable || copyable) {
                    TextButton(onClick = onEdit) { Text(if (editable) "编辑" else "复制为本地规则") }
                }
                if (deletable) TextButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
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
                .padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatDuration(durationMs: Long): String {
    return "启动后 ${durationMs / 1000} 秒内"
}

private fun formatTime(timeMillis: Long): String {
    return SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timeMillis))
}
