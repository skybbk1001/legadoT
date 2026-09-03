package io.legado.app.help

import android.webkit.WebSettings
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.script.rhino.rhinoContext
import com.script.rhino.rhinoContextOrNull
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppConst.dateFormat
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.BaseSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.crypto.base64ToByteArray
import io.legado.app.help.crypto.hexToByteArray
import io.legado.app.help.crypto.toHexString
import io.legado.app.help.http.BackstageWebView
import io.legado.app.help.http.CookieManager.cookieJarHeader
import io.legado.app.help.http.CookieStore
import io.legado.app.help.http.SSLHelper
import io.legado.app.help.http.StrResponse
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.help.source.getSourceType
import io.legado.app.model.Debug
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.QueryTTF
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.ui.association.OpenUrlConfirmActivity
import io.legado.app.ui.widget.dialog.BottomWebViewDialog
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.EncoderUtils
import io.legado.app.utils.EncodingDetect
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.JsURL
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.StringUtils
import io.legado.app.utils.UrlUtil
import io.legado.app.utils.compress.LibArchiveUtils
import io.legado.app.utils.createFileReplace
import io.legado.app.utils.externalCache
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isMainThread
import io.legado.app.utils.longToastOnUi
import io.legado.app.utils.mapAsync
import io.legado.app.utils.postEvent
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.startActivity
import io.legado.app.utils.toStringArray
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okio.use
import org.jsoup.Connection
import org.jsoup.Jsoup
import splitties.init.appCtx
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.SimpleTimeZone
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * js扩展类, 在js中通过java变量调用
 * 添加方法，请更新文档/legado/app/src/main/assets/help/JsHelp.md
 * 所有对于文件的读写删操作都是相对路径,只能操作阅读缓存内的文件
 * /android/data/{package}/cache/...
 */
@Keep
@Suppress("unused")
interface JsExtensions : JsEncodeUtils {

    fun getSource(): BaseSource?

    private val context: CoroutineContext
        get() = rhinoContext.coroutineContext ?: EmptyCoroutineContext

    /**
     * 访问网络,返回String
     */
    fun ajax(url: Any): String? {
        val urlStr = if (url is List<*>) {
            url.firstOrNull().toString()
        } else {
            url.toString()
        }
        val analyzeUrl = AnalyzeUrl(urlStr, source = getSource(), coroutineContext = context)
        return kotlin.runCatching {
            analyzeUrl.getStrResponse().body
        }.onFailure {
            rhinoContext.ensureActive()
            AppLog.put("ajax(${urlStr}) error\n${it.localizedMessage}", it)
        }.getOrElse {
            it.stackTraceStr
        }
    }

    /**
     * 并发访问网络
     */
    fun ajaxAll(urlList: Array<String>): Array<StrResponse> {
        return runBlocking(context) {
            urlList.asFlow().mapAsync(AppConfig.threadCount) { url ->
                val analyzeUrl = AnalyzeUrl(
                    url,
                    source = getSource(),
                    coroutineContext = coroutineContext
                )
                analyzeUrl.getStrResponseAwait()
            }.flowOn(IO).toList().toTypedArray()
        }
    }

    /**
     * 访问网络,返回Response<String>
     */
    fun connect(urlStr: String): StrResponse {
        val analyzeUrl = AnalyzeUrl(
            urlStr,
            source = getSource(),
            coroutineContext = context
        )
        return kotlin.runCatching {
            analyzeUrl.getStrResponse()
        }.onFailure {
            rhinoContext.ensureActive()
            AppLog.put("connect(${urlStr}) error\n${it.localizedMessage}", it)
        }.getOrElse {
            StrResponse(analyzeUrl.url, it.stackTraceStr)
        }
    }

    fun connect(urlStr: String, header: String?): StrResponse {
        val headerMap = GSON.fromJsonObject<Map<String, String>>(header).getOrNull()
        val analyzeUrl = AnalyzeUrl(
            urlStr,
            headerMapF = headerMap,
            source = getSource(),
            coroutineContext = context
        )
        return kotlin.runCatching {
            analyzeUrl.getStrResponse()
        }.onFailure {
            rhinoContext.ensureActive()
            AppLog.put("ajax($urlStr,$header) error\n${it.localizedMessage}", it)
        }.getOrElse {
            StrResponse(analyzeUrl.url, it.stackTraceStr)
        }
    }

