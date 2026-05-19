package com.example.skip.data

import android.content.Context
import androidx.core.content.edit
import com.example.skip.model.AppPolicy
import com.example.skip.util.AccessibilityUtil
import org.json.JSONArray
import org.json.JSONObject

object SettingsRepository {
    private const val PREFS_NAME = "skip_helper_config"
    private const val ICON_PREFS_NAME = "skip_icon_config"
    private const val KEY_MASTER_ENABLED = "master_enabled"
    private const val KEY_SUCCESS_TOAST_ENABLED = "success_toast_enabled"
    private const val KEY_SUCCESS_TOAST_MIGRATED = "success_toast_default_enabled_migration_v1"
    private const val KEY_DEBUG_TOAST_ENABLED = "debug_toast_enabled"
    private const val KEY_BLACKLIST = "blacklist_packages"
    private const val KEY_APP_POLICIES_JSON = "app_policies_json_v1"
    private const val KEY_ICON_SCHEME = "icon_scheme"
    private const val KEY_ICON_DEFAULT_MIGRATED = "icon_default_sky_blue_migration_v1"
    private const val KEY_SERVICE_CONNECTED_AT = "service_connected_at"
    private const val KEY_SERVICE_ACTIVE_AT = "service_active_at"
    private const val KEY_SERVICE_INTERRUPTED_AT = "service_interrupted_at"
    private const val KEY_LAST_CLICK_AT = "last_click_at"
    private const val KEY_LAST_FAILURE_REASON = "last_failure_reason"
    private const val KEY_SAFETY_MODE_ENABLED = "safety_mode_enabled"
    private const val KEY_RELEASE_DISCLOSURE_ACCEPTED = "release_disclosure_accepted_v1"

    data class DiagnosticSnapshot(
        val masterEnabled: Boolean,
        val safetyModeEnabled: Boolean,
        val debugLogEnabled: Boolean,
        val releaseDisclosureAccepted: Boolean,
        val accessibilityServiceEnabled: Boolean,
        val serviceConnectedAt: Long,
        val serviceActiveAt: Long,
        val serviceInterruptedAt: Long,
        val lastClickAt: Long,
        val lastFailureReason: String,
        val appPolicies: List<AppPolicy>,
        val otherAccessibilityServices: List<String> = emptyList()
    )

