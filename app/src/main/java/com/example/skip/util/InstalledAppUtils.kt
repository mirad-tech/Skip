package com.example.skip.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.example.skip.model.InstalledApp

object InstalledAppUtils {
    fun loadLaunchableApps(context: Context): List<InstalledApp> {
        val packageManager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching {
            packageManager.queryIntentActivities(intent, 0)
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
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
    }
}