    /**
     * 使用webView访问网络
     * @param html 直接用webView载入的html, 如果html为空直接访问url
     * @param url html内如果有相对路径的资源不传入url访问不了
     * @param js 用来取返回值的js语句, 没有就返回整个源代码
     * @return 返回js获取的内容
     */
    fun webView(html: String?, url: String?, js: String?): String? {
        if (isMainThread) {
            error("webView must be called on a background thread")
        }
        return runBlocking(context) {
            BackstageWebView(
                url = url,
                html = html,
                javaScript = js,
                headerMap = getSource()?.getHeaderMap(true),
                tag = getSource()?.getKey(),
                source = getSource(),
            ).getStrResponse().body
        }
    }

    /**
     * 使用webView获取资源url
     */
    fun webViewGetSource(html: String?, url: String?, js: String?, sourceRegex: String): String? {
        if (isMainThread) {
            error("webViewGetSource must be called on a background thread")
        }
        return runBlocking(context) {
            BackstageWebView(
                url = url,
                html = html,
                javaScript = js,
                headerMap = getSource()?.getHeaderMap(true),
                tag = getSource()?.getKey(),
                sourceRegex = sourceRegex,
                source = getSource(),
            ).getStrResponse().body
        }
    }

    /**
     * 使用webView获取跳转url
     */
    fun webViewGetOverrideUrl(
        html: String?,
        url: String?,
        js: String?,
        overrideUrlRegex: String
    ): String? {
        if (isMainThread) {
            error("webViewGetOverrideUrl must be called on a background thread")
        }
        return runBlocking(context) {
            BackstageWebView(
                url = url,
                html = html,
                javaScript = js,
                headerMap = getSource()?.getHeaderMap(true),
                tag = getSource()?.getKey(),
                overrideUrlRegex = overrideUrlRegex,
                source = getSource(),
            ).getStrResponse().body
        }
    }

    /**
     * 使用内置浏览器打开链接，手动验证网站防爬
     * @param url 要打开的链接
     * @param title 浏览器页面的标题
     */
    fun startBrowser(url: String, title: String) {
        rhinoContext.ensureActive()
        SourceVerificationHelp.startBrowser(getSource(), url, title)
    }

    /**
     * 使用内置浏览器打开链接，并等待网页结果
     */
    fun startBrowserAwait(url: String, title: String, refetchAfterSuccess: Boolean): StrResponse {
        rhinoContext.ensureActive()
        val body = SourceVerificationHelp.getVerificationResult(
            getSource(), url, title, true, refetchAfterSuccess
        )
        return StrResponse(url, body)
    }

    fun startBrowserAwait(url: String, title: String): StrResponse {
        return startBrowserAwait(url, title, true)
    }

    /**
     * 打开图片验证码对话框，等待返回验证结果
     */
    fun getVerificationCode(imageUrl: String): String {
        rhinoContext.ensureActive()
        return SourceVerificationHelp.getVerificationResult(getSource(), imageUrl, "", false)
    }

    /**
     * 可从网络，本地文件(阅读私有数据目录相对路径)导入JavaScript脚本
     */
    fun importScript(path: String): String {
        val result = when {
            path.startsWith("http") -> cacheFile(path)
            else -> readTxtFile(path)
        }
        if (result.isBlank()) throw NoStackTraceException("$path 内容获取失败或者为空")
        return result
    }

    /**
     * 缓存以文本方式保存的文件 如.js .txt等
     * @param urlStr 网络文件的链接
     * @return 返回缓存后的文件内容
     */
    fun cacheFile(urlStr: String): String {
        return cacheFile(urlStr, 0)
    }

    /**
     * 缓存以文本方式保存的文件 如.js .txt等
     * @param saveTime 缓存时间，单位：秒
     */
    fun cacheFile(urlStr: String, saveTime: Int): String {
        val key = md5Encode16(urlStr)
        val cachePath = CacheManager.get(key)
        return if (
            cachePath.isNullOrBlank() ||
            !getFile(cachePath).exists()
        ) {
            val path = downloadFile(urlStr)
            log("首次下载 $urlStr >> $path")
            CacheManager.put(key, path, saveTime)
            readTxtFile(path)
        } else {
            readTxtFile(cachePath)
        }
    }

    /**
     *js实现读取cookie
     */
    fun getCookie(tag: String): String {
        return getCookie(tag, null)
    }

    fun getCookie(tag: String, key: String?): String {
        return if (key != null) {
            CookieStore.getKey(tag, key)
        } else {
            CookieStore.getCookie(tag)
        }
    }

