package com.example.skip.ui.about

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

internal object ApkUpdateInstaller {
    fun validateDownloadedApk(
        context: Context,
        file: File,
        currentVersionCode: Long
    ): ApkValidationResult {
        if (!file.exists() || file.length() <= 0L) {
            return ApkValidationResult.Invalid("下载文件为空")
        }
        val packageManager = context.packageManager
        val archivePackageInfo = packageArchiveInfo(packageManager, file)
            ?: return invalidAndDelete(file, "无法读取下载的 APK")
        val installedPackageInfo = installedPackageInfo(packageManager, context.packageName)
            ?: return invalidAndDelete(file, "无法读取当前安装的 Skip APK")
        val installedCertificateSha256 = signingCertificateSha256(installedPackageInfo)
        if (installedCertificateSha256.isEmpty()) {
            return invalidAndDelete(file, "无法读取当前 Skip APK 的签名证书")
        }
        val archiveCertificateSha256 = signingCertificateSha256(archivePackageInfo)
        if (archiveCertificateSha256.isEmpty()) {
            return invalidAndDelete(file, "无法读取下载 APK 的签名证书")
        }
        return validateArchiveMetadataOrDelete(
            file = file,
            expectedPackageName = context.packageName,
            currentVersionCode = currentVersionCode,
            installedCertificateSha256 = installedCertificateSha256,
            archive = ApkArchiveMetadata(
                packageName = archivePackageInfo.packageName,
                versionCode = archivePackageInfo.longVersionCode,
                signingCertificateSha256 = archiveCertificateSha256
            )
        )
    }

    internal fun validateArchiveMetadataOrDelete(
        file: File,
        expectedPackageName: String,
        currentVersionCode: Long,
        installedCertificateSha256: Set<String>,
        archive: ApkArchiveMetadata
    ): ApkValidationResult {
        if (archive.packageName != expectedPackageName) {
            return invalidAndDelete(file, "下载的 APK 包名不匹配")
        }
        if (archive.versionCode <= currentVersionCode) {
            return invalidAndDelete(file, "下载的 APK 版本不高于当前版本")
        }
        if (installedCertificateSha256.isEmpty() || archive.signingCertificateSha256.isEmpty()) {
            return invalidAndDelete(file, "APK 签名证书缺失")
        }
        if (installedCertificateSha256 != archive.signingCertificateSha256) {
            return invalidAndDelete(file, "下载 APK 签名证书不匹配")
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
        val flags = packageInfoFlags()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(flags.toLong())
            )
        } else {
            packageManager.getPackageArchiveInfo(file.absolutePath, flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun installedPackageInfo(packageManager: PackageManager, packageName: String): PackageInfo? {
        val flags = packageInfoFlags()
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(flags.toLong())
                )
            } else {
                packageManager.getPackageInfo(packageName, flags)
            }
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun signingCertificateSha256(packageInfo: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            packageInfo.signatures.orEmpty()
        }
        return signatures
            .map(Signature::toByteArray)
            .map(::sha256)
            .toSet()
    }

    private fun packageInfoFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    private fun invalidAndDelete(file: File, message: String): ApkValidationResult.Invalid {
        file.delete()
        return ApkValidationResult.Invalid(message)
    }
}

internal data class ApkArchiveMetadata(
    val packageName: String,
    val versionCode: Long,
    val signingCertificateSha256: Set<String>
)

internal sealed interface ApkValidationResult {
    data object Valid : ApkValidationResult
    data class Invalid(val message: String) : ApkValidationResult
}

internal sealed interface InstallStartResult {
    data object Started : InstallStartResult
    data object PermissionNeeded : InstallStartResult
    data class Error(val message: String) : InstallStartResult
}