    fun isMasterEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_MASTER_ENABLED, true)
    }

    fun setMasterEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_MASTER_ENABLED, enabled) }
    }

    fun isSuccessToastEnabled(context: Context): Boolean {
        migrateSuccessToastDefault(context)
        return prefs(context).getBoolean(KEY_SUCCESS_TOAST_ENABLED, true)
    }

    fun setSuccessToastEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(KEY_SUCCESS_TOAST_ENABLED, enabled)
            putBoolean(KEY_SUCCESS_TOAST_MIGRATED, true)
        }
    }

    fun isDebugToastEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_DEBUG_TOAST_ENABLED, false)
    }

    fun setDebugToastEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_DEBUG_TOAST_ENABLED, enabled) }
    }

    private fun migrateSuccessToastDefault(context: Context) {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_SUCCESS_TOAST_MIGRATED, false)) return
        prefs.edit {
            putBoolean(KEY_SUCCESS_TOAST_ENABLED, true)
            putBoolean(KEY_SUCCESS_TOAST_MIGRATED, true)
        }
    }

    fun isSafetyModeEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SAFETY_MODE_ENABLED, defaultSafetyModeEnabled())
    }

    internal fun defaultSafetyModeEnabled(): Boolean = false

    fun setSafetyModeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_SAFETY_MODE_ENABLED, enabled) }
    }

    fun hasAcceptedReleaseDisclosure(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_RELEASE_DISCLOSURE_ACCEPTED, false)
    }

    fun setReleaseDisclosureAccepted(context: Context, accepted: Boolean) {
        prefs(context).edit { putBoolean(KEY_RELEASE_DISCLOSURE_ACCEPTED, accepted) }
    }

    fun getBlacklistPackages(context: Context): List<String> {
        val legacy = getLegacyBlacklistPackages(context)
        val disabledByPolicy = getAppPolicies(context)
            .filterNot { it.defaultRuleEnabled }
            .map { it.packageName }
        return (legacy + disabledByPolicy).cleanPackageNames()
    }

    fun saveBlacklistPackages(context: Context, packages: Collection<String>) {
        prefs(context)
            .edit { putStringSet(KEY_BLACKLIST, packages.cleanPackageNames().toSet()) }
        val existing = getAppPolicies(context)
            .filter { it.packageName !in packages.cleanPackageNames().toSet() }
        saveAppPolicies(
            context,
            existing + packages.cleanPackageNames().map(AppPolicy::fromLegacyBlacklist)
        )
    }

    fun isBlacklisted(context: Context, packageName: String): Boolean {
        return !getAppPolicy(context, packageName).defaultRuleEnabled
    }

    fun setBlacklisted(context: Context, packageName: String, blacklisted: Boolean) {
        val cleanPackage = packageName.trim()
        if (cleanPackage.isBlank()) return
        setAppPolicy(
            context,
            getAppPolicy(context, cleanPackage).copy(
                defaultRuleEnabled = !blacklisted,
                migratedFromBlacklist = blacklisted
            )
        )
        val packages = getLegacyBlacklistPackages(context).toMutableSet()
        if (blacklisted) {
            packages += cleanPackage
        } else {
            packages -= cleanPackage
        }
        saveBlacklistPackages(context, packages)
    }

    fun clearBlacklist(context: Context) {
        prefs(context).edit { remove(KEY_BLACKLIST) }
        saveAppPolicies(context, getAppPolicies(context).map {
            if (!it.defaultRuleEnabled && it.migratedFromBlacklist) {
                it.copy(defaultRuleEnabled = true, migratedFromBlacklist = false)
            } else {
                it
            }
        })
    }

    fun getAppPolicy(context: Context, packageName: String): AppPolicy {
        val cleanPackage = packageName.trim()
        if (cleanPackage.isBlank()) return AppPolicy.defaultFor("")
        return getAppPolicies(context).firstOrNull { it.packageName == cleanPackage }
            ?: if (cleanPackage in getLegacyBlacklistPackages(context)) {
                AppPolicy.fromLegacyBlacklist(cleanPackage)
            } else {
                AppPolicy.defaultFor(cleanPackage)
            }
    }

    fun getAppPolicies(context: Context): List<AppPolicy> {
        val raw = prefs(context).getString(KEY_APP_POLICIES_JSON, null).orEmpty()
        val stored = if (raw.isBlank()) {
            emptyList()
        } else {
            runCatching {
                val array = JSONArray(raw)
                buildList {
                    for (index in 0 until array.length()) {
                        array.optJSONObject(index)?.toAppPolicy()?.let(::add)
                    }
                }
            }.getOrDefault(emptyList())
        }
        val storedPackages = stored.map { it.packageName }.toSet()
        val migrated = getLegacyBlacklistPackages(context)
            .filter { it !in storedPackages }
            .map(AppPolicy::fromLegacyBlacklist)
        return (stored + migrated)
            .filter { it.packageName.isNotBlank() }
            .distinctBy { it.packageName }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.packageName })
    }

    fun setAppPolicy(context: Context, policy: AppPolicy) {
        val cleanPolicy = policy.copy(packageName = policy.packageName.trim())
        if (cleanPolicy.packageName.isBlank()) return
        val updated = getAppPolicies(context)
            .filterNot { it.packageName == cleanPolicy.packageName }
            .plus(cleanPolicy.copy(updatedAt = System.currentTimeMillis()))
        saveAppPolicies(context, updated)
    }

    fun setDefaultRuleEnabled(context: Context, packageName: String, enabled: Boolean) {
        val policy = getAppPolicy(context, packageName)
        setAppPolicy(context, policy.copy(defaultRuleEnabled = enabled))
    }

    fun setCustomRulesEnabled(context: Context, packageName: String, enabled: Boolean) {
        val policy = getAppPolicy(context, packageName)
        setAppPolicy(context, policy.copy(customRulesEnabled = enabled))
    }

    fun setAppAssistanceEnabled(context: Context, packageName: String, enabled: Boolean) {
        val policy = getAppPolicy(context, packageName)
        setAppPolicy(
            context,
            policy.copy(
                defaultRuleEnabled = enabled,
                customRulesEnabled = enabled
            )
        )
    }

    fun saveAppPolicies(context: Context, policies: Collection<AppPolicy>) {
        val cleaned = policies
            .map { it.copy(packageName = it.packageName.trim()) }
            .filter { it.packageName.isNotBlank() }
            .distinctBy { it.packageName }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.packageName })
        prefs(context).edit {
            putString(KEY_APP_POLICIES_JSON, JSONArray().apply {
                cleaned.forEach { put(it.toJson()) }
            }.toString())
            putStringSet(
                KEY_BLACKLIST,
                cleaned.filterNot { it.defaultRuleEnabled }
                    .map { it.packageName }
                    .toSet()
            )
        }
    }

    fun getIconScheme(context: Context): String {
        return iconPrefs(context).getString(KEY_ICON_SCHEME, "sky_blue").orEmpty()
            .ifBlank { "sky_blue" }
    }

    fun setIconScheme(context: Context, schemeId: String) {
        iconPrefs(context).edit { putString(KEY_ICON_SCHEME, schemeId) }
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

        iconPrefs.edit {
            putString(KEY_ICON_SCHEME, scheme)
            putBoolean(KEY_ICON_DEFAULT_MIGRATED, true)
        }
        prefs(context).edit { remove(KEY_ICON_SCHEME) }
    }

    fun markServiceConnected(context: Context, timeMillis: Long = System.currentTimeMillis()) {
        prefs(context).edit { putLong(KEY_SERVICE_CONNECTED_AT, timeMillis) }
    }

    fun markServiceActive(context: Context, timeMillis: Long = System.currentTimeMillis()) {
        prefs(context).edit { putLong(KEY_SERVICE_ACTIVE_AT, timeMillis) }
    }

    fun markServiceInterrupted(context: Context, timeMillis: Long = System.currentTimeMillis()) {
        prefs(context).edit { putLong(KEY_SERVICE_INTERRUPTED_AT, timeMillis) }
    }

    fun markLastClick(context: Context, timeMillis: Long = System.currentTimeMillis()) {
        prefs(context).edit { putLong(KEY_LAST_CLICK_AT, timeMillis) }
    }

    fun setLastFailureReason(context: Context, reason: String) {
        prefs(context).edit { putString(KEY_LAST_FAILURE_REASON, reason) }
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

    fun getDiagnosticSnapshot(context: Context): DiagnosticSnapshot {
        return DiagnosticSnapshot(
            masterEnabled = isMasterEnabled(context),
            safetyModeEnabled = isSafetyModeEnabled(context),
            debugLogEnabled = isDebugToastEnabled(context),
            releaseDisclosureAccepted = hasAcceptedReleaseDisclosure(context),
            accessibilityServiceEnabled = AccessibilityUtil.isSkipServiceEnabled(context),
            serviceConnectedAt = getServiceConnectedAt(context),
            serviceActiveAt = getServiceActiveAt(context),
            serviceInterruptedAt = getServiceInterruptedAt(context),
            lastClickAt = getLastClickAt(context),
            lastFailureReason = getLastFailureReason(context),
            otherAccessibilityServices = AccessibilityUtil.getOtherEnabledAccessibilityServices(context),
            appPolicies = getAppPolicies(context)
        )
    }

    internal fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun iconPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(ICON_PREFS_NAME, Context.MODE_PRIVATE)

    private fun Collection<String>.cleanPackageNames(): List<String> {
        return map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
    }

    private fun getLegacyBlacklistPackages(context: Context): List<String> {
        return prefs(context)
            .getStringSet(KEY_BLACKLIST, emptySet())
            .orEmpty()
            .cleanPackageNames()
    }

    private fun AppPolicy.toJson(): JSONObject {
        return JSONObject()
            .put("packageName", packageName)
            .put("defaultRuleEnabled", defaultRuleEnabled)
            .put("customRulesEnabled", customRulesEnabled)
            .put("migratedFromBlacklist", migratedFromBlacklist)
            .put("updatedAt", updatedAt)
    }

    private fun JSONObject.toAppPolicy(): AppPolicy? {
        val packageName = optString("packageName").trim()
        if (packageName.isBlank()) return null
        return AppPolicy(
            packageName = packageName,
            defaultRuleEnabled = optBoolean("defaultRuleEnabled", true),
            customRulesEnabled = optBoolean("customRulesEnabled", true),
            migratedFromBlacklist = optBoolean("migratedFromBlacklist", false),
            updatedAt = optLong("updatedAt", System.currentTimeMillis())
        )
    }
}
