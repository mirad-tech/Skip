package com.example.skip.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.skip.data.RuleRepository
import com.example.skip.model.SkipRule
import com.example.skip.ui.common.SimpleScreenScaffold

@Composable
fun RuleListScreen(
    onBack: () -> Unit,
    onEditSimpleRule: (String) -> Unit
) {
    val context = LocalContext.current
    var rules by remember { mutableStateOf(RuleRepository.getRules(context)) }
    var packages by remember { mutableStateOf(RuleRepository.getRulePackages(context)) }

    SimpleScreenScaffold(title = "已创建规则", onBack = onBack) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "这里显示普通创建和 JSON 导入的规则。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (rules.isEmpty()) {
                InfoCard("还没有规则。")
            } else {
                packages.filter { it.id != "local" }.forEach { rulePackage ->
                    Card(
                        shape = RoundedCornerShape(16.dp),
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
                                onCheckedChange = {
                                    RuleRepository.setRulePackageEnabled(context, rulePackage.id, it)
                                    packages = RuleRepository.getRulePackages(context)
                                    rules = RuleRepository.getRules(context)
                                }
                            )
                            TextButton(
                                onClick = {
                                    RuleRepository.deleteRulePackage(context, rulePackage.id)
                                    packages = RuleRepository.getRulePackages(context)
                                    rules = RuleRepository.getRules(context)
                                }
                            ) {
                                Text("删除")
                            }
                        }
                    }
                }

                rules.forEach { rule ->
                    RuleCard(
                        rule = rule,
                        onEnabledChanged = { enabled ->
                            RuleRepository.setRuleEnabled(context, rule.id, enabled)
                            rules = RuleRepository.getRules(context)
                        },
                        onEdit = {
                            if (rule.source.value == "user_simple") onEditSimpleRule(rule.id)
                        },
                        onDelete = {
                            RuleRepository.deleteRule(context, rule.id)
                            packages = RuleRepository.getRulePackages(context)
                            rules = RuleRepository.getRules(context)
                        }
                    )
                }
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
                        "${rule.appName.ifBlank { rule.packageName }} · ${rule.source.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = rule.enabled, onCheckedChange = onEnabledChanged)
            }
            Text(
                text = "位置：${rule.area.label} · ${rule.validDurationMs / 1000} 秒内 · 分数 ${rule.minScore}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
