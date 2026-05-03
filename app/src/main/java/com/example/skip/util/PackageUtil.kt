package com.example.skip.util

object PackageUtil {
    fun isLikelyPackageName(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.length < 3 || !trimmed.contains(".")) return false
        return trimmed.all { it.isLetterOrDigit() || it == '_' || it == '.' }
    }
}
