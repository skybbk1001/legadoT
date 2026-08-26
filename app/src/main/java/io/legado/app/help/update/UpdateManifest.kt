package io.legado.app.help.update

import androidx.annotation.Keep

@Keep
data class UpdateManifest(
    val schema: Int = 1,
    val source: String = "",
    val channel: String = "",
    val versionName: String = "",
    val versionCode: Long? = null,
    val tag: String = "",
    val publishedAt: String = "",
    val updateLog: String = "",
    val pageUrl: String = "",
    val artifacts: List<Artifact> = emptyList(),
    val error: String? = null
) {
    @Keep
    data class Artifact(
        val abi: String = "",
        val fileName: String = "",
        val size: Long = 0L,
        val url: String = "",
        val githubUrl: String? = null,
        val r2Mirrored: Boolean = false
    )
}

sealed class UpdateManifestResult {
    data class HasUpdate(val updateInfo: AppUpdate.UpdateInfo) : UpdateManifestResult()
    data object NoUpdate : UpdateManifestResult()
    data class Unavailable(val message: String) : UpdateManifestResult()
}

object UpdateManifestSelector {

    private val versionRegex = Regex("""\d+(?:\.\d+)+""")

    fun parseVersionName(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return versionRegex.find(value)?.value.orEmpty()
    }

    fun compareVersionName(left: String?, right: String?): Int {
        val leftParts = parseVersionName(left).toVersionParts()
        val rightParts = parseVersionName(right).toVersionParts()
        if (leftParts.isEmpty() || rightParts.isEmpty()) {
            return parseVersionName(left).compareTo(parseVersionName(right))
        }
        val maxSize = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until maxSize) {
            val leftPart = leftParts.getOrElse(index) { "0" }
            val rightPart = rightParts.getOrElse(index) { "0" }
            val diff = if (index == maxSize - 1) {
                // 末段是 MMddHH[mm] 时间戳:位宽随流水线而异(release 6 位、beta 带分钟 8 位),
                // 且月份首位可能为 0。按原字符串补零到同宽比较,同时保住月份高位与分钟精度
                val width = maxOf(leftPart.length, rightPart.length)
                leftPart.padEnd(width, '0').compareTo(rightPart.padEnd(width, '0'))
            } else {
                leftPart.toLong().compareTo(rightPart.toLong())
            }
            if (diff != 0) {
                return diff
            }
        }
        return 0
    }

    fun selectArtifact(
        artifacts: List<UpdateManifest.Artifact>,
        supportedAbis: List<String>
    ): UpdateManifest.Artifact? {
        val candidates = artifacts.filter { it.url.isNotBlank() }
        if (candidates.isEmpty()) return null
        val supported = supportedAbis.map { it.lowercase() }
        supported.forEach { abi ->
            candidates.firstOrNull { it.resolvedAbi() == abi }?.let {
                return it
            }
        }
        return candidates.firstOrNull { it.resolvedAbi() == "universal" }
    }

    fun toUpdateResult(
        manifest: UpdateManifest,
        currentVersionName: String,
        supportedAbis: List<String>,
        currentVersionCode: Long = 0L
    ): UpdateManifestResult {
        if (!manifest.error.isNullOrBlank()) {
            return UpdateManifestResult.Unavailable(manifest.error)
        }
        val versionName = manifest.versionName.ifBlank {
            parseVersionName(manifest.tag).ifBlank {
                manifest.artifacts.firstNotNullOfOrNull { parseVersionName(it.fileName).takeIf(String::isNotBlank) }.orEmpty()
            }
        }
        if (versionName.isBlank()) {
            return UpdateManifestResult.Unavailable("更新清单缺少版本号")
        }
        val isNewer = if (manifest.versionCode != null && currentVersionCode > 0) {
            manifest.versionCode > currentVersionCode
        } else {
            compareVersionName(versionName, currentVersionName) > 0
        }
        if (!isNewer) {
            return UpdateManifestResult.NoUpdate
        }
        val artifact = selectArtifact(manifest.artifacts, supportedAbis)
            ?: return UpdateManifestResult.Unavailable("更新清单没有适合当前设备的安装包")
        return UpdateManifestResult.HasUpdate(
            AppUpdate.UpdateInfo(
                tagName = versionName,
                updateLog = manifest.updateLog,
                downloadUrl = artifact.url,
                fileName = artifact.fileName.ifBlank { "legado_$versionName.apk" },
                size = artifact.size,
                publishedAt = manifest.publishedAt,
                pageUrl = manifest.pageUrl,
                backupDownloadUrl = artifact.githubUrl?.takeIf { it.isNotBlank() && it != artifact.url }
            )
        )
    }

    fun toUpdateInfo(
        manifest: UpdateManifest,
        currentVersionName: String,
        supportedAbis: List<String>,
        currentVersionCode: Long = 0L
    ): AppUpdate.UpdateInfo? {
        return (toUpdateResult(
            manifest = manifest,
            currentVersionName = currentVersionName,
            currentVersionCode = currentVersionCode,
            supportedAbis = supportedAbis
        ) as? UpdateManifestResult.HasUpdate)?.updateInfo
    }

    fun inferAbi(fileName: String?): String {
        val lowerName = fileName.orEmpty().lowercase()
        return when {
            "arm64-v8a" in lowerName -> "arm64-v8a"
            "armeabi-v7a" in lowerName -> "armeabi-v7a"
            "x86_64" in lowerName -> "x86_64"
            Regex("""(?:^|[_\-.])x86(?:[_\-.]|$)""").containsMatchIn(lowerName) -> "x86"
            else -> "universal"
        }
    }

    private fun String.toVersionParts(): List<String> {
        if (isBlank()) return emptyList()
        return split('.')
            .filter { it.toLongOrNull() != null }
    }

    private fun UpdateManifest.Artifact.resolvedAbi(): String {
        return abi.ifBlank { inferAbi(fileName) }.lowercase()
    }
}
