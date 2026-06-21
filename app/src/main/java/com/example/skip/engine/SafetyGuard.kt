package com.example.skip.engine

import android.content.Context
import java.util.Locale

object SafetyGuard {
    private val protectedExactPackages = setOf(
        "com.android.systemui",
        "com.bbk.launcher2",
        "com.vivo.upslide",
        "com.android.launcher",
        "com.google.android.apps.nexuslauncher",
        "com.miui.home",
        "com.huawei.android.launcher",
        "com.oppo.launcher",
        "com.coloros.recents",
        "com.android.settings",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.vivo.ai.ime.nex",
        "com.samsung.android.packageinstaller",
        "com.eg.android.alipaygphone",
        "com.tencent.mm",
        "com.unionpay",
        "com.unionpay.tsmservice",
        "com.icbc",
        "com.icbc.android",
        "cn.com.icbc",
        "cn.com.icbc.android",
        "com.chinamworld",
        "com.ccb",
        "com.bankcomm",
        "com.cmbchina",
        "com.boc",
        "com.psbc",
        "com.spdb",
        "com.citicbank"
    )

    private val protectedPrefixes = listOf(
        "com.android.systemui",
        "com.bbk.launcher",
        "com.vivo.upslide",
        "com.android.launcher",
        "com.google.android.apps.nexuslauncher",
        "com.miui.home",
        "com.huawei.android.launcher",
        "com.oppo.launcher",
        "com.coloros.recents",
        "com.android.settings",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.android.inputmethod",
        "com.google.android.inputmethod",
        "com.samsung.android.honeyboard",
        "com.baidu.input",
        "com.sohu.inputmethod",
        "com.iflytek.inputmethod",
        "com.touchtype",
        "com.agilebits",
        "com.lastpass",
        "com.bitwarden",
        "com.dashlane",
        "com.onepassword",
        "com.icbc",
        "cn.com.icbc",
        "com.ccb",
        "com.cmbchina",
        "com.boc",
        "com.psbc",
        "com.spdb",
        "com.citicbank"
    )

    private val protectedKeywords = listOf(
        "launcher",
        "systemui",
        "recents",
        "upslide",
        "settings",
        "packageinstaller",
        "inputmethod",
        "keyboard",
        "ime",
        "alipay",
        "bank",
        "icbc",
        "cmb",
        "ccb",
        "boc",
        "psbc",
        "spdb",
        "citic",
        "finance",
        "financial",
        "paypal",
        "pay",
        "payment",
        "unionpay",
        "wallet",
        "cash",
        "credit",
        "debit",
        "securities",
        "broker",
        "insurance",
        "password",
        "passkey",
        "authenticator",
        "permission",
        "installer"
    )

    val protectedPageKeywords = listOf(
        "支付",
        "付款",
        "转账",
        "银行",
        "验证码",
        "登录",
        "注册",
        "授权",
        "权限",
        "安装",
        "输入法",
        "密码",
        "pay",
        "wallet",
        "login",
        "verify",
        "permission",
        "install",
        "password"
    ) + HighRiskClickPolicy.blockedTerms

    fun canHandlePackage(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        if (isSelfPackage(context, packageName)) return false
        if (isProtectedPackage(packageName)) return false
        return true
    }

    fun isSelfPackage(context: Context, packageName: String): Boolean {
        return packageName == context.packageName
    }

    fun isProtectedPackage(packageName: String): Boolean {
        val lower = packageName.lowercase(Locale.ROOT)
        if (lower in protectedExactPackages) return true
        if (protectedPrefixes.any { lower.startsWith(it) }) return true
        return protectedKeywords.any { lower.contains(it) }
    }

    fun protectedPackageReason(packageName: String): String {
        val lower = packageName.lowercase(Locale.ROOT)
        return when {
            isLauncherPackage(packageName) || isSystemPackage(packageName) -> {
                "system_or_launcher_package"
            }
            lower.contains("inputmethod") || lower.contains("keyboard") || lower.contains("ime") -> {
                "input_method_package"
            }
            lower.contains("packageinstaller") || lower.contains("installer") -> {
                "package_installer_package"
            }
            lower.contains("settings") || lower.contains("permission") -> {
                "settings_or_permission_package"
            }
            isProtectedPackage(packageName) -> "protected_sensitive_package"
            else -> ""
        }
    }

    fun isSystemPackage(packageName: String): Boolean {
        val lower = packageName.lowercase(Locale.ROOT)
        return lower == "com.android.systemui" ||
            lower == "com.vivo.upslide" ||
            lower.contains("systemui") ||
            lower.contains("recents") ||
            lower.contains("upslide") ||
            lower.contains("settings") ||
            lower.contains("packageinstaller")
    }

    fun isLauncherPackage(packageName: String): Boolean {
        val lower = packageName.lowercase(Locale.ROOT)
        return lower == "com.bbk.launcher2" ||
            lower == "com.android.launcher" ||
            lower == "com.google.android.apps.nexuslauncher" ||
            lower == "com.miui.home" ||
            lower == "com.huawei.android.launcher" ||
            lower == "com.oppo.launcher" ||
            lower.contains("launcher")
    }

    fun isLauncherOrSystemPackage(packageName: String): Boolean {
        return isLauncherPackage(packageName) || isSystemPackage(packageName)
    }

    fun isStandaloneSkipText(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.equals("skip", ignoreCase = true) || trimmed == "跳过"
    }

    fun isSelfAppLabelCandidate(
        context: Context,
        text: String,
        contentDescription: String
    ): Boolean {
        val labels = setOf(
            "Skip",
            context.packageManager
                .getApplicationLabel(context.applicationInfo)
                .toString()
        )
        return labels.any { label ->
            label.isNotBlank() &&
                (text.trim().equals(label, ignoreCase = true) ||
                    contentDescription.trim().equals(label, ignoreCase = true))
        }
    }

    fun isOwnAppIconOnLauncher(
        context: Context,
        packageName: String,
        text: String,
        contentDescription: String
    ): Boolean {
        return isLauncherOrSystemPackage(packageName) &&
            isSelfAppLabelCandidate(context, text, contentDescription)
    }

    fun isSensitiveText(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return protectedPageKeywords.any { lower.contains(it.lowercase(Locale.ROOT)) } ||
            HighRiskClickPolicy.isHighRiskText(text)
    }

    fun protectedSummary(): String {
        return "为避免误触支付、银行、系统权限等敏感页面，银行、支付、钱包、系统设置、安装器、密码管理器和输入法默认不执行自动点击。"
    }
}
