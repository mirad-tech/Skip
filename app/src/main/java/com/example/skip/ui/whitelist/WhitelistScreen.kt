package com.example.skip.ui.whitelist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.example.skip.data.SettingsRepository
import com.example.skip.ui.common.SimpleScreenScaffold
import com.example.skip.util.PackageUtil

@Composable
fun WhitelistScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var packages by remember { mutableStateOf(SettingsRepository.getWhitelistPackages(context)) }
    var input by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    SimpleScreenScaffold(
        title = "App 白名单",
        onBack = onBack
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = "只有加入白名单的 App 才会启用自动点击。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.trim() },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("包名") },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false
                    )
                )
                Button(
                    onClick = {
                        if (!PackageUtil.isLikelyPackageName(input)) {
                            message = "请输入完整包名"
                            return@Button
                        }
                        packages = (packages + input).distinct().sorted()
                        SettingsRepository.saveWhitelistPackages(context, packages)
                        input = ""
                        message = "已添加"
                    }
                ) {
                    Text("添加")
                }
            }

            message?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (packages.isEmpty()) {
                EmptyCard("还没有白名单 App。")
            } else {
                packages.forEach { packageName ->
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = packageName,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            TextButton(
                                onClick = {
                                    packages = packages.filterNot { it == packageName }
                                    SettingsRepository.saveWhitelistPackages(context, packages)
                                }
                            ) {
                                Text("删除")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyCard(text: String) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
