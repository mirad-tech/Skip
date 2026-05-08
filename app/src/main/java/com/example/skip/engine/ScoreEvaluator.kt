package com.example.skip.engine

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.skip.model.RuleArea
import com.example.skip.model.RuleSource
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
        "close",
        "我知道了",
        "知道了"
    )

    fun evaluate(
        node: AccessibilityNodeInfo,
        rule: SkipRule,
        appElapsedMs: Long,
        clickSelection: ClickTargetSelection?
    ): ScoreEvaluation? {
        if (!node.isVisibleToUser) return null
        if (!node.isEnabled) return null
        if (node.isPassword || node.isInputNode()) return null
        if (isDangerousButtonText(node)) return null
        if (appElapsedMs > rule.validDurationMs) {
            return ScoreEvaluation(rule, 0, rule.minScore, "", node.areaInScreen(), false, "skipped_by_time_window")
        }

        val textValue = node.text?.toString().orEmpty()
        val descriptionValue = node.contentDescription?.toString().orEmpty()
        val viewId = node.viewIdResourceName.orEmpty()
        val textRule = matchedTextRule(listOf(textValue), rule.matchTexts)
        val descriptionRule = matchedTextRule(listOf(descriptionValue), rule.matchContentDescriptions)
        val idRule = matchedIdRule(viewId, rule.matchViewIds)

        if (textRule == null && descriptionRule == null && idRule == null) return null

        var score = 0
        val area = node.areaInScreen()
        val matchedKeyword = textRule ?: descriptionRule ?: idRule ?: rule.name
        val defaultRule = rule.source == RuleSource.BuiltIn
        val textKeywordIsStandaloneSkip = listOf(textValue, descriptionValue)
            .any(SafetyGuard::isStandaloneSkipText)
        if (defaultRule && textKeywordIsStandaloneSkip) {
            return ScoreEvaluation(
                rule,
                score = 0,
                minScore = rule.minScore,
                matchedKeyword = matchedKeyword,
                area = area,
                passesMinScore = false,
                failureReason = "standalone_skip_forbidden",
                defaultRuleAreaAllowed = isDefaultRuleAreaAllowedForCandidate(area),
                textKeywordIsStandaloneSkip = true
            )
        }

        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        val areaRatio = ClickExecutor.areaRatio(bounds)
        val largeCandidate = ClickExecutor.isLargeDefaultCandidate(bounds)
        val defaultAreaAllowed = isDefaultRuleAreaAllowedForCandidate(area)
        if (defaultRule && largeCandidate) {
            return ScoreEvaluation(
                rule,
                score = 0,
                minScore = rule.minScore,
                matchedKeyword = matchedKeyword,
                area = area,
                passesMinScore = false,
                failureReason = "ambiguous_candidate_large_bounds",
                defaultRuleAreaAllowed = defaultAreaAllowed,
                textKeywordIsStandaloneSkip = textKeywordIsStandaloneSkip,
                candidateAreaRatio = areaRatio,
                isLargeCandidateBounds = true
            )
        }
        if (defaultRule && clickSelection == null) {
            return ScoreEvaluation(
                rule,
                score = 0,
                minScore = rule.minScore,
                matchedKeyword = matchedKeyword,
                area = area,
                passesMinScore = false,
                failureReason = "no_safe_clickable_target",
                defaultRuleAreaAllowed = defaultAreaAllowed,
                textKeywordIsStandaloneSkip = textKeywordIsStandaloneSkip,
                candidateAreaRatio = areaRatio,
                isLargeCandidateBounds = largeCandidate
            )
        }

        if (textRule != null) score += 40
        if (descriptionRule != null) score += 30
        if (idRule != null) score += 30
        if (rule.area == RuleArea.Any || area == rule.area) score += 20
        if (ClickExecutor.isSelfSafeClickable(node)) score += 20
        if (clickSelection != null && clickSelection.node != node) score += 10
        if (appElapsedMs <= rule.validDurationMs) score += 20
        if (viewId.containsAdOrSplashSignal()) score += 10
        if (listOf(textValue, descriptionValue).any { it.containsAdSignal() }) score += 10
        if (defaultRule && area == RuleArea.TopCenter) score -= 20

        val onlyGenericClose = textRule != null &&
            closeNeedsAdContext.any { textRule.equals(it, ignoreCase = true) } &&
            !viewId.containsAdOrSplashSignal() &&
            listOf(textValue, descriptionValue).none { it.containsAdSignal() }
        if (onlyGenericClose) score -= 35
        val minScore = if (defaultRule && area == RuleArea.TopCenter) {
            rule.minScore + 20
        } else {
            rule.minScore
        }

        return ScoreEvaluation(
            rule = rule,
            score = score,
            minScore = minScore,
            matchedKeyword = matchedKeyword,
            area = area,
            passesMinScore = score >= minScore,
            failureReason = if (score >= minScore) "" else "score_below_min_score",
            defaultRuleAreaAllowed = if (defaultRule) defaultAreaAllowed else null,
            textKeywordIsStandaloneSkip = textKeywordIsStandaloneSkip,
            candidateAreaRatio = areaRatio,
            isLargeCandidateBounds = largeCandidate
        )
    }

    private fun matchedTextRule(values: List<String>, keywords: List<String>): String? {
        return values.firstNotNullOfOrNull { value ->
            val normalized = value.trim()
            if (normalized.isEmpty() || normalized.length > MAX_SKIP_TEXT_LENGTH) return@firstNotNullOfOrNull null
            val lower = normalized.lowercase(Locale.ROOT)
            if (blockedTextFragments.any { lower.contains(it) }) return@firstNotNullOfOrNull null
            keywords.firstOrNull { keyword -> lower.contains(keyword.lowercase(Locale.ROOT)) }
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
        return SafetyGuard.protectedPageKeywords.any { combined.contains(it) } ||
            listOf(
                "支付",
                "付款",
                "转账",
                "确认支付",
                "立即支付",
                "立即安装",
                "继续安装",
                "安装",
                "允许",
                "授权",
                "同意授权",
                "验证码",
                "登录",
                "注册",
                "password",
                "pay",
                "permission",
                "allow",
                "login",
                "sign in",
                "verify",
                "install"
            ).any { combined.contains(it) }
    }

    private fun AccessibilityNodeInfo.isInputNode(): Boolean {
        val classNameValue = className?.toString().orEmpty()
        return classNameValue.contains("EditText", ignoreCase = true)
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
            lower.contains("tt_") ||
            lower.contains("csj")
    }

    private fun String.normalizeForRuleMatch(): String {
        return lowercase(Locale.ROOT)
            .replace("-", "_")
            .replace(".", "_")
            .replace(":", "_")
    }

    internal fun isDefaultRuleAreaAllowedForCandidate(area: RuleArea): Boolean {
        return true
    }

    data class ScoreEvaluation(
        val rule: SkipRule,
        val score: Int,
        val minScore: Int,
        val matchedKeyword: String,
        val area: RuleArea,
        val passesMinScore: Boolean,
        val failureReason: String,
        val defaultRuleAreaAllowed: Boolean? = null,
        val textKeywordIsStandaloneSkip: Boolean = false,
        val candidateAreaRatio: Float = 0f,
        val isLargeCandidateBounds: Boolean = false
    )
}
