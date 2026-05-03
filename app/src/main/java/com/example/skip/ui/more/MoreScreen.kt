package com.example.skip.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.example.skip.data.SettingsRepository

enum class MoreDestination {
    Whitelist,
    Keywords,
    Logs,
    Safety,
    Privacy,
    About
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    onBack: () -> Unit,
    onOpenDestination: (MoreDestination) -> Unit
) {
    val context = LocalContext.current
    var masterEnabled by remember { mutableStateOf(SettingsRepository.isMasterEnabled(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("更多") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "自动跳过",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (masterEnabled) "总开关已启用" else "总开关已关闭",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = masterEnabled,
                        onCheckedChange = { enabled ->
                            masterEnabled = enabled
                            SettingsRepository.setMasterEnabled(context, enabled)
                        }
                    )
                }
            }

            MoreItem("App 白名单", "仅对白名单 App 生效") {
                onOpenDestination(MoreDestination.Whitelist)
            }
            MoreItem("关键词规则", "管理跳过、Skip 等本地规则") {
                onOpenDestination(MoreDestination.Keywords)
            }
            MoreItem("点击日志", "只记录时间、包名和命中规则") {
                onOpenDestination(MoreDestination.Logs)
            }
            MoreItem("安全保护", "默认避开敏感类型 App") {
                onOpenDestination(MoreDestination.Safety)
            }
            MoreItem("隐私说明", "无障碍服务的使用边界") {
                onOpenDestination(MoreDestination.Privacy)
            }
            MoreItem("关于", "版本与项目说明") {
                onOpenDestination(MoreDestination.About)
            }
        }
    }
}

@Composable
private fun MoreItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "进入",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
