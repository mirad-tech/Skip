package com.example.skip.ui.about

import com.example.skip.util.SimpleJson
import com.example.skip.util.SimpleJsonObject

internal object UpdateReleaseParser {
    fun parse(jsonText: String): UpdateRelease {
        val root = runCatching { SimpleJson.parseObject(jsonText) }
            .getOrElse { error("更新信息解析失败") }
        val tagName = root.optString("tag_name").trim()
        require(tagName.isNotBlank()) { "更新信息缺少版本号" }
        val versionName = VersionComparator.normalizeVersionName(tagName)
        val assets = root.optJSONArray("assets")?.values
            ?.mapNotNull { it as? SimpleJsonObject }
            ?.mapNotNull(::toAsset)
            .orEmpty()
        val preferredNames = setOf(
            "Skip-$tagName-release.apk".lowercase(),
            "Skip-$versionName-release.apk".lowercase()
        )
        val apkAsset = assets.firstOrNull { it.name.lowercase() in preferredNames }
            ?: assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            ?: error("更新信息中没有可下载的 APK")

        return UpdateRelease(
            tagName = tagName,
            versionName = versionName,
            htmlUrl = root.optString("html_url").trim(),
            publishedAt = root.optString("published_at").trim(),
            apkAsset = apkAsset
        )
    }

    private fun toAsset(assetJson: SimpleJsonObject): UpdateAsset? {
        val name = assetJson.optString("name").trim()
        val url = assetJson.optString("browser_download_url").trim()
        if (name.isBlank() || url.isBlank()) return null
        return UpdateAsset(
            name = name,
            size = assetJson.optLong("size", 0L),
            browserDownloadUrl = url,
            digestSha256 = normalizeSha256(assetJson.optString("digest").trim())
        )
    }

    private fun normalizeSha256(value: String): String? {
        if (value.isBlank()) return null
        val trimmed = value.trim()
        val digest = if (trimmed.startsWith("sha256:", ignoreCase = true)) {
            trimmed.substringAfter(":")
        } else {
            trimmed
        }.trim().lowercase()
        require(digest.length == 64 && digest.all { char -> char in '0'..'9' || char in 'a'..'f' }) {
            "更新 APK SHA-256 校验信息无效"
        }
        return digest
    }
}
