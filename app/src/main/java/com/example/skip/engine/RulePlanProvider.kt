package com.example.skip.engine

import com.example.skip.model.AppPolicy
import com.example.skip.model.ClickLogStage
import com.example.skip.model.RuleKind
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
        builtInRule: SkipRule?,
        currentActivity: String = "",
        builtInPreciseRules: List<SkipRule> = emptyList()
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
        val preciseRules = if (currentActivity.isNotBlank()) {
            val customPrecise = enabledCustomRules.filter {
                it.kind == RuleKind.Precise && it.activityName == currentActivity && PreciseRulePolicy.isValid(it)
            }
            val builtInPrecise = if (effective.defaultRuleEnabled) {
                builtInPreciseRules.filter {
                    it.enabled && it.kind == RuleKind.Precise && it.activityName == currentActivity &&
                        PreciseRulePolicy.isValid(it)
                }
            } else emptyList()
            (customPrecise + builtInPrecise).sortedWith(preciseRuleComparator)
        } else emptyList()
        if (preciseRules.isNotEmpty()) {
            return RulePlan(
                rules = preciseRules,
                customRules = preciseRules.filter { it.source != RuleSource.BuiltIn },
                builtInRule = null,
                scope = "precise_takeover",
                skipStage = null,
                failureReason = "",
                effectiveWindowMs = preciseRules.maxOf { it.validDurationMs },
                builtInPreciseRules = preciseRules.filter { it.source == RuleSource.BuiltIn }
            )
        }

        val standardCustomRules = enabledCustomRules.filter { it.kind == RuleKind.Standard }
        val rules = (standardCustomRules + listOfNotNull(enabledBuiltInRule))
            .sortedWith(standardRuleComparator)

        if (rules.isEmpty()) {
            return RulePlan(
                rules = emptyList(),
                customRules = standardCustomRules,
                builtInRule = enabledBuiltInRule,
                scope = "app_policy_disabled",
                skipStage = ClickLogStage.SkippedByBlacklist,
                failureReason = "app_policy_disabled"
            )
        }

        return RulePlan(
            rules = rules,
            customRules = standardCustomRules,
            builtInRule = enabledBuiltInRule,
            scope = when {
                standardCustomRules.isNotEmpty() && enabledBuiltInRule != null -> "custom_and_default"
                standardCustomRules.isNotEmpty() -> "custom_only"
                else -> "default_splash_only"
            },
            skipStage = null,
            failureReason = "",
            effectiveWindowMs = 8_000L
        )
    }

    internal fun isBuiltInDefaultRuleDisabledPackage(packageName: String): Boolean {
        return packageName.trim().lowercase() in builtInDefaultRuleDisabledPackages
    }

    private fun sourceRank(source: RuleSource): Int = when (source) {
        RuleSource.UserSimple -> 0
        RuleSource.JsonFile, RuleSource.Subscription -> 1
        RuleSource.BuiltIn -> 2
    }

    private val preciseRuleComparator = compareByDescending<SkipRule> { it.priority }
        .thenBy { sourceRank(it.source) }
        .thenBy { it.createdAt }
        .thenBy { it.id }

    private val standardRuleComparator = compareByDescending<SkipRule> { it.priority }
        .thenBy { it.createdAt }
        .thenBy { it.id }
}

data class RulePlan(
    val rules: List<SkipRule>,
    val customRules: List<SkipRule>,
    val builtInRule: SkipRule?,
    val scope: String,
    val skipStage: ClickLogStage?,
    val failureReason: String,
    val effectiveWindowMs: Long = 8_000L,
    val builtInPreciseRules: List<SkipRule> = emptyList()
)
