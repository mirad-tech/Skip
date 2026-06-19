package com.example.skip.ui.about

import java.io.File
import java.security.MessageDigest

internal object UpdateDownloadVerifier {
    fun verifySha256OrDelete(file: File, expectedDigestSha256: String?): Boolean {
        val expected = normalizeExpectedDigest(expectedDigestSha256)
        if (expected == null && expectedDigestSha256.isNullOrBlank()) return true
        if (expected == null) {
            file.delete()
            return false
        }
        val actual = sha256(file)
        val matches = actual.equals(expected, ignoreCase = true)
        if (!matches) file.delete()
        return matches
    }

    private fun normalizeExpectedDigest(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val digest = value.removePrefix("sha256:")
            .removePrefix("SHA256:")
            .trim()
            .lowercase()
        return digest.takeIf { it.length == 64 && it.all { char -> char in '0'..'9' || char in 'a'..'f' } }
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
