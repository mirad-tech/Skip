package com.example.skip.ui.rules

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.skip.data.RuleImportManager
import com.example.skip.data.RuleLifecycleRepository
import com.example.skip.data.RuleRepository
import com.example.skip.model.DuplicateStrategy
import com.example.skip.model.RuleImportResult
import com.example.skip.ui.common.SimpleScreenScaffold

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JsonImportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var importResult by remember { mutableStateOf<RuleImportResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var strategy by remember { mutableStateOf(DuplicateStrategy.Override) }
    var showPreview by remember { mutableStateOf(false) }
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
        }.getOrElse {
            error = "读取文件失败：${it.message.orEmpty()}"
            return@rememberLauncherForActivityResult
        }
        val result = RuleLifecycleRepository.parseJsonImport(text = text, context = context)
        if (result.success) {
            importResult = result
            showPreview = true
            error = null
        } else {
            error = result.errorMessage
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(RuleRepository.exportRulesAsJson(context).toByteArray(Charsets.UTF_8))
            }
        }.onFailure {
            error = "导出失败：${it.message.orEmpty()}"
        }
    }

    SimpleScreenScaffold(title = "JSON 文件导入", onBack = onBack) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = "给懂规则格式的用户使用。导入前会先预览，不会直接写入。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { filePicker.launch("application/json") }
            ) {
                Text("选择 JSON 文件")
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { exportLauncher.launch("skip-rules.json") }
            ) {
                Text("导出当前规则")
            }

            Text("重复规则处理", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DuplicateStrategy.entries.forEach { item ->
                    FilterChip(
                        selected = strategy == item,
                        onClick = { strategy = item },
                        label = { Text(item.label) }
                    )
                }
            }

            error?.let {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = it,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }

    if (showPreview) {
        importResult?.let { result ->
            JsonPreviewDialog(
                result = result,
                strategy = strategy,
                onDismiss = { showPreview = false },
                onConfirm = {
                    RuleLifecycleRepository.saveJsonImport(context, result, strategy)
                    showPreview = false
                    onBack()
                }
            )
        }
    }
}

@Composable
private fun JsonPreviewDialog(
    result: RuleImportResult,
    strategy: DuplicateStrategy,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认导入") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RuleImportManager.previewImport(result, strategy).forEachIndexed { index, line ->
                    Text(
                        text = line,
                        fontWeight = if (index == 0) FontWeight.Medium else null,
                        color = if (line.startsWith("提示：") || line.startsWith("额外确认：")) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("确认导入")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
