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
                text = "导入规则默认也只在应用打开后的前 8 秒生效。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            InfoCard(
                title = "主要字段",
                body = "schemaVersion、name、version、author、appPolicies、apps、packageName、rules、id、kind、activityName、matchTexts、matchContentDescriptions、matchViewIds、textMatchMode、contentDescriptionMatchMode、viewIdMatchMode、area、action、cooldownMs、validDurationMs、minScore、coordinateFallback。"
            )
            InfoCard(
                title = "按应用策略",
                body = "appPolicies 可导入 defaultRuleEnabled 和 customRulesEnabled；apps[].enabled 当前不生效。Skip 自身和受保护应用不会执行规则。"
            )
            InfoCard(
                title = "area 支持",
                body = "top_left、top_center、top_right、middle_left、center、middle_right、bottom_left、bottom_center、bottom_right、any。"
            )
            InfoCard(
                title = "matchMode 支持",
                body = "contains、exact、regex。默认 contains；regex 仅建议用于范围很明确的本地规则。"
            )
            InfoCard(
                title = "常见错误",
                body = "JSON 格式错误、packageName 为空、rules 为空、rule id 为空、action 不是 click、area 不合法、minScore 不在 0 到 100、文件过大、规则过多或嵌套过深。"
            )
            InfoCard(
                title = "建议",
                body = "JSON 规则默认以停用状态导入；请先观察再本地启用。regex、area=any、纯 View ID 和坐标兜底需要额外确认。"
            )
            InfoCard(
                title = "坐标兜底限制",
                body = "coordinateFallback 默认关闭；启用时必须绑定 packageName、限制启动后 6 秒内、配置强 anchorTexts/anchorContentDescriptions/anchorViewIds、cooldownMs 不低于 800，并且不能包含同意、授权、允许、支付、购买、确认支付、登录、注册、隐私政策、用户协议、安装、删除、卸载、转账、发送、提交等高风险内容。"
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
