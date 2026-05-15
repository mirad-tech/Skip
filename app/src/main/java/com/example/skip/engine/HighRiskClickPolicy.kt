package com.example.skip.engine

import com.example.skip.model.SkipRule
import java.util.Locale

object HighRiskClickPolicy {
    const val BLOCKED_REASON = "blocked_by_safety_policy"

    val blockedTerms = listOf(
        "同意",
        "授权",
        "允许",
        "支付",
        "购买",
        "确认支付",
        "登录",
        "注册",
        "隐私政策",
        "用户协议",
        "安装",
        "删除",
        "卸载",
        "转账",
        "发送",
        "提交"
    )
    private val blockedTermsBySpecificity = blockedTerms.sortedByDescending { it.length }

    fun evaluateTexts(values: Iterable<String>): HighRiskClickDecision {
        values.forEach { value ->
            val normalized = value.normalizeForPolicy()
            if (normalized.isBlank()) return@forEach
            blockedTermsBySpecificity.firstOrNull { term ->
                normalized.contains(term.normalizeForPolicy())
            }?.let { term ->
                return HighRiskClickDecision(
                    allowed = false,
                    reason = BLOCKED_REASON,
                    matchedTerm = term,
                    matchedText = value
                )
            }
        }
        return HighRiskClickDecision.Allowed
    }

    fun evaluateRule(rule: SkipRule, includeCoordinateAnchors: Boolean = true): HighRiskClickDecision {
        val values = buildList {
            add(rule.name)
            addAll(rule.matchTexts)
            addAll(rule.matchContentDescriptions)
            if (includeCoordinateAnchors) {
                rule.coordinateFallback?.let { fallback ->
                    addAll(fallback.anchorTexts)
                    addAll(fallback.anchorContentDescriptions)
                }
            }
        }
        return evaluateTexts(values)
    }

    fun isHighRiskText(text: String): Boolean {
        return !evaluateTexts(listOf(text)).allowed
    }

    private fun String.normalizeForPolicy(): String {
        return lowercase(Locale.ROOT)
            .replace("\\s+".toRegex(), "")
            .replace("　", "")
            .trim()
    }
}

data class HighRiskClickDecision(
    val allowed: Boolean,
    val reason: String = "",
    val matchedTerm: String = "",
    val matchedText: String = ""
) {
    companion object {
        val Allowed = HighRiskClickDecision(allowed = true)
    }
}
