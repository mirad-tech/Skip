package com.example.skip

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.skip.data.IconManager
import com.example.skip.data.IconScheme
import com.example.skip.data.RuleRepository
import com.example.skip.data.SettingsRepository
import com.example.skip.ui.about.AboutScreen
import com.example.skip.ui.apps.AppDetailScreen
import com.example.skip.ui.apps.BlacklistScreen
import com.example.skip.ui.apps.InstalledAppsScreen
import com.example.skip.ui.home.HomeScreen
import com.example.skip.ui.icons.IconAppearanceScreen
import com.example.skip.ui.keywords.KeywordScreen
import com.example.skip.ui.logs.ClickLogScreen
import com.example.skip.ui.logs.StatsScreen
import com.example.skip.ui.more.MoreDestination
import com.example.skip.ui.more.MoreHubScreen
import com.example.skip.ui.more.MoreHubType
import com.example.skip.ui.more.MoreScreen
import com.example.skip.ui.onboarding.AccessibilityPurposeScreen
import com.example.skip.ui.onboarding.OnboardingDisclosureScreen
import com.example.skip.ui.privacy.PrivacyPageMode
import com.example.skip.ui.privacy.PrivacyScreen
import com.example.skip.ui.rules.DefaultRuleInfoScreen
import com.example.skip.ui.rules.JsonImportScreen
import com.example.skip.ui.rules.RuleFormatScreen
import com.example.skip.ui.rules.RuleListScreen
import com.example.skip.ui.rules.SimpleRuleScreen
import com.example.skip.ui.system.SystemCompatScreen
import com.example.skip.ui.theme.SkipTheme
import com.example.skip.util.AccessibilityUtil
import com.example.skip.util.SettingsIntentUtils

