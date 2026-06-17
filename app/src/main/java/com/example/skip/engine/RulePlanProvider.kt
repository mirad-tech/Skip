package com.example.skip.engine

import com.example.skip.model.AppPolicy
import com.example.skip.model.ClickLogStage
import com.example.skip.model.RuleSource
import com.example.skip.model.SkipRule

object RulePlanProvider {
    private val builtInDefaultRuleDisabledPackages = setOf(
        "com.android.chrome"
    )

    fun plan(
        packageName: String,
        selfPackageName: String,
        policy: AppPolicy,
        customRules: List<SkipRule>,
        builtInRule: SkipRule?
    ): RulePlan {
        val effective = AppPolicy.effectiveFor(policy, packageName, selfPackageName)
        if (effective.blockedBySafety) {
            return RulePlan(
                rules = emptyList(),
                customRules = emptyList(),
                builtInRule = null,
                scope = "safety_blocked",
                skipStage = ClickLogStage.SkippedBySafety,
                failureReason = "safety_guard_blocked"
            )
        }

        val enabledCustomRules = if (effective.customRulesEnabled) {
            customRules.filter { it.enabled && it.source != RuleSource.BuiltIn }
        } else {
            emptyList()
        }
        val enabledBuiltInRule = if (
            effective.defaultRuleEnabled &&
            !isBuiltInDefaultRuleDisabledPackage(effective.packageName)
        ) {
            builtInRule
        } else {
            null
        }
        val rules = (enabledCustomRules + listOfNotNull(enabledBuiltInRule))
            .sortedWith(compareByDescending<SkipRule> { it.priority }.thenBy { it.createdAt })

        if (rules.isEmpty()) {
            return RulePlan(
                rules = emptyList(),
                customRules = enabledCustomRules,
                builtInRule = enabledBuiltInRule,
                scope = "app_policy_disabled",
                skipStage = ClickLogStage.SkippedByBlacklist,
                failureReason = "app_policy_disabled"
            )
        }

        return RulePlan(
            rules = rules,
            customRules = enabledCustomRules,
            builtInRule = enabledBuiltInRule,
            scope = when {
                enabledCustomRules.isNotEmpty() && enabledBuiltInRule != null -> "custom_and_default"
                enabledCustomRules.isNotEmpty() -> "custom_only"
                else -> "default_splash_only"
            },
            skipStage = null,
            failureReason = ""
        )
    }

    internal fun isBuiltInDefaultRuleDisabledPackage(packageName: String): Boolean {
        return packageName.trim().lowercase() in builtInDefaultRuleDisabledPackages
    }
}

data class RulePlan(
    val rules: List<SkipRule>,
    val customRules: List<SkipRule>,
    val builtInRule: SkipRule?,
    val scope: String,
    val skipStage: ClickLogStage?,
    val failureReason: String
)
