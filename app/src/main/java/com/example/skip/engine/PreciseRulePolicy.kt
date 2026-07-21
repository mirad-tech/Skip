package com.example.skip.engine

import com.example.skip.model.MatchMode
import com.example.skip.model.RuleArea
import com.example.skip.model.RuleKind
import com.example.skip.model.SkipRule

object PreciseRulePolicy {
    const val MIN_WINDOW_MS = 3_000L
    const val MAX_WINDOW_MS = 15_000L
    const val MIN_COOLDOWN_MS = 800L

    fun validationError(rule: SkipRule): String? {
        if (rule.kind != RuleKind.Precise) return null
        if (rule.packageName.isBlank() || rule.packageName == "*") return "精确规则必须绑定具体包名"
        if (rule.activityName.isBlank() || rule.activityName == "*") return "精确规则必须绑定具体 Activity"
        if (rule.textMatchMode != MatchMode.Exact ||
            rule.contentDescriptionMatchMode != MatchMode.Exact ||
            rule.viewIdMatchMode != MatchMode.Exact
        ) return "精确规则的匹配模式必须全部为 exact"
        if (rule.cooldownMs < MIN_COOLDOWN_MS) return "精确规则点击间隔不能低于 800ms"

        val classA = rule.matchViewIds.any(::isCompleteViewId) && rule.minScore >= 70
        val hasExactLabel = rule.matchTexts.any { it.isNotBlank() } ||
            rule.matchContentDescriptions.any { it.isNotBlank() }
        val classB = hasExactLabel && rule.area != RuleArea.Any && rule.minScore >= 80
        return if (classA || classB) null else {
            "精确规则需要完整 View ID 且最低分 70，或精确文本/描述、明确区域且最低分 80"
        }
    }

    fun isValid(rule: SkipRule): Boolean = validationError(rule) == null

    fun candidateEvidenceError(
        rule: SkipRule,
        matchedViewIdRule: String?,
        matchedTextRule: String?,
        matchedDescriptionRule: String?,
        candidateArea: RuleArea
    ): String? {
        if (rule.kind != RuleKind.Precise) return null
        val actualClassA = rule.minScore >= 70 &&
            matchedViewIdRule?.let(::isCompleteViewId) == true
        if (actualClassA) return null

        val hasActualExactLabel = matchedTextRule != null || matchedDescriptionRule != null
        val classBConfigured = hasActualExactLabel && rule.area != RuleArea.Any && rule.minScore >= 80
        if (classBConfigured && candidateArea == rule.area) return null
        if (classBConfigured) return "precise_area_mismatch"
        if (rule.matchViewIds.any(::isCompleteViewId)) return "precise_view_id_required"
        return "precise_candidate_evidence_missing"
    }

    fun canonicalWindowMs(requestedWindowMs: Long): Long =
        requestedWindowMs.coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS)

    fun isCompleteViewId(value: String): Boolean {
        val clean = value.trim()
        return clean.contains(":id/") && clean.substringBefore(":id/").isNotBlank() &&
            clean.substringAfter(":id/").isNotBlank()
    }
}
