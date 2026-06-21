package com.example.skip.data

import com.example.skip.engine.HighRiskClickPolicy
import com.example.skip.engine.CoordinateFallbackMatcher
import com.example.skip.engine.SafetyGuard
import com.example.skip.model.MatchMode
import com.example.skip.model.RuleArea
import com.example.skip.model.SkipRule
import java.util.Locale

enum class RuleImportRiskLevel {
    HardBlock,
    ExtraConfirm
}

data class RuleImportRisk(
    val level: RuleImportRiskLevel,
    val code: String,
    val message: String
)

/**
 * Classifies untrusted local/JSON rule inputs before they reach persistent storage.
 * Hard blocks are non-bypassable; extra-confirm risks remain disabled after JSON import.
 */
object RuleImportRiskPolicy {
    const val MAX_JSON_FILE_BYTES = 256 * 1024
    const val MAX_JSON_NESTING_DEPTH = 16
    const val MAX_APP_COUNT = 50
    const val MAX_RULE_COUNT = 100
    const val MAX_RULES_PER_APP = 20
    const val MAX_REGEX_LENGTH = 256
    const val MAX_MATCH_VALUE_LENGTH = 128
    const val MIN_SAFE_SCORE = 60
    private const val MIN_CONTAINS_LENGTH = 2

    private val genericLooseViewIds = setOf(
        "x", "close", "btn", "button", "skip", "cancel", "dismiss", "ok"
    )
    private val sensitiveActivitySignals = listOf(
        "login", "sign_in", "signin", "register", "signup", "permission", "authorize",
        "authentication", "payment", "pay", "wallet", "bank", "settings", "installer", "install",
        "update", "upgrade", "登录", "注册", "授权", "权限", "支付", "钱包", "银行",
        "设置", "安装", "更新"
    )

    fun assess(
        rule: SkipRule,
        selfPackageName: String,
        importedFromJson: Boolean
    ): List<RuleImportRisk> {
        val risks = mutableListOf<RuleImportRisk>()

        if (rule.packageName.isBlank()) {
            risks += hardBlock("missing_package", "规则必须绑定明确包名")
        }
        if (selfPackageName.isNotBlank() && rule.packageName == selfPackageName) {
            risks += hardBlock("self_package", "规则不能作用于 Skip 自身")
        }
        if (SafetyGuard.isProtectedPackage(rule.packageName)) {
            risks += hardBlock("sensitive_package", "规则不能作用于系统或敏感应用包名")
        }
        if (rule.activityName != "*" && isSensitiveActivity(rule.activityName)) {
            risks += hardBlock("sensitive_activity", "规则不能作用于登录、支付、授权、设置、安装或更新页面")
        }

        HighRiskClickPolicy.evaluateRule(rule).takeUnless { it.allowed }?.let { decision ->
            risks += hardBlock(
                "high_risk_term",
                "规则包含高风险点击内容：${HighRiskClickPolicy.BLOCKED_REASON}（${decision.matchedTerm}）"
            )
        }

        risks += assessFieldLengths(rule)

        val regexPatterns = listOf(
            rule.textMatchMode to rule.matchTexts,
            rule.contentDescriptionMatchMode to rule.matchContentDescriptions,
            rule.viewIdMatchMode to rule.matchViewIds
        ).filter { it.first == MatchMode.Regex }.flatMap { it.second }
        if (regexPatterns.any(::isMatchAllRegex)) {
            risks += hardBlock("match_all_regex", "regex 不能匹配全部内容")
        }

        val containsValues = buildList {
            if (rule.textMatchMode == MatchMode.Contains) addAll(rule.matchTexts)
            if (rule.contentDescriptionMatchMode == MatchMode.Contains) {
                addAll(rule.matchContentDescriptions)
            }
        }
        if (containsValues.any { it.trim().length < MIN_CONTAINS_LENGTH }) {
            risks += hardBlock("short_contains", "contains 匹配词至少需要 $MIN_CONTAINS_LENGTH 个字符")
        }
        if (containsValues.any { it.trim().length in MIN_CONTAINS_LENGTH..3 }) {
            risks += extraConfirm("short_contains", "contains 匹配词较短，可能扩大匹配范围")
        }

        val pureViewIdRule = rule.matchTexts.isEmpty() &&
            rule.matchContentDescriptions.isEmpty() &&
            rule.matchViewIds.isNotEmpty()
        if (pureViewIdRule && rule.matchViewIds.any(::isLooseOrGenericViewId)) {
            risks += hardBlock("generic_view_id", "纯 View ID 规则必须使用完整、可识别的资源 ID")
        }

        val hasBroadRegex = regexPatterns.any(::isBroadRegex)
        if (hasBroadRegex && rule.area == RuleArea.Any) {
            risks += hardBlock("broad_regex_any_area", "过宽 regex 不能与 area=any 组合")
        }
        if (rule.minScore < MIN_SAFE_SCORE &&
            (hasBroadRegex || rule.area == RuleArea.Any || containsValues.any { it.length < 4 })
        ) {
            risks += hardBlock(
                "low_score_broad_match",
                "低 minScore 不能与宽匹配条件组合"
            )
        }

        rule.coordinateFallback?.takeIf { it.enabled }?.let { fallback ->
            if (rule.packageName.isBlank()) {
                risks += hardBlock("coordinate_missing_package", "坐标兜底必须绑定明确包名")
            }
            if (!fallback.hasAnchorRequirement()) {
                risks += hardBlock("coordinate_missing_anchor", "坐标兜底必须配置强锚点")
            }
            CoordinateFallbackMatcher.anchorValidationReason(fallback)?.let { reason ->
                risks += hardBlock("coordinate_untrusted_anchor", "坐标兜底锚点不可信：$reason")
            }
            risks += extraConfirm("coordinate_fallback", "坐标兜底会在普通节点匹配失败后尝试手势点击")
        }

        if (regexPatterns.isNotEmpty()) {
            risks += extraConfirm("regex", "regex 会扩大匹配范围")
        }
        if (rule.area == RuleArea.Any) {
            risks += extraConfirm("area_any", "area=any 会扩大点击区域")
        }
        if (pureViewIdRule && rule.matchViewIds.all(::isCompleteResourceId)) {
            risks += extraConfirm("pure_view_id", "纯 View ID 规则使用完整 View ID，仍需确认")
        }
        if (rule.minScore in MIN_SAFE_SCORE until 70) {
            risks += extraConfirm("near_min_score", "minScore 接近安全下限")
        }
        if (importedFromJson) {
            risks += extraConfirm("json_import", "第三方 JSON 规则会驱动无障碍点击，建议先观察再启用")
        }
        return risks.distinctBy { it.level to it.code }
    }

