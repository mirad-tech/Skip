package com.example.skip.data

import android.content.Context
import com.example.skip.model.DuplicateStrategy
import com.example.skip.model.RuleImportResult
import com.example.skip.model.RuleLog
import com.example.skip.model.RuleSource
import com.example.skip.model.SkipRule

object RuleLifecycleRepository {
    data class MutationResult(
        val savedCount: Int = 0,
        val log: RuleLog? = null
    )

    fun parseJsonImport(
        context: Context,
        text: String,
        selfPackageName: String = context.packageName,
        now: Long = System.currentTimeMillis()
    ): RuleImportResult {
        val result = RuleImportManager.parseRulePackage(text, selfPackageName)
        if (!result.success) {
            LogRepository.addRuleLog(context, jsonImportFailedLog(result.errorMessage, now))
        }
        return result
    }

    fun saveJsonImport(
        context: Context,
        result: RuleImportResult,
        strategy: DuplicateStrategy,
        now: Long = System.currentTimeMillis()
    ): MutationResult {
        val savedCount = RuleRepository.saveImportResult(context, result, strategy)
        val log = jsonImportSavedLog(result, savedCount, now)
        log?.let { LogRepository.addRuleLog(context, it) }
        return MutationResult(savedCount = savedCount, log = log)
    }

    fun saveLocalRule(
        context: Context,
        rule: SkipRule,
        now: Long = System.currentTimeMillis()
    ): MutationResult {
        RuleRepository.createLocalPackageIfNeeded(context)
        RuleRepository.upsertRule(context, rule)
        val log = localRuleSavedLog(rule, now)
        LogRepository.addRuleLog(context, log)
        return MutationResult(savedCount = 1, log = log)
    }

    internal fun localRuleSavedLog(rule: SkipRule, now: Long): RuleLog {
        return RuleLog(
            timeMillis = now,
            source = RuleSource.UserSimple,
            ruleName = rule.name,
            targetApp = rule.appName.ifBlank { rule.packageName },
            success = true,
            reason = "已保存"
        )
    }

    internal fun jsonImportSavedLog(
        result: RuleImportResult,
        savedCount: Int,
        now: Long
    ): RuleLog? {
        val rulePackage = result.rulePackage ?: return null
        return RuleLog(
            timeMillis = now,
            source = RuleSource.JsonFile,
            ruleName = rulePackage.name,
            targetApp = "${result.parsedAppCount} 个 App",
            success = true,
            reason = "导入 $savedCount 条规则"
        )
    }

    internal fun jsonImportFailedLog(errorMessage: String, now: Long): RuleLog {
        return RuleLog(
            timeMillis = now,
            source = RuleSource.JsonFile,
            ruleName = "JSON 文件导入",
            targetApp = "-",
            success = false,
            reason = errorMessage
        )
    }
}
