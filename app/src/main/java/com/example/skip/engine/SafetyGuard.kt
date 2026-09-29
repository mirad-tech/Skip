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

    // Matched against the whole lowercased package name, except for the short tokens listed in
    // `boundaryMatchedKeywords` below.
    //
    // Never truncate this list: an earlier edit dropped "citic" while rewriting the block, which
    // silently un-protected the `com.citics.*` brokerage apps. "citic" is deliberately kept even
    // though `com.citicbank` is also in `protectedPrefixes` — that prefix does not cover the
    // `com.citics.*` namespace.
    private val protectedKeywords = listOf(
        "launcher", "systemui", "recents", "upslide", "settings", "packageinstaller",
        "inputmethod", "keyboard", "ime", "alipay", "bank", "icbc", "cmb", "ccb", "boc",
        "psbc", "spdb", "citic", "finance", "financial", "paypal", "pay", "payment",
        "unionpay", "wallet", "cash", "credit", "debit", "securities", "broker",
        "insurance", "password", "passkey", "authenticator", "permission", "installer"
    )

    private val whitespaceRegex = Regex("\\s+")

    /**
     * The one token that cannot be matched across the whole package name.
     *
     * `ime` is a substring of ordinary words — `time` (`com.time.mobile`), `prime`
     * (`com.prime.video`) — so a full-name match silently disabled skipping for those apps. It has
     * to be a whole dot-separated segment instead, which still covers `com.lge.ime`, `com.sogou.ime`
     * and `com.baidu.ime.android`.
     *
     * The other short financial tokens are deliberately left on the broader full-name match: they
     * carry real signal inside a word (`tenpay`, `jdpaysdk`, `paybyphone`, `mycashier`) and no common
     * package word contains them by accident. `bank`, `wallet`, `login` and `settings` likewise stay
     * full-name matches.
     */
    private const val SEGMENT_ONLY_KEYWORD = "ime"

    /**
     * Keywords that identify a system surface rather than a merely sensitive app. Kept as keyword
     * literals so [isSystemPackage] classifies on the matched keyword, never on a package name.
     */
    private val systemKeywords = setOf(
        "systemui", "recents", "upslide", "settings", "packageinstaller"
    )

    /** Phrases that must keep reporting the launcher/system reason string. */
    private val launcherReasonKeywords = setOf("launcher", "systemui", "recents", "upslide")

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

    private val sensitiveSkipPhrases = listOf(
        "跳过登录",
        "跳过注册",
        "跳过验证",
        "跳过绑定",
        "跳过设置",
        "跳过更新",
        "跳过授权",
        "跳过支付",
        "跳过安装",
        "跳过删除",
        "跳过卸载",
        "跳过此步骤"
    )

    fun isSelfPackage(context: Context, packageName: String): Boolean {
        return packageName == context.packageName
    }

    fun isProtectedPackage(packageName: String): Boolean {
        val lower = packageName.lowercase(Locale.ROOT)
        if (lower in protectedExactPackages) return true
        if (protectedPrefixes.any { lower.startsWith(it) }) return true
        return matchedProtectedKeyword(packageName) != null
    }

    /**
     * Returns the **keyword** that made [isProtectedPackage] true, or null when the decision came
     * from [protectedExactPackages] / [protectedPrefixes] or from no rule at all.
     *
     * The contract is deliberately "keyword only": [isSystemPackage] and [protectedPackageReason]
     * classify by comparing this value against keyword literals, so returning a package name or a
     * prefix here (as an earlier revision did) silently sent every exact/prefix match down the
     * generic branch.
     */
    fun matchedProtectedKeyword(packageName: String): String? {
        val lower = packageName.lowercase(Locale.ROOT)
        val segments = lower.split('.').filter { it.isNotEmpty() }
        return protectedKeywords.firstOrNull { keyword ->
            if (keyword == SEGMENT_ONLY_KEYWORD) {
                segments.any { it == keyword }
            } else {
                lower.contains(keyword)
            }
        }
    }

    fun protectedPackageReason(packageName: String): String {
        val lower = packageName.lowercase(Locale.ROOT)
        val isLauncher = isLauncherPackage(packageName)
        val isExactOrPrefix = lower in protectedExactPackages ||
            protectedPrefixes.any { lower.startsWith(it) }
        if (isExactOrPrefix || isLauncher) {
            if (isLauncher) return "system_or_launcher_package"
            return classifyProtectedKeyword(matchedProtectedKeyword(packageName))
                .ifBlank { "protected_sensitive_package" }
        }
        return classifyProtectedKeyword(matchedProtectedKeyword(packageName))
    }

    private fun classifyProtectedKeyword(keyword: String?): String {
        return when (keyword) {
            null -> ""
            in launcherReasonKeywords -> "system_or_launcher_package"
            "inputmethod", "keyboard", "ime" -> "input_method_package"
            "packageinstaller", "installer" -> "package_installer_package"
            "settings", "permission" -> "settings_or_permission_package"
            else -> "protected_sensitive_package"
        }
    }

    fun isSystemPackage(packageName: String): Boolean {
        return matchedProtectedKeyword(packageName) in systemKeywords
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
        return hasSensitiveSkipSemantic(text) ||
            protectedPageKeywords.any { lower.contains(it.lowercase(Locale.ROOT)) } ||
            HighRiskClickPolicy.isHighRiskText(text)
    }

    fun hasSensitiveSkipSemantic(text: String): Boolean {
        val normalized = text.lowercase(Locale.ROOT)
            .replace(whitespaceRegex, "")
        return sensitiveSkipPhrases.any(normalized::contains)
    }

    fun protectedSummary(): String {
        return "为避免误触支付、银行、系统权限等敏感页面，银行、支付、钱包、系统设置、安装器、密码管理器和输入法默认不执行自动点击。"
    }
}
