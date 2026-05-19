package com.example.skip.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.skip.data.RuleRepository
import com.example.skip.model.RuleArea
import com.example.skip.ui.common.SimpleScreenScaffold

@Composable
fun DefaultRuleInfoScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(RuleRepository.getDefaultRuleConfig(context)) }
    var minScoreText by remember { mutableStateOf(config.minScore.toString()) }
    var cooldownText by remember { mutableStateOf(config.cooldownMs.toString()) }
    var keywords by remember { mutableStateOf(RuleRepository.getKeywords(context)) }
    var viewIdKeywords by remember { mutableStateOf(RuleRepository.getViewIdKeywords(context)) }
    var keywordInput by remember { mutableStateOf("") }
    var viewIdInput by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    fun saveConfig(next: RuleRepository.DefaultRuleConfig) {
        config = RuleRepository.saveDefaultRuleConfig(context, next)
        minScoreText = config.minScore.toString()
        cooldownText = config.cooldownMs.toString()
        message = "默认规则设置已保存"
    }

    SimpleScreenScaffold(title = "默认规则设置", onBack = onBack) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = "全局默认规则适用于未关闭默认规则的普通 App。坐标兜底仍只允许在具体 App 的自定义规则中配置。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            ConfigCard(config = config)

            SectionTitle("生效时间")
            ChipGrid(
                values = RuleRepository.defaultRuleDurationOptionsMs,
                selected = config.validDurationMs,
                label = { formatDuration(it) },
                onSelected = { saveConfig(config.copy(validDurationMs = it)) }
            )

            SectionTitle("位置偏好")
            ChipGrid(
                values = RuleArea.entries,
                selected = config.area,
                label = { it.label },
                onSelected = { saveConfig(config.copy(area = it)) }
            )

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = minScoreText,
                        onValueChange = { minScoreText = it.filter(Char::isDigit).take(2) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("最低分 60-90") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = cooldownText,
                        onValueChange = { cooldownText = it.filter(Char::isDigit).take(5) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("点击间隔 ms，最低 800") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val minScore = minScoreText.toIntOrNull()
                            val cooldownMs = cooldownText.toLongOrNull()
                            if (minScore == null || cooldownMs == null) {
                                message = "最低分和点击间隔需要填写数字"
                            } else {
                                saveConfig(config.copy(minScore = minScore, cooldownMs = cooldownMs))
                            }
                        }
                    ) {
                        Text("保存分数和间隔")
                    }
                }
            }

            KeywordEditor(
                title = "按钮文字和描述关键词",
                values = keywords,
                input = keywordInput,
                onInputChange = { keywordInput = it },
                onAdd = {
                    val value = keywordInput.trim()
                    if (value.isNotBlank()) {
                        keywords = (keywords + value).cleanItems()
                        RuleRepository.saveKeywords(context, keywords)
                        keywordInput = ""
                        message = "关键词已保存"
                    }
                },
                onDelete = { value ->
                    keywords = keywords.filterNot { it == value }
                    RuleRepository.saveKeywords(context, keywords)
                    message = "关键词已保存"
                },
                onReset = {
                    keywords = RuleRepository.defaultKeywords.cleanItems()
                    RuleRepository.saveKeywords(context, keywords)
                    message = "关键词已恢复默认"
                }
            )

            KeywordEditor(
                title = "View ID 关键词",
                values = viewIdKeywords,
                input = viewIdInput,
                onInputChange = { viewIdInput = it },
                onAdd = {
                    val value = viewIdInput.trim()
                    if (value.isNotBlank()) {
                        viewIdKeywords = (viewIdKeywords + value).cleanItems()
                        RuleRepository.saveViewIdKeywords(context, viewIdKeywords)
                        viewIdInput = ""
                        message = "View ID 关键词已保存"
                    }
                },
                onDelete = { value ->
                    viewIdKeywords = viewIdKeywords.filterNot { it == value }
                    RuleRepository.saveViewIdKeywords(context, viewIdKeywords)
                    message = "View ID 关键词已保存"
                },
                onReset = {
                    viewIdKeywords = RuleRepository.defaultViewIdKeywords.cleanItems()
                    RuleRepository.saveViewIdKeywords(context, viewIdKeywords)
                    message = "View ID 关键词已恢复默认"
                }
            )

            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    RuleRepository.resetDefaultRuleConfig(context)
                    config = RuleRepository.getDefaultRuleConfig(context)
                    minScoreText = config.minScore.toString()
                    cooldownText = config.cooldownMs.toString()
                    message = "默认规则参数已恢复默认"
                }
            ) {
                Text("恢复默认参数")
            }

            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ConfigCard(config: RuleRepository.DefaultRuleConfig) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("当前模板", fontWeight = FontWeight.Medium)
            Text(
                text = "${formatDuration(config.validDurationMs)} · 最低分 ${config.minScore} · ${config.area.label} · 间隔 ${config.cooldownMs}ms",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun KeywordEditor(
    title: String,
    values: List<String>,
    input: String,
    onInputChange: (String) -> Unit,
    onAdd: () -> Unit,
    onDelete: (String) -> Unit,
    onReset: () -> Unit
) {
    SectionTitle(title)
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
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    label = { Text(title) },
                    singleLine = true
                )
                Button(onClick = onAdd) {
                    Text("添加")
                }
            }
            values.forEach { value ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = value,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(onClick = { onDelete(value) }) {
                        Text("删除")
                    }
                }
            }
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onReset
            ) {
                Text("恢复默认列表")
            }
        }
    }
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
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun formatDuration(durationMs: Long): String {
    return "启动后 ${durationMs / 1000} 秒内"
}

private fun Collection<String>.cleanItems(): List<String> {
    return map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
}
