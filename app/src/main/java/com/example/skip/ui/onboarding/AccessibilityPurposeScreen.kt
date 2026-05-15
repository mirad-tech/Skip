package com.example.skip.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.skip.ui.common.SimpleScreenScaffold

@Composable
fun AccessibilityPurposeScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPermissions: () -> Unit
) {
    SimpleScreenScaffold(
        title = "无障碍权限用途",
        onBack = onBack
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = "只有你主动点击下方按钮后，才会进入系统无障碍设置。",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            ReleaseDisclosureCopy.accessibilityPurposeItems.forEach { item ->
                Text(
                    text = "• $item",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenSettings
            ) {
                Text("去系统设置开启")
            }
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenPermissions
            ) {
                Text("查看完整权限说明")
            }
        }
    }
}
