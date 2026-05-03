package com.example.skip.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.skip.data.RuleImportManager
import com.example.skip.ui.common.SimpleScreenScaffold

@Composable
fun RuleFormatScreen(onBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val sample = RuleImportManager.sampleJson()

    SimpleScreenScaffold(title = "规则格式说明", onBack = onBack) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = "这是给高级用户看的 JSON 规则格式。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            InfoCard(
                title = "主要字段",
                body = "name、version、author、apps、packageName、rules、id、matchTexts、matchContentDescriptions、matchViewIds、area、action、cooldownMs、validDurationMs、minScore。"
            )
            InfoCard(
                title = "area 支持",
                body = "top_left、top_center、top_right、middle_left、center、middle_right、bottom_left、bottom_center、bottom_right、any。"
            )
            InfoCard(
                title = "常见错误",
                body = "JSON 格式错误、packageName 为空、rules 为空、rule id 为空、action 不是 click、area 不合法、minScore 不是 0 到 100 的数字。"
            )
            InfoCard(
                title = "建议",
                body = "validDurationMs 不建议超过 15000，cooldownMs 不建议小于 800，area=any 和低 minScore 都会增加误触风险。"
            )

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { clipboard.setText(AnnotatedString(sample)) }
            ) {
                Text("复制示例规则")
            }

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Text(
                    text = sample,
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
