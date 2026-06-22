package com.example.skip.engine

import java.util.Locale

object TextInputClearButtonPolicy {
    const val BLOCKED_REASON = "text_input_clear_button"

    private val inputIdSignals = listOf(
        "search",
        "query",
        "input",
        "edit_text",
        "edittext",
        "text_field",
        "textfield"
    )
    private val clearIdSignals = listOf("clear", "close", "delete")
    private val clearLabelSignals = listOf(
        "清除查询",
        "清除搜索",
        "清空搜索",
        "clear query",
        "clear search"
    )

    fun shouldBlockRuleCandidate(
        viewId: String,
        text: String,
        contentDescription: String
    ): Boolean {
        val normalizedViewId = viewId.normalizeForPolicy()
        val hasInputIdSignal = inputIdSignals.any(normalizedViewId::contains)
        val hasClearIdSignal = clearIdSignals.any(normalizedViewId::contains)
        if (hasInputIdSignal && hasClearIdSignal) return true

        val label = listOf(text, contentDescription)
            .joinToString(" ")
            .trim()
            .lowercase(Locale.ROOT)
        if (label.isBlank()) return false
        return clearLabelSignals.any { signal -> label.contains(signal) }
    }

    fun shouldBlockDefaultRuleCandidate(
        viewId: String,
        text: String,
        contentDescription: String
    ): Boolean {
        return shouldBlockRuleCandidate(viewId, text, contentDescription)
    }

    private fun String.normalizeForPolicy(): String {
        return lowercase(Locale.ROOT)
            .replace("-", "_")
            .replace(".", "_")
            .replace(":", "_")
    }
}
