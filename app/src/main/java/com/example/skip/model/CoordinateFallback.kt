package com.example.skip.model

data class CoordinateFallback(
    val enabled: Boolean = false,
    val xRatio: Float = 0f,
    val yRatio: Float = 0f,
    val anchorTexts: List<String> = emptyList(),
    val anchorContentDescriptions: List<String> = emptyList(),
    val anchorViewIds: List<String> = emptyList()
) {
    fun isValid(): Boolean {
        return !enabled || (xRatio in 0f..1f && yRatio in 0f..1f)
    }

    fun hasAnchorRequirement(): Boolean {
        return anchorTexts.isNotEmpty() ||
            anchorContentDescriptions.isNotEmpty() ||
            anchorViewIds.isNotEmpty()
    }
}
