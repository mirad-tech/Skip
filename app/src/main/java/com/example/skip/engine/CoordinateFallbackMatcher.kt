package com.example.skip.engine

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.skip.data.RuleRepository
import com.example.skip.model.CoordinateFallback
import com.example.skip.model.RuleSource
import com.example.skip.model.SkipRule
import java.util.Locale
import kotlin.math.abs

object CoordinateFallbackMatcher {
    private const val MIN_COORDINATE_FALLBACK_COOLDOWN_MS = 800L
    private const val MIN_ANCHOR_TEXT_LENGTH = 3
    private const val MIN_ANCHOR_VIEW_ID_LENGTH = 6
    private const val MIN_ANCHOR_CONTAINS_LENGTH = 6
    private const val MAX_CLICKABLE_ANCESTOR_DEPTH = 4
    private const val MAX_TARGET_DRIFT_PX = 48

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
            rule.validDurationMs > RuleRepository.MAX_COORDINATE_FALLBACK_WINDOW_MS
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
        anchorValidationReason(fallback)?.let { reason ->
            return CoordinateFallbackDecision.blocked(reason)
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
        return when (
            val result = findResult(
                root = root,
                rules = rules,
                packageName = packageName,
                selfPackageName = selfPackageName,
                elapsedSinceForegroundMs = elapsedSinceForegroundMs,
                screenWidth = screenWidth,
                screenHeight = screenHeight
            )
        ) {
            is CoordinateFallbackMatchResult.Matched -> result.match
            is CoordinateFallbackMatchResult.Blocked,
            CoordinateFallbackMatchResult.NotApplicable -> null
        }
    }

    fun findResult(
        root: AccessibilityNodeInfo,
        rules: List<SkipRule>,
        packageName: String,
        selfPackageName: String,
        elapsedSinceForegroundMs: Long,
        screenWidth: Int,
        screenHeight: Int,
        activeTextInput: Boolean = false
    ): CoordinateFallbackMatchResult {
        val coordinateRules = rules.filter { it.coordinateFallback?.enabled == true }
        if (coordinateRules.isEmpty()) return CoordinateFallbackMatchResult.NotApplicable
        if (activeTextInput) {
            return CoordinateFallbackMatchResult.Blocked(
                reason = "coordinate_active_text_input",
                rule = coordinateRules.first()
            )
        }

        var firstBlocked: CoordinateFallbackMatchResult.Blocked? = null
        val pageSafetyTexts by lazy { root.pageSafetyTexts() }
        coordinateRules.forEach { rule ->
            val fallback = rule.coordinateFallback ?: return@forEach
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
            if (!decision.allowed) {
                if (firstBlocked == null) {
                    firstBlocked = CoordinateFallbackMatchResult.Blocked(
                        reason = decision.toInitialMatchResultReason(),
                        rule = rule
                    )
                }
                return@forEach
            }
            val coordinateTarget = root.findTargetAtPoint(decision.x, decision.y)
            val initialResult = evaluateInitialCandidate(
                rule = rule,
                packageName = packageName,
                selfPackageName = selfPackageName,
                elapsedSinceForegroundMs = elapsedSinceForegroundMs,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                hasAnchor = hasAnchor,
                activeTextInput = false,
                pageSafetyTexts = emptyList(),
                target = coordinateTarget?.toSnapshot()
            )
            when (initialResult) {
                is CoordinateFallbackMatchResult.Matched -> {
                    if (pageSafetyTexts.any(SafetyGuard::isSensitiveText)) {
                        return CoordinateFallbackMatchResult.Blocked(
                            reason = "coordinate_page_unsafe",
                            rule = rule
                        )
                    }
                    return initialResult
                }
                is CoordinateFallbackMatchResult.Blocked -> {
                    if (firstBlocked == null) firstBlocked = initialResult
                }
                CoordinateFallbackMatchResult.NotApplicable -> Unit
            }
        }
        return firstBlocked ?: CoordinateFallbackMatchResult.Blocked(
            reason = "coordinate_rule_invalid",
            rule = coordinateRules.first()
        )
    }

