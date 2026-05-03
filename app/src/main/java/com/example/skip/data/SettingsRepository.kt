package com.example.skip.data

import android.content.Context

object SettingsRepository {
    private const val PREFS_NAME = "skip_helper_config"
    private const val KEY_MASTER_ENABLED = "master_enabled"
    private const val KEY_WHITELIST = "whitelist_packages"

    fun isMasterEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_MASTER_ENABLED, true)
    }

    fun setMasterEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MASTER_ENABLED, enabled).apply()
    }

    fun getWhitelistPackages(context: Context): List<String> {
        return prefs(context)
            .getStringSet(KEY_WHITELIST, emptySet())
            .orEmpty()
            .cleanPackageNames()
    }

    fun saveWhitelistPackages(context: Context, packages: Collection<String>) {
        prefs(context)
            .edit()
            .putStringSet(KEY_WHITELIST, packages.cleanPackageNames().toSet())
            .apply()
    }

    internal fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun Collection<String>.cleanPackageNames(): List<String> {
        return map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
    }
}
