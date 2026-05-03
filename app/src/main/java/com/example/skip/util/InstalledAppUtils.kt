package com.example.skip.util

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.example.skip.model.InstalledApp

object InstalledAppUtils {
    fun loadLaunchableApps(context: Context): List<InstalledApp> {
        val packageManager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchableApps = runCatching {
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
        if (launchableApps.isNotEmpty()) return launchableApps

        return runCatching {
            packageManager.getInstalledApplicationsCompat()
                .filter { info ->
                    info.packageName != context.packageName &&
                        (info.flags and ApplicationInfo.FLAG_SYSTEM == 0 ||
                            info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
                }
                .map { info ->
                    InstalledApp(
                        label = packageManager.getApplicationLabel(info).toString(),
                        packageName = info.packageName,
                        icon = runCatching { packageManager.getApplicationIcon(info) }.getOrNull()
                    )
                }
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
    private fun PackageManager.getInstalledApplicationsCompat() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } else {
            getInstalledApplications(PackageManager.MATCH_ALL)
        }

    @Suppress("DEPRECATION")
    private fun PackageManager.getApplicationInfoCompat(packageName: String) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            getApplicationInfo(packageName, 0)
        }
}
