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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.skip.data.IconManager

enum class MoreDestination {
    AppHub,
    InstalledApps,
    Blacklist,
    IconAppearance,
    DefaultRuleInfo,
    SystemHub,
    DataHub,
    Keywords,
    RuleList,
    RuleFormat,
    SystemCompat,
    AccessibilitySettings,
    BatterySettings,
    RuleLogs,
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
    val currentIcon = remember { IconManager.currentScheme(context).name }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("更多") }
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
            MoreItem("应用管理", "应用与规则") {
                onOpenDestination(MoreDestination.AppHub)
            }
            MoreItem("图标与外观", currentIcon) {
                onOpenDestination(MoreDestination.IconAppearance)
            }
            MoreItem("系统与权限", "授权与兼容") {
                onOpenDestination(MoreDestination.SystemHub)
            }
            MoreItem("日志与隐私", "记录与说明") {
                onOpenDestination(MoreDestination.DataHub)
            }
            MoreItem("关于", "版本与说明") {
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
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
    }
}
