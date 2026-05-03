package com.example.skip.ui.privacy

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
import com.example.skip.engine.SafetyGuard
import com.example.skip.ui.common.SimpleScreenScaffold

@Composable
fun PrivacyScreen(
    mode: PrivacyPageMode,
    versionName: String,
    onBack: () -> Unit
) {
    SimpleScreenScaffold(
        title = mode.title,
        onBack = onBack
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (mode) {
                PrivacyPageMode.Privacy -> {
                    InfoCard(
                        title = "无障碍服务用途",
                        body = "本 App 只在你主动开启无障碍服务后，用于识别疑似跳过按钮并点击。"
                    )
                    InfoCard(
                        title = "隐私边界",
                        body = "不上传屏幕内容，不读取短信、联系人、相册或定位，不做后台截图。"
                    )
                }

                PrivacyPageMode.Safety -> {
                    InfoCard(
                        title = "默认保护",
                        body = SafetyGuard.protectedSummary()
                    )
                    InfoCard(
                        title = "实现边界",
                        body = "不使用 Root、Hook、改包、抓包、注入或绕过授权方案。"
                    )
                }

                PrivacyPageMode.About -> {
                    InfoCard(
                        title = "Skip $versionName",
                        body = "一个本地化的 Android 辅助点击工具。"
                    )
                    InfoCard(
                        title = "项目定位",
                        body = "减少重复点击，不破解广告，不绕过其他 App 的安全机制。"
                    )
                }
            }
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
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

enum class PrivacyPageMode(val title: String) {
    Privacy("隐私说明"),
    Safety("安全保护"),
    About("关于")
}
