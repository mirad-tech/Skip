package com.example.skip.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.skip.ui.common.SimpleScreenScaffold

@Composable
fun DefaultRuleInfoScreen(onBack: () -> Unit) {
    SimpleScreenScaffold(title = "默认规则说明", onBack = onBack) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard(
                title = "启用范围",
                body = "非黑名单且非安全保护 App，默认启用开屏跳过。"
            )
            InfoCard(
                title = "匹配方式",
                body = "默认仅在应用打开后的短时间内尝试跳过，以减少误触。"
            )
            InfoCard(
                title = "安全限制",
                body = "支付、安装、登录、权限确认、输入法和系统设置等场景默认避开。"
            )
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    body: String
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
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