    /**
     * 下载文件
     * @param url 下载地址:可带参数type
     * @return 下载的文件相对路径
     */
    fun downloadFile(url: String): String {
        rhinoContext.ensureActive()
        val analyzeUrl = AnalyzeUrl(url, source = getSource(), coroutineContext = context)
        val type = analyzeUrl.type ?: UrlUtil.getSuffix(url)
        val path = FileUtils.getPath(
            File(FileUtils.getCachePath()),
            "${MD5Utils.md5Encode16(url)}.${type}"
        )
        val file = File(path)
        file.delete()
        analyzeUrl.getInputStream().use { iStream ->
            file.createFileReplace()
            try {
                file.outputStream().buffered().use { oStream ->
                    iStream.copyTo(oStream)
                }
            } catch (e: Throwable) {
                file.delete()
                throw e
            }
        }
        return path.substring(FileUtils.getCachePath().length)
    }


    /**
     * 实现16进制字符串转文件
     * @param content 需要转成文件的16进制字符串
     * @param url 通过url里的参数来判断文件类型
     * @return 相对路径
     */
    @Deprecated(
        "Deprecated",
        ReplaceWith("downloadFile(url)")
    )
    fun downloadFile(content: String, url: String): String {
        rhinoContext.ensureActive()
        val type = AnalyzeUrl(url, source = getSource(), coroutineContext = context).type
            ?: return ""
        val path = FileUtils.getPath(
            FileUtils.createFolderIfNotExist(FileUtils.getCachePath()),
            "${MD5Utils.md5Encode16(url)}.${type}"
        )
        val file = File(path)
        file.createFileReplace()
        content.hexToByteArray().let {
            if (it.isNotEmpty()) {
                file.writeBytes(it)
            }
        }
        return path.substring(FileUtils.getCachePath().length)
    }

    /**
     * js实现重定向拦截,网络访问get
     */
    fun get(urlStr: String, headers: Map<String, String>): Connection.Response {
        val requestHeaders = if (getSource()?.enabledCookieJar == true) {
            headers.toMutableMap().apply { put(cookieJarHeader, "1") }
        } else headers
        val rateLimiter = ConcurrentRateLimiter(getSource())
        val response = rateLimiter.withLimitBlocking {
            rhinoContext.ensureActive()
            Jsoup.connect(urlStr)
                .sslSocketFactory(SSLHelper.unsafeSSLSocketFactory)
                .ignoreContentType(true)
                .followRedirects(false)
                .headers(requestHeaders)
                .method(Connection.Method.GET)
                .execute()
        }
        return response
    }

    /**
     * js实现重定向拦截,网络访问get (header为JSON字符串)
     */
    fun get(urlStr: String, header: String?): Connection.Response {
        val headerMap = GSON.fromJsonObject<Map<String, String>>(header).getOrNull() ?: emptyMap()
        return get(urlStr, headerMap)
    }

    /**
     * js实现重定向拦截,网络访问head,不返回Response Body更省流量
     */
    fun head(urlStr: String, headers: Map<String, String>): Connection.Response {
        val requestHeaders = if (getSource()?.enabledCookieJar == true) {
            headers.toMutableMap().apply { put(cookieJarHeader, "1") }
        } else headers
        val rateLimiter = ConcurrentRateLimiter(getSource())
        val response = rateLimiter.withLimitBlocking {
            rhinoContext.ensureActive()
            Jsoup.connect(urlStr)
                .sslSocketFactory(SSLHelper.unsafeSSLSocketFactory)
                .ignoreContentType(true)
                .followRedirects(false)
                .headers(requestHeaders)
                .method(Connection.Method.HEAD)
                .execute()
        }
        return response
    }

    /**
     * js实现重定向拦截,网络访问head (header为JSON字符串)
     */
    fun head(urlStr: String, header: String?): Connection.Response {
        val headerMap = GSON.fromJsonObject<Map<String, String>>(header).getOrNull() ?: emptyMap()
        return head(urlStr, headerMap)
    }

    /**
     * 网络访问post
     */
    fun post(urlStr: String, body: String, headers: Map<String, String>): Connection.Response {
        val requestHeaders = if (getSource()?.enabledCookieJar == true) {
            headers.toMutableMap().apply { put(cookieJarHeader, "1") }
        } else headers
        val rateLimiter = ConcurrentRateLimiter(getSource())
        val response = rateLimiter.withLimitBlocking {
            rhinoContext.ensureActive()
            Jsoup.connect(urlStr)
                .sslSocketFactory(SSLHelper.unsafeSSLSocketFactory)
                .ignoreContentType(true)
                .followRedirects(false)
                .requestBody(body)
                .headers(requestHeaders)
                .method(Connection.Method.POST)
                .execute()
        }
        return response
    }

