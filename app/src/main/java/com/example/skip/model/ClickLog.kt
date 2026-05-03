package com.example.skip.model

data class ClickLog(
    val timeMillis: Long,
    val packageName: String,
    val ruleName: String
)
