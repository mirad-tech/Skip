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

enum class MoreHubType(val title: String) {
    Apps("应用管理"),
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
    val context = LocalContext.current
    val items = when (type) {
        MoreHubType.Apps -> listOf(
            HubItem("已安装应用", "搜索和配置", MoreDestination.InstalledApps),
            HubItem("黑名单", "关闭默认跳过", MoreDestination.Blacklist),
            HubItem("默认规则", "开屏模板", MoreDestination.DefaultRuleInfo),
            HubItem("规则列表", "启用和删除", MoreDestination.RuleList),
            HubItem("关键词", "默认匹配词", MoreDestination.Keywords),
            HubItem("格式说明", "JSON 示例", MoreDestination.RuleFormat)
        )
        MoreHubType.System -> listOf(
            HubItem("兼容诊断", "ROM 和服务状态", MoreDestination.SystemCompat),
            HubItem("无障碍设置", "系统授权", MoreDestination.AccessibilitySettings),
            HubItem("电池设置", "后台限制", MoreDestination.BatterySettings)
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
                title = { Text(type.title) }
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
            when (type) {
                MoreHubType.Apps -> {
                    var safetyModeEnabled by remember {
                        mutableStateOf(SettingsRepository.isSafetyModeEnabled(context))
                    }
                    SettingRow(
                        title = "安全模式",
                        subtitle = "安全模式下只记录命中结果，不会自动点击。",
                        checked = safetyModeEnabled,
                        onCheckedChange = { enabled ->
                            safetyModeEnabled = enabled
                            SettingsRepository.setSafetyModeEnabled(context, enabled)
                        }
                    )
                    if (safetyModeEnabled) {
                        Text(
                            text = "当前为安全模式：仅记录，不会自动点击",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                MoreHubType.Data -> {
                    var successToastEnabled by remember {
                        mutableStateOf(SettingsRepository.isSuccessToastEnabled(context))
                    }
                    var debugToastEnabled by remember {
                        mutableStateOf(SettingsRepository.isDebugToastEnabled(context))
                    }
                    SettingRow(
                        title = "成功提示",
                        subtitle = "跳过成功后短提示",
                        checked = successToastEnabled,
                        onCheckedChange = { enabled ->
                            successToastEnabled = enabled
                            SettingsRepository.setSuccessToastEnabled(context, enabled)
                        }
                    )
                    SettingRow(
                        title = "调试提示",
                        subtitle = "显示安全保护和失败提示",
                        checked = debugToastEnabled,
                        onCheckedChange = { enabled ->
                            debugToastEnabled = enabled
                            SettingsRepository.setDebugToastEnabled(context, enabled)
                        }
                    )
                }
                MoreHubType.System -> Unit
            }
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

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
