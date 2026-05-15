package com.example.skip.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.skip.data.IconManager
import com.example.skip.data.SettingsRepository
import com.example.skip.ui.theme.SkipTheme

@Composable
fun HomeScreen(
    serviceEnabled: Boolean,
    masterEnabled: Boolean,
    safetyModeEnabled: Boolean,
    onEnableService: () -> Unit,
    onDisableService: () -> Unit,
    onOpenMore: () -> Unit
) {
    val context = LocalContext.current
    val skipEnabled = serviceEnabled && masterEnabled
    val displaySafetyMode = safetyModeEnabled || SettingsRepository.isSafetyModeEnabled(context)
    val iconScheme = remember(context) { IconManager.currentScheme(context) }
    val brandColor = Color(iconScheme.previewColor)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp, vertical = 34.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(brandColor),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(IconManager.homeImageRes),
                        contentDescription = "Skip",
                        modifier = Modifier.size(58.dp)
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = "Skip",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "开屏页面助手",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            StatusCard(
                title = if (skipEnabled) "服务运行中" else "服务未开启",
                body = when {
                    displaySafetyMode -> "安全模式开启：仅记录命中结果，不会自动点击。"
                    skipEnabled -> "仅在应用打开后的短时间内辅助点击明确的开屏页面控件。"
                    else -> "开启无障碍服务后，Skip 才会开始处理本地开屏页面。"
                },
                accentColor = if (skipEnabled) brandColor else MaterialTheme.colorScheme.outline
            )

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = if (skipEnabled) onDisableService else onEnableService,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (skipEnabled) MaterialTheme.colorScheme.secondary else brandColor,
                    contentColor = Color.White
                )
            ) {
                Text(if (skipEnabled) "关闭服务" else "开启服务")
            }

            FilledTonalButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenMore
            ) {
                Text("更多设置")
            }

            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "所有规则、日志和统计都保存在本机。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    body: String,
    accentColor: Color
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(accentColor)
            )
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    SkipTheme {
        HomeScreen(
            serviceEnabled = false,
            masterEnabled = true,
            safetyModeEnabled = false,
            onEnableService = {},
            onDisableService = {},
            onOpenMore = {}
        )
    }
}
