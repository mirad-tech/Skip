package com.example.skip.util

object PrivacySanitizer {
    private const val MAX_TEXT_LENGTH = 30
    private val emailRegex = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val phoneRegex = Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)")
    private val idCardRegex = Regex("(?<![0-9Xx])\\d{17}[0-9Xx](?![0-9Xx])")
    private val bankCardRegex = Regex("(?<!\\d)\\d{13,19}(?!\\d)")
    private val longNumberRegex = Regex("(?<!\\d)\\d{8,}(?!\\d)")

    fun sanitizeNodeText(value: String, isInput: Boolean = false): String {
        if (isInput) return "[REDACTED]"
        return sanitizeText(value)
    }

    fun sanitizeText(value: String): String {
        if (value.isBlank()) return ""
        return value
            .replace(emailRegex, "[EMAIL]")
            .replace(phoneRegex, "[PHONE]")
            .replace(idCardRegex, "[ID]")
            .replace(bankCardRegex, "[CARD]")
            .replace(longNumberRegex, "[NUMBER]")
            .take(MAX_TEXT_LENGTH)
    }
}
