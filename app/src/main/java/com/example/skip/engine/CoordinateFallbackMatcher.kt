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
            val coordinateTarget = root.findTargetAtPoint(decision.x, decision.y)
            val targetDecision = evaluateTargetAtPoint(
                target = coordinateTarget?.target,
                targetPackageName = coordinateTarget?.packageName.orEmpty(),
                expectedPackageName = packageName
            )
            if (!targetDecision.allowed || coordinateTarget == null) return@firstNotNullOfOrNull null
            CoordinateFallbackMatch(
                rule = rule,
                target = coordinateTarget.target,
                x = decision.x,
                y = decision.y,
                reason = decision.reason
            )
        }
    }

    fun evaluateTargetAtPoint(
        target: ClickTargetInfo?,
        targetPackageName: String,
        expectedPackageName: String
    ): CoordinateFallbackDecision {
        if (target == null) {
            return CoordinateFallbackDecision.blocked("coordinate_fallback_target_missing")
        }
        if (targetPackageName.isBlank() || targetPackageName != expectedPackageName) {
            return CoordinateFallbackDecision.blocked("coordinate_fallback_target_package_mismatch")
        }
        if (SafetyGuard.isProtectedPackage(targetPackageName)) {
            return CoordinateFallbackDecision.blocked("coordinate_fallback_safety_blocked")
        }
        if (target.bounds.isEmptyForPolicy() ||
            !target.enabled ||
            !target.visibleToUser ||
            target.password ||
            target.input
        ) {
            return CoordinateFallbackDecision.blocked("coordinate_fallback_target_unsafe")
        }
        val highRiskDecision = HighRiskClickPolicy.evaluateTexts(
            listOf(
                target.text,
                target.contentDescription,
                target.viewId,
                target.className,
                targetPackageName
            )
        )
        if (!highRiskDecision.allowed) {
            return CoordinateFallbackDecision.blocked(highRiskDecision.reason)
        }
        return CoordinateFallbackDecision(allowed = true, reason = "coordinate_fallback_target_allowed")
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

    private fun AccessibilityNodeInfo.findTargetAtPoint(x: Int, y: Int): CoordinateTarget? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(this)
        var best: CoordinateTarget? = null
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (node.isVisibleToUser && bounds.containsForPolicy(x, y)) {
                val candidate = CoordinateTarget(
                    target = ClickExecutor.describeTarget(node),
                    packageName = node.packageName?.toString().orEmpty()
                )
                if (best == null || bounds.area() < best!!.target.bounds.area()) {
                    best = candidate
                }
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::add)
            }
        }
        return best
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

    private fun Rect.area(): Int {
        return (right - left).coerceAtLeast(0) * (bottom - top).coerceAtLeast(0)
    }

    private fun Rect.isEmptyForPolicy(): Boolean {
        return left >= right || top >= bottom
    }

    private fun Rect.containsForPolicy(x: Int, y: Int): Boolean {
        return left < right && top < bottom && x >= left && x < right && y >= top && y < bottom
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

private data class CoordinateTarget(
    val target: ClickTargetInfo,
    val packageName: String
)
