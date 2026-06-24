package com.example.skip.service

import com.example.skip.model.SkipRule

internal object PendingActivityScopePolicy {
    fun evaluate(
        rule: SkipRule?,
        currentActivityName: String,
        activityIdentityKnown: Boolean
    ): PendingActivityScopeDecision {
        val resolvedRule = rule ?: return PendingActivityScopeDecision.blocked("pending_rule_missing")
        val scope = resolvedRule.activityName.trim()
        if (scope.isBlank() || scope == "*") return PendingActivityScopeDecision.Allowed
        if (!activityIdentityKnown || currentActivityName.isBlank()) {
            return PendingActivityScopeDecision.blocked("activity_scope_unknown")
        }
        if (!scope.equals(currentActivityName, ignoreCase = true)) {
            return PendingActivityScopeDecision.blocked("activity_scope_changed")
        }
        return PendingActivityScopeDecision.Allowed
    }
}

internal data class PendingActivityScopeDecision(
    val allowed: Boolean,
    val reason: String
) {
    companion object {
        val Allowed = PendingActivityScopeDecision(allowed = true, reason = "")

        fun blocked(reason: String): PendingActivityScopeDecision {
            return PendingActivityScopeDecision(allowed = false, reason = reason)
        }
    }
}
