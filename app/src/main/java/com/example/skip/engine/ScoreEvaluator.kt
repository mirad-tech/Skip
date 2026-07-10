package com.example.skip.engine

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.skip.model.MatchMode
import com.example.skip.model.RuleArea
import com.example.skip.model.RuleSource
import com.example.skip.model.SkipRule
import java.util.Locale

object ScoreEvaluator {
    private const val MAX_SKIP_TEXT_LENGTH = 32
    private const val TRUSTED_GENERIC_CLOSE_BONUS = 15
    private const val MAX_TRUSTED_GENERIC_CLOSE_AREA_RATIO = 0.02f
    private val splitAdRegex = Regex("[^a-z0-9]+")
    private val allowedGenericSkipRegex1 = Regex("^跳过\\s*\\d{1,2}\\s*[sS]?$")
    private val allowedGenericSkipRegex2 = Regex("^skip\\s*\\d{1,2}\\s*[sS]?$", RegexOption.IGNORE_CASE)

    private val blockedTextFragments = listOf(
        "跳过登录",
        "跳过验证",
        "跳过绑定",
        "跳过设置",
        "跳过登录",
        "跳过更新",
        "跳过授权",
        "跳过此步骤",
        "不跳过",
        "skip login",
        "skip verification",
        "skip setup"
    )

    private val closeNeedsAdContext = listOf(
        "关闭",
        "close",
        "×",
        "✕",
        "x",
        "我知道了",
        "知道了"
    )

    private val trustedGenericClosePackages = emptySet<String>()