    fun evaluateInitialCandidate(
        rule: SkipRule,
        packageName: String,
        selfPackageName: String,
        elapsedSinceForegroundMs: Long,
        screenWidth: Int,
        screenHeight: Int,
        hasAnchor: Boolean,
        activeTextInput: Boolean,
        pageSafetyTexts: List<String>,
        target: CoordinateFallbackTargetSnapshot?
    ): CoordinateFallbackMatchResult {
        if (activeTextInput) {
            return CoordinateFallbackMatchResult.Blocked(
                reason = "coordinate_active_text_input",
                rule = rule
            )
        }
        val decision = evaluate(
            rule = rule,
            packageName = packageName,
            selfPackageName = selfPackageName,
            elapsedSinceForegroundMs = elapsedSinceForegroundMs,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            hasAnchor = hasAnchor
        )
        if (!decision.allowed) {
            return CoordinateFallbackMatchResult.Blocked(
                reason = decision.toInitialMatchResultReason(),
                rule = rule
            )
        }
        val snapshot = target ?: return CoordinateFallbackMatchResult.Blocked(
            reason = "coordinate_target_missing",
            rule = rule
        )
        val targetDecision = evaluateTargetAtPoint(
            target = snapshot.target,
            targetPackageName = snapshot.packageName,
            expectedPackageName = packageName,
            ancestorSafetyTexts = snapshot.ancestorSafetyTexts,
            hasClickableNodeOrAncestor = snapshot.hasClickableNodeOrAncestor
        )
        if (!targetDecision.allowed) {
            return CoordinateFallbackMatchResult.Blocked(
                reason = targetDecision.toInitialMatchResultReason(),
                rule = rule
            )
        }
        if (pageSafetyTexts.any(SafetyGuard::isSensitiveText)) {
            return CoordinateFallbackMatchResult.Blocked(
                reason = "coordinate_page_unsafe",
                rule = rule
            )
        }
        return CoordinateFallbackMatchResult.Matched(
            CoordinateFallbackMatch(
                rule = rule,
                target = snapshot.target,
                x = decision.x,
                y = decision.y,
                reason = decision.reason
            )
        )
    }

    fun evaluateTargetAtPoint(
        target: ClickTargetInfo?,
        targetPackageName: String,
        expectedPackageName: String,
        ancestorSafetyTexts: List<String> = emptyList(),
        hasClickableNodeOrAncestor: Boolean = false
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
        if (!target.hasIdentifyingSignal()) {
            return CoordinateFallbackDecision.blocked("coordinate_fallback_target_blank")
        }
        ClickExecutor.coordinateFallbackGestureTargetBlockReason(
            target = target,
            hasClickableNodeOrAncestor = hasClickableNodeOrAncestor
        )?.let { reason ->
            return CoordinateFallbackDecision.blocked(reason)
        }
        val highRiskDecision = HighRiskClickPolicy.evaluateTexts(
            listOf(
                target.text,
                target.contentDescription,
                target.viewId,
                target.className,
                targetPackageName
            ) + ancestorSafetyTexts
        )
        if (!highRiskDecision.allowed) {
            return CoordinateFallbackDecision.blocked("coordinate_fallback_target_unsafe")
        }
        return CoordinateFallbackDecision(allowed = true, reason = "coordinate_fallback_target_allowed")
    }

