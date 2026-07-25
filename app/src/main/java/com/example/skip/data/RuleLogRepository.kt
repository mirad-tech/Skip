package com.example.skip.data

import android.content.Context
import androidx.core.content.edit
import com.example.skip.model.RuleLog
import com.example.skip.model.RuleSource

internal object RuleLogRepository {
    private const val KEY_RULE_LOGS = "rule_logs"
    private const val MAX_RULE_LOG_COUNT = 100
    private const val FIELD_SEPARATOR = "\t"
    private const val ROW_SEPARATOR = "\n"
    fun addRuleLog(context: Context, log: RuleLog) {
        val logs = buildList {
            add(log)
            addAll(getRuleLogs(context))
        }.take(MAX_RULE_LOG_COUNT)

        SettingsRepository.prefs(context)
            .edit { putString(KEY_RULE_LOGS, logs.joinToString(ROW_SEPARATOR) { it.serialize() }) }
    }

    fun getRuleLogs(context: Context): List<RuleLog> {
        return SettingsRepository.prefs(context)
            .getString(KEY_RULE_LOGS, null)
            .orEmpty()
            .lineSequence()
            .mapNotNull { it.deserializeRuleLog() }
            .toList()
    }

    fun clearRuleLogs(context: Context) {
        SettingsRepository.prefs(context).edit { remove(KEY_RULE_LOGS) }
    }

    private fun RuleLog.serialize(): String {
        return listOf(
            timeMillis.toString(),
            source.value,
            ruleName.safeField(),
            targetApp.safeField(),
            success.toString(),
            reason.safeField()
        ).joinToString(FIELD_SEPARATOR)
    }

    private fun String.deserializeRuleLog(): RuleLog? {
        val parts = split(FIELD_SEPARATOR)
        if (parts.size != 6) return null
        val time = parts[0].toLongOrNull() ?: return null
        return RuleLog(
            timeMillis = time,
            source = RuleSource.fromValue(parts[1]),
            ruleName = parts[2],
            targetApp = parts[3],
            success = parts[4].toBooleanStrictOrNull() ?: false,
            reason = parts[5]
        )
    }

    private fun String.safeField(): String {
        return replace(FIELD_SEPARATOR, " ").replace(ROW_SEPARATOR, " ")
    }

}
