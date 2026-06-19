package com.example.skip.ui.about

internal object VersionComparator {
    fun isNewer(remoteTag: String, currentVersionName: String): Boolean {
        val remote = parseVersion(remoteTag) ?: return false
        val current = parseVersion(currentVersionName) ?: return false
        val length = maxOf(remote.size, current.size)
        for (index in 0 until length) {
            val remotePart = remote.getOrElse(index) { 0 }
            val currentPart = current.getOrElse(index) { 0 }
            if (remotePart != currentPart) return remotePart > currentPart
        }
        return false
    }

    fun normalizeVersionName(tag: String): String {
        return tag.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore("+")
            .substringBefore("-")
    }

    private fun parseVersion(value: String): List<Int>? {
        val normalized = normalizeVersionName(value)
        val parts = normalized.split(".")
        if (parts.isEmpty() || parts.any { it.isBlank() }) return null
        return parts.map { part ->
            if (!part.all(Char::isDigit)) return null
            part.toIntOrNull() ?: return null
        }
    }
}
