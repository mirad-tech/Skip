package com.example.skip.data

import com.example.skip.data.db.ClickLogEntity
import com.example.skip.model.ClickLog
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal object ClickLogEntityMapper {
    fun toEntity(log: ClickLog): ClickLogEntity {
        val payload = ClickLogCodec.clickLogToJson(log).toString()
        return ClickLogEntity(
            storageKey = payload.sha256(),
            timeMillis = log.timeMillis,
            packageName = log.packageName,
            ruleId = log.ruleId,
            stage = log.stage.value,
            failureReason = log.failureReason,
            payloadJson = payload
        )
    }

    private fun String.sha256(): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

}
