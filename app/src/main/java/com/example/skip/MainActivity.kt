package com.example.skip

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.skip.ui.home.HomeScreen
import com.example.skip.ui.keywords.KeywordScreen
import com.example.skip.ui.logs.ClickLogScreen
import com.example.skip.ui.more.MoreDestination
import com.example.skip.ui.more.MoreScreen
import com.example.skip.ui.privacy.PrivacyPageMode
import com.example.skip.ui.privacy.PrivacyScreen
import com.example.skip.ui.theme.SkipTheme
import com.example.skip.ui.whitelist.WhitelistScreen
import com.example.skip.util.AccessibilityUtil

class MainActivity : ComponentActivity() {
    private var serviceEnabled by mutableStateOf(false)
    private var currentScreen by mutableStateOf<AppScreen>(AppScreen.Home)
    private val appVersionName: String by lazy {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshAccessibilityState()
        setContent {
            SkipTheme {
                BackHandler(enabled = currentScreen != AppScreen.Home) {
                    currentScreen = when (currentScreen) {
                        AppScreen.Home -> AppScreen.Home
                        AppScreen.More -> AppScreen.Home
                        else -> AppScreen.More
                    }
                }

                when (val screen = currentScreen) {
                    AppScreen.Home -> HomeScreen(
                        serviceEnabled = serviceEnabled,
                        onOpenAccessibilitySettings = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onOpenMore = { currentScreen = AppScreen.More }
                    )

                    AppScreen.More -> MoreScreen(
                        onBack = { currentScreen = AppScreen.Home },
                        onOpenDestination = { destination ->
                            currentScreen = when (destination) {
                                MoreDestination.Whitelist -> AppScreen.Whitelist
                                MoreDestination.Keywords -> AppScreen.Keywords
                                MoreDestination.Logs -> AppScreen.Logs
                                MoreDestination.Safety -> AppScreen.Safety
                                MoreDestination.Privacy -> AppScreen.Privacy
                                MoreDestination.About -> AppScreen.About
                            }
                        }
                    )

                    AppScreen.Whitelist -> WhitelistScreen(
                        onBack = { currentScreen = AppScreen.More }
                    )

                    AppScreen.Keywords -> KeywordScreen(
                        onBack = { currentScreen = AppScreen.More }
                    )

                    AppScreen.Logs -> ClickLogScreen(
                        onBack = { currentScreen = AppScreen.More }
                    )

                    AppScreen.Privacy -> PrivacyScreen(
                        mode = PrivacyPageMode.Privacy,
                        versionName = appVersionName,
                        onBack = { currentScreen = AppScreen.More }
                    )

                    AppScreen.Safety -> PrivacyScreen(
                        mode = PrivacyPageMode.Safety,
                        versionName = appVersionName,
                        onBack = { currentScreen = AppScreen.More }
                    )

                    AppScreen.About -> PrivacyScreen(
                        mode = PrivacyPageMode.About,
                        versionName = appVersionName,
                        onBack = { currentScreen = AppScreen.More }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAccessibilityState()
    }

    private fun refreshAccessibilityState() {
        serviceEnabled = AccessibilityUtil.isSkipServiceEnabled(this)
    }
}

private sealed interface AppScreen {
    data object Home : AppScreen
    data object More : AppScreen
    data object Whitelist : AppScreen
    data object Keywords : AppScreen
    data object Logs : AppScreen
    data object Privacy : AppScreen
    data object Safety : AppScreen
    data object About : AppScreen
}
