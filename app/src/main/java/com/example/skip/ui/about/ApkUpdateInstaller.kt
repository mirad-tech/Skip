package com.example.skip.ui.about

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

internal object ApkUpdateInstaller {
    fun validateDownloadedApk(
        context: Context,
        file: File,
        currentVersionCode: Long
    ): ApkValidationResult {
        if (!file.exists() || file.length() <= 0L) {
            return ApkValidationResult.Invalid("下载文件为空")
        }
        val packageInfo = packageArchiveInfo(context.packageManager, file)
            ?: return ApkValidationResult.Invalid("无法读取下载的 APK")
        if (packageInfo.packageName != context.packageName) {
            file.delete()
            return ApkValidationResult.Invalid("下载的 APK 包名不匹配")
        }
        if (packageInfo.longVersionCode <= currentVersionCode) {
            file.delete()
            return ApkValidationResult.Invalid("下载的 APK 版本不高于当前版本")
        }
        return ApkValidationResult.Valid
    }

    @Suppress("DEPRECATION")
    fun installDownloadedApk(context: Context, file: File): InstallStartResult {
        if (!canRequestPackageInstalls(context)) {
            return InstallStartResult.PermissionNeeded
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        return try {
            context.startActivity(intent)
            InstallStartResult.Started
        } catch (_: ActivityNotFoundException) {
            InstallStartResult.Error("系统安装器不可用")
        } catch (throwable: RuntimeException) {
            InstallStartResult.Error("启动系统安装器失败：${throwable.message.orEmpty()}")
        }
    }

    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )
        runCatching { context.startActivity(intent) }
            .onFailure {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                }
            }
    }

    private fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    @Suppress("DEPRECATION")
    private fun packageArchiveInfo(packageManager: PackageManager, file: File): PackageInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        }
    }
}

internal sealed interface ApkValidationResult {
    data object Valid : ApkValidationResult
    data class Invalid(val message: String) : ApkValidationResult
}

internal sealed interface InstallStartResult {
    data object Started : InstallStartResult
    data object PermissionNeeded : InstallStartResult
    data class Error(val message: String) : InstallStartResult
}
