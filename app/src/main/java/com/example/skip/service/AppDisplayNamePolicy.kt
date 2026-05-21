package com.example.skip.service

internal object AppDisplayNamePolicy {
    fun displayName(
        configuredAppName: String,
        packageName: String,
        resolveLabel: (String) -> String
    ): String {
        val configured = configuredAppName.trim()
        val normalizedPackage = packageName.trim()
        if (configured.isNotBlank() && configured != normalizedPackage) {
            return configured
        }
        return resolveLabel(normalizedPackage).trim().ifBlank { normalizedPackage }
    }
}
