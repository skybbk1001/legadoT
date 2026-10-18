package io.legado.app.help.update

import android.os.Build
import androidx.annotation.Keep
import io.legado.app.constant.AppConst
import io.legado.app.constant.appInfo
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.coroutines.coroutineContext

@Keep
@Suppress("unused")
object AppUpdateGitHub : AppUpdate.AppUpdateInterface {

    private val manifestUrls = listOf(
        "https://skybook.qzz.io/legado-download/update.json"
    )

    private const val githubReleasesUrl =
        "https://api.github.com/repos/skybbk1001/legadoT/releases?per_page=15"

    private val checkVariant: AppVariant
        get() = when (AppConfig.updateToVariant) {
            "official_version" -> AppVariant.OFFICIAL
            "beta_release_version" -> AppVariant.BETA_RELEASE
            "beta_releaseA_version" -> AppVariant.BETA_RELEASEA
            else -> AppConst.appInfo.appVariant.takeUnless { it == AppVariant.UNKNOWN } ?: AppVariant.OFFICIAL
        }

    private val checkChannel: String
        get() = if (checkVariant.isBeta()) "beta" else "release"

    private suspend fun getManifestUpdate(): UpdateManifestResult {
        var lastError: Throwable? = null
        manifestUrls.forEach { manifestUrl ->
            try {
                val url = manifestUrl.toHttpUrl().newBuilder()
                    .addQueryParameter("channel", checkChannel)
                    .build()
                val res = okHttpClient.newCallResponse {
                    url(url)
                }
                if (!res.isSuccessful) {
                    lastError = NoStackTraceException("获取更新清单出错(${res.code})")
                    return@forEach
                }
                val body = res.body.text()
                if (body.isBlank()) {
                    lastError = NoStackTraceException("获取更新清单出错")
                    return@forEach
                }
                val manifest = GSON.fromJsonObject<UpdateManifest>(body)
                    .getOrElse {
                        lastError = it
                        return@forEach
                    }
                return UpdateManifestSelector.toUpdateResult(
                    manifest = manifest,
                    currentVersionName = AppConst.appInfo.versionName,
                    currentVersionCode = AppConst.appInfo.versionCode,
                    supportedAbis = Build.SUPPORTED_ABIS.toList()
                )
            } catch (e: Throwable) {
                lastError = e
            }
        }
        return UpdateManifestResult.Unavailable(
            lastError?.localizedMessage ?: "获取更新清单出错"
        )
    }

    /**
     * 清单主链全部失败时回退 GitHub Releases API,按渠道取最新一条
     */
    private suspend fun getGithubUpdate(): UpdateManifestResult {
        return try {
            val res = okHttpClient.newCallResponse {
                url(githubReleasesUrl)
                header("Accept", "application/vnd.github+json")
            }
            if (!res.isSuccessful) {
                return UpdateManifestResult.Unavailable("GitHub 获取版本出错(${res.code})")
            }
            val releases = GSON.fromJsonArray<GithubRelease>(res.body.text()).getOrThrow()
            val wantBeta = checkChannel == "beta"
            val release = releases.firstOrNull { !it.draft && it.isPreRelease == wantBeta }
                ?: return UpdateManifestResult.Unavailable("GitHub 没有对应渠道的版本")
            UpdateManifestSelector.toUpdateResult(
                manifest = release.toUpdateManifest(),
                currentVersionName = AppConst.appInfo.versionName,
                currentVersionCode = AppConst.appInfo.versionCode,
                supportedAbis = Build.SUPPORTED_ABIS.toList()
            )
        } catch (e: Throwable) {
            coroutineContext.ensureActive()
            UpdateManifestResult.Unavailable(e.localizedMessage ?: "GitHub 获取版本出错")
        }
    }

    override fun check(
        scope: CoroutineScope,
    ): Coroutine<AppUpdate.UpdateInfo> {
        return Coroutine.async(scope) {
            var result = getManifestUpdate()
            if (result is UpdateManifestResult.Unavailable) {
                val fallback = getGithubUpdate()
                if (fallback !is UpdateManifestResult.Unavailable) {
                    result = fallback
                }
            }
            when (result) {
                is UpdateManifestResult.HasUpdate -> result.updateInfo
                UpdateManifestResult.NoUpdate -> throw NoStackTraceException("已是最新版本")
                is UpdateManifestResult.Unavailable -> throw NoStackTraceException(result.message)
            }
        }.timeout(30000)
    }
}
