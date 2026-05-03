package com.example.skip.util

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.example.skip.service.SkipAccessibilityService

object AccessibilityUtil {
    fun isSkipServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(
            context,
            SkipAccessibilityService::class.java
        ).flattenToString()
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
}
