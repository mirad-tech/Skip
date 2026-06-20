package com.example.skip.engine

import com.example.skip.model.SkipRule
import java.util.Locale

object HighRiskClickPolicy {
    const val BLOCKED_REASON = "blocked_by_safety_policy"

    val blockedTerms = listOf(
        "同意",
        "授权",
        "允许",
        "付款",
        "支付",
        "购买",
        "确认支付",
        "登录",
        "注册",
        "隐私政策",
        "用户协议",
        "更新",
        "安装",
        "删除",
        "卸载",
        "转账",
        "发送",
        "提交",
        "验证码",
        "密码",
        "pay",
        "payment",
        "wallet",
        "login",
        "sign in",
        "permission",
        "allow",
        "install",
        "password",
        "verify"
    )
    private val blockedTermsBySpecificity = blockedTerms.sortedByDescending { it.length }
    private val normalizedBlockedTerms = blockedTermsBySpecificity.map { it.normalizeForPolicy() }

    fun evaluateTexts(values: Iterable<String>): HighRiskClickDecision {
        values.forEach { value ->
            val normalized = value.normalizeForPolicy()
            if (normalized.isBlank()) return@forEach
            normalizedBlockedTerms.forEachIndexed { index, normalizedTerm ->
                if (normalized.contains(normalizedTerm)) {
                    return HighRiskClickDecision(
                        allowed = false,
                        reason = BLOCKED_REASON,
                        matchedTerm = blockedTermsBySpecificity[index],
                        matchedText = value
                    )
                }
            }
        }
        return HighRiskClickDecision.Allowed
    }

    fun evaluateRule(rule: SkipRule, includeCoordinateAnchors: Boolean = true): HighRiskClickDecision {
        val values = buildList {
            add(rule.name)
            addAll(rule.matchTexts)
            addAll(rule.matchContentDescriptions)
            addAll(rule.matchViewIds)
            if (includeCoordinateAnchors) {
                rule.coordinateFallback?.let { fallback ->
                    addAll(fallback.anchorTexts)
                    addAll(fallback.anchorContentDescriptions)
                    addAll(fallback.anchorViewIds)
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
            .replace("-", "")
            .replace("_", "")
            .replace(".", "")
            .replace(":", "")
            .replace("/", "")
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
