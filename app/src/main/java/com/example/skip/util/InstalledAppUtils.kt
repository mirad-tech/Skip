package com.example.skip.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.example.skip.model.InstalledApp

object InstalledAppUtils {
    fun loadLaunchableApps(context: Context): List<InstalledApp> {
        val packageManager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching {
            packageManager.queryIntentActivitiesCompat(intent)
                .map { resolveInfo ->
                    val packageName = resolveInfo.activityInfo.packageName
                    InstalledApp(
                        label = resolveInfo.loadLabel(packageManager).toString(),
                        packageName = packageName,
                        icon = runCatching { resolveInfo.loadIcon(packageManager) }.getOrNull()
                    )
                }
                .filterNot { it.packageName == context.packageName }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())
    }

    fun getAppLabel(context: Context, packageName: String): String {
        return runCatching {
            val info = context.packageManager.getApplicationInfoCompat(packageName)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
    }

    fun resolveApp(context: Context, packageName: String): InstalledApp {
        val packageManager = context.packageManager
        return runCatching {
            val info = packageManager.getApplicationInfoCompat(packageName)
            InstalledApp(
                label = packageManager.getApplicationLabel(info).toString(),
                packageName = packageName,
                icon = runCatching { packageManager.getApplicationIcon(info) }.getOrNull()
            )
        }.getOrDefault(
            InstalledApp(
                label = packageName,
                packageName = packageName,
                icon = null
            )
        )
    }

    @Suppress("DEPRECATION")
    private fun PackageManager.queryIntentActivitiesCompat(intent: Intent) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            queryIntentActivities(intent, 0)
        }

    @Suppress("DEPRECATION")
    private fun PackageManager.getApplicationInfoCompat(packageName: String) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            getApplicationInfo(packageName, 0)
        }
}