    fun revalidateAtPoint(
        root: AccessibilityNodeInfo?,
        rule: SkipRule,
        expectedPackageName: String,
        currentPackageName: String,
        selfPackageName: String,
        elapsedSinceForegroundMs: Long,
        x: Int,
        y: Int,
        originalTarget: ClickTargetInfo,
        activeTextInput: Boolean
    ): CoordinateFallbackRevalidation {
        val fallback = rule.coordinateFallback
        val hasAnchor = root != null && fallback != null && root.containsAnchor(fallback)
        val currentTarget = root?.findTargetAtPoint(x, y)
        return evaluateBeforeGesture(
            rule = rule,
            expectedPackageName = expectedPackageName,
            currentPackageName = currentPackageName,
            selfPackageName = selfPackageName,
            elapsedSinceForegroundMs = elapsedSinceForegroundMs,
            rootAvailable = root != null,
            hasAnchor = hasAnchor,
            activeTextInput = activeTextInput,
            pageSafetyTexts = root?.pageSafetyTexts().orEmpty(),
            originalTarget = originalTarget,
            currentTarget = currentTarget?.toSnapshot()
        )
    }

    fun evaluateBeforeGesture(
        rule: SkipRule,
        expectedPackageName: String,
        currentPackageName: String,
        selfPackageName: String,
        elapsedSinceForegroundMs: Long,
        rootAvailable: Boolean,
        hasAnchor: Boolean,
        activeTextInput: Boolean,
        pageSafetyTexts: List<String>,
        originalTarget: ClickTargetInfo,
        currentTarget: CoordinateFallbackTargetSnapshot?
    ): CoordinateFallbackRevalidation {
        if (!rootAvailable) return CoordinateFallbackRevalidation.blocked("coordinate_root_missing")
        if (currentPackageName != expectedPackageName) {
            return CoordinateFallbackRevalidation.blocked("coordinate_package_changed")
        }
        if (activeTextInput) {
            return CoordinateFallbackRevalidation.blocked("coordinate_active_text_input")
        }
        val ruleDecision = evaluate(
            rule = rule,
            packageName = currentPackageName,
            selfPackageName = selfPackageName,
            elapsedSinceForegroundMs = elapsedSinceForegroundMs,
            screenWidth = 1,
            screenHeight = 1,
            hasAnchor = hasAnchor
        )
        if (!ruleDecision.allowed) {
            return CoordinateFallbackRevalidation.blocked(ruleDecision.toRevalidationReason())
        }
        if (pageSafetyTexts.any(SafetyGuard::isSensitiveText)) {
            return CoordinateFallbackRevalidation.blocked("coordinate_page_unsafe")
        }
        val snapshot = currentTarget
            ?: return CoordinateFallbackRevalidation.blocked("coordinate_target_missing")
        val targetDecision = evaluateTargetAtPoint(
            target = snapshot.target,
            targetPackageName = snapshot.packageName,
            expectedPackageName = expectedPackageName,
            ancestorSafetyTexts = snapshot.ancestorSafetyTexts,
            hasClickableNodeOrAncestor = snapshot.hasClickableNodeOrAncestor
        )
        if (!targetDecision.allowed) {
            return CoordinateFallbackRevalidation.blocked(targetDecision.toRevalidationReason())
        }
        if (!snapshot.target.matchesOriginalTarget(originalTarget)) {
            return CoordinateFallbackRevalidation.blocked("coordinate_target_changed")
        }
        return CoordinateFallbackRevalidation(
            allowed = true,
            reason = "coordinate_target_revalidated",
            target = snapshot.target
        )
    }

    fun anchorValidationReason(fallback: CoordinateFallback): String? {
        if (fallback.anchorTexts.any { it.normalizedAnchorText().length < MIN_ANCHOR_TEXT_LENGTH }) {
            return "coordinate_fallback_anchor_too_short"
        }
        if (fallback.anchorContentDescriptions.any {
                it.normalizedAnchorText().length < MIN_ANCHOR_TEXT_LENGTH
            }
        ) {
            return "coordinate_fallback_anchor_too_short"
        }
        if (fallback.anchorViewIds.any { it.trim().length < MIN_ANCHOR_VIEW_ID_LENGTH }) {
            return "coordinate_fallback_anchor_view_id_too_short"
        }
        return null
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
                val candidate = node.toCoordinateTarget()
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
        return matchesTrustedAnchor(
            text = text?.toString().orEmpty(),
            contentDescription = contentDescription?.toString().orEmpty(),
            viewId = viewIdResourceName.orEmpty(),
            fallback = fallback
        )
    }

