package com.example.skip.model

data class ClickLog(
    val timeMillis: Long,
    val packageName: String,
    val appName: String = "",
    val ruleName: String,
    val success: Boolean = true,
    val reason: String = ""
)
