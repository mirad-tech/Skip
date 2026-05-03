package com.example.skip.util

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings

object SettingsIntentUtils {
    fun accessibilityIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }

    fun batteryOptimizationIntent(context: Context): Intent {
        return Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).also {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean? {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return null
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }
}