    fun matchesTrustedAnchor(
        text: String,
        contentDescription: String,
        viewId: String,
        fallback: CoordinateFallback
    ): Boolean {
        return text.matchesTextAnchor(fallback.anchorTexts) ||
            contentDescription.matchesTextAnchor(fallback.anchorContentDescriptions) ||
            viewId.matchesViewIdAnchor(fallback.anchorViewIds)
    }

    private fun String.normalize(): String {
        return lowercase(Locale.ROOT)
            .replace("-", "_")
            .replace(".", "_")
            .replace(":", "_")
    }

    private fun String.normalizedAnchorText(): String {
        return lowercase(Locale.ROOT).replace("\\s+".toRegex(), "").trim()
    }

    private fun String.matchesTextAnchor(anchors: List<String>): Boolean {
        val normalizedValue = normalizedAnchorText()
        if (normalizedValue.isBlank()) return false
        return anchors.any { anchor ->
            val normalizedAnchor = anchor.normalizedAnchorText()
            normalizedAnchor.isNotBlank() && (
                normalizedValue == normalizedAnchor ||
                    (normalizedAnchor.length >= MIN_ANCHOR_CONTAINS_LENGTH &&
                        normalizedValue.contains(normalizedAnchor))
                )
        }
    }

    private fun String.matchesViewIdAnchor(anchors: List<String>): Boolean {
        if (isBlank()) return false
        return anchors.any { anchor ->
            equals(anchor, ignoreCase = true) || normalize() == anchor.normalize()
        }
    }

    private fun ClickTargetInfo.hasIdentifyingSignal(): Boolean {
        return ClickExecutor.hasCoordinateFallbackIdentity(this)
    }

    private fun AccessibilityNodeInfo.toCoordinateTarget(): CoordinateTarget {
        val ancestorSafetyTexts = mutableListOf<String>()
        var current: AccessibilityNodeInfo? = this
        var depth = 0
        var hasClickableNodeOrAncestor = false
        while (current != null && depth <= MAX_CLICKABLE_ANCESTOR_DEPTH) {
            hasClickableNodeOrAncestor = hasClickableNodeOrAncestor || current.isClickable
            ancestorSafetyTexts += listOf(
                current.text?.toString().orEmpty(),
                current.contentDescription?.toString().orEmpty(),
                current.viewIdResourceName.orEmpty(),
                current.className?.toString().orEmpty()
            )
            current = current.parent
            depth++
        }
        return CoordinateTarget(
            target = ClickExecutor.describeTarget(this),
            packageName = packageName?.toString().orEmpty(),
            ancestorSafetyTexts = ancestorSafetyTexts,
            hasClickableNodeOrAncestor = hasClickableNodeOrAncestor
        )
    }

