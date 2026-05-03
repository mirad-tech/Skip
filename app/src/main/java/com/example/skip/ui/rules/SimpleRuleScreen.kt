package com.example.skip.ui.rules

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.example.skip.data.LogRepository
import com.example.skip.data.RuleImportManager
import com.example.skip.data.RuleRepository
import com.example.skip.data.SettingsRepository
import com.example.skip.model.InstalledApp
import com.example.skip.model.RuleArea
import com.example.skip.model.RuleLog
import com.example.skip.model.RuleSource
import com.example.skip.model.SkipRule
import com.example.skip.ui.common.SimpleScreenScaffold
import com.example.skip.util.InstalledAppUtils
import com.example.skip.util.PackageUtil

@Composable
fun SimpleRuleScreen(
    editingRuleId: String?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val editingRule = remember(editingRuleId) {
        editingRuleId?.let { id -> RuleRepository.getRules(context).firstOrNull { it.id == id } }
    }
    val apps = remember { InstalledAppUtils.loadLaunchableApps(context) }
    var appQuery by remember { mutableStateOf("") }
    var packageName by remember { mutableStateOf(editingRule?.packageName.orEmpty()) }
    var appName by remember { mutableStateOf(editingRule?.appName.orEmpty()) }
    var ruleName by remember { mutableStateOf(editingRule?.name.orEmpty()) }
    var texts by remember {
        mutableStateOf(
            editingRule?.matchTexts?.joinToString("，")
                ?: "跳过，跳过广告，Skip，skip，关闭广告"
        )
    }
    var area by remember { mutableStateOf(editingRule?.area ?: RuleArea.TopRight) }
    var validDurationMs by remember { mutableStateOf(editingRule?.validDurationMs ?: 10_000L) }
    var avoidRepeat by remember { mutableStateOf((editingRule?.cooldownMs ?: 1200L) >= 1200L) }
    var previewRule by remember { mutableStateOf<SkipRule?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    SimpleScreenScaffold(
        title = if (editingRule == null) "创建跳过规则" else "编辑跳过规则",
        onBack = onBack
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = "选择目标 App，填写按钮文字和大概位置。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = appQuery,
                onValueChange = { appQuery = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("搜索已安装 App") },
                singleLine = true
            )

            val filteredApps = apps.filter {
                appQuery.isBlank() ||
                    it.label.contains(appQuery, ignoreCase = true) ||
                    it.packageName.contains(appQuery, ignoreCase = true)
            }.take(8)

            if (filteredApps.isNotEmpty()) {
                filteredApps.forEach { app ->
                    AppPickItem(
                        app = app,
                        selected = app.packageName == packageName,
                        onClick = {
                            packageName = app.packageName
                            appName = app.label
                            if (ruleName.isBlank()) ruleName = "${app.label} - 开屏跳过"
                        }
                    )
                }
            }

            OutlinedTextField(
                value = packageName,
                onValueChange = { packageName = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("目标 App 包名") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false
                )
            )

            OutlinedTextField(
                value = texts,
                onValueChange = { texts = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("按钮文字") },
                placeholder = { Text("例如：跳过、跳过广告、Skip、关闭") },
                minLines = 2
            )

            Text("按钮大概位置", style = MaterialTheme.typography.titleSmall)
            ChipGrid(
                values = RuleArea.entries,
                selected = area,
                label = { it.label },
                onSelected = { area = it }
            )

            Text("规则生效时间", style = MaterialTheme.typography.titleSmall)
            ChipGrid(
                values = listOf(5000L, 10_000L, 15_000L),
                selected = validDurationMs,
                label = { "App 打开后 ${it / 1000} 秒内" },
                onSelected = { validDurationMs = it }
            )

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("避免连续重复点击")
                    Switch(checked = avoidRepeat, onCheckedChange = { avoidRepeat = it })
                }
            }

            OutlinedTextField(
                value = ruleName,
                onValueChange = { ruleName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("规则名称") },
                singleLine = true
            )

            message?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (!PackageUtil.isLikelyPackageName(packageName)) {
                        message = "请输入完整包名"
                        return@Button
                    }
                    val result = RuleImportManager.createSimpleRule(
                        packageName = packageName,
                        appName = appName.ifBlank { packageName },
                        name = ruleName,
                        texts = splitInput(texts),
                        area = area,
                        validDurationMs = validDurationMs,
                        avoidRepeatClick = avoidRepeat
                    )
                    if (!result.success) {
                        message = result.errorMessage
                    } else {
                        previewRule = result.rules.first().let { newRule ->
                            editingRule?.let {
                                newRule.copy(
                                    id = it.id,
                                    createdAt = it.createdAt,
                                    enabled = it.enabled
                                )
                            } ?: newRule
                        }
                    }
                }
            ) {
                Text("保存规则")
            }
        }
    }

    previewRule?.let { rule ->
        RulePreviewDialog(
            rule = rule,
            warnings = RuleImportManager.validateForSave(rule),
            onDismiss = { previewRule = null },
            onConfirm = {
                RuleRepository.createLocalPackageIfNeeded(context)
                RuleRepository.upsertRule(context, rule)
                SettingsRepository.saveWhitelistPackages(
                    context,
                    (SettingsRepository.getWhitelistPackages(context) + rule.packageName)
                )
                LogRepository.addRuleLog(
                    context,
                    RuleLog(
                        timeMillis = System.currentTimeMillis(),
                        source = RuleSource.UserSimple,
                        ruleName = rule.name,
                        targetApp = rule.appName,
                        success = true,
                        reason = "已保存"
                    )
                )
                previewRule = null
                onBack()
            }
        )
    }
}

@Composable
private fun AppPickItem(
    app: InstalledApp,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            app.icon?.let { AppIcon(it) }
            Column(modifier = Modifier.weight(1f)) {
                Text(app.label, fontWeight = FontWeight.Medium)
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AppIcon(drawable: Drawable) {
    Image(
        bitmap = drawable.toBitmap(width = 96, height = 96).asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.size(36.dp)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipGrid(
    values: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit
) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        values.forEach { value ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelected(value) },
                label = { Text(label(value)) }
            )
        }
    }
}

@Composable
private fun RulePreviewDialog(
    rule: SkipRule,
    warnings: List<String>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认保存规则") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("规则：${rule.name}")
                Text("目标：${rule.appName}")
                Text("包名：${rule.packageName}")
                Text("文字：${rule.matchTexts.joinToString("、")}")
                Text("位置：${rule.area.label}")
                Text("生效：App 打开后 ${rule.validDurationMs / 1000} 秒内")
                Text("防重复点击：${if (rule.cooldownMs >= 1200) "开启" else "关闭"}")
                warnings.forEach {
                    Text("提示：$it", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("确认保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

private fun splitInput(text: String): List<String> {
    return text.split(',', '，', ';', '；', '\n', '\t', ' ')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
}
