package com.example.skip.engine

import android.content.Context
import com.example.skip.data.SettingsRepository
import java.util.Locale

object SafetyGuard {
    private val protectedExactPackages = setOf(
        "com.android.settings",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.eg.android.alipaygphone",
        "com.tencent.mm",
        "com.unionpay",
        "com.unionpay.tsmservice",
        "com.paypal.android.p2pmobile"
    )

    private val protectedPrefixes = listOf(
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
        "com.onepassword"
    )

    private val protectedKeywords = listOf(
        "alipay",
        "bank",
        "finance",
        "financial",
        "paypal",
        "pay",
        "payment",
        "unionpay",
        "wallet",
        "securities",
        "broker",
        "insurance",
        "password",
        "passkey",
        "authenticator",
        "inputmethod",
        "keyboard",
        "ime"
    )

    fun canHandlePackage(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        if (packageName == context.packageName) return false
        if (isProtectedPackage(packageName)) return false
        return SettingsRepository.getWhitelistPackages(context).contains(packageName)
    }

    fun isProtectedPackage(packageName: String): Boolean {
        val lower = packageName.lowercase(Locale.ROOT)
        if (lower in protectedExactPackages) return true
        if (protectedPrefixes.any { lower.startsWith(it) }) return true
        return protectedKeywords.any { lower.contains(it) }
    }

    fun protectedSummary(): String {
        return "系统设置、应用安装器、支付、银行、钱包、金融、密码管理器和输入法默认不处理。"
    }
}