    internal fun evaluate(
        node: AccessibilityNodeInfo,
        rule: SkipRule,
        appElapsedMs: Long,
        resolution: ClickCandidateResolution
    ): ScoreEvaluation? {
        val candidate = resolution.candidate
        if (!candidate.visibleToUser) return null
        if (!candidate.enabled) return null
        if (candidate.password || candidate.input) return null
        if (appElapsedMs > rule.validDurationMs) {
            return ScoreEvaluation(rule, 0, rule.minScore, "", areaInScreen(candidate.bounds), false, "skipped_by_time_window")
        }

        val textValue = candidate.text
        val descriptionValue = candidate.contentDescription
        val viewId = candidate.viewId
        val defaultRule = rule.source == RuleSource.BuiltIn
        if (SafetyGuard.isSensitiveText(textValue) || SafetyGuard.isSensitiveText(descriptionValue)) {
            return ScoreEvaluation(
                rule = rule,
                score = 0,
                minScore = rule.minScore,
                matchedKeyword = textValue.ifBlank { descriptionValue }.ifBlank { rule.name },
                area = areaInScreen(candidate.bounds),
                passesMinScore = false,
                failureReason = "sensitive_skip_semantic"
            )
        }
        if (TextInputClearButtonPolicy.shouldBlockRuleCandidate(
                viewId = viewId,
                text = textValue,
                contentDescription = descriptionValue
            )
        ) {
            return ScoreEvaluation(
                rule,
                score = 0,
                minScore = rule.minScore,
                matchedKeyword = TextInputClearButtonPolicy.BLOCKED_REASON,
                area = areaInScreen(candidate.bounds),
                passesMinScore = false,
                failureReason = TextInputClearButtonPolicy.BLOCKED_REASON
            )
        }
        val textRule = matchedTextRule(listOf(textValue), rule.matchTexts, rule.textMatchMode)
        val descriptionRule = matchedTextRule(
            listOf(descriptionValue),
            rule.matchContentDescriptions,
            rule.contentDescriptionMatchMode
        )
        val idRule = matchedIdRule(viewId, rule.matchViewIds, rule.viewIdMatchMode)

        if (textRule == null && descriptionRule == null && idRule == null) return null

        var score = 0
        val area = areaInScreen(candidate.bounds)
        val matchedKeyword = textRule ?: descriptionRule ?: idRule ?: rule.name
        if (isDangerousButtonText(textValue, descriptionValue)) {
            return ScoreEvaluation(
                rule,
                score = 0,
                minScore = rule.minScore,
                matchedKeyword = matchedKeyword,
                area = area,
                passesMinScore = false,
                failureReason = HighRiskClickPolicy.BLOCKED_REASON
            )
        }
        val textKeywordIsStandaloneSkip = DefaultStandaloneSkipPolicy
            .isStandaloneSkipLabel(textValue, descriptionValue)
        val areaRatio = ClickExecutor.areaRatio(candidate.bounds)
        val largeCandidate = ClickExecutor.isLargeDefaultCandidate(candidate.bounds)
        val defaultAreaAllowed = isDefaultRuleAreaAllowedForCandidate(area)
        var clickSelection = resolution.strictSelection
        var standaloneSkipAllowed = false
        if (textKeywordIsStandaloneSkip) {
            if (!defaultRule) {
                return ScoreEvaluation(
                    rule,
                    score = 0,
                    minScore = rule.minScore,
                    matchedKeyword = matchedKeyword,
                    area = area,
                    passesMinScore = false,
                    failureReason = "standalone_skip_forbidden",
                    defaultRuleAreaAllowed = null,
                    textKeywordIsStandaloneSkip = true,
                    candidateAreaRatio = areaRatio,
                    isLargeCandidateBounds = largeCandidate,
                    clickSelection = clickSelection
                )
            }
            val standaloneDecision = DefaultStandaloneSkipPolicy.evaluate(
                DefaultStandaloneSkipContext(
                    ruleSource = rule.source,
                    appElapsedMs = appElapsedMs,
                    area = area,
                    candidateAreaRatio = areaRatio,
                    candidate = candidate,
                    actionPath = resolution.actionPathFor(resolution.relaxedSelection),
                    ancestorSafetyTexts = resolution.ancestorSafetyTexts
                )
            )
            if (!standaloneDecision.allowed) {
                return ScoreEvaluation(
                    rule,
                    score = 0,
                    minScore = rule.minScore,
                    matchedKeyword = matchedKeyword,
                    area = area,
                    passesMinScore = false,
                    failureReason = standaloneDecision.reason,
                    defaultRuleAreaAllowed = defaultAreaAllowed,
                    textKeywordIsStandaloneSkip = true,
                    candidateAreaRatio = areaRatio,
                    isLargeCandidateBounds = largeCandidate,
                    clickSelection = clickSelection
                )
            }
            clickSelection = resolution.relaxedSelection
            standaloneSkipAllowed = true
        }
        if (!standaloneSkipAllowed &&
            idRule == null &&
            matchedKeyword.isGenericSkipKeyword() &&
            !isAllowedDefaultGenericSkipLabel(textValue, descriptionValue)
        ) {
            return ScoreEvaluation(
                rule,
                score = 0,
                minScore = rule.minScore,
                matchedKeyword = matchedKeyword,
                area = area,
                passesMinScore = false,
                failureReason = "generic_skip_context_missing",
                clickSelection = clickSelection
            )
        }

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

        val onlyGenericClose = (textRule != null || descriptionRule != null) &&
            closeNeedsAdContext.any { matchedKeyword.equals(it, ignoreCase = true) } &&
            idRule == null &&
            !viewId.containsAdOrSplashSignal() &&
            listOf(textValue, descriptionValue).none { it.containsAdSignal() }
        if (onlyGenericClose) score -= 35
        score += trustedGenericCloseBonusForDefaultRule(
            packageName = rule.packageName,
            matchedKeyword = matchedKeyword,
            area = area,
            candidateAreaRatio = areaRatio,
            onlyGenericClose = onlyGenericClose
        )
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
            isLargeCandidateBounds = largeCandidate,
            clickSelection = clickSelection,
            standaloneSkipAllowed = standaloneSkipAllowed
        )
    }

    private fun matchedTextRule(values: List<String>, keywords: List<String>, mode: MatchMode): String? {
        return values.firstNotNullOfOrNull { value ->
            val normalized = value.trim()
            if (normalized.isEmpty() || normalized.length > MAX_SKIP_TEXT_LENGTH) return@firstNotNullOfOrNull null
            val lower = normalized.lowercase(Locale.ROOT)
            if (blockedTextFragments.any { lower.contains(it) }) return@firstNotNullOfOrNull null
            keywords.firstOrNull { keyword -> lower.matchesRuleKeyword(keyword, mode) }
        }
    }

    private fun matchedIdRule(viewId: String, keywords: List<String>, mode: MatchMode): String? {
        val normalizedViewId = viewId.normalizeForRuleMatch()
        if (normalizedViewId.isEmpty()) return null
        if (mode == MatchMode.Regex) {
            return keywords.firstOrNull { keyword ->
                viewId.matchesRuleKeyword(keyword, MatchMode.Regex) ||
                    normalizedViewId.matchesLegacyNormalizedRegex(keyword)
            }
        }
        return keywords.firstOrNull { keyword ->
            normalizedViewId.matchesRuleKeyword(keyword.normalizeForRuleMatch(), mode)
        }
    }

    private fun String.matchesRuleKeyword(keyword: String, mode: MatchMode): Boolean {
        val trimmedKeyword = keyword.trim()
        if (trimmedKeyword.isEmpty()) return false
        return when (mode) {
            MatchMode.Contains -> contains(trimmedKeyword.lowercase(Locale.ROOT))
            MatchMode.Exact -> this == trimmedKeyword.lowercase(Locale.ROOT)
            MatchMode.Regex -> SafeRegexMatcher.containsMatch(trimmedKeyword, this)
        }
    }

    private fun String.matchesLegacyNormalizedRegex(keyword: String): Boolean {
        return SafeRegexMatcher.containsMatch(keyword.normalizeForRuleMatch(), this)
    }

    internal fun areaInScreen(bounds: Rect): RuleArea {
        val screenWidth = android.content.res.Resources.getSystem().displayMetrics.widthPixels
            .coerceAtLeast(1)
        val screenHeight = android.content.res.Resources.getSystem().displayMetrics.heightPixels
            .coerceAtLeast(1)
        return areaInScreen(bounds, screenWidth, screenHeight)
    }

    internal fun areaInScreen(
        bounds: Rect,
        screenWidth: Int,
        screenHeight: Int
    ): RuleArea {
        if (bounds.left >= bounds.right || bounds.top >= bounds.bottom) return RuleArea.Any
        val width = screenWidth.coerceAtLeast(1)
        val height = screenHeight.coerceAtLeast(1)
        val centerX = (bounds.left + bounds.right) shr 1
        val centerY = (bounds.top + bounds.bottom) shr 1
        val column = when {
            centerX < width / 3f -> 0
            centerX < width * 2f / 3f -> 1
            else -> 2
        }
        val row = when {
            centerY < height / 3f -> 0
            centerY < height * 2f / 3f -> 1
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

    private fun isDangerousButtonText(text: String, contentDescription: String): Boolean {
        val combined = listOf(text, contentDescription)
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

    private fun String.containsAdSignal(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains("广告") ||
            lower.hasAdToken() ||
            lower.contains("splash") ||
            lower.contains("skip")
    }

    private fun String.containsAdOrSplashSignal(): Boolean {
        val lower = normalizeForRuleMatch()
        return lower.hasAdToken() ||
            lower.contains("splash") ||
            lower.contains("skip") ||
            lower.contains("gdt") ||
            lower.contains("ksad") ||
            lower.contains("tt_") ||
            lower.contains("csj")
    }

    private fun String.hasAdToken(): Boolean {
        return split(splitAdRegex)
            .any { token -> token == "ad" || token == "ads" }
    }

    private fun String.isGenericSkipKeyword(): Boolean {
        return trim().equals("跳过", ignoreCase = true) ||
            trim().equals("skip", ignoreCase = true)
    }

    private fun isAllowedDefaultGenericSkipLabel(textValue: String, descriptionValue: String): Boolean {
        return listOf(textValue, descriptionValue).any { value ->
            val label = value.trim()
            if (label.isBlank()) return@any false
            if (label.containsExplicitAdOrSplashSignal()) return@any true
            allowedGenericSkipRegex1.matches(label) || allowedGenericSkipRegex2.matches(label)
        }
    }

    private fun String.containsExplicitAdOrSplashSignal(): Boolean {
        val lower = normalizeForRuleMatch()
        return lower.hasAdToken() ||
            lower.contains("广告") ||
            lower.contains("splash") ||
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

    internal fun trustedGenericCloseBonusForDefaultRule(
        packageName: String,
        matchedKeyword: String,
        area: RuleArea,
        candidateAreaRatio: Float,
        onlyGenericClose: Boolean
    ): Int {
        if (!onlyGenericClose) return 0
        if (packageName.lowercase(Locale.ROOT) !in trustedGenericClosePackages) return 0
        if (area != RuleArea.TopRight) return 0
        if (candidateAreaRatio <= 0f || candidateAreaRatio > MAX_TRUSTED_GENERIC_CLOSE_AREA_RATIO) return 0
        val isGenericCloseKeyword = closeNeedsAdContext.any { closeKeyword ->
            matchedKeyword.equals(closeKeyword, ignoreCase = true)
        }
        return if (isGenericCloseKeyword) TRUSTED_GENERIC_CLOSE_BONUS else 0
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
        val isLargeCandidateBounds: Boolean = false,
        val clickSelection: ClickTargetSelection? = null,
        val standaloneSkipAllowed: Boolean = false
    )
}
