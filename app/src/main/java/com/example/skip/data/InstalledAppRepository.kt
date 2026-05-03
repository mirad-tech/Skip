package com.example.skip.data

import android.content.Context
import com.example.skip.engine.SafetyGuard
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
        val blacklisted = SettingsRepository.isBlacklisted(context, packageName)
        val selfPackage = packageName == context.packageName
        val protected = SafetyGuard.isProtectedPackage(packageName)
        val customRules = RuleRepository.getCustomRulesForPackage(context, packageName)
        return InstalledAppStatus(
            app = this,
            isBlacklisted = blacklisted,
            hasCustomRules = customRules.isNotEmpty(),
            customRuleCount = customRules.size,
            defaultSkipEnabled = !blacklisted && !protected && !selfPackage,
            isProtected = protected,
            isSelfPackage = selfPackage
        )
    }
}

data class InstalledAppStatus(
    val app: InstalledApp,
    val isBlacklisted: Boolean,
    val hasCustomRules: Boolean,
    val customRuleCount: Int,
    val defaultSkipEnabled: Boolean,
    val isProtected: Boolean,
    val isSelfPackage: Boolean = false
)
