package com.example.skip.data

import java.io.OutputStream

object JsonExportWriter {
    fun writeJson(
        openOutputStream: () -> OutputStream?,
        json: String
    ) {
        val output = openOutputStream()
            ?: error("无法打开导出文件，请重新选择保存位置")
        output.use {
            it.write(json.toByteArray(Charsets.UTF_8))
        }
    }
}
