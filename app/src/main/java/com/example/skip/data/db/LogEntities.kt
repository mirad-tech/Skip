package com.example.skip.data.db

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "click_logs",
    indices = [
        Index(value = ["storageKey"], unique = true),
        Index(value = ["timeMillis"]),
        Index(value = ["packageName"]),
        Index(value = ["packageName", "ruleId", "stage", "failureReason", "timeMillis"])
    ]
)
data class ClickLogEntity(
    @androidx.room.PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val storageKey: String,
    val timeMillis: Long,
    val packageName: String,
    val ruleId: String,
    val stage: String,
    val failureReason: String,
    val payloadJson: String
)

@Entity(
    tableName = "click_log_throttle_counts",
    primaryKeys = ["dayStartMillis", "reasonKey"],
    indices = [Index(value = ["dayStartMillis"])]
)
data class ClickLogThrottleCountEntity(
    val dayStartMillis: Long,
    val reasonKey: String,
    val count: Long,
    val updatedAtMillis: Long
)

@Entity(tableName = "storage_metadata")
data class StorageMetadataEntity(
    @androidx.room.PrimaryKey
    val key: String,
    val value: String
)

data class ClickLogThrottleCountRow(
    val dayStartMillis: Long,
    val reasonKey: String,
    val count: Long
)