    /**
     * 网络访问post (header为JSON字符串)
     */
    fun post(urlStr: String, body: String, header: String?): Connection.Response {
        val headerMap = GSON.fromJsonObject<Map<String, String>>(header).getOrNull() ?: emptyMap()
        return post(urlStr, body, headerMap)
    }

    /* Str转ByteArray */
    fun strToBytes(str: String): ByteArray {
        return str.toByteArray(charset("UTF-8"))
    }

    fun strToBytes(str: String, charset: String): ByteArray {
        return str.toByteArray(charset(charset))
    }

    /* ByteArray转Str */
    fun bytesToStr(bytes: ByteArray): String {
        return String(bytes, charset("UTF-8"))
    }

    fun bytesToStr(bytes: ByteArray, charset: String): String {
        return String(bytes, charset(charset))
    }

    /**
     * js实现base64解码,不能删
     */
    fun base64Decode(str: String?): String? {
        return str?.let { String(it.base64ToByteArray()) }
    }

    fun base64Decode(str: String?, charset: String): String? {
        return str?.let { String(it.base64ToByteArray(), charset(charset)) }
    }

    fun base64Decode(str: String, flags: Int): String {
        return EncoderUtils.base64Decode(str, flags)
    }

    fun base64DecodeToByteArray(str: String?): ByteArray? {
        if (str.isNullOrBlank()) {
            return null
        }
        return EncoderUtils.base64DecodeToByteArray(str, 0)
    }

    fun base64DecodeToByteArray(str: String?, flags: Int): ByteArray? {
        if (str.isNullOrBlank()) {
            return null
        }
        return EncoderUtils.base64DecodeToByteArray(str, flags)
    }

    fun base64Encode(str: String): String? {
        return EncoderUtils.base64Encode(str, 2)
    }

    fun base64Encode(str: String, flags: Int): String? {
        return EncoderUtils.base64Encode(str, flags)
    }

    /* HexString 解码为字节数组 */
    fun hexDecodeToByteArray(hex: String?): ByteArray? {
        return hex?.takeIf(String::isNotEmpty)?.hexToByteArray()
    }

    /* hexString 解码为utf8String*/
    fun hexDecodeToString(hex: String?): String? {
        return hex?.takeIf(String::isNotEmpty)?.let { String(it.hexToByteArray(), Charsets.UTF_8) }
    }

    /* utf8 编码为hexString */
    fun hexEncodeToString(utf8: String?): String? {
        return utf8?.toByteArray()?.toHexString()
    }

    /**
     * 格式化时间
     */
    fun timeFormatUTC(time: Long, format: String, sh: Int): String? {
        val utc = SimpleTimeZone(sh, "UTC")
        return SimpleDateFormat(format, Locale.getDefault()).run {
            timeZone = utc
            format(Date(time))
        }
    }

    /**
     * 时间格式化
     */
    fun timeFormat(time: Long): String {
        return dateFormat.format(Date(time))
    }

    fun encodeURI(str: String): String {
        return try {
            URLEncoder.encode(str, "UTF-8")
        } catch (e: Exception) {
            ""
        }
    }

    fun encodeURI(str: String, enc: String): String {
        return try {
            URLEncoder.encode(str, enc)
        } catch (e: Exception) {
            ""
        }
    }

    fun htmlFormat(str: String): String {
        return HtmlFormatter.formatKeepImg(str)
    }

    /** 声明式正文管道同款(BookContent formatKeepImg + rUrl):相对 <img> 地址按 redirectUrl 补全 */
    fun htmlFormat(str: String, redirectUrl: String): String {
        val url = kotlin.runCatching { URL(redirectUrl) }.getOrNull()
        return HtmlFormatter.formatKeepImg(str, url)
    }

    fun t2s(text: String): String {
        return ChineseUtils.t2s(text)
    }

    fun s2t(text: String): String {
        return ChineseUtils.s2t(text)
    }

    fun getWebViewUA(): String {
        return WebSettings.getDefaultUserAgent(appCtx)
    }

//****************文件操作******************//

    /**
     * 获取本地文件
     * @param path 相对路径
     * @return File
     */
    fun getFile(path: String): File {
        val cachePath = appCtx.externalCache.absolutePath
        val aPath = if (path.startsWith(File.separator)) {
            cachePath + path
        } else {
            cachePath + File.separator + path
        }
        val file = File(aPath)
        val safePath = appCtx.externalCache.parent!!
        if (!file.canonicalPath.startsWith(safePath)) {
            throw SecurityException("非法路径")
        }
        return file
    }

    fun readFile(path: String): ByteArray? {
        val file = getFile(path)
        if (file.exists()) {
            return file.readBytes()
        }
        return null
    }

