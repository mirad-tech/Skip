package com.example.skip

import android.os.Bundle
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
import com.example.skip.ui.more.MoreHubScreen
import com.example.skip.ui.more.MoreHubType
import com.example.skip.ui.more.MoreScreen
import com.example.skip.ui.privacy.PrivacyPageMode
import com.example.skip.ui.privacy.PrivacyScreen
import com.example.skip.ui.rules.JsonImportScreen
import com.example.skip.ui.rules.RuleFormatScreen
import com.example.skip.ui.rules.RuleListScreen
import com.example.skip.ui.rules.SimpleRuleScreen
import com.example.skip.ui.system.SystemCompatScreen
import com.example.skip.ui.theme.SkipTheme
import com.example.skip.ui.whitelist.WhitelistScreen
import com.example.skip.util.AccessibilityUtil
import com.example.skip.util.SettingsIntentUtils

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
                            startActivity(SettingsIntentUtils.accessibilityIntent())
                        },
                        onOpenMore = { currentScreen = AppScreen.More }
                    )

                    AppScreen.More -> MoreScreen(
                        onBack = { currentScreen = AppScreen.Home },
                        onOpenDestination = { destination -> openMoreDestination(destination) }
                    )

                    AppScreen.Whitelist -> WhitelistScreen(
                        onBack = { currentScreen = AppScreen.RulesHub }
                    )

                    AppScreen.Keywords -> KeywordScreen(
                        onBack = { currentScreen = AppScreen.RulesHub }
                    )

                    AppScreen.RulesHub -> MoreHubScreen(
                        type = MoreHubType.Rules,
                        onBack = { currentScreen = AppScreen.More },
                        onOpenDestination = { destination -> openMoreDestination(destination) }
                    )

                    AppScreen.SystemHub -> MoreHubScreen(
                        type = MoreHubType.System,
                        onBack = { currentScreen = AppScreen.More },
                        onOpenDestination = { destination -> openMoreDestination(destination) }
                    )

                    AppScreen.DataHub -> MoreHubScreen(
                        type = MoreHubType.Data,
                        onBack = { currentScreen = AppScreen.More },
                        onOpenDestination = { destination -> openMoreDestination(destination) }
                    )

                    AppScreen.Logs -> ClickLogScreen(
                        showRuleLogs = false,
                        onBack = { currentScreen = AppScreen.DataHub }
                    )

                    AppScreen.RuleLogs -> ClickLogScreen(
                        showRuleLogs = true,
                        onBack = { currentScreen = AppScreen.DataHub }
                    )

                    is AppScreen.CreateRule -> SimpleRuleScreen(
                        editingRuleId = screen.ruleId,
                        onBack = { currentScreen = AppScreen.RulesHub }
                    )

                    AppScreen.JsonImport -> JsonImportScreen(
                        onBack = { currentScreen = AppScreen.RulesHub }
                    )

                    AppScreen.RuleList -> RuleListScreen(
                        onBack = { currentScreen = AppScreen.RulesHub },
                        onEditSimpleRule = { ruleId ->
                            currentScreen = AppScreen.CreateRule(ruleId)
                        }
                    )

                    AppScreen.RuleFormat -> RuleFormatScreen(
                        onBack = { currentScreen = AppScreen.RulesHub }
                    )

                    AppScreen.SystemCompat -> SystemCompatScreen(
                        onBack = { currentScreen = AppScreen.SystemHub }
                    )

                    AppScreen.Privacy -> PrivacyScreen(
                        mode = PrivacyPageMode.Privacy,
                        versionName = appVersionName,
                        onBack = { currentScreen = AppScreen.DataHub }
                    )

                    AppScreen.Safety -> PrivacyScreen(
                        mode = PrivacyPageMode.Safety,
                        versionName = appVersionName,
                        onBack = { currentScreen = AppScreen.DataHub }
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

    private fun openMoreDestination(destination: MoreDestination) {
        currentScreen = when (destination) {
            MoreDestination.RulesHub -> AppScreen.RulesHub
            MoreDestination.SystemHub -> AppScreen.SystemHub
            MoreDestination.DataHub -> AppScreen.DataHub
            MoreDestination.Whitelist -> AppScreen.Whitelist
            MoreDestination.Keywords -> AppScreen.Keywords
            MoreDestination.CreateRule -> AppScreen.CreateRule(null)
            MoreDestination.JsonImport -> AppScreen.JsonImport
            MoreDestination.RuleList -> AppScreen.RuleList
            MoreDestination.RuleFormat -> AppScreen.RuleFormat
            MoreDestination.SystemCompat -> AppScreen.SystemCompat
            MoreDestination.AccessibilitySettings -> {
                startActivity(SettingsIntentUtils.accessibilityIntent())
                AppScreen.SystemHub
            }
            MoreDestination.BatterySettings -> {
                startActivity(SettingsIntentUtils.batteryOptimizationIntent(this))
                AppScreen.SystemHub
            }
            MoreDestination.NotificationSettings -> {
                startActivity(SettingsIntentUtils.notificationIntent(this))
                AppScreen.SystemHub
            }
            MoreDestination.RuleLogs -> AppScreen.RuleLogs
            MoreDestination.Logs -> AppScreen.Logs
            MoreDestination.Safety -> AppScreen.Safety
            MoreDestination.Privacy -> AppScreen.Privacy
            MoreDestination.About -> AppScreen.About
        }
    }
}

private sealed interface AppScreen {
    data object Home : AppScreen
    data object More : AppScreen
    data object RulesHub : AppScreen
    data object SystemHub : AppScreen
    data object DataHub : AppScreen
    data object Whitelist : AppScreen
    data object Keywords : AppScreen
    data object Logs : AppScreen
    data object RuleLogs : AppScreen
    data class CreateRule(val ruleId: String?) : AppScreen
    data object JsonImport : AppScreen
    data object RuleList : AppScreen
    data object RuleFormat : AppScreen
    data object SystemCompat : AppScreen
    data object Privacy : AppScreen
    data object Safety : AppScreen
    data object About : AppScreen
}
