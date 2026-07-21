package com.example.skip.ui.rules

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.mutableLongStateOf
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
import com.example.skip.data.RuleImportManager
import com.example.skip.data.RuleLifecycleRepository
import com.example.skip.data.RuleRepository
import com.example.skip.model.CoordinateFallback
import com.example.skip.model.InstalledApp
import com.example.skip.model.RuleArea
import com.example.skip.model.RuleKind
import com.example.skip.model.SkipRule
import com.example.skip.ui.common.SimpleScreenScaffold
import com.example.skip.util.InstalledAppUtils

@Composable
fun SimpleRuleScreen(
    editingRuleId: String?,
    initialPackageName: String? = null,
    createPrecise: Boolean = false,
    initialActivityName: String = "",
    initialText: String = "",
    initialDescription: String = "",
    initialViewId: String = "",
    initialArea: String = "",
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val editingRule = remember(editingRuleId) {
        editingRuleId?.let { id -> RuleRepository.getRules(context).firstOrNull { it.id == id } }
    }
    val preciseMode = editingRule?.kind == RuleKind.Precise || (editingRule == null && createPrecise)
    val fixedPackageName = editingRule?.packageName ?: initialPackageName.orEmpty()
    val apps = remember(fixedPackageName) {
        if (fixedPackageName.isBlank()) InstalledAppUtils.loadLaunchableApps(context) else emptyList()
    }
    val fixedApp = remember(fixedPackageName) {
        fixedPackageName.takeIf { it.isNotBlank() }?.let { InstalledAppUtils.resolveApp(context, it) }
    }

    var appQuery by remember { mutableStateOf("") }
    var packageName by remember { mutableStateOf(fixedPackageName) }
    var appName by remember { mutableStateOf(editingRule?.appName ?: fixedApp?.label.orEmpty()) }
    var ruleName by remember { mutableStateOf(editingRule?.name ?: if (preciseMode) "精确跳过" else "首页弹窗关闭") }
    var activityName by remember { mutableStateOf(editingRule?.activityName?.takeUnless { it == "*" } ?: initialActivityName) }
    var texts by remember {
        mutableStateOf(editingRule?.matchTexts?.joinToString("，") ?: if (preciseMode) initialText else "关闭，跳过，以后再说，我知道了")
    }
    var descriptions by remember {
        mutableStateOf(
            editingRule?.matchContentDescriptions?.joinToString("，")
                ?: if (preciseMode) initialDescription else "关闭，跳过，以后再说，我知道了"
        )
    }
    var viewIds by remember {
        mutableStateOf(editingRule?.matchViewIds?.joinToString("，") ?: initialViewId)
    }
    var area by remember {
        mutableStateOf(editingRule?.area ?: RuleArea.fromValue(initialArea) ?: RuleArea.TopRight)
    }
    var validDurationMs by remember { mutableLongStateOf(editingRule?.validDurationMs ?: RuleRepository.DEFAULT_RULE_WINDOW_MS) }
    var enabled by remember { mutableStateOf(editingRule?.enabled ?: true) }
    var priorityText by remember { mutableStateOf((editingRule?.priority ?: if (preciseMode) 300 else 100).toString()) }
    var minScoreText by remember {
        mutableStateOf((editingRule?.minScore ?: if (preciseMode && initialViewId.contains(":id/")) 70 else if (preciseMode) 80 else 72).toString())
    }
    var cooldownText by remember { mutableStateOf((editingRule?.cooldownMs ?: if (preciseMode) 1500L else 1200L).toString()) }
    var coordinateEnabled by remember { mutableStateOf(editingRule?.coordinateFallback?.enabled == true) }
    var coordinateXRatio by remember {
        mutableStateOf(editingRule?.coordinateFallback?.xRatio?.toString() ?: "0.90")
    }
    var coordinateYRatio by remember {
        mutableStateOf(editingRule?.coordinateFallback?.yRatio?.toString() ?: "0.12")
    }
    var coordinateAnchors by remember {
        mutableStateOf(editingRule?.coordinateFallback?.anchorTexts?.joinToString("，") ?: "")
    }
    var previewRule by remember { mutableStateOf<SkipRule?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    SimpleScreenScaffold(
        title = if (editingRule == null) (if (preciseMode) "创建精确规则" else "创建普通规则") else "编辑跳过规则",
        onBack = onBack
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = if (preciseMode) "精确规则仅在指定 Activity 接管，匹配模式固定为 exact。" else "普通规则只在应用打开后的前 8 秒内生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (fixedApp != null) {
                AppPickItem(app = fixedApp, selected = true, onClick = {})
            } else {
                OutlinedTextField(
                    value = appQuery,
                    onValueChange = { appQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索已安装 App") },
                    singleLine = true
                )

                apps.filter {
                    appQuery.isBlank() ||
                        it.label.contains(appQuery, ignoreCase = true) ||
                        it.packageName.contains(appQuery, ignoreCase = true)
                }.take(8).forEach { app ->
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

            if (preciseMode) {
                OutlinedTextField(
                    value = activityName,
                    onValueChange = { activityName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Activity 名称（必填）") },
                    placeholder = { Text("com.example.app.SplashActivity") },
                    singleLine = true
                )
            }

            OutlinedTextField(
                value = texts,
                onValueChange = { texts = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("按钮文字") },
                placeholder = { Text("例如：跳过、跳过广告、关闭") },
                minLines = 2
            )

            OutlinedTextField(
                value = descriptions,
                onValueChange = { descriptions = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("内容描述") },
                placeholder = { Text("例如：关闭、跳过、Skip") },
                minLines = 2
            )

            OutlinedTextField(
                value = viewIds,
                onValueChange = {
                    viewIds = it
                    if (preciseMode && minScoreText == "80" && it.contains(":id/")) minScoreText = "70"
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (preciseMode) "完整 View ID" else "View ID 关键词") },
                placeholder = { Text(if (preciseMode) "例如：com.example.app:id/ad_skip" else "例如：skip、ad_skip、close_btn") },
                minLines = 2
            )

            Text("按钮大概位置", style = MaterialTheme.typography.titleSmall)
            ChipGrid(
                values = if (preciseMode) RuleArea.entries.filterNot { it == RuleArea.Any } else RuleArea.entries,
                selected = area,
                label = { it.label },
                onSelected = { area = it }
            )

            Text("规则生效时间", style = MaterialTheme.typography.titleSmall)
            ChipGrid(
                values = if (preciseMode) listOf(3_000L, 5_000L, 8_000L, 10_000L, 15_000L) else listOf(RuleRepository.DEFAULT_RULE_WINDOW_MS),
                selected = validDurationMs,
                label = { formatDuration(it) },
                onSelected = { validDurationMs = it }
            )

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("启用规则")
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                    OutlinedTextField(
                        value = priorityText,
                        onValueChange = { priorityText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("优先级") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = minScoreText,
                        onValueChange = { minScoreText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("最低分") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = cooldownText,
                        onValueChange = { cooldownText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("点击间隔 ms") },
                        singleLine = true
                    )
                }
            }

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("坐标兜底")
                            Text(
                                "默认关闭，仅限绑定包名、启动时间窗、锚点和冷却时间完整的低风险规则",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = coordinateEnabled,
                            onCheckedChange = { coordinateEnabled = it }
                        )
                    }
                    if (coordinateEnabled) {
                        OutlinedTextField(
                            value = coordinateXRatio,
                            onValueChange = { coordinateXRatio = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("X 比例 0-1") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = coordinateYRatio,
                            onValueChange = { coordinateYRatio = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Y 比例 0-1") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = coordinateAnchors,
                            onValueChange = { coordinateAnchors = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("兜底锚点文字") },
                            placeholder = { Text("例如：开屏提示、跳过") },
                            minLines = 2
                        )
                        Text(
                            text = "不得用于支付、授权、登录、隐私同意、安装、删除、转账、发送、提交等场景。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            OutlinedTextField(
                value = ruleName,
                onValueChange = { ruleName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("规则名称") },
                singleLine = true
            )

            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (packageName.isBlank()) {
                        message = "请选择应用"
                        return@Button
                    }
                    val priority = priorityText.toIntOrNull()
                    val minScore = minScoreText.toIntOrNull()
                    val cooldownMs = cooldownText.toLongOrNull()
                    val xRatio = coordinateXRatio.toFloatOrNull()
                    val yRatio = coordinateYRatio.toFloatOrNull()
                    if (priority == null || minScore == null || cooldownMs == null) {
                        message = "优先级、最低分和点击间隔需要填写数字"
                        return@Button
                    }
                    if (coordinateEnabled && (xRatio == null || yRatio == null)) {
                        message = "坐标比例需要填写 0 到 1 之间的小数"
                        return@Button
                    }
                    val coordinateFallback = if (coordinateEnabled) {
                        CoordinateFallback(
                            enabled = true,
                            xRatio = xRatio ?: 0f,
                            yRatio = yRatio ?: 0f,
                            anchorTexts = splitInput(coordinateAnchors)
                        )
                    } else {
                        null
                    }
                    val result = RuleImportManager.createLocalRule(
                        packageName = packageName,
                        appName = appName.ifBlank { packageName },
                        name = ruleName,
                        texts = splitInput(texts),
                        contentDescriptions = splitInput(descriptions),
                        viewIds = splitInput(viewIds),
                        area = area,
                        enabled = enabled,
                        priority = priority,
                        cooldownMs = cooldownMs,
                        validDurationMs = validDurationMs,
                        minScore = minScore,
                        coordinateFallback = coordinateFallback,
                        selfPackageName = context.packageName,
                        kind = if (preciseMode) RuleKind.Precise else RuleKind.Standard,
                        activityName = if (preciseMode) activityName else "*"
                    )
                    if (!result.success) {
                        message = result.errorMessage
                    } else {
                        previewRule = result.rules.first().let { newRule ->
                            editingRule?.let {
                                newRule.copy(id = it.id, createdAt = it.createdAt)
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
                RuleLifecycleRepository.saveLocalRule(context, rule)
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
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
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
    FlowRow(
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
                Text("文字：${rule.matchTexts.joinToString("、").ifBlank { "-" }}")
                Text("描述：${rule.matchContentDescriptions.joinToString("、").ifBlank { "-" }}")
                Text("View ID：${rule.matchViewIds.joinToString("、").ifBlank { "-" }}")
                Text("位置：${rule.area.label}")
                Text("生效：${formatDuration(rule.validDurationMs)}")
                Text("最低分：${rule.minScore} · 优先级：${rule.priority} · 间隔：${rule.cooldownMs}ms")
                rule.coordinateFallback?.takeIf { it.enabled }?.let { fallback ->
                    Text("坐标兜底：${fallback.xRatio}, ${fallback.yRatio}")
                    Text("兜底锚点：${fallback.anchorTexts.joinToString("、").ifBlank { "-" }}")
                }
                warnings.forEach { Text("提示：$it", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("确认保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
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
    return "启动后 ${durationMs / 1000} 秒内"
}
