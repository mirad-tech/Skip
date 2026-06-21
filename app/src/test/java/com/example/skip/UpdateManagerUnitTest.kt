package com.example.skip

import com.example.skip.ui.about.ApkArchiveMetadata
import com.example.skip.ui.about.ApkUpdateInstaller
import com.example.skip.ui.about.ApkValidationResult
import com.example.skip.ui.about.UpdateDownloadVerifier
import com.example.skip.ui.about.UpdateCardAction
import com.example.skip.ui.about.UpdateCardBehavior
import com.example.skip.ui.about.UpdateAsset
import com.example.skip.ui.about.UpdateCheckState
import com.example.skip.ui.about.UpdateRepository
import com.example.skip.ui.about.UpdateRelease
import com.example.skip.ui.about.UpdateReleaseParser
import com.example.skip.ui.about.VersionComparator
import com.example.skip.ui.about.updateReleaseSummary
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun releaseParserRejectsUnexpectedApkAssetName() {
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

        val result = runCatching { UpdateReleaseParser.parse(json) }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("可信"))
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
    fun releaseParserRejectsReleaseApkWithoutDigest() {
        val json = releaseJson(
            assetJson = """
                {
                  "name": "Skip-v1.0.5-release.apk",
                  "size": 12,
                  "browser_download_url": "https://example.com/release.apk"
                }
            """.trimIndent()
        )

        val result = runCatching { UpdateReleaseParser.parse(json) }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("digest"))
    }

    @Test
    fun releaseParserRejectsNonSha256Digest() {
        val json = releaseJson(
            assetJson = trustedAssetJson(digest = "md5:${"a".repeat(32)}")
        )

        val result = runCatching { UpdateReleaseParser.parse(json) }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("sha256"))
    }

    @Test
    fun releaseParserNormalizesUppercaseSha256Digest() {
        val json = releaseJson(
            assetJson = trustedAssetJson(digest = "sha256:${"A".repeat(64)}")
        )

        val release = UpdateReleaseParser.parse(json)

        assertEquals("a".repeat(64), release.apkAsset.digestSha256)
    }

    @Test
    fun releaseParserRejectsAmbiguousTrustedApkAssets() {
        val json = """
            {
              "tag_name": "v1.0.5",
              "assets": [
                ${trustedAssetJson(digest = "sha256:${"a".repeat(64)}")},
                ${trustedAssetJson(digest = "sha256:${"b".repeat(64)}")}
              ]
            }
        """.trimIndent()

        val result = runCatching { UpdateReleaseParser.parse(json) }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("唯一"))
    }

    @Test
    fun updateDownloadVerifierDeletesFileWhenSha256DoesNotMatch() {
        val temp = File.createTempFile("skip-update", ".apk")
        temp.writeText("bad-apk")
        val expected = sha256("good-apk".toByteArray())

        val verified = UpdateDownloadVerifier.verifySha256OrDelete(temp, "sha256:$expected")

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
    fun updateDownloadVerifierDeletesFileWhenExpectedDigestIsMissingOrBlank() {
        listOf(null, "", "   ").forEach { digest ->
            val temp = File.createTempFile("skip-update", ".apk")
            temp.writeText("apk")

            val verified = UpdateDownloadVerifier.verifySha256OrDelete(temp, digest)

            assertFalse(verified)
            assertFalse(temp.exists())
        }
    }

    @Test
    fun updateDownloadVerifierDeletesFileWhenExpectedDigestFormatIsInvalid() {
        listOf(
            "${"a".repeat(64)}",
            "md5:${"a".repeat(32)}",
            "sha256:not-valid"
        ).forEach { digest ->
            val temp = File.createTempFile("skip-update", ".apk")
            temp.writeText("apk")

            val verified = UpdateDownloadVerifier.verifySha256OrDelete(temp, digest)

            assertFalse(verified)
            assertFalse(temp.exists())
        }
    }

    @Test
    fun updateDownloadVerifierRejectsUppercaseDigestAlgorithmEvenWhenHashMatches() {
        val temp = File.createTempFile("skip-update", ".apk")
        val bytes = "apk".toByteArray()
        temp.writeBytes(bytes)

        val verified = UpdateDownloadVerifier.verifySha256OrDelete(temp, "SHA256:${sha256(bytes)}")

        assertFalse(verified)
        assertFalse(temp.exists())
    }

    @Test
    fun apkInstallerRejectsPackageNameMismatchAndDeletesFile() {
        val temp = tempApk()

        val result = ApkUpdateInstaller.validateArchiveMetadataOrDelete(
            file = temp,
            expectedPackageName = "com.example.skip",
            currentVersionCode = 14,
            installedCertificateSha256 = setOf("installed"),
            archive = ApkArchiveMetadata("com.example.other", 15, setOf("installed"))
        )

        assertInvalid(result, "包名不匹配")
        assertFalse(temp.exists())
    }

    @Test
    fun apkInstallerRejectsNonUpgradeVersionAndDeletesFile() {
        val temp = tempApk()

        val result = ApkUpdateInstaller.validateArchiveMetadataOrDelete(
            file = temp,
            expectedPackageName = "com.example.skip",
            currentVersionCode = 14,
            installedCertificateSha256 = setOf("installed"),
            archive = ApkArchiveMetadata("com.example.skip", 14, setOf("installed"))
        )

        assertInvalid(result, "版本不高于")
        assertFalse(temp.exists())
    }

    @Test
    fun apkInstallerRejectsMismatchedCertificateAndDeletesFile() {
        val temp = tempApk()

        val result = ApkUpdateInstaller.validateArchiveMetadataOrDelete(
            file = temp,
            expectedPackageName = "com.example.skip",
            currentVersionCode = 14,
            installedCertificateSha256 = setOf("installed"),
            archive = ApkArchiveMetadata("com.example.skip", 15, setOf("downloaded"))
        )

        assertInvalid(result, "签名证书不匹配")
        assertFalse(temp.exists())
    }

    @Test
    fun apkInstallerAcceptsNewerArchiveWithMatchingCertificate() {
        val temp = tempApk()

        val result = ApkUpdateInstaller.validateArchiveMetadataOrDelete(
            file = temp,
            expectedPackageName = "com.example.skip",
            currentVersionCode = 14,
            installedCertificateSha256 = setOf("first", "second"),
            archive = ApkArchiveMetadata("com.example.skip", 15, setOf("second", "first"))
        )

        assertTrue(result is ApkValidationResult.Valid)
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
    fun updateCardBehaviorMakesNewVersionAvailableBeforeDownload() {
        val release = sampleRelease()

        assertEquals(
            UpdateCheckState.Available(release),
            UpdateCardBehavior.stateAfterCheck(release, currentVersionName = "1.0.4")
        )
        assertEquals(
            UpdateCheckState.Latest(release),
            UpdateCardBehavior.stateAfterCheck(release, currentVersionName = "1.0.5")
        )
    }

    @Test
    fun updateReleaseSummaryIncludesAssetSizeAndDigest() {
        val summary = updateReleaseSummary("发现新版本", sampleRelease())

        assertTrue(summary.contains("Skip-v1.0.5-release.apk"))
        assertTrue(summary.contains("大小：12 B"))
        assertTrue(summary.contains("SHA-256：aaaaaaaaaaaa"))
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

    private fun releaseJson(assetJson: String): String {
        return """
            {
              "tag_name": "v1.0.5",
              "assets": [$assetJson]
            }
        """.trimIndent()
    }

    private fun trustedAssetJson(digest: String): String {
        return """
            {
              "name": "Skip-v1.0.5-release.apk",
              "size": 12,
              "browser_download_url": "https://example.com/release.apk",
              "digest": "$digest"
            }
        """.trimIndent()
    }

    private fun tempApk(): File {
        return File.createTempFile("skip-update", ".apk").apply { writeText("apk") }
    }

    private fun assertInvalid(result: ApkValidationResult, messagePart: String) {
        assertTrue(result is ApkValidationResult.Invalid)
        assertTrue((result as ApkValidationResult.Invalid).message.contains(messagePart))
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