    fun readTxtFile(path: String): String {
        val file = getFile(path)
        if (file.exists()) {
            val charsetName = EncodingDetect.getEncode(file)
            return String(file.readBytes(), charset(charsetName))
        }
        return ""
    }

    fun readTxtFile(path: String, charsetName: String): String {
        val file = getFile(path)
        if (file.exists()) {
            return String(file.readBytes(), charset(charsetName))
        }
        return ""
    }

    /**
     * 删除本地文件
     */
    fun deleteFile(path: String): Boolean {
        val file = getFile(path)
        return FileUtils.delete(file, true)
    }

    /**
     * js实现Zip压缩文件解压
     * @param zipPath 相对路径
     * @return 相对路径
     */
    fun unzipFile(zipPath: String): String {
        return unArchiveFile(zipPath)
    }

    /**
     * js实现7Zip压缩文件解压
     * @param zipPath 相对路径
     * @return 相对路径
     */
    fun un7zFile(zipPath: String): String {
        return unArchiveFile(zipPath)
    }

    /**
     * js实现Rar压缩文件解压
     * @param zipPath 相对路径
     * @return 相对路径
     */
    fun unrarFile(zipPath: String): String {
        return unArchiveFile(zipPath)
    }

    /**
     * js实现压缩文件解压
     * @param zipPath 相对路径
     * @return 相对路径
     */
    fun unArchiveFile(zipPath: String): String {
        if (zipPath.isEmpty()) return ""
        val zipFile = getFile(zipPath)
        return ArchiveUtils.deCompress(zipFile.absolutePath).let {
            ArchiveUtils.TEMP_FOLDER_NAME + File.separator + MD5Utils.md5Encode16(zipFile.name)
        }
    }

    /**
     * js实现文件夹内所有文本文件读取
     * @param path 文件夹相对路径
     * @return 所有文件字符串换行连接
     */
    fun getTxtInFolder(path: String): String {
        if (path.isEmpty()) return ""
        val folder = getFile(path)
        val contents = StringBuilder()
        folder.listFiles().let {
            if (it != null) {
                for (f in it) {
                    val charsetName = EncodingDetect.getEncode(f)
                    contents.append(String(f.readBytes(), charset(charsetName)))
                        .append("\n")
                }
                contents.deleteCharAt(contents.length - 1)
            }
        }
        FileUtils.delete(folder.absolutePath)
        return contents.toString()
    }

    /**
     * 获取网络zip文件里面的数据
     * @param url zip文件的链接或十六进制字符串
     * @param path 所需获取文件在zip内的路径
     * @return zip指定文件的数据
     */
    fun getZipStringContent(url: String, path: String): String {
        val byteArray = getZipByteArrayContent(url, path) ?: return ""
        val charsetName = EncodingDetect.getEncode(byteArray)
        return String(byteArray, Charset.forName(charsetName))
    }

    fun getZipStringContent(url: String, path: String, charsetName: String): String {
        val byteArray = getZipByteArrayContent(url, path) ?: return ""
        return String(byteArray, Charset.forName(charsetName))
    }

    /**
     * 获取网络zip文件里面的数据
     * @param url zip文件的链接或十六进制字符串
     * @param path 所需获取文件在zip内的路径
     * @return zip指定文件的数据
     */
    fun getRarStringContent(url: String, path: String): String {
        val byteArray = getRarByteArrayContent(url, path) ?: return ""
        val charsetName = EncodingDetect.getEncode(byteArray)
        return String(byteArray, Charset.forName(charsetName))
    }

    fun getRarStringContent(url: String, path: String, charsetName: String): String {
        val byteArray = getRarByteArrayContent(url, path) ?: return ""
        return String(byteArray, Charset.forName(charsetName))
    }

    /**
     * 获取网络7zip文件里面的数据
     * @param url 7zip文件的链接或十六进制字符串
     * @param path 所需获取文件在7zip内的路径
     * @return zip指定文件的数据
     */
    fun get7zStringContent(url: String, path: String): String {
        val byteArray = get7zByteArrayContent(url, path) ?: return ""
        val charsetName = EncodingDetect.getEncode(byteArray)
        return String(byteArray, Charset.forName(charsetName))
    }

    fun get7zStringContent(url: String, path: String, charsetName: String): String {
        val byteArray = get7zByteArrayContent(url, path) ?: return ""
        return String(byteArray, Charset.forName(charsetName))
    }

