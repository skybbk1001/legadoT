package io.legado.app.model.analyzeRule

import androidx.media3.common.MediaItem
import com.bumptech.glide.load.model.GlideUrl
import io.legado.app.constant.AppConst.UA_NAME
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.SourceContract
import io.legado.app.help.JsExtensionsAndroid
import io.legado.app.help.config.AppConfig
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.help.glide.GlideHeaders
import io.legado.app.help.http.BackstageWebView
import io.legado.app.help.http.StrResponse
import io.legado.app.utils.get
import okhttp3.Response
import java.io.InputStream

/**
 * AnalyzeUrl 迁入 shared 后仅实现纯 JsExtensions; app 侧经 AnalyzeUrl.javaDelegateFactory
 * 注入本委托, evalJS 的 java 绑定指向本实例, 使书源 JS 的 java.toast/webView 等
 * Android 扩展与 java.put/getStrResponse 等 AnalyzeUrl 上下文方法同时可用。
 * 行为与迁移前(java=AnalyzeUrl 实例, 其实现 JsExtensionsAndroid)保持一致。
 */
class AnalyzeUrlAndroidDelegate(
    private val analyzeUrl: AnalyzeUrl
) : JsExtensionsAndroid {

    override fun getSource(): BaseSource? = analyzeUrl.getSource() as? BaseSource

    fun initUrl() = analyzeUrl.initUrl()

    fun evalJS(jsStr: String, result: Any? = null): Any? = analyzeUrl.evalJS(jsStr, result)

    fun put(key: String, value: String): String = analyzeUrl.put(key, value)

    fun get(key: String): String = analyzeUrl.get(key)

    suspend fun getStrResponseAwait(
        jsStr: String? = null,
        sourceRegex: String? = null,
        useWebView: Boolean = true
    ): StrResponse = analyzeUrl.getStrResponseAwait(jsStr, sourceRegex, useWebView)

    fun getStrResponse(
        jsStr: String? = null,
        sourceRegex: String? = null,
        useWebView: Boolean = true
    ): StrResponse = analyzeUrl.getStrResponse(jsStr, sourceRegex, useWebView)

    suspend fun getResponseAwait(): Response = analyzeUrl.getResponseAwait()

    fun getResponse(): Response = analyzeUrl.getResponse()

    suspend fun getByteArrayAwait(): ByteArray = analyzeUrl.getByteArrayAwait()

    fun getByteArray(): ByteArray = analyzeUrl.getByteArray()

    suspend fun getInputStreamAwait(): InputStream = analyzeUrl.getInputStreamAwait()

    fun getInputStream(): InputStream = analyzeUrl.getInputStream()

    suspend fun upload(fileName: String, file: Any, contentType: String): StrResponse =
        analyzeUrl.upload(fileName, file, contentType)

    fun isPost(): Boolean = analyzeUrl.isPost()

    fun getUserAgent(): String = analyzeUrl.getUserAgent()

    fun getGlideUrl(): GlideUrl = analyzeUrl.getGlideUrl()

    fun getMediaItem(): MediaItem = analyzeUrl.getMediaItem()

    val ruleUrl: String get() = analyzeUrl.ruleUrl

    val url: String get() = analyzeUrl.url

    val type: String? get() = analyzeUrl.type

    val headerMap: LinkedHashMap<String, String> get() = analyzeUrl.headerMap

    val serverID: Long? get() = analyzeUrl.serverID

}

/**
 * AnalyzeUrl.WebViewFetcher 的 app 实现: 承接迁移前 getStrResponseAwait 中
 * 对 BackstageWebView 的调用(useWebView 请求路径)。
 */
object BackstageWebViewFetcher : AnalyzeUrl.WebViewFetcher {

    override suspend fun getStrResponse(
        url: String?,
        html: String?,
        tag: String?,
        javaScript: String?,
        sourceRegex: String?,
        headerMap: Map<String, String>?,
        delayTime: Long,
        source: SourceContract?
    ): StrResponse {
        return BackstageWebView(
            url = url,
            html = html,
            tag = tag,
            javaScript = javaScript,
            sourceRegex = sourceRegex,
            headerMap = headerMap,
            delayTime = delayTime,
            source = source as? BaseSource,
        ).getStrResponse()
    }

}

/**
 * 获取处理过阅读定义的urlOption和cookie的GlideUrl
 */
fun AnalyzeUrl.getGlideUrl(): GlideUrl {
    setCookie()
    return GlideUrl(url, GlideHeaders(headerMap))
}

fun AnalyzeUrl.getUserAgent(): String {
    return headerMap.get(UA_NAME, true) ?: AppConfig.userAgent
}

fun AnalyzeUrl.getMediaItem(): MediaItem {
    setCookie()
    return ExoPlayerHelper.createMediaItem(url, headerMap)
}
