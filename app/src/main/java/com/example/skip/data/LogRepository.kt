package com.example.skip.data

import android.content.Context
import com.example.skip.model.ClickLog

object LogRepository {
    private const val KEY_CLICK_LOGS = "click_logs"
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

    private fun ClickLog.serialize(): String {
        return listOf(
            timeMillis.toString(),
            packageName.safeField(),
            ruleName.safeField()
        ).joinToString(FIELD_SEPARATOR)
    }

    private fun String.deserializeLog(): ClickLog? {
        val parts = split(FIELD_SEPARATOR)
        if (parts.size != 3) return null
        val time = parts[0].toLongOrNull() ?: return null
        return ClickLog(
            timeMillis = time,
            packageName = parts[1],
            ruleName = parts[2]
        )
    }

    private fun String.safeField(): String {
        return replace(FIELD_SEPARATOR, " ").replace(ROW_SEPARATOR, " ")
    }
}
