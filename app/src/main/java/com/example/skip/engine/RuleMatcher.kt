package com.example.skip.engine

import android.view.accessibility.AccessibilityNodeInfo
import com.example.skip.model.MatchResult
import com.example.skip.model.SkipRule

object RuleMatcher {
    fun match(node: AccessibilityNodeInfo, rule: SkipRule): MatchResult? {
        val scoredRule = ScoreEvaluator.evaluate(node, rule) ?: return null
        val clickNode = ClickExecutor.findClickableTarget(node) ?: return null
        return MatchResult(
            sourceNode = node,
            clickNode = clickNode,
            ruleName = scoredRule.ruleName,
            score = scoredRule.score
        )
    }
}