    private fun assessFieldLengths(rule: SkipRule): List<RuleImportRisk> {
        val risks = mutableListOf<RuleImportRisk>()
        val fields = listOf(
            "matchTexts" to rule.matchTexts,
            "matchContentDescriptions" to rule.matchContentDescriptions,
            "matchViewIds" to rule.matchViewIds,
            "anchorTexts" to rule.coordinateFallback?.anchorTexts.orEmpty(),
            "anchorContentDescriptions" to rule.coordinateFallback?.anchorContentDescriptions.orEmpty(),
            "anchorViewIds" to rule.coordinateFallback?.anchorViewIds.orEmpty()
        )
        fields.forEach { (fieldName, values) ->
            val maxLength = when (fieldName) {
                "matchTexts" -> if (rule.textMatchMode == MatchMode.Regex) MAX_REGEX_LENGTH else MAX_MATCH_VALUE_LENGTH
                "matchContentDescriptions" -> if (rule.contentDescriptionMatchMode == MatchMode.Regex) MAX_REGEX_LENGTH else MAX_MATCH_VALUE_LENGTH
                "matchViewIds" -> if (rule.viewIdMatchMode == MatchMode.Regex) MAX_REGEX_LENGTH else MAX_MATCH_VALUE_LENGTH
                else -> MAX_MATCH_VALUE_LENGTH
            }
            if (values.any { it.length > maxLength }) {
                risks += hardBlock("field_too_long", "$fieldName 长度不能超过 $maxLength")
            }
        }
        return risks
    }

    private fun isMatchAllRegex(pattern: String): Boolean {
        val normalized = pattern.replace("\\s+".toRegex(), "")
        return normalized in setOf(
            ".*", "^.*$", "(?s).*", "(?s)^.*$", "(?s:.*)", "^(?s:.*)$",
            "[\\s\\S]*", "^[\\s\\S]*$"
        )
    }

    private fun isBroadRegex(pattern: String): Boolean {
        val normalized = pattern.replace("\\s+".toRegex(), "")
        return normalized.contains(".*") || normalized.contains(".+") || isMatchAllRegex(pattern)
    }

    private fun isLooseOrGenericViewId(value: String): Boolean {
        val trimmed = value.trim()
        if (!isCompleteResourceId(trimmed)) return true
        val resourceName = trimmed.substringAfter(":id/", "")
        return resourceName.length < 3 || resourceName.lowercase(Locale.ROOT) in genericLooseViewIds
    }

    private fun isCompleteResourceId(value: String): Boolean {
        return value.matches(Regex("^[a-zA-Z][a-zA-Z0-9_.]*:id/[a-zA-Z][a-zA-Z0-9_]*$"))
    }

    private fun isSensitiveActivity(value: String): Boolean {
        val normalized = value.lowercase(Locale.ROOT)
        return sensitiveActivitySignals.any { normalized.contains(it.lowercase(Locale.ROOT)) }
    }

    private fun hardBlock(code: String, message: String): RuleImportRisk {
        return RuleImportRisk(RuleImportRiskLevel.HardBlock, code, message)
    }

    private fun extraConfirm(code: String, message: String): RuleImportRisk {
        return RuleImportRisk(RuleImportRiskLevel.ExtraConfirm, code, message)
    }
}
