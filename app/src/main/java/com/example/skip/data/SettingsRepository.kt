package com.example.skip.data

import android.content.Context
import android.content.pm.ApplicationInfo

object SettingsRepository {
    private const val PREFS_NAME = "skip_helper_config"
    private const val ICON_PREFS_NAME = "skip_icon_config"
    private const val KEY_MASTER_ENABLED = "master_enabled"
    private const val KEY_SUCCESS_TOAST_ENABLED = "success_toast_enabled"
    private const val KEY_DEBUG_TOAST_ENABLED = "debug_toast_enabled"
    private const val KEY_BLACKLIST = "blacklist_packages"
    private const val KEY_ICON_SCHEME = "icon_scheme"
    private const val KEY_ICON_DEFAULT_MIGRATED = "icon_default_sky_blue_migration_v1"
    private const val KEY_SERVICE_CONNECTED_AT = "service_connected_at"
    private const val KEY_SERVICE_ACTIVE_AT = "service_active_at"
    private const val KEY_SERVICE_INTERRUPTED_AT = "service_interrupted_at"
    private const val KEY_LAST_CLICK_AT = "last_click_at"
    private const val KEY_LAST_FAILURE_REASON = "last_failure_reason"
    private const val KEY_SAFETY_MODE_ENABLED = "safety_mode_enabled"

    fun isMasterEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_MASTER_ENABLED, true)
    }

    fun setMasterEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MASTER_ENABLED, enabled).apply()
    }

    fun isSuccessToastEnabled(context: Context): Boolean {
        return isDebugToastEnabled(context)
    }

    fun setSuccessToastEnabled(context: Context, enabled: Boolean) {
        setDebugToastEnabled(context, enabled)
    }

    fun isDebugToastEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_DEBUG_TOAST_ENABLED, false)
    }

    fun setDebugToastEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_DEBUG_TOAST_ENABLED, enabled)
            .putBoolean(KEY_SUCCESS_TOAST_ENABLED, enabled)
            .apply()
    }

    fun isSafetyModeEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SAFETY_MODE_ENABLED, isDebuggable(context))
    }

    fun setSafetyModeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SAFETY_MODE_ENABLED, enabled).apply()
    }

    fun getBlacklistPackages(context: Context): List<String> {
        return prefs(context)
            .getStringSet(KEY_BLACKLIST, emptySet())
            .orEmpty()
            .cleanPackageNames()
    }

    fun saveBlacklistPackages(context: Context, packages: Collection<String>) {
        prefs(context)
            .edit()
            .putStringSet(KEY_BLACKLIST, packages.cleanPackageNames().toSet())
            .apply()
    }

    fun isBlacklisted(context: Context, packageName: String): Boolean {
        return packageName.trim() in getBlacklistPackages(context)
    }

    fun setBlacklisted(context: Context, packageName: String, blacklisted: Boolean) {
        val cleanPackage = packageName.trim()
        if (cleanPackage.isBlank()) return
        val packages = getBlacklistPackages(context).toMutableSet()
        if (blacklisted) {
            packages += cleanPackage
        } else {
            packages -= cleanPackage
        }
        saveBlacklistPackages(context, packages)
    }

    fun clearBlacklist(context: Context) {
        prefs(context).edit().remove(KEY_BLACKLIST).apply()
    }

    fun getIconScheme(context: Context): String {
        return iconPrefs(context).getString(KEY_ICON_SCHEME, "sky_blue").orEmpty()
            .ifBlank { "sky_blue" }
    }

    fun setIconScheme(context: Context, schemeId: String) {
        iconPrefs(context).edit().putString(KEY_ICON_SCHEME, schemeId).apply()
    }

    fun migrateIconSchemeDefault(context: Context) {
        val iconPrefs = iconPrefs(context)
        if (iconPrefs.getBoolean(KEY_ICON_DEFAULT_MIGRATED, false)) return

        val legacyScheme = prefs(context).getString(KEY_ICON_SCHEME, null)
            ?.takeIf { it.isNotBlank() }
        val storedScheme = iconPrefs.getString(KEY_ICON_SCHEME, null)
            ?.takeIf { it.isNotBlank() }
        val scheme = when (storedScheme ?: legacyScheme) {
            null, "obsidian_gold" -> "sky_blue"
            else -> storedScheme ?: legacyScheme ?: "sky_blue"
        }

        iconPrefs.edit()
            .putString(KEY_ICON_SCHEME, scheme)
            .putBoolean(KEY_ICON_DEFAULT_MIGRATED, true)
            .apply()
        prefs(context).edit().remove(KEY_ICON_SCHEME).apply()
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

    private fun iconPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(ICON_PREFS_NAME, Context.MODE_PRIVATE)

    private fun isDebuggable(context: Context): Boolean {
        return context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }

    private fun Collection<String>.cleanPackageNames(): List<String> {
        return map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
    }
}