class MainActivity : ComponentActivity() {
    private var serviceEnabled by mutableStateOf(false)
    private var masterEnabled by mutableStateOf(true)
    private var safetyModeEnabled by mutableStateOf(false)
    private var releaseDisclosureAccepted by mutableStateOf(false)
    private var currentScreen by mutableStateOf<AppScreen>(AppScreen.Home)
    private val appVersionName: String by lazy {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
    }
    private val appVersionCode: Long by lazy {
        packageManager.getPackageInfo(packageName, 0).longVersionCode
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SettingsRepository.migrateIconSchemeDefault(this)
        RuleRepository.disableRulesForPackage(this, packageName)
        IconManager.syncCurrentScheme(applicationContext)
        refreshRuntimeState()
        if (!releaseDisclosureAccepted) {
            currentScreen = AppScreen.Onboarding()
        }
        setContent {
            SkipTheme {
                BackHandler(enabled = currentScreen != AppScreen.Home) {
                    currentScreen = previousScreen(currentScreen)
                }

                when (val screen = currentScreen) {
                    AppScreen.Home -> HomeScreen(
                        serviceEnabled = serviceEnabled,
                        masterEnabled = masterEnabled,
                        safetyModeEnabled = safetyModeEnabled,
                        onEnableService = { requestEnableSkipService() },
                        onDisableService = { disableSkipService() },
                        onOpenMore = { currentScreen = AppScreen.More }
                    )

                    is AppScreen.Onboarding -> OnboardingDisclosureScreen(
                        onAccept = {
                            SettingsRepository.setReleaseDisclosureAccepted(this, true)
                            releaseDisclosureAccepted = true
                            currentScreen = screen.nextAfterAccept
                        },
                        onDecline = { currentScreen = screen.declineTarget },
                        onOpenPrivacy = { currentScreen = AppScreen.Privacy(returnScreen = screen) },
                        onOpenPermissions = { currentScreen = AppScreen.Permissions(returnScreen = screen) }
                    )

                    is AppScreen.AccessibilityPurpose -> AccessibilityPurposeScreen(
                        onBack = { currentScreen = screen.returnScreen },
                        onOpenSettings = { openAccessibilitySettingsAfterPurpose() },
                        onOpenPermissions = {
                            currentScreen = AppScreen.Permissions(returnScreen = screen)
                        }
                    )

                    AppScreen.More -> MoreScreen(
                        onBack = { currentScreen = AppScreen.Home },
                        onOpenDestination = { destination -> openMoreDestination(destination) }
                    )

                    AppScreen.InstalledApps -> InstalledAppsScreen(
                        onBack = { currentScreen = AppScreen.AppHub },
                        onOpenApp = { packageName ->
                            currentScreen = AppScreen.AppDetail(
                                packageName = packageName,
                                returnTarget = AppDetailReturnTarget.InstalledApps
                            )
                        }
                    )

                    AppScreen.Blacklist -> BlacklistScreen(
                        onBack = { currentScreen = AppScreen.AppHub },
                        onOpenApp = { packageName ->
                            currentScreen = AppScreen.AppDetail(
                                packageName = packageName,
                                returnTarget = AppDetailReturnTarget.Blacklist
                            )
                        }
                    )

                    is AppScreen.AppDetail -> AppDetailScreen(
                        packageName = screen.packageName,
                        onBack = { currentScreen = screen.returnTarget.toScreen() },
                        onAddRule = { packageName ->
                            currentScreen = AppScreen.CreateRule(
                                ruleId = null,
                                packageName = packageName,
                                returnTarget = screen.returnTarget
                            )
                        },
                        onImportJsonRule = { packageName ->
                            currentScreen = AppScreen.JsonImport(
                                returnPackageName = packageName,
                                returnTarget = screen.returnTarget
                            )
                        },
                        onDefaultRuleSettings = {
                            currentScreen = AppScreen.DefaultRuleInfo(returnScreen = screen)
                        },
                        onEditRule = { ruleId ->
                            currentScreen = AppScreen.CreateRule(
                                ruleId = ruleId,
                                packageName = screen.packageName,
                                returnTarget = screen.returnTarget
                            )
                        }
                    )

                    AppScreen.IconAppearance -> IconAppearanceScreen(
                        onBack = { currentScreen = AppScreen.More },
                        onApplyAndExit = { scheme -> applyIconAndExit(scheme) }
                    )

                    is AppScreen.DefaultRuleInfo -> DefaultRuleInfoScreen(
                        onBack = { currentScreen = screen.returnScreen }
                    )

                    AppScreen.Keywords -> KeywordScreen(
                        onBack = { currentScreen = AppScreen.AppHub }
                    )

                    AppScreen.AppHub -> MoreHubScreen(
                        type = MoreHubType.Apps,
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

                    AppScreen.Stats -> StatsScreen(
                        onBack = { currentScreen = AppScreen.DataHub }
                    )

                    is AppScreen.CreateRule -> SimpleRuleScreen(
                        editingRuleId = screen.ruleId,
                        initialPackageName = screen.packageName,
                        onBack = {
                            currentScreen = appDetailOrHub(screen.packageName, screen.returnTarget)
                        }
                    )

                    is AppScreen.JsonImport -> JsonImportScreen(
                        onBack = {
                            currentScreen = appDetailOrHub(screen.returnPackageName, screen.returnTarget)
                        }
                    )

                    AppScreen.RuleList -> RuleListScreen(
                        onBack = { currentScreen = AppScreen.AppHub },
                        onEditSimpleRule = { ruleId ->
                            currentScreen = AppScreen.CreateRule(
                                ruleId = ruleId,
                                packageName = null,
                                returnTarget = null
                            )
                        }
                    )

                    AppScreen.RuleFormat -> RuleFormatScreen(
                        onBack = { currentScreen = AppScreen.AppHub }
                    )

                    AppScreen.SystemCompat -> SystemCompatScreen(
                        onBack = { currentScreen = AppScreen.SystemHub }
                    )

                    is AppScreen.Permissions -> PrivacyScreen(
                        mode = PrivacyPageMode.Permissions,
                        versionName = appVersionName,
                        onBack = { currentScreen = screen.returnScreen }
                    )

                    is AppScreen.Privacy -> PrivacyScreen(
                        mode = PrivacyPageMode.Privacy,
                        versionName = appVersionName,
                        onBack = { currentScreen = screen.returnScreen }
                    )

                    is AppScreen.Safety -> PrivacyScreen(
                        mode = PrivacyPageMode.Safety,
                        versionName = appVersionName,
                        onBack = { currentScreen = screen.returnScreen }
                    )

                    is AppScreen.About -> AboutScreen(
                        versionName = appVersionName,
                        versionCode = appVersionCode,
                        onBack = { currentScreen = screen.returnScreen }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshRuntimeState()
    }

    private fun refreshRuntimeState() {
        serviceEnabled = AccessibilityUtil.isSkipServiceEnabled(this)
        masterEnabled = SettingsRepository.isMasterEnabled(this)
        safetyModeEnabled = SettingsRepository.isSafetyModeEnabled(this)
        releaseDisclosureAccepted = SettingsRepository.hasAcceptedReleaseDisclosure(this)
    }

    private fun requestEnableSkipService() {
        currentScreen = if (releaseDisclosureAccepted) {
            AppScreen.AccessibilityPurpose()
        } else {
            AppScreen.Onboarding()
        }
    }

    private fun openAccessibilitySettingsAfterPurpose() {
        if (!releaseDisclosureAccepted) {
            currentScreen = AppScreen.Onboarding()
            return
        }
        SettingsRepository.setMasterEnabled(this, true)
        masterEnabled = true
        if (!serviceEnabled) {
            startActivity(SettingsIntentUtils.accessibilityIntent())
        }
        currentScreen = AppScreen.Home
    }

    private fun disableSkipService() {
        SettingsRepository.setMasterEnabled(this, false)
        masterEnabled = false
    }

    private fun applyIconAndExit(scheme: IconScheme): Boolean {
        moveTaskToBack(true)
        val success = IconManager.applyScheme(applicationContext, scheme)
        if (success) {
            finishAndRemoveTask()
        }
        return success
    }

    private fun openMoreDestination(destination: MoreDestination) {
        currentScreen = when (destination) {
            MoreDestination.AppHub -> AppScreen.AppHub
            MoreDestination.InstalledApps -> AppScreen.InstalledApps
            MoreDestination.Blacklist -> AppScreen.Blacklist
            MoreDestination.IconAppearance -> AppScreen.IconAppearance
            MoreDestination.DefaultRuleInfo -> AppScreen.DefaultRuleInfo(returnScreen = AppScreen.AppHub)
            MoreDestination.SystemHub -> AppScreen.SystemHub
            MoreDestination.DataHub -> AppScreen.DataHub
            MoreDestination.Keywords -> AppScreen.Keywords
            MoreDestination.RuleList -> AppScreen.RuleList
            MoreDestination.RuleFormat -> AppScreen.RuleFormat
            MoreDestination.SystemCompat -> AppScreen.SystemCompat
            MoreDestination.AccessibilitySettings -> {
                if (releaseDisclosureAccepted) {
                    AppScreen.AccessibilityPurpose(returnScreen = AppScreen.SystemHub)
                } else {
                    AppScreen.Onboarding(
                        nextAfterAccept = AppScreen.AccessibilityPurpose(
                            returnScreen = AppScreen.SystemHub
                        ),
                        declineTarget = AppScreen.SystemHub
                    )
                }
            }
            MoreDestination.BatterySettings -> {
                startActivity(SettingsIntentUtils.batteryOptimizationIntent(this))
                AppScreen.SystemHub
            }
            MoreDestination.Permissions -> AppScreen.Permissions(returnScreen = AppScreen.SystemHub)
            MoreDestination.RuleLogs -> AppScreen.RuleLogs
            MoreDestination.Logs -> AppScreen.Logs
            MoreDestination.Stats -> AppScreen.Stats
            MoreDestination.Safety -> AppScreen.Safety(returnScreen = AppScreen.DataHub)
            MoreDestination.Privacy -> AppScreen.Privacy(returnScreen = AppScreen.DataHub)
            MoreDestination.About -> AppScreen.About(returnScreen = AppScreen.More)
        }
    }
}
