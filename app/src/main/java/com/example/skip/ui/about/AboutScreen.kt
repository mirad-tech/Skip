package com.example.skip.ui.about

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.skip.ui.common.InfoCard
import com.example.skip.ui.common.SimpleScreenScaffold
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AboutScreen(
    versionName: String,
    versionCode: Long,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateCheckState>(UpdateCheckState.Idle) }

    fun installUpdate(release: UpdateRelease, file: File) {
        when (val result = ApkUpdateInstaller.installDownloadedApk(context, file)) {
            InstallStartResult.Started -> state = UpdateCheckState.Downloaded(release, file)
            InstallStartResult.PermissionNeeded -> {
                state = UpdateCheckState.InstallPermissionNeeded(release, file)
            }
            is InstallStartResult.Error -> state = UpdateCheckState.Error(result.message, release)
        }
    }

    fun downloadAndInstall(release: UpdateRelease) {
        state = UpdateCheckState.Downloading(release, progressPercent = null)
        scope.launch {
            val destination = UpdateRepository.updateApkFile(context, release.apkAsset.name)
            runCatching {
                val file = UpdateRepository.downloadApk(release.apkAsset, destination) { progress ->
                    withContext(Dispatchers.Main.immediate) {
                        state = UpdateCheckState.Downloading(release, progress)
                    }
                }
                if (!UpdateDownloadVerifier.verifySha256OrDelete(file, release.apkAsset.digestSha256)) {
                    error(UpdateDownloadVerifier.failureMessageFor(release.apkAsset.digestSha256))
                }
                when (val validation = ApkUpdateInstaller.validateDownloadedApk(context, file, versionCode)) {
                    ApkValidationResult.Valid -> file
                    is ApkValidationResult.Invalid -> error(validation.message)
                }
            }.onSuccess { file ->
                state = UpdateCheckState.Downloaded(release, file)
                installUpdate(release, file)
            }.onFailure {
                state = UpdateCheckState.Error(it.userMessage("下载失败"), release)
            }
        }
    }

    fun checkForUpdates() {
        state = UpdateCheckState.Checking
        scope.launch {
            runCatching { UpdateRepository.checkLatestRelease() }
                .onSuccess { release ->
                    state = UpdateCardBehavior.stateAfterCheck(release, versionName)
                }
                .onFailure {
                    state = UpdateCheckState.Error(it.userMessage("检测失败"))
                }
        }
    }

    fun handleVersionCardClick() {
        when (val currentState = state) {
            UpdateCheckState.Idle,
            is UpdateCheckState.Latest,
            is UpdateCheckState.Error -> checkForUpdates()
            is UpdateCheckState.Available -> downloadAndInstall(currentState.release)
            is UpdateCheckState.Downloaded -> installUpdate(currentState.release, currentState.file)
            is UpdateCheckState.InstallPermissionNeeded -> installUpdate(currentState.release, currentState.file)
            UpdateCheckState.Checking,
            is UpdateCheckState.Downloading -> Unit
        }
    }

    fun openInstallPermissionSettings() {
        ApkUpdateInstaller.openInstallPermissionSettings(context)
    }

    fun openRelease(release: UpdateRelease) {
        openReleasePage(context, release.htmlUrl)
    }

    SimpleScreenScaffold(
        title = "关于",
        onBack = onBack
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            VersionUpdateCard(
                currentVersionName = versionName,
                state = state,
                onClick = ::handleVersionCardClick,
                onOpenInstallPermissionSettings = ::openInstallPermissionSettings,
                onOpenReleasePage = ::openRelease
            )
            InfoCard(
                title = "项目定位",
                body = "减少重复点击，不破解广告，不绕过其他 App 的安全机制。"
            )
        }
    }
}

@Composable
private fun VersionUpdateCard(
    currentVersionName: String,
    state: UpdateCheckState,
    onClick: () -> Unit,
    onOpenInstallPermissionSettings: () -> Unit,
    onOpenReleasePage: (UpdateRelease) -> Unit
) {
    val action = UpdateCardBehavior.nextActionFor(state)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = action != UpdateCardAction.None, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Skip $currentVersionName",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "一个本地化的 Android 辅助点击工具。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            VersionCardStateContent(
                state = state,
                onOpenInstallPermissionSettings = onOpenInstallPermissionSettings,
                onOpenReleasePage = onOpenReleasePage
            )
        }
    }
}

@Composable
private fun VersionCardStateContent(
    state: UpdateCheckState,
    onOpenInstallPermissionSettings: () -> Unit,
    onOpenReleasePage: (UpdateRelease) -> Unit
) {
    when (state) {
        UpdateCheckState.Idle -> {
            Text(
                text = "检测新版本",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        UpdateCheckState.Checking -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = "正在检测新版本",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        is UpdateCheckState.Latest -> {
            Text(
                text = "已是最新版本",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ReleasePageButton(state.release, onOpenReleasePage)
        }

        is UpdateCheckState.Available -> {
            Text(
                text = updateReleaseSummary("发现新版本", state.release),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "点击此卡片下载并交给系统安装器。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ReleasePageButton(state.release, onOpenReleasePage)
        }

        is UpdateCheckState.Downloading -> {
            if (state.progressPercent == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = "正在下载更新 APK",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LinearProgressIndicator(
                    progress = { state.progressPercent / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "下载进度：${state.progressPercent}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        is UpdateCheckState.Downloaded -> {
            Text(
                text = updateReleaseSummary("更新 APK 已下载", state.release),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ReleasePageButton(state.release, onOpenReleasePage)
        }

        is UpdateCheckState.InstallPermissionNeeded -> {
            Text(
                text = "需要允许 Skip 安装未知应用后继续安装。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenInstallPermissionSettings
            ) {
                Text("去系统设置允许")
            }
        }

        is UpdateCheckState.Error -> {
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            state.release?.let { ReleasePageButton(it, onOpenReleasePage) }
        }
    }
}

@Composable
private fun ReleasePageButton(
    release: UpdateRelease,
    onOpenReleasePage: (UpdateRelease) -> Unit
) {
    if (release.htmlUrl.isBlank()) return
    TextButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = { onOpenReleasePage(release) }
    ) {
        Text("打开发布页")
    }
}

internal fun updateReleaseSummary(prefix: String, release: UpdateRelease): String {
    val published = release.publishedAt.takeIf(String::isNotBlank)
        ?.let { "\n发布时间：$it" }
        .orEmpty()
    val digestSummary = release.apkAsset.digestSha256.take(12)
    return "$prefix：${release.versionName}" +
        "\nAPK：${release.apkAsset.name}" +
        "\n大小：${formatUpdateAssetSize(release.apkAsset.size)}" +
        "\nSHA-256：${digestSummary}…" +
        published
}

private fun formatUpdateAssetSize(size: Long): String {
    return if (size < 1024L) {
        "${size} B"
    } else {
        "${size / 1024L} KB"
    }
}

private fun Throwable.userMessage(prefix: String): String {
    val detail = message.orEmpty().ifBlank { "请稍后重试" }
    return "$prefix：$detail"
}

private fun openReleasePage(context: Context, url: String) {
    if (url.isBlank()) return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    }
}
