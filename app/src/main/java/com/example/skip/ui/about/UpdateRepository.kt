package com.example.skip.ui.about

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object UpdateRepository {
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/mirad-tech/Skip/releases/latest"
    private const val MAX_UPDATE_BYTES = 200L * 1024 * 1024
    private const val MAX_DOWNLOAD_REDIRECTS = 5
    private val REDIRECT_CODES = setOf(
        HttpURLConnection.HTTP_MOVED_PERM,
        HttpURLConnection.HTTP_MOVED_TEMP,
        HttpURLConnection.HTTP_SEE_OTHER,
        HttpURLConnection.HTTP_MULT_CHOICE,
        307,
        308
    )

    /**
     * The release JSON supplies both the digest and the download URL, so the download target can
     * never be trusted on its own. Only HTTPS URLs on these hosts are followed.
     */
    private val allowedDownloadHosts = setOf(
        "github.com",
        "api.github.com",
        "objects.githubusercontent.com",
        "github-releases.githubusercontent.com",
        "release-assets.githubusercontent.com"
    )

    suspend fun checkLatestRelease(): UpdateRelease = withContext(Dispatchers.IO) {
        val text = requestText(LATEST_RELEASE_URL)
        UpdateReleaseParser.parse(text)
    }

    suspend fun downloadApk(
        asset: UpdateAsset,
        destination: File,
        onProgress: suspend (Int?) -> Unit
    ): File = withContext(Dispatchers.IO) {
        cleanUpdateDirectory(destination.parentFile)
        destination.parentFile?.mkdirs()
        runCatching {
            val connection = openRedirectCheckedConnection(asset.browserDownloadUrl)
            try {
                connection.connect()
                val code = connection.responseCode
                if (code !in 200..299) {
                    error("下载失败：HTTP $code")
                }
                val totalBytes = connection.contentLengthLong.takeIf { it > 0L }
                if (totalBytes != null && totalBytes > MAX_UPDATE_BYTES) {
                    error("更新包体积超出上限")
                }
                var downloadedBytes = 0L
                var lastProgress: Int? = null
                connection.inputStream.use { input ->
                    destination.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            downloadedBytes += read
                            if (downloadedBytes > MAX_UPDATE_BYTES) {
                                error("更新包体积超出上限")
                            }
                            output.write(buffer, 0, read)
                            val progress = progressPercent(downloadedBytes, totalBytes)
                            if (progress != lastProgress) {
                                lastProgress = progress
                                onProgress(progress)
                            }
                        }
                    }
                }
                if (totalBytes != null && downloadedBytes != totalBytes) {
                    error("下载不完整：期望 $totalBytes 字节，实际 $downloadedBytes 字节")
                }
            } finally {
                connection.disconnect()
            }
            if (!destination.exists() || destination.length() <= 0L) {
                error("下载文件为空")
            }
            destination
        }.getOrElse { throwable ->
            destination.delete()
            throw throwable
        }
    }

    /**
     * Follows redirects manually so every hop can be validated. `instanceFollowRedirects` would
     * silently follow a `Location` that points at an unexpected host.
     */
    private fun openRedirectCheckedConnection(url: String): HttpURLConnection {
        var currentUrl = url
        var redirects = 0
        while (true) {
            assertTrustedDownloadUrl(currentUrl)
            val connection = openConnection(currentUrl, followRedirects = false)
            connection.connect()
            val code = connection.responseCode
            if (code !in REDIRECT_CODES) return connection
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (location.isNullOrBlank()) {
                error("更新下载重定向缺少目标地址")
            }
            redirects++
            if (redirects > MAX_DOWNLOAD_REDIRECTS) {
                error("更新下载重定向次数过多")
            }
            currentUrl = location
        }
    }

    private fun assertTrustedDownloadUrl(url: String) {
        val parsed = runCatching { URL(url) }.getOrElse { error("更新下载地址无效") }
        if (!parsed.protocol.equals("https", ignoreCase = true)) {
            error("更新下载地址必须使用 HTTPS")
        }
        val host = parsed.host.orEmpty().lowercase()
        if (host !in allowedDownloadHosts) {
            error("更新下载地址不受信任：$host")
        }
    }

    fun updateApkFile(context: Context, assetName: String): File {
        return File(File(context.cacheDir, "updates"), safeApkFileName(assetName))
    }

    internal fun safeApkFileName(assetName: String): String {
        val rawName = assetName
            .replace('\\', '/')
            .substringAfterLast('/')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('.', ' ', '_')
        if (rawName.isBlank()) return "skip-update.apk"
        val baseName = rawName.substringBeforeLast(".", rawName)
        if (baseName.uppercase() in reservedWindowsDeviceNames) return "skip-update.apk"
        return if (rawName.endsWith(".apk", ignoreCase = true)) rawName else "$rawName.apk"
    }

    private fun requestText(url: String): String {
        val connection = openConnection(url, followRedirects = true)
        try {
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
            connection.connect()
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                error("检测失败：HTTP $code")
            }
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String, followRedirects: Boolean): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = followRedirects
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Skip-Android")
        }
    }

    private fun cleanUpdateDirectory(directory: File?) {
        directory?.listFiles()
            ?.filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
            ?.forEach { it.delete() }
    }

    private fun progressPercent(done: Long, total: Long?): Int? {
        if (total == null || total <= 0L) return null
        return ((done * 100L) / total).coerceIn(0L, 100L).toInt()
    }

    private val reservedWindowsDeviceNames = setOf(
        "CON",
        "PRN",
        "AUX",
        "NUL",
        "COM1",
        "COM2",
        "COM3",
        "COM4",
        "COM5",
        "COM6",
        "COM7",
        "COM8",
        "COM9",
        "LPT1",
        "LPT2",
        "LPT3",
        "LPT4",
        "LPT5",
        "LPT6",
        "LPT7",
        "LPT8",
        "LPT9"
    )
}
