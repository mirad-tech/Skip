package com.example.skip

import android.content.Context

object SkipConfigStore {
    private const val PREFS_NAME = "skip_helper_config"
    private const val KEY_MASTER_ENABLED = "master_enabled"
    private const val KEY_KEYWORDS = "keywords"
    private const val KEY_VIEW_ID_KEYWORDS = "view_id_keywords"
    private const val KEY_WHITELIST = "whitelist_packages"

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
        "Skip Ad",
        "Skip Ads",
        "Skip",
        "skip",
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

    fun isMasterEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_MASTER_ENABLED, true)
    }

    fun setMasterEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MASTER_ENABLED, enabled).apply()
    }

    fun getKeywords(context: Context): List<String> {
        return prefs(context)
            .getStringSet(KEY_KEYWORDS, defaultKeywords.toSet())
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
    }

    fun setKeywords(context: Context, keywords: Collection<String>) {
        val normalized = keywords
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        prefs(context).edit().putStringSet(KEY_KEYWORDS, normalized).apply()
    }

    fun getViewIdKeywords(context: Context): List<String> {
        return prefs(context)
            .getStringSet(KEY_VIEW_ID_KEYWORDS, defaultViewIdKeywords.toSet())
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
    }

    fun setViewIdKeywords(context: Context, keywords: Collection<String>) {
        val normalized = keywords
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        prefs(context).edit().putStringSet(KEY_VIEW_ID_KEYWORDS, normalized).apply()
    }

    fun getWhitelistPackages(context: Context): Set<String> {
        return prefs(context)
            .getStringSet(KEY_WHITELIST, emptySet())
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    fun setWhitelistPackages(context: Context, packages: Collection<String>) {
        val normalized = packages
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        prefs(context).edit().putStringSet(KEY_WHITELIST, normalized).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
