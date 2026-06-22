package com.example.skip.ui.about

import java.io.File
import java.security.MessageDigest

internal object UpdateDownloadVerifier {
    fun verifySha256OrDelete(file: File, expectedDigestSha256: String?): Boolean {
        val expected = normalizeExpectedDigest(expectedDigestSha256)
        if (expected == null) {
            file.delete()
            return false
        }
        val actual = sha256(file)
        val matches = actual.equals(expected, ignoreCase = true)
        if (!matches) file.delete()
        return matches
    }

    fun failureMessageFor(expectedDigestSha256: String?): String {
        return when {
            expectedDigestSha256.isNullOrBlank() -> "更新 APK 缺少 SHA-256 digest"
            normalizeExpectedDigest(expectedDigestSha256) == null -> {
                "更新 APK digest 格式错误，必须为 sha256:<64hex> 或 <64hex>"
            }
            else -> "更新 APK SHA-256 不匹配"
        }
    }

    private fun normalizeExpectedDigest(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val digest = when {
            value.startsWith("sha256:") -> value.substring("sha256:".length)
            ':' in value -> return null
            else -> value
        }
        return digest.takeIf { it.length == 64 && it.all { char ->
            char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'
        } }
            ?.lowercase()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
