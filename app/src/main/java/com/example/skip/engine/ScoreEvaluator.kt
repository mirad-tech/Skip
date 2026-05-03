package com.example.skip.engine

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.skip.model.SkipRule
import java.util.Locale

object ScoreEvaluator {
    private const val MAX_SKIP_TEXT_LENGTH = 32
    private const val CLICK_THRESHOLD = 70

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

    fun evaluate(node: AccessibilityNodeInfo, rule: SkipRule): ScoredRule? {
        if (!node.isVisibleToUser || !node.isEnabled || node.isPassword) return null
        val classNameValue = node.className?.toString().orEmpty()
        if (classNameValue.contains("EditText", ignoreCase = true)) return null

        val textValues = listOfNotNull(
            node.text?.toString(),
            node.contentDescription?.toString()
        )
        val viewId = node.viewIdResourceName.orEmpty()
        val textRule = matchedTextRule(textValues, rule.textKeywords)
        val idRule = matchedIdRule(viewId, rule.viewIdKeywords)

        if (textRule == null && idRule == null) return null

        var score = 0
        var ruleName = textRule ?: idRule.orEmpty()

        if (textRule != null) score += 45
        if (idRule != null) {
            score += if (idRule.contains("skip", ignoreCase = true)) 35 else 20
            if (textRule == null) ruleName = idRule
        }
        if (node.isClickable) score += 15
        if (node.isTopRightNode()) score += 20
        if (viewId.containsAdOrSplashSignal()) score += 10
        if (textValues.any { it.containsAdSignal() }) score += 10

        val onlyGenericClose = textRule != null &&
            closeNeedsAdContext.any { textRule.equals(it, ignoreCase = true) } &&
            !viewId.containsAdOrSplashSignal() &&
            textValues.none { it.containsAdSignal() }
        if (onlyGenericClose) score -= 35

        return if (score >= CLICK_THRESHOLD) {
            ScoredRule(ruleName = ruleName, score = score)
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

    private fun AccessibilityNodeInfo.isTopRightNode(): Boolean {
        val bounds = Rect()
        getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false
        val screenWidth = android.content.res.Resources.getSystem().displayMetrics.widthPixels
            .coerceAtLeast(1)
        val screenHeight = android.content.res.Resources.getSystem().displayMetrics.heightPixels
            .coerceAtLeast(1)
        return bounds.centerX() >= screenWidth * 0.55f &&
            bounds.centerY() <= screenHeight * 0.35f
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
