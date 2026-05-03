package com.example.skip.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class MoreHubType(val title: String) {
    Rules("规则"),
    System("系统与权限"),
    Data("日志与隐私")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreHubScreen(
    type: MoreHubType,
    onBack: () -> Unit,
    onOpenDestination: (MoreDestination) -> Unit
) {
    val items = when (type) {
        MoreHubType.Rules -> listOf(
            HubItem("创建规则", "普通表单", MoreDestination.CreateRule),
            HubItem("导入 JSON", "高级规则", MoreDestination.JsonImport),
            HubItem("规则列表", "启用和删除", MoreDestination.RuleList),
            HubItem("关键词", "默认匹配词", MoreDestination.Keywords),
            HubItem("白名单", "目标 App", MoreDestination.Whitelist),
            HubItem("格式说明", "JSON 示例", MoreDestination.RuleFormat)
        )
        MoreHubType.System -> listOf(
            HubItem("兼容诊断", "ROM 和服务状态", MoreDestination.SystemCompat),
            HubItem("无障碍设置", "系统授权", MoreDestination.AccessibilitySettings),
            HubItem("电池设置", "后台限制", MoreDestination.BatterySettings),
            HubItem("通知设置", "权限入口", MoreDestination.NotificationSettings)
        )
        MoreHubType.Data -> listOf(
            HubItem("点击日志", "结果记录", MoreDestination.Logs),
            HubItem("规则日志", "创建和导入", MoreDestination.RuleLogs),
            HubItem("安全保护", "敏感 App 默认避开", MoreDestination.Safety),
            HubItem("隐私说明", "本地处理", MoreDestination.Privacy)
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(type.title) },
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
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items.forEach { item ->
                Card(
                    onClick = { onOpenDestination(item.destination) },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(item.title, fontWeight = FontWeight.Medium)
                        Text(
                            item.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private data class HubItem(
    val title: String,
    val subtitle: String,
    val destination: MoreDestination
)
