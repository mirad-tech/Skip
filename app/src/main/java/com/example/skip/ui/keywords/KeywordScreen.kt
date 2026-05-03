package com.example.skip.ui.keywords

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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.example.skip.data.RuleRepository
import com.example.skip.ui.common.SimpleScreenScaffold

@Composable
fun KeywordScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var keywords by remember { mutableStateOf(RuleRepository.getKeywords(context)) }
    var input by remember { mutableStateOf("") }

    SimpleScreenScaffold(
        title = "关键词规则",
        onBack = onBack
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = "匹配无障碍节点的文字和描述，默认关键词可以保留。",
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
                    label = { Text("关键词") },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false
                    )
                )
                Button(
                    onClick = {
                        if (input.isBlank()) return@Button
                        keywords = (keywords + input).distinct().sortedWith(
                            compareBy(String.CASE_INSENSITIVE_ORDER) { it }
                        )
                        RuleRepository.saveKeywords(context, keywords)
                        input = ""
                    }
                ) {
                    Text("添加")
                }
            }

            keywords.forEach { keyword ->
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = keyword,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            if (keyword in RuleRepository.defaultKeywords) {
                                Text(
                                    text = "默认",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        TextButton(
                            enabled = keyword !in RuleRepository.defaultKeywords,
                            onClick = {
                                keywords = keywords.filterNot { it == keyword }
                                RuleRepository.saveKeywords(context, keywords)
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
