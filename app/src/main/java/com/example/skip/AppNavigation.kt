package com.example.skip

internal enum class AppDetailReturnTarget {
    InstalledApps,
    Blacklist
}

internal fun AppDetailReturnTarget.toScreen(): AppScreen {
    return when (this) {
        AppDetailReturnTarget.InstalledApps -> AppScreen.InstalledApps
        AppDetailReturnTarget.Blacklist -> AppScreen.Blacklist
    }
}

internal fun previousScreen(screen: AppScreen): AppScreen {
    return when (screen) {
        AppScreen.Home -> AppScreen.Home
        is AppScreen.Onboarding -> screen.declineTarget
        is AppScreen.AccessibilityPurpose -> screen.returnScreen
        AppScreen.More -> AppScreen.Home
        AppScreen.InstalledApps,
        AppScreen.Blacklist,
        AppScreen.Keywords,
        AppScreen.RuleList,
        AppScreen.RuleFormat -> AppScreen.AppHub
        is AppScreen.DefaultRuleInfo -> screen.returnScreen
        is AppScreen.Permissions -> screen.returnScreen
        AppScreen.SystemCompat -> AppScreen.SystemHub
        is AppScreen.Privacy -> screen.returnScreen
        is AppScreen.Safety -> screen.returnScreen
        AppScreen.Logs,
        AppScreen.Stats,
        AppScreen.RuleLogs -> AppScreen.DataHub
        AppScreen.IconAppearance,
        AppScreen.AppHub,
        AppScreen.SystemHub,
        AppScreen.DataHub -> AppScreen.More
        is AppScreen.About -> screen.returnScreen
        is AppScreen.AppDetail -> screen.returnTarget.toScreen()
        is AppScreen.CreateRule -> appDetailOrHub(screen.packageName, screen.returnTarget)
        is AppScreen.JsonImport -> appDetailOrHub(screen.returnPackageName, screen.returnTarget)
    }
}

internal fun appDetailOrHub(
    packageName: String?,
    returnTarget: AppDetailReturnTarget?
): AppScreen {
    return packageName?.let {
        AppScreen.AppDetail(
            packageName = it,
            returnTarget = returnTarget ?: AppDetailReturnTarget.InstalledApps
        )
    } ?: AppScreen.AppHub
}

internal data class EnableAutomationDecision(
    val enableMaster: Boolean,
    val nextScreen: AppScreen
)

internal fun enableAutomationDecision(
    releaseDisclosureAccepted: Boolean,
    serviceEnabled: Boolean
): EnableAutomationDecision {
    return when {
        !releaseDisclosureAccepted -> EnableAutomationDecision(
            enableMaster = false,
            nextScreen = AppScreen.Onboarding()
        )

        serviceEnabled -> EnableAutomationDecision(
            enableMaster = true,
            nextScreen = AppScreen.Home
        )

        else -> EnableAutomationDecision(
            enableMaster = false,
            nextScreen = AppScreen.AccessibilityPurpose()
        )
    }
}

internal data class AccessibilitySettingsDecision(
    val enableMaster: Boolean,
    val returnScreen: AppScreen
)

internal fun accessibilitySettingsDecision(
    returnScreen: AppScreen
): AccessibilitySettingsDecision {
    return AccessibilitySettingsDecision(
        enableMaster = returnScreen == AppScreen.Home,
        returnScreen = returnScreen
    )
}

internal sealed interface AppScreen {
    data object Home : AppScreen
    data class Onboarding(
        val nextAfterAccept: AppScreen = AccessibilityPurpose(),
        val declineTarget: AppScreen = Home
    ) : AppScreen
    data class AccessibilityPurpose(val returnScreen: AppScreen = Home) : AppScreen
    data object More : AppScreen
    data object InstalledApps : AppScreen
    data object Blacklist : AppScreen
    data class AppDetail(
        val packageName: String,
        val returnTarget: AppDetailReturnTarget
    ) : AppScreen
    data object IconAppearance : AppScreen
    data class DefaultRuleInfo(val returnScreen: AppScreen = AppHub) : AppScreen
    data object AppHub : AppScreen
    data object SystemHub : AppScreen
    data object DataHub : AppScreen
    data object Keywords : AppScreen
    data object Logs : AppScreen
    data object RuleLogs : AppScreen
    data object Stats : AppScreen
    data class CreateRule(
        val ruleId: String?,
        val packageName: String?,
        val returnTarget: AppDetailReturnTarget?,
        val precise: Boolean = false,
        val initialActivityName: String = "",
        val initialText: String = "",
        val initialDescription: String = "",
        val initialViewId: String = "",
        val initialArea: String = ""
    ) : AppScreen
    data class JsonImport(
        val returnPackageName: String?,
        val returnTarget: AppDetailReturnTarget?
    ) : AppScreen
    data object RuleList : AppScreen
    data object RuleFormat : AppScreen
    data object SystemCompat : AppScreen
    data class Permissions(val returnScreen: AppScreen = SystemHub) : AppScreen
    data class Privacy(val returnScreen: AppScreen = DataHub) : AppScreen
    data class Safety(val returnScreen: AppScreen = DataHub) : AppScreen
    data class About(val returnScreen: AppScreen = More) : AppScreen
}
