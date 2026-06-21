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
        val trustedAssets = assets.filter { it.name.lowercase() in preferredNames }
        if (trustedAssets.size != 1) {
            error("更新信息中没有唯一可信的发布 APK 资产")
        }
        val apkAsset = trustedAssets.single().toUpdateAsset()

        return UpdateRelease(
            tagName = tagName,
            versionName = versionName,
            htmlUrl = root.optString("html_url").trim(),
            publishedAt = root.optString("published_at").trim(),
            apkAsset = apkAsset
        )
    }

    private fun toAsset(assetJson: SimpleJsonObject): ReleaseAssetCandidate? {
        val name = assetJson.optString("name").trim()
        val url = assetJson.optString("browser_download_url").trim()
        if (name.isBlank() || url.isBlank()) return null
        return ReleaseAssetCandidate(
            name = name,
            size = assetJson.optLong("size", 0L),
            browserDownloadUrl = url,
            digest = assetJson.optString("digest").trim()
        )
    }

    private fun ReleaseAssetCandidate.toUpdateAsset(): UpdateAsset {
        return UpdateAsset(
            name = name,
            size = size,
            browserDownloadUrl = browserDownloadUrl,
            digestSha256 = normalizeRequiredSha256Digest(digest)
        )
    }

    private fun normalizeRequiredSha256Digest(value: String): String {
        require(value.isNotBlank()) { "更新 APK digest 缺失" }
        require(value.startsWith("sha256:")) {
            "更新 APK digest 格式错误，必须为 sha256:<64hex>（SHA-256）"
        }
        val digest = value.substring("sha256:".length)
        require(digest.length == 64 && digest.all { char ->
            char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'
        }) {
            "更新 APK digest 格式错误，必须为 sha256:<64hex>（SHA-256）"
        }
        return digest.lowercase()
    }

    private data class ReleaseAssetCandidate(
        val name: String,
        val size: Long,
        val browserDownloadUrl: String,
        val digest: String
    )
}
