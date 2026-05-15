package com.example.skip.model

import com.example.skip.engine.SafetyGuard

data class AppPolicy(
    val packageName: String,
    val defaultRuleEnabled: Boolean = true,
    val customRulesEnabled: Boolean = true,
    val migratedFromBlacklist: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        fun defaultFor(packageName: String): AppPolicy {
            return AppPolicy(packageName = packageName.trim())
        }

        fun fromLegacyBlacklist(packageName: String): AppPolicy {
            return AppPolicy(
                packageName = packageName.trim(),
                defaultRuleEnabled = false,
                customRulesEnabled = true,
                migratedFromBlacklist = true
            )
        }

        fun effectiveFor(
            policy: AppPolicy,
            packageName: String,
            selfPackageName: String
        ): EffectiveAppPolicy {
            val cleanPackage = packageName.trim()
            val blocked = cleanPackage == selfPackageName.trim() ||
                SafetyGuard.isProtectedPackage(cleanPackage)
            return EffectiveAppPolicy(
                packageName = cleanPackage,
                defaultRuleEnabled = policy.defaultRuleEnabled && !blocked,
                customRulesEnabled = policy.customRulesEnabled && !blocked,
                blockedBySafety = blocked
            )
        }
    }
}

data class EffectiveAppPolicy(
    val packageName: String,
    val defaultRuleEnabled: Boolean,
    val customRulesEnabled: Boolean,
    val blockedBySafety: Boolean
)
