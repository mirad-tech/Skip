package com.example.skip.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.skip.data.IconManager
import com.example.skip.data.SettingsRepository
import com.example.skip.ui.common.AppIconView
import com.example.skip.ui.theme.SkipTheme

@Composable
fun HomeScreen(
    serviceEnabled: Boolean,
    masterEnabled: Boolean,
    safetyModeEnabled: Boolean,
    onEnableService: () -> Unit,
    onDisableService: () -> Unit,
    onOpenMore: () -> Unit
) {
    val context = LocalContext.current
    val skipEnabled = serviceEnabled && masterEnabled
    val displaySafetyMode = safetyModeEnabled || SettingsRepository.isSafetyModeEnabled(context)
    val iconScheme = remember(context) {
        IconManager.currentScheme(context)
    }
    val icon = remember(context, iconScheme.iconRes) {
        context.getDrawable(iconScheme.iconRes)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 28.dp)
        ) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                AppIconView(icon, "Skip", size = 96.dp)
                Button(
                    modifier = Modifier.width(120.dp),
                    onClick = if (skipEnabled) {
                        onDisableService
                    } else {
                        onEnableService
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (skipEnabled) {
                            Color(0xFF6B7280)
                        } else {
                            Color(iconScheme.previewColor)
                        },
                        contentColor = Color.White,
                    )
                ) {
                    Text(if (skipEnabled) "关闭服务" else "开启服务")
                }
            }

            if (displaySafetyMode) {
                Text(
                    modifier = Modifier.align(Alignment.TopCenter),
                    text = "当前为安全模式：仅记录，不会自动点击",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            FilledTonalButton(
                modifier = Modifier.align(Alignment.BottomCenter),
                onClick = onOpenMore
            ) {
                Text("更多")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    SkipTheme {
        HomeScreen(
            serviceEnabled = false,
            masterEnabled = true,
            safetyModeEnabled = false,
            onEnableService = {},
            onDisableService = {},
            onOpenMore = {}
        )
    }
}
