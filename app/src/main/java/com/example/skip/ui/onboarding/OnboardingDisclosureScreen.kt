package com.example.skip.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.skip.ui.common.SimpleScreenScaffold

@Composable
fun OnboardingDisclosureScreen(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenPermissions: () -> Unit
) {
    var checked by remember { mutableStateOf(false) }

    SimpleScreenScaffold(
        title = "上线前使用说明",
        onBack = onDecline
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = "请先确认 Skip 的用途和边界",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            ReleaseDisclosureCopy.disclosureItems.forEach { item ->
                Text(
                    text = "• $item",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { checked = it }
                )
                Text(
                    text = "我已阅读并同意以上说明",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = checked,
                onClick = onAccept
            ) {
                Text("继续查看无障碍用途")
            }
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenPrivacy
            ) {
                Text("查看隐私说明")
            }
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenPermissions
            ) {
                Text("查看权限说明")
            }
            TextButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                onClick = onDecline
            ) {
                Text("暂不同意，先返回")
            }
        }
    }
}
