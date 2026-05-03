package com.example.skip

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.skip.ui.theme.SkipTheme

class MainActivity : ComponentActivity() {
    private val serviceEnabled: MutableState<Boolean> = mutableStateOf(false)
    private val appVersionName: String by lazy {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshAccessibilityState()
        setContent {
            SkipTheme {
                SkipHelperScreen(
                    serviceEnabled = serviceEnabled.value,
                    versionName = appVersionName,
                    onOpenAccessibilitySettings = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onRefreshServiceState = ::refreshAccessibilityState
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAccessibilityState()
    }

    private fun refreshAccessibilityState() {
        serviceEnabled.value = isSkipAccessibilityServiceEnabled()
    }

    private fun isSkipAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, SkipAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()

        return Settings.Secure.getInt(
            contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        ) == 1 && enabledServices
            .split(':')
            .any { it.equals(expected, ignoreCase = true) }
    }
}

@Composable
private fun SkipHelperScreen(
    serviceEnabled: Boolean,
    versionName: String,
    onOpenAccessibilitySettings: () -> Unit,
    onRefreshServiceState: () -> Unit
) {
    val context = LocalContext.current
    var masterEnabled by remember { mutableStateOf(SkipConfigStore.isMasterEnabled(context)) }
    var keywordsText by remember {
        mutableStateOf(SkipConfigStore.getKeywords(context).joinToString("\n"))
    }
    var viewIdKeywordsText by remember {
        mutableStateOf(SkipConfigStore.getViewIdKeywords(context).joinToString("\n"))
    }
    var whitelistText by remember {
        mutableStateOf(SkipConfigStore.getWhitelistPackages(context).sorted().joinToString("\n"))
    }
    var message by remember { mutableStateOf<String?>(null) }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Header(versionName = versionName)

            StatusSection(
                serviceEnabled = serviceEnabled,
                masterEnabled = masterEnabled,
                onMasterChanged = { enabled ->
                    masterEnabled = enabled
                    SkipConfigStore.setMasterEnabled(context, enabled)
                    message = if (enabled) "总开关已开启" else "总开关已关闭"
                },
                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                onRefreshServiceState = onRefreshServiceState
            )

            HorizontalDivider()

            ConfigSection(
                title = "关键词列表",
                value = keywordsText,
                placeholder = "每行一个可见文案，例如：跳过广告",
                onValueChange = { keywordsText = it },
                onSave = {
                    val keywords = parseConfigLines(keywordsText)
                    SkipConfigStore.setKeywords(context, keywords)
                    keywordsText = SkipConfigStore.getKeywords(context).joinToString("\n")
                    message = "关键词已保存，共 ${keywords.size} 条"
                }
            )

            ConfigSection(
                title = "控件 ID 规则",
                value = viewIdKeywordsText,
                placeholder = "每行一个控件 ID 片段，例如：splash_skip",
                onValueChange = { viewIdKeywordsText = it },
                onSave = {
                    val idKeywords = parseConfigLines(viewIdKeywordsText)
                    SkipConfigStore.setViewIdKeywords(context, idKeywords)
                    viewIdKeywordsText = SkipConfigStore.getViewIdKeywords(context).joinToString("\n")
                    message = "控件 ID 规则已保存，共 ${idKeywords.size} 条"
                }
            )

            ConfigSection(
                title = "白名单 App 包名",
                value = whitelistText,
                placeholder = "每行一个完整包名，例如：com.example.target",
                onValueChange = { whitelistText = it },
                onSave = {
                    val packages = parseConfigLines(whitelistText)
                    SkipConfigStore.setWhitelistPackages(context, packages)
                    whitelistText = SkipConfigStore.getWhitelistPackages(context)
                        .sorted()
                        .joinToString("\n")
                    message = "白名单已保存，共 ${packages.size} 个 App"
                }
            )

            SafetyNote()

            message?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun Header(versionName: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "开屏广告跳过助手",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "版本 $versionName，仅在你授权无障碍服务后，对白名单应用中的本地无障碍节点做关键词、控件 ID 匹配和点击。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatusSection(
    serviceEnabled: Boolean,
    masterEnabled: Boolean,
    onMasterChanged: (Boolean) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onRefreshServiceState: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (serviceEnabled) "无障碍服务已开启" else "无障碍服务未开启",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = if (serviceEnabled) "服务可以响应白名单应用窗口变化" else "需要到系统设置中手动授权",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = masterEnabled,
                onCheckedChange = onMasterChanged
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = onOpenAccessibilitySettings,
                modifier = Modifier.weight(1f)
            ) {
                Text("打开无障碍设置")
            }
            OutlinedButton(
                onClick = onRefreshServiceState,
                modifier = Modifier.weight(1f)
            ) {
                Text("刷新状态")
            }
        }
    }
}

@Composable
private fun ConfigSection(
    title: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp),
            placeholder = { Text(placeholder) },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false
            ),
            minLines = 5
        )
        Button(
            onClick = onSave,
            modifier = Modifier.align(Alignment.End)
        ) {
            Text("保存")
        }
    }
}

@Composable
private fun SafetyNote() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "安全边界",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = "本工具不上传屏幕内容，不请求短信、联系人、相册等无关权限；不会 Hook、Root、改包、抓包或绕过其他 App 安全机制。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "系统设置、支付、钱包、银行、证券、保险和金融类包名会被服务层拦截，即使加入白名单也不会自动点击。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun parseConfigLines(text: String): List<String> {
    return text
        .lineSequence()
        .flatMap { it.split(',', '，', ';', '；').asSequence() }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()
}

@Preview(showBackground = true)
@Composable
private fun SkipHelperScreenPreview() {
    SkipTheme {
        SkipHelperScreen(
            serviceEnabled = false,
            versionName = "1.1.0",
            onOpenAccessibilitySettings = {},
            onRefreshServiceState = {}
        )
    }
}
