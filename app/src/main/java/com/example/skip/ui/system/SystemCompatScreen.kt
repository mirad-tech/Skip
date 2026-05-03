package com.example.skip.ui.system

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.skip.data.SettingsRepository
import com.example.skip.ui.common.SimpleScreenScaffold
import com.example.skip.util.AccessibilityUtil
import com.example.skip.util.RomUtils
import com.example.skip.util.SettingsIntentUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SystemCompatScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var refreshKey by remember { mutableStateOf(0) }
    val deviceInfo = remember(refreshKey) { RomUtils.getDeviceInfo() }
    val serviceEnabled = remember(refreshKey) { AccessibilityUtil.isSkipServiceEnabled(context) }
    val batteryState = remember(refreshKey) {
        SettingsIntentUtils.isIgnoringBatteryOptimizations(context)
    }
    SimpleScreenScaffold(title = "系统兼容诊断", onBack = onBack) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard(
                title = "设备信息",
                lines = listOf(
                    "品牌：${deviceInfo.brand.ifBlank { deviceInfo.manufacturer }}",
                    "型号：${deviceInfo.model}",
                    "Android：${deviceInfo.androidVersion} / API ${deviceInfo.sdkInt}",
                    "系统识别：${deviceInfo.romType.label}"
                )
            )

            InfoCard(
                title = "服务状态",
                lines = listOf(
                    "无障碍服务：${if (serviceEnabled) "已开启" else "未开启"}",
                    "电池优化：${batteryState.toBatteryText()}",
                    "自启动状态：需要用户手动确认",
                    "最近服务连接：${formatTime(SettingsRepository.getServiceConnectedAt(context))}",
                    "最近服务活跃：${formatTime(SettingsRepository.getServiceActiveAt(context))}",
                    "最近服务中断：${formatTime(SettingsRepository.getServiceInterruptedAt(context))}",
                    "最近自动点击：${formatTime(SettingsRepository.getLastClickAt(context))}",
                    "最近失败原因：${SettingsRepository.getLastFailureReason(context).ifBlank { "暂无" }}"
                )
            )

            InfoCard(
                title = "${deviceInfo.romType.label} 建议",
                lines = RomUtils.guidanceFor(deviceInfo.romType)
            )

            Button(onClick = { refreshKey++ }) {
                Text("重新检查")
            }
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    lines: List<String>
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
            lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun Boolean?.toBatteryText(): String {
    return when (this) {
        true -> "已忽略电池优化"
        false -> "未忽略电池优化"
        null -> "无法检测，需要手动确认"
    }
}

private fun formatTime(timeMillis: Long): String {
    if (timeMillis <= 0L) return "暂无"
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timeMillis))
}
