package com.example.skip.engine

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.skip.model.RuleArea
import com.example.skip.model.SkipRule
import java.util.Locale

object ScoreEvaluator {
    private const val MAX_SKIP_TEXT_LENGTH = 32

    private val blockedTextFragments = listOf(
        "跳过登录",
        "跳过验证",
        "跳过绑定",
        "跳过设置",
        "不跳过",
        "skip login",
        "skip verification",
        "skip setup"
    )

    private val closeNeedsAdContext = listOf(
        "关闭",
        "close"
    )

    fun evaluate(
        node: AccessibilityNodeInfo,
        rule: SkipRule,
        appElapsedMs: Long,
        clickTarget: AccessibilityNodeInfo?
    ): ScoredRule? {
        if (!node.isVisibleToUser || !node.isEnabled || node.isPassword) return null
        val classNameValue = node.className?.toString().orEmpty()
        if (classNameValue.contains("EditText", ignoreCase = true)) return null
        if (isDangerousButtonText(node)) return null
        if (appElapsedMs > rule.validDurationMs) return null

        val textValue = node.text?.toString().orEmpty()
        val descriptionValue = node.contentDescription?.toString().orEmpty()
        val viewId = node.viewIdResourceName.orEmpty()
        val textRule = matchedTextRule(listOf(textValue), rule.matchTexts)
        val descriptionRule = matchedTextRule(listOf(descriptionValue), rule.matchContentDescriptions)
        val idRule = matchedIdRule(viewId, rule.matchViewIds)

        if (textRule == null && descriptionRule == null && idRule == null) return null

        var score = 0
        val matchedRuleName = textRule ?: descriptionRule ?: idRule ?: rule.name

        if (textRule != null) score += 40
        if (descriptionRule != null) score += 30
        if (idRule != null) score += 30
        if (rule.area == RuleArea.Any || node.areaInScreen() == rule.area) score += 20
        if (ClickExecutor.isSelfSafeClickable(node)) score += 20
        if (clickTarget != null && clickTarget != node) score += 10
        if (appElapsedMs <= rule.validDurationMs) score += 20
        if (viewId.containsAdOrSplashSignal()) score += 10
        if (listOf(textValue, descriptionValue).any { it.containsAdSignal() }) score += 10

        val onlyGenericClose = textRule != null &&
            closeNeedsAdContext.any { textRule.equals(it, ignoreCase = true) } &&
            !viewId.containsAdOrSplashSignal() &&
            listOf(textValue, descriptionValue).none { it.containsAdSignal() }
        if (onlyGenericClose) score -= 35

        return if (score >= rule.minScore) {
            ScoredRule(ruleName = "${rule.name} / $matchedRuleName", score = score)
        } else {
            null
        }
    }

    private fun matchedTextRule(values: List<String>, keywords: List<String>): String? {
        return values.firstNotNullOfOrNull { value ->
            val normalized = value.trim()
            if (normalized.isEmpty() || normalized.length > MAX_SKIP_TEXT_LENGTH) return@firstNotNullOfOrNull null
            val lower = normalized.lowercase(Locale.ROOT)
            if (blockedTextFragments.any { lower.contains(it) }) return@firstNotNullOfOrNull null
            keywords.firstOrNull { keyword ->
                lower.contains(keyword.lowercase(Locale.ROOT))
            }
        }
    }

    private fun matchedIdRule(viewId: String, keywords: List<String>): String? {
        val normalizedViewId = viewId.normalizeForRuleMatch()
        if (normalizedViewId.isEmpty()) return null
        return keywords.firstOrNull { keyword ->
            normalizedViewId.contains(keyword.normalizeForRuleMatch())
        }
    }

    private fun AccessibilityNodeInfo.areaInScreen(): RuleArea {
        val bounds = Rect()
        getBoundsInScreen(bounds)
        if (bounds.isEmpty) return RuleArea.Any
        val screenWidth = android.content.res.Resources.getSystem().displayMetrics.widthPixels
            .coerceAtLeast(1)
        val screenHeight = android.content.res.Resources.getSystem().displayMetrics.heightPixels
            .coerceAtLeast(1)
        val column = when {
            bounds.centerX() < screenWidth / 3f -> 0
            bounds.centerX() < screenWidth * 2f / 3f -> 1
            else -> 2
        }
        val row = when {
            bounds.centerY() < screenHeight / 3f -> 0
            bounds.centerY() < screenHeight * 2f / 3f -> 1
            else -> 2
        }
        return when (row to column) {
            0 to 0 -> RuleArea.TopLeft
            0 to 1 -> RuleArea.TopCenter
            0 to 2 -> RuleArea.TopRight
            1 to 0 -> RuleArea.MiddleLeft
            1 to 1 -> RuleArea.Center
            1 to 2 -> RuleArea.MiddleRight
            2 to 0 -> RuleArea.BottomLeft
            2 to 1 -> RuleArea.BottomCenter
            else -> RuleArea.BottomRight
        }
    }

    private fun isDangerousButtonText(node: AccessibilityNodeInfo): Boolean {
        val combined = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            .joinToString(" ")
            .lowercase(Locale.ROOT)
        if (combined.isBlank()) return false
        return listOf(
            "支付",
            "付款",
            "转账",
            "确认支付",
            "允许",
            "授权",
            "验证码",
            "登录",
            "password",
            "pay",
            "permission",
            "allow",
            "login",
            "verify"
        ).any { combined.contains(it) }
    }

    private fun String.containsAdSignal(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains("广告") ||
            lower.contains("ad") ||
            lower.contains("splash") ||
            lower.contains("skip")
    }

    private fun String.containsAdOrSplashSignal(): Boolean {
        val lower = normalizeForRuleMatch()
        return lower.contains("ad") ||
            lower.contains("splash") ||
            lower.contains("skip") ||
            lower.contains("gdt") ||
            lower.contains("ksad") ||
            lower.contains("tt_")
    }

    private fun String.normalizeForRuleMatch(): String {
        return lowercase(Locale.ROOT)
            .replace("-", "_")
            .replace(".", "_")
            .replace(":", "_")
    }

    data class ScoredRule(
        val ruleName: String,
        val score: Int
    )
}