    /**
     * 获取网络zip文件里面的数据
     * @param url zip文件的链接或十六进制字符串
     * @param path 所需获取文件在zip内的路径
     * @return zip指定文件的数据
     */
    fun getZipByteArrayContent(url: String, path: String): ByteArray? {
        val bytes = if (url.isAbsUrl()) {
            AnalyzeUrl(url, source = getSource(), coroutineContext = context).getByteArray()
        } else {
            url.hexToByteArray()
        }
        val bos = ByteArrayOutputStream()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry: ZipEntry
            while (zis.nextEntry.also { entry = it } != null) {
                if (entry.name.equals(path)) {
                    zis.use { it.copyTo(bos) }
                    return bos.toByteArray()
                }
                entry = zis.nextEntry
            }
        }

        log("getZipContent 未发现内容")
        return null
    }

    /**
     * 获取网络Rar文件里面的数据
     * @param url Rar文件的链接或十六进制字符串
     * @param path 所需获取文件在Rar内的路径
     * @return Rar指定文件的数据
     */
    fun getRarByteArrayContent(url: String, path: String): ByteArray? {
        val bytes = if (url.isAbsUrl()) {
            AnalyzeUrl(url, source = getSource(), coroutineContext = context).getByteArray()
        } else {
            url.hexToByteArray()
        }

        return ByteArrayInputStream(bytes).use {
            LibArchiveUtils.getByteArrayContent(it, path)
        }
    }

    /**
     * 获取网络7zip文件里面的数据
     * @param url 7zip文件的链接或十六进制字符串
     * @param path 所需获取文件在7zip内的路径
     * @return 7zip指定文件的数据
     */
    fun get7zByteArrayContent(url: String, path: String): ByteArray? {
        val bytes = if (url.isAbsUrl()) {
            AnalyzeUrl(url, source = getSource(), coroutineContext = context).getByteArray()
        } else {
            url.hexToByteArray()
        }

        return ByteArrayInputStream(bytes).use {
            LibArchiveUtils.getByteArrayContent(it, path)
        }
    }


