package com.example.skip.model

data class RulePackage(
    val id: String,
    val name: String,
    val version: Int,
    val author: String,
    val updateTime: String,
    val description: String,
    val enabled: Boolean = true,
    val source: RuleSource = RuleSource.JsonFile,
    val createdAt: Long = System.currentTimeMillis()
)
