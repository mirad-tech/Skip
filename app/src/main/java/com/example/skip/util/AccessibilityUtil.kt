package com.example.skip.util

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.example.skip.service.SkipAccessibilityService

object AccessibilityUtil {
    fun isSkipServiceEnabled(context: Context): Boolean {
        val expected = skipServiceComponent(context)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()

        return Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        ) == 1 && enabledServices
            .split(':')
            .any { it.equals(expected, ignoreCase = true) }
    }

    fun getOtherEnabledAccessibilityServices(context: Context): List<String> {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return parseOtherEnabledServices(
            enabledServices = enabledServices,
            ownService = skipServiceComponent(context)
        )
    }

    internal fun parseOtherEnabledServices(
        enabledServices: String,
        ownService: String
    ): List<String> {
        return enabledServices
            .split(':')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.equals(ownService, ignoreCase = true) }
            .distinct()
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
    }

    private fun skipServiceComponent(context: Context): String {
        return ComponentName(
            context,
            SkipAccessibilityService::class.java
        ).flattenToString()
    }
}
