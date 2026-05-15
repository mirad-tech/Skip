package com.example.skip.model

data class RuleImportResult(
    val success: Boolean,
    val errorMessage: String = "",
    val warningMessages: List<String> = emptyList(),
    val parsedAppCount: Int = 0,
    val parsedRuleCount: Int = 0,
    val rulePackage: RulePackage? = null,
    val rules: List<SkipRule> = emptyList(),
    val appPolicies: List<AppPolicy> = emptyList()
)

enum class DuplicateStrategy(val label: String) {
    Override("覆盖"),
    Skip("跳过"),
    Merge("合并")
}
