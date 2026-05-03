package com.example.skip.ui.icons

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.skip.data.IconManager
import com.example.skip.data.IconScheme
import com.example.skip.ui.common.SimpleScreenScaffold

@Composable
fun IconAppearanceScreen(
    onBack: () -> Unit,
    onApplyAndExit: (IconScheme) -> Boolean
) {
    val context = LocalContext.current
    var applied by remember { mutableStateOf(IconManager.currentScheme(context)) }
    var selected by remember { mutableStateOf(applied) }
    var message by remember { mutableStateOf<String?>(null) }
    val hasPendingChange = selected.id != applied.id

    SimpleScreenScaffold(title = "图标与外观", onBack = onBack) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "当前：${applied.name}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconManager.schemes.forEach { scheme ->
                IconSchemeRow(
                    scheme = scheme,
                    selected = selected.id == scheme.id,
                    applied = applied.id == scheme.id,
                    onClick = {
                        selected = scheme
                        message = null
                    }
                )
            }
            Button(
                enabled = hasPendingChange,
                onClick = {
                    val success = onApplyAndExit(selected)
                    if (success) {
                        applied = selected
                    } else {
                        message = "切换失败，请重试"
                    }
                },
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(140.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(selected.previewColor),
                    contentColor = Color.White
                )
            ) {
                Text(if (hasPendingChange) "应用并退出" else "当前已启用")
            }
            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun IconSchemeRow(
    scheme: IconScheme,
    selected: Boolean,
    applied: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(scheme.previewColor)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Skip",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(scheme.name, fontWeight = FontWeight.Medium)
                Text(
                    when {
                        applied -> "当前启用"
                        selected -> "待应用"
                        else -> "可选择"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
