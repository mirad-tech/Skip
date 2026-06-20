package com.example.skip.engine

/** Caches compiled user-defined expressions so accessibility scans do not recompile them per node. */
internal object SafeRegexMatcher {
    private const val MAX_CACHED_PATTERNS = 128

    private sealed interface CachedRegex {
        data class Valid(val regex: Regex) : CachedRegex
        data object Invalid : CachedRegex
    }

    private val cache = object : LinkedHashMap<String, CachedRegex>(
        MAX_CACHED_PATTERNS + 1,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedRegex>?): Boolean {
            return size > MAX_CACHED_PATTERNS
        }
    }

    fun containsMatch(pattern: String, input: CharSequence): Boolean {
        return (cached(pattern) as? CachedRegex.Valid)
            ?.regex
            ?.containsMatchIn(input)
            ?: false
    }

    fun isValid(pattern: String): Boolean = cached(pattern) is CachedRegex.Valid

    private fun cached(pattern: String): CachedRegex {
        return synchronized(cache) {
            cache[pattern] ?: compile(pattern).also { cache[pattern] = it }
        }
    }

    private fun compile(pattern: String): CachedRegex {
        return runCatching {
            CachedRegex.Valid(Regex(pattern, RegexOption.IGNORE_CASE))
        }.getOrDefault(CachedRegex.Invalid)
    }
}
