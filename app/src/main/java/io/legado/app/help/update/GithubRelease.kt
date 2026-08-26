package io.legado.app.help.update

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

/**
 * GitHub Releases API 响应模型,更新清单主链失效时的回退数据源
 */
@Keep
data class GithubRelease(
    @SerializedName("tag_name")
    val tagName: String = "",
    val name: String = "",
    @SerializedName("html_url")
    val htmlUrl: String = "",
    @SerializedName("published_at")
    val publishedAt: String = "",
    @SerializedName("prerelease")
    val isPreRelease: Boolean = false,
    val draft: Boolean = false,
    val body: String = "",
    val assets: List<Asset>? = null
) {

    fun toUpdateManifest(): UpdateManifest {
        return UpdateManifest(
            source = "github",
            channel = if (isPreRelease) "beta" else "release",
            // beta 是滚动 tag,版本号取 release 标题(legado_app_3.26.081620);
            // 产物名带分钟位,位宽与已装版本号不同,只作最后兜底
            versionName = UpdateManifestSelector.parseVersionName(tagName)
                .ifBlank { UpdateManifestSelector.parseVersionName(name) },
            tag = tagName,
            publishedAt = publishedAt,
            updateLog = body,
            pageUrl = htmlUrl,
            artifacts = assets.orEmpty().filter { it.isValid }.map {
                UpdateManifest.Artifact(
                    abi = UpdateManifestSelector.inferAbi(it.name),
                    fileName = it.name,
                    size = it.size,
                    url = it.apkUrl
                )
            }
        )
    }

    @Keep
    data class Asset(
        @SerializedName("browser_download_url")
        val apkUrl: String = "",
        val name: String = "",
        val size: Long = 0L,
        val state: String = ""
    ) {
        val isValid: Boolean
            get() = name.endsWith(".apk", ignoreCase = true) && state == "uploaded"
    }
}
