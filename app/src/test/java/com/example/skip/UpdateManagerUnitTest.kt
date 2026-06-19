package com.example.skip

import com.example.skip.ui.about.UpdateDownloadVerifier
import com.example.skip.ui.about.UpdateCardAction
import com.example.skip.ui.about.UpdateCardBehavior
import com.example.skip.ui.about.UpdateAsset
import com.example.skip.ui.about.UpdateCheckState
import com.example.skip.ui.about.UpdateRepository
import com.example.skip.ui.about.UpdateRelease
import com.example.skip.ui.about.UpdateReleaseParser
import com.example.skip.ui.about.VersionComparator
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerUnitTest {
    @Test
    fun versionComparatorHandlesReleaseTags() {
        assertTrue(VersionComparator.isNewer(remoteTag = "v1.0.5", currentVersionName = "1.0.4"))
        assertFalse(VersionComparator.isNewer(remoteTag = "v1.0.4", currentVersionName = "1.0.4"))
        assertFalse(VersionComparator.isNewer(remoteTag = "1.0.3", currentVersionName = "1.0.4"))
        assertFalse(VersionComparator.isNewer(remoteTag = "not-a-version", currentVersionName = "1.0.4"))
    }

    @Test
    fun releaseParserSelectsMatchingReleaseApk() {
        val json = """
            {
              "tag_name": "v1.0.5",
              "html_url": "https://github.com/mirad-tech/Skip/releases/tag/v1.0.5",
              "published_at": "2026-06-19T06:00:00Z",
              "assets": [
                {
                  "name": "Skip-v1.0.5-debug.apk",
                  "size": 11,
                  "browser_download_url": "https://example.com/debug.apk"
                },
                {
                  "name": "Skip-v1.0.5-release.apk",
                  "size": 12,
                  "browser_download_url": "https://example.com/release.apk",
                  "digest": "sha256:${"a".repeat(64)}"
                }
              ]
            }
        """.trimIndent()

        val release = UpdateReleaseParser.parse(json)

        assertEquals("v1.0.5", release.tagName)
        assertEquals("1.0.5", release.versionName)
        assertEquals("https://github.com/mirad-tech/Skip/releases/tag/v1.0.5", release.htmlUrl)
        assertEquals("Skip-v1.0.5-release.apk", release.apkAsset.name)
        assertEquals("https://example.com/release.apk", release.apkAsset.browserDownloadUrl)
        assertEquals("a".repeat(64), release.apkAsset.digestSha256)
    }

    @Test
    fun releaseParserFallsBackToFirstApkAsset() {
        val json = """
            {
              "tag_name": "v1.0.5",
              "html_url": "https://github.com/mirad-tech/Skip/releases/tag/v1.0.5",
              "published_at": "2026-06-19T06:00:00Z",
              "assets": [
                {
                  "name": "Skip-1.0.5.apk",
                  "size": 12,
                  "browser_download_url": "https://example.com/skip.apk"
                }
              ]
            }
        """.trimIndent()

        val release = UpdateReleaseParser.parse(json)

        assertEquals("Skip-1.0.5.apk", release.apkAsset.name)
        assertNull(release.apkAsset.digestSha256)
    }

    @Test
    fun releaseParserRejectsReleaseWithoutApkAsset() {
        val json = """
            {
              "tag_name": "v1.0.5",
              "html_url": "https://github.com/mirad-tech/Skip/releases/tag/v1.0.5",
              "assets": [
                {
                  "name": "source.zip",
                  "size": 12,
                  "browser_download_url": "https://example.com/source.zip"
                }
              ]
            }
        """.trimIndent()

        val result = runCatching { UpdateReleaseParser.parse(json) }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("APK"))
    }

    @Test
    fun releaseParserRejectsMalformedProvidedSha256Digest() {
        val json = """
            {
              "tag_name": "v1.0.5",
              "html_url": "https://github.com/mirad-tech/Skip/releases/tag/v1.0.5",
              "assets": [
                {
                  "name": "Skip-v1.0.5-release.apk",
                  "size": 12,
                  "browser_download_url": "https://example.com/release.apk",
                  "digest": "sha256:not-valid"
                }
              ]
            }
        """.trimIndent()

        val result = runCatching { UpdateReleaseParser.parse(json) }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("SHA-256"))
    }

    @Test
    fun updateDownloadVerifierDeletesFileWhenSha256DoesNotMatch() {
        val temp = File.createTempFile("skip-update", ".apk")
        temp.writeText("bad-apk")
        val expected = sha256("good-apk".toByteArray())

        val verified = UpdateDownloadVerifier.verifySha256OrDelete(temp, expected)

        assertFalse(verified)
        assertFalse(temp.exists())
    }

    @Test
    fun updateDownloadVerifierKeepsFileWhenSha256Matches() {
        val temp = File.createTempFile("skip-update", ".apk")
        val bytes = "good-apk".toByteArray()
        temp.writeBytes(bytes)

        val verified = UpdateDownloadVerifier.verifySha256OrDelete(temp, "sha256:${sha256(bytes)}")

        assertTrue(verified)
        assertTrue(temp.exists())
        temp.delete()
    }

    @Test
    fun updateRepositorySanitizesApkAssetFileNames() {
        assertEquals(
            "Skip-v1.0.5-release.apk",
            UpdateRepository.safeApkFileName("Skip-v1.0.5-release.apk")
        )
        assertEquals(
            "skip-update.apk",
            UpdateRepository.safeApkFileName("../NUL.apk")
        )
        assertEquals(
            "evil.apk",
            UpdateRepository.safeApkFileName("..\\folder\\evil.apk")
        )
    }

    @Test
    fun updateCardBehaviorMapsStatesToTapActions() {
        val release = sampleRelease()
        val file = File("Skip-v1.0.5-release.apk")

        assertEquals(UpdateCardAction.Check, UpdateCardBehavior.nextActionFor(UpdateCheckState.Idle))
        assertEquals(UpdateCardAction.None, UpdateCardBehavior.nextActionFor(UpdateCheckState.Checking))
        assertEquals(UpdateCardAction.Check, UpdateCardBehavior.nextActionFor(UpdateCheckState.Latest(release)))
        assertEquals(UpdateCardAction.DownloadAndInstall, UpdateCardBehavior.nextActionFor(UpdateCheckState.Available(release)))
        assertEquals(UpdateCardAction.None, UpdateCardBehavior.nextActionFor(UpdateCheckState.Downloading(release, null)))
        assertEquals(UpdateCardAction.Install, UpdateCardBehavior.nextActionFor(UpdateCheckState.Downloaded(release, file)))
        assertEquals(UpdateCardAction.Install, UpdateCardBehavior.nextActionFor(UpdateCheckState.InstallPermissionNeeded(release, file)))
        assertEquals(UpdateCardAction.Check, UpdateCardBehavior.nextActionFor(UpdateCheckState.Error("网络错误", release)))
    }

    @Test
    fun manifestDeclaresUpdatePermissionsAndNarrowFileProvider() {
        val manifest = readProjectFile("app/src/main/AndroidManifest.xml")
        val filePaths = readProjectFile("app/src/main/res/xml/file_paths.xml")

        assertTrue(manifest.contains("android.permission.INTERNET"))
        assertTrue(manifest.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        assertTrue(manifest.contains("androidx.core.content.FileProvider"))
        assertTrue(manifest.contains("\${applicationId}.fileprovider"))
        assertTrue(filePaths.contains("""<cache-path name="updates" path="updates/" />"""))
        assertFalse(filePaths.contains("<external-path"))
        assertFalse(filePaths.contains("""path=".""""))
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    private fun readProjectFile(path: String): String {
        return listOf(File(path), File("../$path")).first { it.exists() }.readText()
    }

    private fun sampleRelease(): UpdateRelease {
        return UpdateRelease(
            tagName = "v1.0.5",
            versionName = "1.0.5",
            htmlUrl = "https://github.com/mirad-tech/Skip/releases/tag/v1.0.5",
            publishedAt = "2026-06-19T06:00:00Z",
            apkAsset = UpdateAsset(
                name = "Skip-v1.0.5-release.apk",
                size = 12,
                browserDownloadUrl = "https://example.com/release.apk",
                digestSha256 = "a".repeat(64)
            )
        )
    }
}
