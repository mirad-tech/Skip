package com.example.skip.ui.about

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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

    fun checkForUpdates() {
        state = UpdateCheckState.Checking
        scope.launch {
            runCatching { UpdateRepository.checkLatestRelease() }
                .onSuccess { release ->
                    state = if (VersionComparator.isNewer(release.tagName, versionName)) {
                        UpdateCheckState.Available(release)
                    } else {
                        UpdateCheckState.Latest(release)
                    }
                }
                .onFailure {
                    state = UpdateCheckState.Error(it.userMessage("检测失败"))
                }
        }
    }

    fun downloadUpdate(release: UpdateRelease) {
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
                    error("更新 APK 校验失败")
                }
                when (val validation = ApkUpdateInstaller.validateDownloadedApk(context, file, versionCode)) {
                    ApkValidationResult.Valid -> file
                    is ApkValidationResult.Invalid -> error(validation.message)
                }
            }.onSuccess { file ->
                state = UpdateCheckState.Downloaded(release, file)
            }.onFailure {
                state = UpdateCheckState.Error(it.userMessage("下载失败"), release)
            }
        }
    }

    fun installUpdate(release: UpdateRelease, file: File) {
        when (val result = ApkUpdateInstaller.installDownloadedApk(context, file)) {
            InstallStartResult.Started -> state = UpdateCheckState.Downloaded(release, file)
            InstallStartResult.PermissionNeeded -> {
                state = UpdateCheckState.InstallPermissionNeeded(release, file)
            }
            is InstallStartResult.Error -> state = UpdateCheckState.Error(result.message, release)
        }
    }

    SimpleScreenScaffold(
        title = "关于",
        onBack = onBack
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard(
                title = "Skip $versionName",
                body = "一个本地化的 Android 辅助点击工具。"
            )
            InfoCard(
                title = "项目定位",
                body = "减少重复点击，不破解广告，不绕过其他 App 的安全机制。"
            )
            UpdateStatusCard(
                currentVersionName = versionName,
                state = state,
                onCheck = ::checkForUpdates,
                onDownload = ::downloadUpdate,
                onInstall = ::installUpdate,
                onOpenInstallPermissionSettings = {
                    ApkUpdateInstaller.openInstallPermissionSettings(context)
                },
                onOpenReleasePage = { release ->
                    openReleasePage(context, release.htmlUrl)
                }
            )
        }
    }
}

@Composable
private fun UpdateStatusCard(
    currentVersionName: String,
    state: UpdateCheckState,
    onCheck: () -> Unit,
    onDownload: (UpdateRelease) -> Unit,
    onInstall: (UpdateRelease, File) -> Unit,
    onOpenInstallPermissionSettings: () -> Unit,
    onOpenReleasePage: (UpdateRelease) -> Unit
) {
    Card(
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
                text = "版本更新",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "当前版本：$currentVersionName",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            UpdateStateContent(
                state = state,
                onCheck = onCheck,
                onDownload = onDownload,
                onInstall = onInstall,
                onOpenInstallPermissionSettings = onOpenInstallPermissionSettings,
                onOpenReleasePage = onOpenReleasePage
            )
        }
    }
}

@Composable
private fun UpdateStateContent(
    state: UpdateCheckState,
    onCheck: () -> Unit,
    onDownload: (UpdateRelease) -> Unit,
    onInstall: (UpdateRelease, File) -> Unit,
    onOpenInstallPermissionSettings: () -> Unit,
    onOpenReleasePage: (UpdateRelease) -> Unit
) {
    when (state) {
        UpdateCheckState.Idle -> {
            Text(
                text = "仅在你点击检测或下载时访问 GitHub Releases。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onCheck
            ) {
                Text("检测新版本")
            }
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
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onCheck
            ) {
                Text("重新检测")
            }
            ReleasePageButton(state.release, onOpenReleasePage)
        }

        is UpdateCheckState.Available -> {
            Text(
                text = releaseSummary("发现新版本", state.release),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onDownload(state.release) }
            ) {
                Text("下载更新")
            }
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
                text = releaseSummary("更新 APK 已下载", state.release),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onInstall(state.release, state.file) }
            ) {
                Text("安装更新")
            }
            ReleasePageButton(state.release, onOpenReleasePage)
        }

        is UpdateCheckState.InstallPermissionNeeded -> {
            Text(
                text = "需要允许 Skip 安装未知应用后继续安装。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenInstallPermissionSettings
            ) {
                Text("去系统设置允许")
            }
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onInstall(state.release, state.file) }
            ) {
                Text("继续安装")
            }
        }

        is UpdateCheckState.Error -> {
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onCheck
            ) {
                Text("重新检测")
            }
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

private fun releaseSummary(prefix: String, release: UpdateRelease): String {
    val published = release.publishedAt.takeIf(String::isNotBlank)
        ?.let { "\n发布时间：$it" }
        .orEmpty()
    return "$prefix：${release.versionName}$published"
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
