package com.example.skip.ui.about

import java.io.File

internal data class UpdateRelease(
    val tagName: String,
    val versionName: String,
    val htmlUrl: String,
    val publishedAt: String,
    val apkAsset: UpdateAsset
)

internal data class UpdateAsset(
    val name: String,
    val size: Long,
    val browserDownloadUrl: String,
    val digestSha256: String?
)

internal sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data class Latest(val release: UpdateRelease) : UpdateCheckState
    data class Available(val release: UpdateRelease) : UpdateCheckState
    data class Downloading(
        val release: UpdateRelease,
        val progressPercent: Int?
    ) : UpdateCheckState
    data class Downloaded(
        val release: UpdateRelease,
        val file: File
    ) : UpdateCheckState
    data class InstallPermissionNeeded(
        val release: UpdateRelease,
        val file: File
    ) : UpdateCheckState
    data class Error(
        val message: String,
        val release: UpdateRelease? = null
    ) : UpdateCheckState
}