//******************文件操作************************//

    /**
     * 解析字体Base64数据,返回字体解析类
     */
    @Deprecated(
        "Deprecated",
        ReplaceWith("queryTTF(data)")
    )
    fun queryBase64TTF(data: String?): QueryTTF? {
        log("queryBase64TTF(String)方法已过时,并将在未来删除；请无脑使用queryTTF(Any)替代，新方法支持传入 url、本地文件、base64、ByteArray 自动判断&自动缓存，特殊情况需禁用缓存请传入第二可选参数false:Boolean")
        return queryTTF(data)
    }

    /**
     * 返回字体解析类
     * @param data 支持url,本地文件,base64,ByteArray,自动判断,自动缓存
     * @param useCache 可选开关缓存,不传入该值默认开启缓存
     */
    @OptIn(ExperimentalStdlibApi::class)
    fun queryTTF(data: Any?, useCache: Boolean): QueryTTF? {
        try {
            var key: String? = null
            var qTTF: QueryTTF?
            when (data) {
                is String -> {
                    if (useCache) {
                        key = MessageDigest.getInstance("SHA-256").digest(data.toByteArray())
                            .toHexString()
                        qTTF = AppCacheManager.getQueryTTF(key)
                        if (qTTF != null) return qTTF
                    }
                    val font: ByteArray? = when {
                        data.isAbsUrl() -> AnalyzeUrl(
                            data,
                            source = getSource(),
                            coroutineContext = context
                        ).getByteArray()

                        else -> base64DecodeToByteArray(data)
                    }
                    font ?: return null
                    qTTF = QueryTTF(font)
                }

                is ByteArray -> {
                    if (useCache) {
                        key = MessageDigest.getInstance("SHA-256").digest(data).toHexString()
                        qTTF = AppCacheManager.getQueryTTF(key)
                        if (qTTF != null) return qTTF
                    }
                    qTTF = QueryTTF(data)
                }

                else -> return null
            }
            if (key != null) AppCacheManager.put(key, qTTF)
            return qTTF
        } catch (e: Exception) {
            AppLog.put("[queryTTF] 获取字体处理类出错", e)
            throw e
        }
    }

    fun queryTTF(data: Any?): QueryTTF? {
        return queryTTF(data, true)
    }

    /**
     * @param text 包含错误字体的内容
     * @param errorQueryTTF 错误的字体
     * @param correctQueryTTF 正确的字体
     * @param filter 删除 errorQueryTTF 中不存在的字符
     */
    fun replaceFont(
        text: String,
        errorQueryTTF: QueryTTF?,
        correctQueryTTF: QueryTTF?,
        filter: Boolean
    ): String {
        if (errorQueryTTF == null || correctQueryTTF == null) return text
        val contentArray = text.toStringArray() //这里不能用toCharArray,因为有些文字占多个字节
        val intArray = IntArray(1)
        contentArray.forEachIndexed { index, s ->
            val oldCode = s.codePointAt(0)
            // 忽略正常的空白字符
            if (errorQueryTTF.isBlankUnicode(oldCode)) {
                return@forEachIndexed
            }
            // 删除轮廓数据不存在的字符
            var glyf = errorQueryTTF.getGlyfByUnicode(oldCode)  // 轮廓数据不存在
            if (errorQueryTTF.getGlyfIdByUnicode(oldCode) == 0) glyf = null // 轮廓数据指向保留索引0
            if (filter && (glyf == null)) {
                contentArray[index] = ""
                return@forEachIndexed
            }
            // 使用轮廓数据反查Unicode
            val code = correctQueryTTF.getUnicodeByGlyf(glyf)
            if (code != 0) {
                intArray[0] = code
                contentArray[index] = String(intArray, 0, 1)
            }
        }
        return contentArray.joinToString("")
    }

    /**
     * @param text 包含错误字体的内容
     * @param errorQueryTTF 错误的字体
     * @param correctQueryTTF 正确的字体
     */
    fun replaceFont(
        text: String,
        errorQueryTTF: QueryTTF?,
        correctQueryTTF: QueryTTF?
    ): String {
        return replaceFont(text, errorQueryTTF, correctQueryTTF, false)
    }


    /**
     * 章节数转数字
     */
    fun toNumChapter(s: String?): String? {
        s ?: return null
        val matcher = AppPattern.titleNumPattern.matcher(s)
        if (matcher.find()) {
            val intStr = StringUtils.stringToInt(matcher.group(2))
            return "${matcher.group(1)}${intStr}${matcher.group(3)}"
        }
        return s
    }


    fun toURL(urlStr: String): JsURL {
        return JsURL(urlStr)
    }

    fun toURL(url: String, baseUrl: String? = null): JsURL {
        return JsURL(url, baseUrl)
    }

    /**
     * 弹窗提示
     */
    fun toast(msg: Any?) {
        rhinoContext.ensureActive()
        appCtx.toastOnUi("${getSource()?.getTag()}: ${msg.toString()}")
    }

    /**
     * 弹窗提示 停留时间较长
     */
    fun longToast(msg: Any?) {
        rhinoContext.ensureActive()
        appCtx.longToastOnUi("${getSource()?.getTag()}: ${msg.toString()}")
    }

    /**
     * 输出调试日志
     */
    fun log(msg: Any?): Any? {
        rhinoContextOrNull?.ensureActive()
        val source = getSource()
        source?.let {
            Debug.log(it.getKey(), msg.toString())
        } ?: Debug.log(msg.toString())
        AppLog.putDebug(msg.toString(), tag = source?.getTag() ?: "源")
        return msg
    }

    /**
     * 输出对象类型
     */
    fun logType(any: Any?) {
        if (any == null) {
            log("null")
        } else {
            log(any.javaClass.name)
        }
    }

    /**
     * 生成UUID
     */
    fun randomUUID(): String {
        return UUID.randomUUID().toString()
    }

    fun androidId(): String {
        return AppConst.androidId
    }

    fun openUrl(url: String) {
        openUrl(url, null)
    }

    // 新增 mimeType 参数，默认为 null（保持兼容性）
    fun openUrl(url: String, mimeType: String? = null) {
        require(url.length < 64 * 1024) { "openUrl parameter url too long" }
        rhinoContext.ensureActive()
        // 阅读自身的导入链接直开导入页, 导入页自带确认
        if (url.startsWith("legado://") || url.startsWith("yuedu://")) {
            appCtx.startActivity<OnLineImportActivity> {
                data = url.toUri()
            }
            return
        }
        val source = getSource() ?: throw NoStackTraceException("openUrl source cannot be null")
        appCtx.startActivity<OpenUrlConfirmActivity> {
            putExtra("uri", url)
            putExtra("mimeType", mimeType)
            putExtra("sourceOrigin", source.getKey())
            putExtra("sourceName", source.getTag())
            putExtra("sourceType", source.getSourceType())
        }
    }

    fun showBrowser(url: String) = showBrowser(url, null, null, null)

    fun showBrowser(url: String, html: String?) = showBrowser(url, html, null, null)

    fun showBrowser(url: String, html: String?, preloadJs: String?) =
        showBrowser(url, html, preloadJs, null)

    /**
     * 显示底部浏览器弹窗
     * 需要前台 Activity；后台(如书架刷新)或无界面时仅记录日志、不显示。
     */
    fun showBrowser(url: String, html: String?, preloadJs: String?, config: String?) {
        val activity = LifecycleHelp.getTopActivity() as? AppCompatActivity ?: run {
            log("showBrowser 调用无效：当前没有前台界面")
            return
        }
        val source = getSource() ?: return
        activity.runOnUiThread {
            activity.showDialogFragment(
                BottomWebViewDialog(source.getKey(), url, html, preloadJs, config)
            )
        }
    }

    /**
     * 复制文本到剪贴板
     */
    fun copyText(text: String) {
        appCtx.sendToClip(text)
    }

    /**
     * 刷新书籍详情信息
     */
    fun refreshBookInfo() {
        postEvent(EventBus.REFRESH_BOOK_INFO, true)
    }

    /**
     * 刷新章节目录
     */
    fun refreshBookToc() {
        postEvent(EventBus.REFRESH_BOOK_TOC, true)
    }

    /**
     * 刷新当前章节正文
     */
    fun refreshContent() {
        postEvent(EventBus.REFRESH_BOOK_CONTENT, true)
    }

    /**
     * single-flight 并发合并:同一 name 并发时只有一个线程执行 action,其余等其完成后跳过,
     * 自行读取结果。要不要发起执行由调用方外层判断;action 失败不算完成,下个线程会重试;
     * 等待超过 timeoutMs(默认 15000)抛异常。
     *
     * action 以其自身 parentScope 作为 this 与执行作用域运行。定义在 jsLib 里的函数,其
     * parentScope 是共享 jsLib 作用域,不含每次执行新建的 book/source/java(这些只在执行
     * 期 bindings 里,是 jsLib 作用域的子节点,原型链取不到);故这类函数需在 JS 侧用
     * fn.bind(this) / () => fn.call(this) 把 this 绑到书源执行环境,并通过 this 取
     * book/source/java。内联在书源里的函数 parentScope 即执行环境,无此问题。
     */
    fun singleFlight(
        name: String,
        action: org.htmlunit.corejs.javascript.Function,
        timeoutMs: Long
    ) {
        val key = "${getSource()?.getKey()}@$name"
        SourceLock.singleFlight(key, timeoutMs) {
            val cx = rhinoContext
            action.call(cx, action.parentScope, functionThisObj(action), emptyArray<Any?>())
        }
    }

    /** 未 bind 函数的 this 取所属顶层作用域的 globalThis(内联函数即书源执行环境) */
    private fun functionThisObj(
        action: org.htmlunit.corejs.javascript.Function
    ): org.htmlunit.corejs.javascript.Scriptable {
        val top = action.parentScope?.let {
            org.htmlunit.corejs.javascript.ScriptableObject.getTopLevelScope(it)
        }
        return top?.globalThis
            ?: org.htmlunit.corejs.javascript.Undefined.SCRIPTABLE_UNDEFINED
    }

    fun singleFlight(
        name: String,
        action: org.htmlunit.corejs.javascript.Function
    ) = singleFlight(name, action, 15000L)

    /**
     * 互斥锁(串行化):同一 name 并发时逐个排队执行 action,每个调用都会执行——与
     * [singleFlight] 的"领跑者跑一次、其余跳过"相反。适合 read-modify-write 这类每次都要
     * 记账、谁都不能被跳过的写操作(如账号额度扣减),把整段读-改-写包进 action 避免丢失更新。
     * 等待超过 timeoutMs(默认 15000)抛异常。
     *
     * action 的 this/作用域绑定规则同 [singleFlight]:jsLib 里的函数需 fn.bind(this)。
     */
    fun lock(
        name: String,
        action: org.htmlunit.corejs.javascript.Function,
        timeoutMs: Long
    ) {
        val key = "${getSource()?.getKey()}@$name"
        SourceLock.lock(key, timeoutMs) {
            val cx = rhinoContext
            action.call(cx, action.parentScope, functionThisObj(action), emptyArray<Any?>())
        }
    }

    fun lock(
        name: String,
        action: org.htmlunit.corejs.javascript.Function
    ) = lock(name, action, 15000L)

    /**
     * 原子轮询计数器:返回自增前的非负序号(到 Int.MAX 后回绕到 0,可直接 % length 取模)。
     * 同 name 跨线程、跨执行共享,用于多账号轮流分配等场景,替代书源自己写
     * new Packages.java.util.concurrent.atomic.AtomicInteger + System.getProperties()。
     */
    fun tick(name: String): Int {
        val key = "${getSource()?.getKey()}@$name"
        return SourceLock.tick(key)
    }

}
