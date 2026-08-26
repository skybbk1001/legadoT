package io.legado.app.help.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestTest {

    @Test
    fun selectArtifact_prefersFirstSupportedAbi() {
        val artifacts = listOf(
            UpdateManifest.Artifact(
                abi = "armeabi-v7a",
                fileName = "legado_app_3.26.061010_armeabi-v7a.apk",
                url = "https://example.com/v7.apk"
            ),
            UpdateManifest.Artifact(
                abi = "arm64-v8a",
                fileName = "legado_app_3.26.061010_arm64-v8a.apk",
                url = "https://example.com/v8.apk"
            )
        )

        val selected = UpdateManifestSelector.selectArtifact(
            artifacts,
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a")
        )

        assertEquals("legado_app_3.26.061010_arm64-v8a.apk", selected?.fileName)
    }

    @Test
    fun selectArtifact_usesUniversalWhenAbiDoesNotMatch() {
        val artifacts = listOf(
            UpdateManifest.Artifact(
                abi = "universal",
                fileName = "legado_app_3.26.061010.apk",
                url = "https://example.com/universal.apk"
            )
        )

        val selected = UpdateManifestSelector.selectArtifact(
            artifacts,
            supportedAbis = listOf("arm64-v8a")
        )

        assertEquals("legado_app_3.26.061010.apk", selected?.fileName)
    }

    @Test
    fun compareVersionName_comparesNumericParts() {
        assertTrue(UpdateManifestSelector.compareVersionName("3.26.061010", "3.26.060923") > 0)
        assertEquals(0, UpdateManifestSelector.compareVersionName("3.26.061010", "3.26.061010"))
        assertEquals(0, UpdateManifestSelector.compareVersionName("3.26.061010debug", "3.26.061010"))
        assertTrue(UpdateManifestSelector.compareVersionName("3.26.061010", "3.26.061011") < 0)
    }

    @Test
    fun parseVersionName_readsCurrentApkNamingWithoutDroppingDigits() {
        assertEquals(
            "3.26.061010",
            UpdateManifestSelector.parseVersionName("legado_app_3.26.061010_arm64-v8a.apk")
        )
        assertEquals(
            "3.26.061010",
            UpdateManifestSelector.parseVersionName("legado_app_3.26.061010_armeabi-v7a.apk")
        )
    }

    @Test
    fun toUpdateInfo_returnsSelectedAbiDownload() {
        val manifest = UpdateManifest(
            versionName = "3.26.061010",
            updateLog = "更新日志",
            artifacts = listOf(
                UpdateManifest.Artifact(
                    abi = "armeabi-v7a",
                    fileName = "legado_app_3.26.061010_armeabi-v7a.apk",
                    url = "https://example.com/v7.apk"
                ),
                UpdateManifest.Artifact(
                    abi = "arm64-v8a",
                    fileName = "legado_app_3.26.061010_arm64-v8a.apk",
                    url = "https://example.com/v8.apk"
                )
            )
        )

        val info = UpdateManifestSelector.toUpdateInfo(
            manifest,
            currentVersionName = "3.26.060923",
            supportedAbis = listOf("arm64-v8a")
        )

        assertNotNull(info)
        assertEquals("3.26.061010", info!!.tagName)
        assertEquals("更新日志", info.updateLog)
        assertEquals("https://example.com/v8.apk", info.downloadUrl)
        assertEquals("legado_app_3.26.061010_arm64-v8a.apk", info.fileName)
    }

    @Test
    fun toUpdateInfo_returnsNullWhenManifestIsNotNewer() {
        val manifest = UpdateManifest(
            versionName = "3.26.061010",
            artifacts = listOf(
                UpdateManifest.Artifact(
                    abi = "arm64-v8a",
                    fileName = "legado_app_3.26.061010_arm64-v8a.apk",
                    url = "https://example.com/v8.apk"
                )
            )
        )

        val info = UpdateManifestSelector.toUpdateInfo(
            manifest,
            currentVersionName = "3.26.061010debug",
            supportedAbis = listOf("arm64-v8a")
        )

        assertNull(info)
    }

    @Test
    fun toUpdateResult_returnsNoUpdateWhenManifestIsReachableButNotNewer() {
        val manifest = UpdateManifest(
            versionName = "3.26.061010",
            artifacts = listOf(
                UpdateManifest.Artifact(
                    abi = "arm64-v8a",
                    fileName = "legado_app_3.26.061010_arm64-v8a.apk",
                    url = "https://example.com/v8.apk"
                )
            )
        )

        val result = UpdateManifestSelector.toUpdateResult(
            manifest,
            currentVersionName = "3.26.061010debug",
            supportedAbis = listOf("arm64-v8a")
        )

        assertTrue(result is UpdateManifestResult.NoUpdate)
    }

    @Test
    fun toUpdateInfo_carriesSizePublishDateAndUrls() {
        val manifest = UpdateManifest(
            versionName = "3.26.061010",
            publishedAt = "2026-06-10T00:00:00Z",
            pageUrl = "https://example.com/download",
            updateLog = "更新日志",
            artifacts = listOf(
                UpdateManifest.Artifact(
                    abi = "arm64-v8a",
                    fileName = "legado_app_3.26.061010_arm64-v8a.apk",
                    size = 15078651L,
                    url = "https://example.com/v8.apk",
                    githubUrl = "https://github.com/example/v8.apk"
                )
            )
        )

        val info = UpdateManifestSelector.toUpdateInfo(
            manifest,
            currentVersionName = "3.26.060923",
            supportedAbis = listOf("arm64-v8a")
        )

        assertNotNull(info)
        assertEquals(15078651L, info!!.size)
        assertEquals("2026-06-10T00:00:00Z", info.publishedAt)
        assertEquals("https://example.com/download", info.pageUrl)
        assertEquals("https://github.com/example/v8.apk", info.backupDownloadUrl)
    }

    @Test
    fun toUpdateInfo_dropsBackupUrlWhenSameAsPrimary() {
        val manifest = UpdateManifest(
            versionName = "3.26.061010",
            artifacts = listOf(
                UpdateManifest.Artifact(
                    abi = "arm64-v8a",
                    fileName = "legado_app_3.26.061010_arm64-v8a.apk",
                    url = "https://github.com/example/v8.apk",
                    githubUrl = "https://github.com/example/v8.apk"
                )
            )
        )

        val info = UpdateManifestSelector.toUpdateInfo(
            manifest,
            currentVersionName = "3.26.060923",
            supportedAbis = listOf("arm64-v8a")
        )

        assertNull(info!!.backupDownloadUrl)
    }

    @Test
    fun githubRelease_mapsToUpdateManifest() {
        val release = GithubRelease(
            tagName = "3.26.061010",
            htmlUrl = "https://github.com/example/releases/tag/3.26.061010",
            publishedAt = "2026-06-10T00:00:00Z",
            isPreRelease = true,
            body = "更新日志",
            assets = listOf(
                GithubRelease.Asset(
                    apkUrl = "https://github.com/example/legado_app_3.26.061010_arm64-v8a.apk",
                    name = "legado_app_3.26.061010_arm64-v8a.apk",
                    size = 15078651L,
                    state = "uploaded"
                ),
                GithubRelease.Asset(
                    apkUrl = "https://github.com/example/sources.zip",
                    name = "sources.zip",
                    state = "uploaded"
                )
            )
        )

        val manifest = release.toUpdateManifest()

        assertEquals("beta", manifest.channel)
        assertEquals("3.26.061010", manifest.versionName)
        assertEquals("3.26.061010", manifest.tag)
        assertEquals("更新日志", manifest.updateLog)
        assertEquals("https://github.com/example/releases/tag/3.26.061010", manifest.pageUrl)
        assertEquals(1, manifest.artifacts.size)
        assertEquals("arm64-v8a", manifest.artifacts[0].abi)
        assertEquals(15078651L, manifest.artifacts[0].size)
    }

    @Test
    fun toUpdateResult_prefersManifestArtifactVersionOverShorterVersionName() {
        // beta 清单 versionName 为 10 位(3.26.081620),产物名为 12 位(3.26.08162006);
        // 已装 12 位 release(3.26.081812)时按位比对必须判定不更新
        val manifest = UpdateManifest(
            channel = "beta",
            versionName = "3.26.081620",
            tag = "beta",
            artifacts = listOf(
                UpdateManifest.Artifact(
                    abi = "arm64-v8a",
                    fileName = "legado_app_3.26.08162006_arm64-v8a_release.apk",
                    url = "https://example.com/v8.apk"
                )
            )
        )

        val result = UpdateManifestSelector.toUpdateResult(
            manifest = manifest,
            currentVersionName = "3.26.081812",
            supportedAbis = listOf("arm64-v8a")
        )

        assertTrue(result is UpdateManifestResult.NoUpdate)
    }

    @Test
    fun compareVersionName_alignsTimestampPartsOfDifferentWidth() {
        assertTrue(UpdateManifestSelector.compareVersionName("3.26.081620", "3.26.081812") < 0)
        assertTrue(UpdateManifestSelector.compareVersionName("3.26.08162006", "3.26.081812") < 0)
        assertTrue(UpdateManifestSelector.compareVersionName("3.26.081812", "3.26.08162006") > 0)
        assertEquals(0, UpdateManifestSelector.compareVersionName("3.26.081620", "3.26.08162000"))
        // 8 月与 10 月:月份首位为 0,补零对齐不能吃掉高位
        assertTrue(UpdateManifestSelector.compareVersionName("3.26.101620", "3.26.08162006") > 0)
        assertTrue(UpdateManifestSelector.compareVersionName("3.26.08162006", "3.26.101620") < 0)
        // 同日同时,beta 带分钟位视为更新
        assertTrue(UpdateManifestSelector.compareVersionName("3.26.08181206", "3.26.081812") > 0)
        // 跨年:年份段仍按数值比较
        assertTrue(UpdateManifestSelector.compareVersionName("3.27.010100", "3.26.123123") > 0)
    }

    @Test
    fun toUpdateResult_installedBetaStillGetsNewerRelease() {
        // 反向保护:已装 12 位 beta 产物版本号时,6 位的新 release 仍须判定为更新
        val manifest = UpdateManifest(
            channel = "release",
            versionName = "3.26.081812",
            tag = "3.26.081812",
            artifacts = listOf(
                UpdateManifest.Artifact(
                    abi = "arm64-v8a",
                    fileName = "legado_app_3.26.081812_arm64-v8a.apk",
                    url = "https://example.com/v8.apk"
                )
            )
        )

        val result = UpdateManifestSelector.toUpdateResult(
            manifest = manifest,
            currentVersionName = "3.26.08162006",
            supportedAbis = listOf("arm64-v8a")
        )

        assertTrue(result is UpdateManifestResult.HasUpdate)
    }

    @Test
    fun githubRelease_readsVersionFromReleaseNameWhenTagIsNotVersioned() {
        val release = GithubRelease(
            tagName = "beta",
            name = "legado_app_3.26.081620",
            isPreRelease = true,
            assets = listOf(
                GithubRelease.Asset(
                    apkUrl = "https://github.com/example/v8.apk",
                    name = "legado_app_3.26.08162006_arm64-v8a_release.apk",
                    state = "uploaded"
                )
            )
        )

        assertEquals("3.26.081620", release.toUpdateManifest().versionName)
    }

    @Test
    fun toUpdateResult_betaTagIsNotNewerThanInstalledRelease() {
        val release = GithubRelease(
            tagName = "beta",
            name = "legado_app_3.26.081620",
            isPreRelease = true,
            assets = listOf(
                GithubRelease.Asset(
                    apkUrl = "https://github.com/example/v8.apk",
                    name = "legado_app_3.26.08162006_arm64-v8a_release.apk",
                    state = "uploaded"
                )
            )
        )

        val result = UpdateManifestSelector.toUpdateResult(
            manifest = release.toUpdateManifest(),
            currentVersionName = "3.26.081812",
            supportedAbis = listOf("arm64-v8a")
        )

        assertTrue(result is UpdateManifestResult.NoUpdate)
    }

    @Test
    fun githubRelease_skipsNotUploadedAssets() {
        val release = GithubRelease(
            tagName = "3.26.061010",
            assets = listOf(
                GithubRelease.Asset(
                    apkUrl = "https://github.com/example/v8.apk",
                    name = "legado_app_3.26.061010_arm64-v8a.apk",
                    state = "starter"
                )
            )
        )

        assertTrue(release.toUpdateManifest().artifacts.isEmpty())
    }
}
