package com.example.skip.engine

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.skip.model.CoordinateFallback
import com.example.skip.model.RuleSource
import com.example.skip.model.SkipRule
import java.util.Locale

object CoordinateFallbackMatcher {
    private const val MIN_COORDINATE_FALLBACK_COOLDOWN_MS = 800L
    private const val MAX_COORDINATE_FALLBACK_WINDOW_MS = 6_000L

    fun evaluate(
        rule: SkipRule,
        packageName: String,
        selfPackageName: String,
        elapsedSinceForegroundMs: Long,
        screenWidth: Int,
        screenHeight: Int,
        hasAnchor: Boolean
    ): CoordinateFallbackDecision {
        val fallback = rule.coordinateFallback
            ?: return CoordinateFallbackDecision.blocked("coordinate_fallback_missing")
        if (!fallback.enabled) return CoordinateFallbackDecision.blocked("coordinate_fallback_disabled")
        if (!fallback.isValid()) return CoordinateFallbackDecision.blocked("coordinate_fallback_invalid")
        if (rule.source == RuleSource.BuiltIn) {
            return CoordinateFallbackDecision.blocked("coordinate_fallback_built_in_forbidden")
        }
        if (rule.source != RuleSource.UserSimple && rule.source != RuleSource.JsonFile) {
            return CoordinateFallbackDecision.blocked("coordinate_fallback_source_forbidden")
        }
        if (rule.packageName.isBlank()) {
            return CoordinateFallbackDecision.blocked("coordinate_fallback_package_required")
        }
        if (rule.packageName != packageName) {
            return CoordinateFallbackDecision.blocked("coordinate_fallback_package_mismatch")
        }
        if (packageName == selfPackageName || SafetyGuard.isProtectedPackage(packageName)) {
            return CoordinateFallbackDecision.blocked("coordinate_fallback_safety_blocked")
        }
        if (rule.validDurationMs <= 0L ||
            rule.validDurationMs > MAX_COORDINATE_FALLBACK_WINDOW_MS
        ) {
            return CoordinateFallbackDecision.blocked("coordinate_fallback_window_required")
        }
        if (elapsedSinceForegroundMs > rule.validDurationMs) {
            return CoordinateFallbackDecision.blocked("coordinate_fallback_window_expired")
        }
        if (rule.cooldownMs < MIN_COORDINATE_FALLBACK_COOLDOWN_MS) {
            return CoordinateFallbackDecision.blocked("coordinate_fallback_cooldown_required")
        }
        if (!fallback.hasAnchorRequirement()) {
            return CoordinateFallbackDecision.blocked("coordinate_fallback_anchor_required")
        }
        if (!hasAnchor) {
            return CoordinateFallbackDecision.blocked("coordinate_fallback_anchor_missing")
        }
        val highRiskDecision = HighRiskClickPolicy.evaluateRule(rule)
        if (!highRiskDecision.allowed) {
            return CoordinateFallbackDecision.blocked(highRiskDecision.reason)
        }
        val width = screenWidth.coerceAtLeast(1)
        val height = screenHeight.coerceAtLeast(1)
        val x = (fallback.xRatio * width).toInt().coerceIn(0, width - 1)
        val y = (fallback.yRatio * height).toInt().coerceIn(0, height - 1)
        return CoordinateFallbackDecision(
            allowed = true,
            x = x,
            y = y,
            reason = "coordinate_fallback_allowed"
        )
    }

    fun find(
        root: AccessibilityNodeInfo,
        rules: List<SkipRule>,
        packageName: String,
        selfPackageName: String,
        elapsedSinceForegroundMs: Long,
        screenWidth: Int,
        screenHeight: Int
    ): CoordinateFallbackMatch? {
        return rules.firstNotNullOfOrNull { rule ->
            val fallback = rule.coordinateFallback ?: return@firstNotNullOfOrNull null
            val hasAnchor = !fallback.hasAnchorRequirement() || root.containsAnchor(fallback)
            val decision = evaluate(
                rule = rule,
                packageName = packageName,
                selfPackageName = selfPackageName,
                elapsedSinceForegroundMs = elapsedSinceForegroundMs,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                hasAnchor = hasAnchor
            )
            if (!decision.allowed) return@firstNotNullOfOrNull null
            val target = ClickTargetInfo(
                bounds = Rect(decision.x, decision.y, decision.x + 1, decision.y + 1),
                text = "",
                contentDescription = "",
                viewId = "",
                className = "coordinate_fallback",
                nodeClickable = false,
                parentClickable = false,
                enabled = true,
                visibleToUser = true,
                password = false,
                input = false
            )
            CoordinateFallbackMatch(
                rule = rule,
                target = target,
                x = decision.x,
                y = decision.y,
                reason = decision.reason
            )
        }
    }

    private fun AccessibilityNodeInfo.containsAnchor(fallback: CoordinateFallback): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(this)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.matchesAnchor(fallback)) return true
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::add)
            }
        }
        return false
    }

    private fun AccessibilityNodeInfo.matchesAnchor(fallback: CoordinateFallback): Boolean {
        val text = text?.toString().orEmpty()
        val description = contentDescription?.toString().orEmpty()
        val viewId = viewIdResourceName.orEmpty()
        return text.matchesAny(fallback.anchorTexts) ||
            description.matchesAny(fallback.anchorContentDescriptions) ||
            viewId.normalize().matchesAny(fallback.anchorViewIds.map { it.normalize() })
    }

    private fun String.matchesAny(values: List<String>): Boolean {
        if (isBlank()) return false
        val lower = lowercase(Locale.ROOT)
        return values.any { lower.contains(it.lowercase(Locale.ROOT)) }
    }

    private fun String.normalize(): String {
        return lowercase(Locale.ROOT)
            .replace("-", "_")
            .replace(".", "_")
            .replace(":", "_")
    }
}

data class CoordinateFallbackDecision(
    val allowed: Boolean,
    val x: Int = 0,
    val y: Int = 0,
    val reason: String
) {
    companion object {
        fun blocked(reason: String): CoordinateFallbackDecision {
            return CoordinateFallbackDecision(allowed = false, reason = reason)
        }
    }
}

data class CoordinateFallbackMatch(
    val rule: SkipRule,
    val target: ClickTargetInfo,
    val x: Int,
    val y: Int,
    val reason: String
)
