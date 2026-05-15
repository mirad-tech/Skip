package com.example.skip.data

import android.content.Context
import com.example.skip.engine.SafetyGuard
import com.example.skip.model.AppPolicy
import com.example.skip.model.InstalledApp
import com.example.skip.util.InstalledAppUtils

object InstalledAppRepository {
    fun loadApps(context: Context): List<InstalledAppStatus> {
        return InstalledAppUtils.loadLaunchableApps(context).map { app ->
            app.toStatus(context)
        }
    }

    fun resolve(context: Context, packageName: String): InstalledAppStatus {
        return InstalledAppUtils.resolveApp(context, packageName).toStatus(context)
    }

    private fun InstalledApp.toStatus(context: Context): InstalledAppStatus {
        val selfPackage = packageName == context.packageName
        val protected = SafetyGuard.isProtectedPackage(packageName)
        val policy = SettingsRepository.getAppPolicy(context, packageName)
        val effective = AppPolicy.effectiveFor(policy, packageName, context.packageName)
        val customRules = RuleRepository.getCustomRulesForPackage(context, packageName)
        val stats = StatsRepository.getStats(context).appStats.firstOrNull {
            it.packageName == packageName
        }
        return InstalledAppStatus(
            app = this,
            isBlacklisted = !policy.defaultRuleEnabled,
            appAssistanceEnabled = effective.defaultRuleEnabled || effective.customRulesEnabled,
            defaultSkipEnabled = effective.defaultRuleEnabled,
            customRulesEnabled = effective.customRulesEnabled,
            hasCustomRules = customRules.isNotEmpty(),
            customRuleCount = customRules.size,
            isProtected = protected,
            isSelfPackage = selfPackage,
            hitCount = stats?.totalCount ?: 0,
            successCount = stats?.successCount ?: 0
        )
    }
}

data class InstalledAppStatus(
    val app: InstalledApp,
    val isBlacklisted: Boolean,
    val appAssistanceEnabled: Boolean,
    val hasCustomRules: Boolean,
    val customRuleCount: Int,
    val defaultSkipEnabled: Boolean,
    val customRulesEnabled: Boolean = true,
    val isProtected: Boolean,
    val isSelfPackage: Boolean = false,
    val hitCount: Int = 0,
    val successCount: Int = 0
)