    private fun AccessibilityNodeInfo.pageSafetyTexts(): List<String> {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(this)
        val values = mutableListOf<String>()
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            values += listOf(
                node.text?.toString().orEmpty(),
                node.contentDescription?.toString().orEmpty(),
                node.viewIdResourceName.orEmpty(),
                node.className?.toString().orEmpty()
            )
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::add)
            }
        }
        return values
    }

    private fun CoordinateFallbackDecision.toRevalidationReason(): String {
        return when (reason) {
            "coordinate_fallback_anchor_missing" -> "coordinate_anchor_missing"
            "coordinate_fallback_anchor_too_short" -> "coordinate_anchor_too_short"
            ClickExecutor.COORDINATE_TEXT_INPUT_CLEAR_BUTTON_REASON -> {
                ClickExecutor.COORDINATE_TEXT_INPUT_CLEAR_BUTTON_REASON
            }
            "coordinate_fallback_window_expired" -> "coordinate_window_expired"
            "coordinate_fallback_package_mismatch" -> "coordinate_package_changed"
            "coordinate_fallback_target_missing" -> "coordinate_target_missing"
            "coordinate_fallback_target_package_mismatch" -> "coordinate_package_changed"
            "coordinate_fallback_target_blank" -> "coordinate_target_blank"
            else -> "coordinate_target_unsafe"
        }
    }

    private fun CoordinateFallbackDecision.toInitialMatchResultReason(): String {
        return when (reason) {
            "coordinate_fallback_anchor_missing" -> "coordinate_anchor_missing"
            "coordinate_fallback_anchor_too_short" -> "coordinate_anchor_too_short"
            "coordinate_fallback_anchor_view_id_too_short" -> "coordinate_anchor_view_id_too_short"
            ClickExecutor.COORDINATE_TEXT_INPUT_CLEAR_BUTTON_REASON -> {
                ClickExecutor.COORDINATE_TEXT_INPUT_CLEAR_BUTTON_REASON
            }
            "coordinate_fallback_window_expired" -> "coordinate_window_expired"
            "coordinate_fallback_package_mismatch",
            "coordinate_fallback_target_package_mismatch" -> "coordinate_package_changed"
            "coordinate_fallback_target_missing" -> "coordinate_target_missing"
            "coordinate_fallback_target_blank" -> "coordinate_target_blank"
            "coordinate_fallback_target_unsafe" -> "coordinate_target_unsafe"
            else -> "coordinate_rule_invalid"
        }
    }

    private fun ClickTargetInfo.matchesOriginalTarget(original: ClickTargetInfo): Boolean {
        if (!bounds.isNearCoordinateTarget(original.bounds)) return false
        if (viewId.isNotBlank() || original.viewId.isNotBlank()) return viewId == original.viewId
        if (text.isNotBlank() || original.text.isNotBlank()) return text == original.text
        if (contentDescription.isNotBlank() || original.contentDescription.isNotBlank()) {
            return contentDescription == original.contentDescription
        }
        return className.isNotBlank() && className == original.className
    }

    private fun Rect.isNearCoordinateTarget(other: Rect): Boolean {
        return abs(left - other.left) <= MAX_TARGET_DRIFT_PX &&
            abs(top - other.top) <= MAX_TARGET_DRIFT_PX &&
            abs(right - other.right) <= MAX_TARGET_DRIFT_PX &&
            abs(bottom - other.bottom) <= MAX_TARGET_DRIFT_PX
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

sealed interface CoordinateFallbackMatchResult {
    data class Matched(val match: CoordinateFallbackMatch) : CoordinateFallbackMatchResult

    data class Blocked(
        val reason: String,
        val rule: SkipRule
    ) : CoordinateFallbackMatchResult

    data object NotApplicable : CoordinateFallbackMatchResult
}

data class CoordinateFallbackTargetSnapshot(
    val target: ClickTargetInfo,
    val packageName: String,
    val ancestorSafetyTexts: List<String> = emptyList(),
    val hasClickableNodeOrAncestor: Boolean = false
)

data class CoordinateFallbackRevalidation(
    val allowed: Boolean,
    val reason: String,
    val target: ClickTargetInfo? = null
) {
    companion object {
        fun blocked(reason: String): CoordinateFallbackRevalidation {
            return CoordinateFallbackRevalidation(allowed = false, reason = reason)
        }
    }
}

private data class CoordinateTarget(
    val target: ClickTargetInfo,
    val packageName: String,
    val ancestorSafetyTexts: List<String>,
    val hasClickableNodeOrAncestor: Boolean
) {
    fun toSnapshot(): CoordinateFallbackTargetSnapshot {
        return CoordinateFallbackTargetSnapshot(
            target = target,
            packageName = packageName,
            ancestorSafetyTexts = ancestorSafetyTexts,
            hasClickableNodeOrAncestor = hasClickableNodeOrAncestor
        )
    }
}
