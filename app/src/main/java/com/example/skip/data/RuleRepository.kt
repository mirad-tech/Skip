package com.example.skip.data

import android.content.Context

object RuleRepository {
    private const val KEY_KEYWORDS = "keywords"
    private const val KEY_VIEW_ID_KEYWORDS = "view_id_keywords"

    val defaultKeywords = listOf(
        "跳过",
        "跳过广告",
        "跳过此广告",
        "跳过开屏广告",
        "跳过视频广告",
        "立即跳过",
        "关闭广告",
        "关闭此广告",
        "关闭开屏广告",
        "关闭推广",
        "Skip",
        "skip",
        "Skip Ad",
        "Skip Ads",
        "skip ad",
        "skip ads",
        "Skip Video Ad"
    )

    val defaultViewIdKeywords = listOf(
        "skip",
        "skip_ad",
        "skipad",
        "ad_skip",
        "skip_btn",
        "btn_skip",
        "splash_skip",
        "splash_skip_btn",
        "splash_ad_skip",
        "ad_skip_btn",
        "tt_splash_skip",
        "tt_splash_skip_btn",
        "ksad_skip",
        "ksad_splash_skip",
        "bd_ad_skip",
        "gdt_skip",
        "close_ad",
        "ad_close",
        "close_btn"
    )

    fun getKeywords(context: Context): List<String> {
        return SettingsRepository.prefs(context)
            .getStringSet(KEY_KEYWORDS, defaultKeywords.toSet())
            .orEmpty()
            .cleanConfigItems()
    }

    fun saveKeywords(context: Context, keywords: Collection<String>) {
        SettingsRepository.prefs(context)
            .edit()
            .putStringSet(KEY_KEYWORDS, keywords.cleanConfigItems().toSet())
            .apply()
    }

    fun getViewIdKeywords(context: Context): List<String> {
        return SettingsRepository.prefs(context)
            .getStringSet(KEY_VIEW_ID_KEYWORDS, defaultViewIdKeywords.toSet())
            .orEmpty()
            .cleanConfigItems()
    }

    fun saveViewIdKeywords(context: Context, keywords: Collection<String>) {
        SettingsRepository.prefs(context)
            .edit()
            .putStringSet(KEY_VIEW_ID_KEYWORDS, keywords.cleanConfigItems().toSet())
            .apply()
    }

    internal fun Collection<String>.cleanConfigItems(): List<String> {
        return map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
    }
}
