package com.example.skip.model

data class RuleLog(
    val timeMillis: Long,
    val source: RuleSource,
    val ruleName: String,
    val targetApp: String,
    val success: Boolean,
    val reason: String
)
