package com.example.skip.engine

import android.view.accessibility.AccessibilityNodeInfo
import com.example.skip.model.MatchResult
import com.example.skip.model.SkipRule

object RuleMatcher {
    fun match(
        node: AccessibilityNodeInfo,
        rule: SkipRule,
        appElapsedMs: Long
    ): MatchResult? {
        val clickNode = ClickExecutor.findClickableTarget(node)
        val scoredRule = ScoreEvaluator.evaluate(node, rule, appElapsedMs, clickNode) ?: return null
        if (clickNode == null) return null
        return MatchResult(
            sourceNode = node,
            clickNode = clickNode,
            ruleId = rule.id,
            ruleName = scoredRule.ruleName,
            score = scoredRule.score
        )
    }
}
