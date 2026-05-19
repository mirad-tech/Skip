package com.example.skip.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import com.example.skip.data.RuleRepository
import com.example.skip.model.RulePackage
import com.example.skip.model.SkipRule
import com.example.skip.ui.common.AutoLoadMoreEffect
import com.example.skip.ui.common.LazyScreenScaffold
import com.example.skip.ui.common.initialVisibleCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val RULE_LIST_BATCH_SIZE = 30

@Composable
fun RuleListScreen(
    onBack: () -> Unit,
    onEditSimpleRule: (String) -> Unit
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var refreshKey by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var rules by remember { mutableStateOf<List<SkipRule>>(emptyList()) }
    var packages by remember { mutableStateOf<List<RulePackage>>(emptyList()) }

    LaunchedEffect(context, refreshKey) {
        loading = true
        val loaded = withContext(Dispatchers.IO) {
            RuleRepository.getRules(context) to RuleRepository.getRulePackages(context)
        }
        rules = loaded.first
        packages = loaded.second
        loading = false
    }

    val entries = remember(rules, packages) {
        buildList {
            packages.filter { it.id != "local" }.forEach { add(RuleListEntry.PackageEntry(it)) }
            rules.forEach { add(RuleListEntry.RuleEntry(it)) }
        }
    }
    var visibleCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(entries.size) {
        listState.scrollToItem(0)
        visibleCount = initialVisibleCount(entries.size, RULE_LIST_BATCH_SIZE)
    }
    AutoLoadMoreEffect(
        listState = listState,
        visibleCount = visibleCount,
        totalCount = entries.size,
        batchSize = RULE_LIST_BATCH_SIZE,
        onVisibleCountChange = { visibleCount = it }
    )

    LazyScreenScaffold(
        title = "已创建规则",
        onBack = onBack,
        listState = listState
    ) {
        item {
            Text(
                text = "默认和自定义规则默认都会限制在应用前台后的前 8 秒内生效。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        when {
            loading -> item { InfoCard("正在加载规则...") }
            entries.isEmpty() -> item { InfoCard("暂无数据") }
            else -> {
                items(
                    items = entries.take(visibleCount),
                    key = { it.key }
                ) { entry ->
                    when (entry) {
                        is RuleListEntry.PackageEntry -> RulePackageCard(
                            rulePackage = entry.rulePackage,
                            onEnabledChanged = {
                                RuleRepository.setRulePackageEnabled(context, entry.rulePackage.id, it)
                                refreshKey++
                            },
                            onDelete = {
                                RuleRepository.deleteRulePackage(context, entry.rulePackage.id)
                                refreshKey++
                            }
                        )

                        is RuleListEntry.RuleEntry -> RuleCard(
                            rule = entry.rule,
                            onEnabledChanged = { enabled ->
                                RuleRepository.setRuleEnabled(context, entry.rule.id, enabled)
                                refreshKey++
                            },
                            onEdit = {
                                if (entry.rule.source.value == "user_simple") onEditSimpleRule(entry.rule.id)
                            },
                            onDelete = {
                                RuleRepository.deleteRule(context, entry.rule.id)
                                refreshKey++
                            }
                        )
                    }
                }
                if (visibleCount < entries.size) {
                    item {
                        Text(
                            text = "继续滚动加载 ${entries.size - visibleCount} 条规则",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private sealed interface RuleListEntry {
    val key: String

    data class PackageEntry(val rulePackage: RulePackage) : RuleListEntry {
        override val key: String = "package:${rulePackage.id}"
    }

    data class RuleEntry(val rule: SkipRule) : RuleListEntry {
        override val key: String = "rule:${rule.id}"
    }
}

@Composable
private fun RulePackageCard(
    rulePackage: RulePackage,
    onEnabledChanged: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(rulePackage.name, fontWeight = FontWeight.Medium)
                Text(
                    "规则包 · ${rulePackage.author}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = rulePackage.enabled,
                onCheckedChange = onEnabledChanged
            )
            TextButton(onClick = onDelete) {
                Text("删除")
            }
        }
    }
}

@Composable
private fun RuleCard(
    rule: SkipRule,
    onEnabledChanged: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
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
                        "${rule.appName.ifBlank { rule.packageName }} · ${rule.source.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = rule.enabled, onCheckedChange = onEnabledChanged)
            }
            Text(
                text = "包名：${rule.packageName}\n位置：${rule.area.label} · ${formatDuration(rule.validDurationMs)} · 分数 ${rule.minScore} · 冷却 ${rule.cooldownMs}ms",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = buildRuleMatchSummary(rule),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            rule.coordinateFallback?.takeIf { it.enabled }?.let { fallback ->
                Text(
                    text = "坐标兜底：${fallback.xRatio}, ${fallback.yRatio} · 锚点 ${fallback.anchorTexts.joinToString("、").ifBlank { "-" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    enabled = rule.source.value == "user_simple",
                    onClick = onEdit
                ) {
                    Text("编辑")
                }
                TextButton(onClick = onDelete) {
                    Text("删除")
                }
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    return "启动后 ${durationMs / 1000} 秒内"
}

private fun buildRuleMatchSummary(rule: SkipRule): String {
    val parts = listOf(
        "文字 ${rule.matchTexts.joinToString("、").ifBlank { "-" }}",
        "描述 ${rule.matchContentDescriptions.joinToString("、").ifBlank { "-" }}",
        "View ID ${rule.matchViewIds.joinToString("、").ifBlank { "-" }}"
    )
    return parts.joinToString("\n")
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
