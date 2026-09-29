package com.example.skip.ui.about

internal object VersionComparator {
    /** Used to tell the user which side of the comparison failed to parse. */
    const val REMOTE_LABEL = "远端版本号"
    const val CURRENT_LABEL = "本机版本号"

    /**
     * Distinguishes "remote is not newer" from "a version could not be parsed at all". Treating a
     * malformed tag as "already up to date" used to hide release-side mistakes and made the update
     * card claim the app was current.
     */
    fun compare(remoteTag: String, currentVersionName: String): VersionComparison {
        val remote = parseVersion(remoteTag)
            ?: return VersionComparison.Unparseable(remoteTag, REMOTE_LABEL)
        val current = parseVersion(currentVersionName)
            ?: return VersionComparison.Unparseable(currentVersionName, CURRENT_LABEL)
        return VersionComparison.Parsed(isNewer = compareParts(remote, current))
    }

    fun normalizeVersionName(tag: String): String {
        return tag.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore("+")
            .substringBefore("-")
    }

    private fun compareParts(remote: List<Int>, current: List<Int>): Boolean {
        val length = maxOf(remote.size, current.size)
        for (index in 0 until length) {
            val remotePart = remote.getOrElse(index) { 0 }
            val currentPart = current.getOrElse(index) { 0 }
            if (remotePart != currentPart) return remotePart > currentPart
        }
        return false
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

internal sealed interface VersionComparison {
    data class Parsed(val isNewer: Boolean) : VersionComparison

    /** [source] says which side failed to parse, so the user-facing message stays truthful. */
    data class Unparseable(val value: String, val source: String) : VersionComparison
}
