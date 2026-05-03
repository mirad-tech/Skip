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
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.example.skip.data.LogRepository
import com.example.skip.data.RuleImportManager
import com.example.skip.data.RuleRepository
import com.example.skip.model.InstalledApp
import com.example.skip.model.RuleArea
import com.example.skip.model.RuleLog
import com.example.skip.model.RuleSource
import com.example.skip.model.SkipRule
import com.example.skip.ui.common.SimpleScreenScaffold
import com.example.skip.util.InstalledAppUtils

@Composable
fun SimpleRuleScreen(
    editingRuleId: String?,
    initialPackageName: String? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val editingRule = remember(editingRuleId) {
        editingRuleId?.let { id -> RuleRepository.getRules(context).firstOrNull { it.id == id } }
    }
    val fixedPackageName = editingRule?.packageName ?: initialPackageName.orEmpty()
    val apps = remember(fixedPackageName) {
        if (fixedPackageName.isBlank()) InstalledAppUtils.loadLaunchableApps(context) else emptyList()
    }
    val fixedApp = remember(fixedPackageName) {
        fixedPackageName.takeIf { it.isNotBlank() }?.let {
            InstalledAppUtils.resolveApp(context, it)
        }
    }
    var appQuery by remember { mutableStateOf("") }
    var packageName by remember { mutableStateOf(fixedPackageName) }
    var appName by remember {
        mutableStateOf(editingRule?.appName ?: fixedApp?.label.orEmpty())
    }
    var ruleName by remember { mutableStateOf(editingRule?.name ?: "首页弹窗关闭") }
    var texts by remember {
        mutableStateOf(
            editingRule?.matchTexts?.joinToString("，")
                ?: "关闭，跳过，以后再说，我知道了"
        )
    }
    var area by remember { mutableStateOf(editingRule?.area ?: RuleArea.TopRight) }
    var validDurationMs by remember { mutableStateOf(editingRule?.validDurationMs ?: 6_000L) }
    var avoidRepeat by remember { mutableStateOf((editingRule?.cooldownMs ?: 1200L) >= 1200L) }
    var previewRule by remember { mutableStateOf<SkipRule?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    SimpleScreenScaffold(
        title = if (editingRule == null) "创建跳过规则" else "编辑跳过规则",
        onBack = onBack
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (fixedApp != null) {
                AppPickItem(
                    app = fixedApp,
                    selected = true,
                    onClick = {}
                )
            } else {
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
                                if (ruleName.isBlank()) ruleName = "首页弹窗关闭"
                            }
                        )
                    }
                }
            }

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
                values = listOf(6_000L, 10_000L, 30_000L, Long.MAX_VALUE),
                selected = validDurationMs,
                label = { formatDuration(it) },
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
                    if (packageName.isBlank()) {
                        message = "请选择应用"
                        return@Button
                    }
                    val result = RuleImportManager.createSimpleRule(
                        packageName = packageName,
                        appName = appName.ifBlank { packageName },
                        name = ruleName,
                        texts = splitInput(texts),
                        area = area,
                        validDurationMs = validDurationMs,
                        avoidRepeatClick = avoidRepeat,
                        selfPackageName = context.packageName
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
                Text("生效：${formatDuration(rule.validDurationMs)}")
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

private fun formatDuration(durationMs: Long): String {
    return if (durationMs > 30_000L) "任意时间" else "启动后 ${durationMs / 1000} 秒内"
}
