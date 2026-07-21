package com.example.skip.engine

import com.example.skip.model.RuleArea
import com.example.skip.model.RuleKind
import com.example.skip.model.RuleSource

internal data class ResolvedActionPath(
    val parentDepth: Int,
    val hasSafeClickableTarget: Boolean,
    val hasUnsafeNode: Boolean = false
)

internal data class DefaultStandaloneSkipContext(
    val ruleSource: RuleSource,
    val appElapsedMs: Long,
    val area: RuleArea,
    val candidateAreaRatio: Float,
    val candidate: ClickTargetInfo,
    val actionPath: ResolvedActionPath,
    val ancestorSafetyTexts: List<String>
)

internal data class DefaultStandaloneSkipDecision(val allowed: Boolean, val reason: String = "")

internal data class ClickActionPathSafetyContext(
    val candidate: ClickTargetInfo,
    val actionPath: ResolvedActionPath,
    val ancestorSafetyTexts: List<String>
)

internal object ClickActionPathSafetyPolicy {
    fun evaluate(context: ClickActionPathSafetyContext): DefaultStandaloneSkipDecision {
        if (!context.actionPath.hasSafeClickableTarget) {
            return DefaultStandaloneSkipDecision(false, "standalone_skip_no_safe_action_path")
        }
        if (
            context.actionPath.hasUnsafeNode ||
            context.candidate.input ||
            context.candidate.password ||
            !context.candidate.enabled ||
            !context.candidate.visibleToUser
        ) {
            return DefaultStandaloneSkipDecision(false, "standalone_skip_candidate_unsafe")
        }
        if (!HighRiskClickPolicy.evaluateTexts(
                context.ancestorSafetyTexts + listOf(
                    context.candidate.text,
                    context.candidate.contentDescription,
                    context.candidate.viewId,
                    context.candidate.className
                )
            ).allowed
        ) {
            return DefaultStandaloneSkipDecision(false, "standalone_skip_unsafe_ancestor")
        }
        return DefaultStandaloneSkipDecision(true)
    }
}

internal object DefaultStandaloneSkipPolicy {
    const val MAX_ELAPSED_MS = 8_000L
    const val MAX_CANDIDATE_AREA_RATIO = 0.02f
    private const val MAX_ACTION_PARENT_DEPTH = 2

    fun isStandaloneSkipLabel(text: String, contentDescription: String): Boolean {
        val labels = listOf(text, contentDescription)
            .map(String::trim)
            .filter(String::isNotEmpty)
        return labels.isNotEmpty() && labels.all { value ->
            value == "跳过" || value.equals("skip", ignoreCase = true)
        }
    }

    fun evaluate(context: DefaultStandaloneSkipContext): DefaultStandaloneSkipDecision {
        if (!isStandaloneSkipLabel(context.candidate.text, context.candidate.contentDescription)) return DefaultStandaloneSkipDecision(false, "standalone_skip_label_not_exact")
        if (context.ruleSource != RuleSource.BuiltIn) return DefaultStandaloneSkipDecision(false, "standalone_skip_rule_source_forbidden")
        if (context.appElapsedMs > MAX_ELAPSED_MS) return DefaultStandaloneSkipDecision(false, "standalone_skip_window_expired")
        if (context.area != RuleArea.TopRight) return DefaultStandaloneSkipDecision(false, "standalone_skip_not_top_right")
        if (!context.candidateAreaRatio.isFinite() || context.candidateAreaRatio <= 0f || context.candidateAreaRatio > MAX_CANDIDATE_AREA_RATIO) return DefaultStandaloneSkipDecision(false, "standalone_skip_candidate_too_large")
        if (context.actionPath.parentDepth > MAX_ACTION_PARENT_DEPTH) return DefaultStandaloneSkipDecision(false, "standalone_skip_no_safe_action_path")
        return ClickActionPathSafetyPolicy.evaluate(
            ClickActionPathSafetyContext(
                candidate = context.candidate,
                actionPath = context.actionPath,
                ancestorSafetyTexts = context.ancestorSafetyTexts
            )
        )
    }
}

internal object StandaloneSkipGestureRevalidationPolicy {
    fun evaluate(
        ruleSource: RuleSource,
        ruleKind: RuleKind = RuleKind.Standard,
        appElapsedMs: Long,
        area: RuleArea,
        candidateAreaRatio: Float,
        snapshot: CoordinateFallbackTargetSnapshot
    ): DefaultStandaloneSkipDecision {
        val actionPath = ResolvedActionPath(
            parentDepth = snapshot.actionParentDepth,
            hasSafeClickableTarget = snapshot.hasClickableNodeOrAncestor,
            hasUnsafeNode = snapshot.hasUnsafeActionNode
        )
        if (ruleKind == RuleKind.Precise) {
            return ClickActionPathSafetyPolicy.evaluate(
                ClickActionPathSafetyContext(
                    candidate = snapshot.target,
                    actionPath = actionPath,
                    ancestorSafetyTexts = snapshot.ancestorSafetyTexts
                )
            )
        }
        return DefaultStandaloneSkipPolicy.evaluate(
            DefaultStandaloneSkipContext(
                ruleSource = ruleSource,
                appElapsedMs = appElapsedMs,
                area = area,
                candidateAreaRatio = candidateAreaRatio,
                candidate = snapshot.target,
                actionPath = actionPath,
                ancestorSafetyTexts = snapshot.ancestorSafetyTexts
            )
        )
    }
}
