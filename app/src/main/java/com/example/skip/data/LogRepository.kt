package com.example.skip.data

import android.content.Context
import com.example.skip.model.ClickLog
import com.example.skip.model.RuleLog
import com.example.skip.model.RuleSource

object LogRepository {
    private const val KEY_CLICK_LOGS = "click_logs"
    private const val KEY_RULE_LOGS = "rule_logs"
    private const val MAX_LOG_COUNT = 80
    private const val FIELD_SEPARATOR = "\t"
    private const val ROW_SEPARATOR = "\n"

    fun addClickLog(context: Context, log: ClickLog) {
        val logs = buildList {
            add(log)
            addAll(getClickLogs(context))
        }.take(MAX_LOG_COUNT)

        SettingsRepository.prefs(context)
            .edit()
            .putString(KEY_CLICK_LOGS, logs.joinToString(ROW_SEPARATOR) { it.serialize() })
            .apply()
    }

    fun getClickLogs(context: Context): List<ClickLog> {
        return SettingsRepository.prefs(context)
            .getString(KEY_CLICK_LOGS, null)
            .orEmpty()
            .lineSequence()
            .mapNotNull { it.deserializeLog() }
            .toList()
    }

    fun clearClickLogs(context: Context) {
        SettingsRepository.prefs(context).edit().remove(KEY_CLICK_LOGS).apply()
    }

    fun addRuleLog(context: Context, log: RuleLog) {
        val logs = buildList {
            add(log)
            addAll(getRuleLogs(context))
        }.take(MAX_LOG_COUNT)

        SettingsRepository.prefs(context)
            .edit()
            .putString(KEY_RULE_LOGS, logs.joinToString(ROW_SEPARATOR) { it.serialize() })
            .apply()
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
        SettingsRepository.prefs(context).edit().remove(KEY_RULE_LOGS).apply()
    }

    private fun ClickLog.serialize(): String {
        return listOf(
            timeMillis.toString(),
            packageName.safeField(),
            appName.safeField(),
            ruleName.safeField(),
            success.toString(),
            reason.safeField()
        ).joinToString(FIELD_SEPARATOR)
    }

    private fun String.deserializeLog(): ClickLog? {
        val parts = split(FIELD_SEPARATOR)
        if (parts.size == 3) {
            val time = parts[0].toLongOrNull() ?: return null
            return ClickLog(
                timeMillis = time,
                packageName = parts[1],
                ruleName = parts[2]
            )
        }
        if (parts.size != 6) return null
        val time = parts[0].toLongOrNull() ?: return null
        return ClickLog(
            timeMillis = time,
            packageName = parts[1],
            appName = parts[2],
            ruleName = parts[3],
            success = parts[4].toBooleanStrictOrNull() ?: true,
            reason = parts[5]
        )
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
