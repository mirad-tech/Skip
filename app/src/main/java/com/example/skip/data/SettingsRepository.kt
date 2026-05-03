package com.example.skip.data

import android.content.Context

object SettingsRepository {
    private const val PREFS_NAME = "skip_helper_config"
    private const val KEY_MASTER_ENABLED = "master_enabled"
    private const val KEY_WHITELIST = "whitelist_packages"
    private const val KEY_SERVICE_CONNECTED_AT = "service_connected_at"
    private const val KEY_SERVICE_ACTIVE_AT = "service_active_at"
    private const val KEY_SERVICE_INTERRUPTED_AT = "service_interrupted_at"
    private const val KEY_LAST_CLICK_AT = "last_click_at"
    private const val KEY_LAST_FAILURE_REASON = "last_failure_reason"

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

    fun markServiceConnected(context: Context, timeMillis: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_SERVICE_CONNECTED_AT, timeMillis).apply()
    }

    fun markServiceActive(context: Context, timeMillis: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_SERVICE_ACTIVE_AT, timeMillis).apply()
    }

    fun markServiceInterrupted(context: Context, timeMillis: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_SERVICE_INTERRUPTED_AT, timeMillis).apply()
    }

    fun markLastClick(context: Context, timeMillis: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_LAST_CLICK_AT, timeMillis).apply()
    }

    fun setLastFailureReason(context: Context, reason: String) {
        prefs(context).edit().putString(KEY_LAST_FAILURE_REASON, reason).apply()
    }

    fun getServiceConnectedAt(context: Context): Long {
        return prefs(context).getLong(KEY_SERVICE_CONNECTED_AT, 0L)
    }

    fun getServiceActiveAt(context: Context): Long {
        return prefs(context).getLong(KEY_SERVICE_ACTIVE_AT, 0L)
    }

    fun getServiceInterruptedAt(context: Context): Long {
        return prefs(context).getLong(KEY_SERVICE_INTERRUPTED_AT, 0L)
    }

    fun getLastClickAt(context: Context): Long {
        return prefs(context).getLong(KEY_LAST_CLICK_AT, 0L)
    }

    fun getLastFailureReason(context: Context): String {
        return prefs(context).getString(KEY_LAST_FAILURE_REASON, "").orEmpty()
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
