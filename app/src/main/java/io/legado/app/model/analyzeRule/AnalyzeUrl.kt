package io.legado.app.model.analyzeRule

import android.annotation.SuppressLint
import android.util.Base64
import androidx.annotation.Keep
import androidx.media3.common.MediaItem
import com.bumptech.glide.load.model.GlideUrl
import com.script.buildScriptBindings
import com.script.rhino.RhinoScriptEngine
import com.script.rhino.runScriptWithContext
import io.legado.app.constant.AppConst.UA_NAME
import io.legado.app.constant.AppPattern
import io.legado.app.constant.AppPattern.JS_PATTERN
import io.legado.app.constant.AppPattern.dataUriRegex
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.CacheManager
import io.legado.app.help.ConcurrentRateLimiter
import io.legado.app.help.JsExtensions
import io.legado.app.help.config.AppConfig
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.help.glide.GlideHeaders
import io.legado.app.help.http.BackstageWebView
import io.legado.app.help.http.CookieManager
import io.legado.app.help.http.CookieManager.mergeCookies
import io.legado.app.help.http.CookieStore
import io.legado.app.help.http.RequestMethod
import io.legado.app.help.http.StrResponse
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.get
import io.legado.app.help.http.getProxyClient
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.postForm
import io.legado.app.help.http.postJson
import io.legado.app.help.http.postMultipart
import io.legado.app.help.source.getShareScope
import io.legado.app.model.Debug
import io.legado.app.model.SharedJsScope
import io.legado.app.utils.EncoderUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.GSONStrict
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.get
import io.legado.app.utils.isJson
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.isXml
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.max

/**
 * Created by GKF on 2018/1/24.
 * 搜索URL规则解析
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
@Keep
@SuppressLint("DefaultLocale")
class AnalyzeUrl(
    private val mUrl: String,
    private val key: String? = null,
    private val page: Int? = null,
    private val extraParams: Map<String, String>? = null,
    private val speakText: String? = null,
    private val speakSpeed: Int? = null,
    private var baseUrl: String = "",
    private val source: BaseSource? = null,
    private val ruleData: RuleDataInterface? = null,
    private val chapter: BookChapter? = null,
    private val readTimeout: Long? = null,
    private val callTimeout: Long? = null,
    private var coroutineContext: CoroutineContext = EmptyCoroutineContext,
    headerMapF: Map<String, String>? = null,
    hasLoginHeader: Boolean = true
) : JsExtensions {

    var ruleUrl = ""
        private set
    var url: String = ""
        private set
    var type: String? = null
        private set
    val headerMap = LinkedHashMap<String, String>()
    private var body: String? = null
    private var urlNoQuery: String = ""
    private var encodedForm: String? = null
    private var encodedQuery: String? = null
    private var charset: String? = null
    private var method = RequestMethod.GET
    private var proxy: String? = null
    private var readTimeoutMs: Long? = readTimeout
    private var callTimeoutMs: Long? = callTimeout
    private var followRedirects: Boolean? = null
    private var resolveIp: String? = null
    private var retry: Int = 0
    private var useWebView: Boolean = false
    private var webJs: String? = null
    private val enabledCookieJar = source?.enabledCookieJar == true
    private val domain: String
    private var webViewDelayTime: Long = 0
    private val concurrentRateLimiter = ConcurrentRateLimiter(source)

    // 服务器ID
    var serverID: Long? = null
        private set

    init {
        coroutineContext = coroutineContext.minusKey(ContinuationInterceptor)
        val urlMatcher = paramPattern.matcher(baseUrl)
        if (urlMatcher.find()) baseUrl = baseUrl.substring(0, urlMatcher.start())
        (headerMapF ?: runScriptWithContext(coroutineContext) {
            source?.getHeaderMap(hasLoginHeader && isLoginHeaderSite(mUrl))
        })?.let {
            headerMap.putAll(it)
            if (it.containsKey("proxy")) {
                proxy = it["proxy"]
                headerMap.remove("proxy")
            }
        }
        initUrl()
        // cookie 域按请求 URL 取,不按书源域:封面/图片等跨域资源不应携带书源 cookie
        // (对方风控会挂起请求直至 60s callTimeout)。同域请求(绝大多数)取值不变;
        // 保存侧(CookieManager.saveResponse/cookieJar)本就按请求域,至此读写对称。
        // 跨域自定义 Cookie 仍可经 urlOption headers 显式携带(setCookie 合并时后者胜)。
        // 登录头同理,见 isLoginHeaderSite。
        domain = NetworkUtils.getSubDomain(url)
    }

    /**
     * 登录头(token/Authorization 等认证信息)只发送给书源同站请求。
     * 封面等资源常托管在第三方 CDN,跨域携带认证头会被对方风控拦截;
     * 与 cookie 的域处理保持同一粒度(按二级域名对齐)。
     * 相对链接按 baseUrl 判定;无法判定域名时保持原有行为(仍附加登录头)。
     * 注意:此处以初始 URL 判定,@js 重写后的跨域跳转不在此保护范围内,
     * 需要跨域携带认证头的场景可经 urlOption headers 显式指定。
     */
    private fun isLoginHeaderSite(url: String): Boolean {
        val source = this.source ?: return true
        val sourceDomain = NetworkUtils.getSubDomainOrNull(source.getKey()) ?: return true
        val target = url.takeIf { it.startsWith("http", true) }
            ?: baseUrl.takeIf { it.isNotBlank() }
            ?: return true
        val targetDomain = NetworkUtils.getSubDomainOrNull(target) ?: return true
        return targetDomain.equals(sourceDomain, ignoreCase = true)
    }

    /**
     * 处理url
     */
    fun initUrl() {
        ruleUrl = mUrl
        //执行@js,<js></js>
        analyzeJs()
        //替换参数
        replaceKeyPageJs()
        //处理URL
        analyzeUrl()
    }

    /**
     * 执行@js,<js></js>
     */
    private fun analyzeJs() {
        var start = 0
        val jsMatcher = JS_PATTERN.matcher(ruleUrl)
        var result = ruleUrl
        while (jsMatcher.find()) {
            if (jsMatcher.start() > start) {
                ruleUrl.substring(start, jsMatcher.start()).trim().let {
                    if (it.isNotEmpty()) {
                        result = it.replace("@result", result)
                    }
                }
            }
            result = evalJS(jsMatcher.group(2) ?: jsMatcher.group(1), result).toString()
            start = jsMatcher.end()
        }
        if (ruleUrl.length > start) {
            ruleUrl.substring(start).trim().let {
                if (it.isNotEmpty()) {
                    result = it.replace("@result", result)
                }
            }
        }
        ruleUrl = result
    }

    /**
     * 替换关键字,页数,JS
     */
    private fun replaceKeyPageJs() { //先替换内嵌规则再替换页数规则，避免内嵌规则中存在大于小于号时，规则被切错
        //js
        if (ruleUrl.contains("{{") && ruleUrl.contains("}}")) {
            val analyze = RuleAnalyzer(ruleUrl) //创建解析
            //替换所有内嵌{{js}}
            val url = analyze.innerRule("{{", "}}") {
                val jsEval = evalJS(it) ?: ""
                when {
                    jsEval is String -> jsEval
                    jsEval is Double && jsEval % 1.0 == 0.0 -> String.format("%.0f", jsEval)
                    else -> jsEval.toString()
                }
            }
            if (url.isNotEmpty()) ruleUrl = url
        }
        //page
        page?.let {
            val matcher = pagePattern.matcher(ruleUrl)
            while (matcher.find()) {
                val pages = matcher.group(1)!!.split(",")
                ruleUrl = if (page < pages.size) { //pages[pages.size - 1]等同于pages.last()
                    ruleUrl.replace(matcher.group(), pages[page - 1].trim { it <= ' ' })
                } else {
                    ruleUrl.replace(matcher.group(), pages.last().trim { it <= ' ' })
                }
            }
        }
    }

    /**
     * 解析Url
     */
    private fun analyzeUrl() {
        //replaceKeyPageJs已经替换掉额外内容，此处url是基础形式，可以直接切首个‘,’之前字符串。
        val urlMatcher = paramPattern.matcher(ruleUrl)
        val urlNoOption =
            if (urlMatcher.find()) ruleUrl.substring(0, urlMatcher.start()) else ruleUrl
        url = NetworkUtils.getAbsoluteURL(baseUrl, urlNoOption)
        NetworkUtils.getBaseUrl(url)?.let {
            baseUrl = it
        }
        if (urlNoOption.length != ruleUrl.length) {
            val urlOptionStr = ruleUrl.substring(urlMatcher.end())
            var urlOption = GSONStrict.fromJsonObject<UrlOption>(urlOptionStr).getOrNull()
            if (urlOption == null) {
                urlOption = GSON.fromJsonObject<UrlOption>(urlOptionStr).getOrNull()
                if (urlOption != null) {
                    log("链接参数 JSON 格式不规范，请改为规范格式")
                }
            }
            urlOption?.let { option ->
                option.getMethod()?.let {
                    if (it.equals("POST", true)) method = RequestMethod.POST
                }
                option.getHeaderMap()?.forEach { entry ->
                    headerMap[entry.key.toString()] = entry.value.toString()
                }
                option.getBody()?.let {
                    body = it
                }
                type = option.getType()
                charset = option.getCharset()
                retry = option.getRetry()
                useWebView = option.useWebView()
                webJs = option.getWebJs()
                option.getTimeout()?.let { timeout ->
                    readTimeoutMs = timeout
                }
                option.getFollowRedirects()?.let {
                    followRedirects = it
                }
                option.getResolveIp()?.let {
                    resolveIp = it
                }
                option.getJs()?.let { jsStr ->
                    evalJS(jsStr, url)?.toString()?.let {
                        url = it
                    }
                }
                serverID = option.getServerID()
                webViewDelayTime = max(0, option.getWebViewDelayTime() ?: 0)
            }
        }
        urlNoQuery = url
        when (method) {
            RequestMethod.GET -> {
                val pos = url.indexOf('?')
                if (pos != -1) {
                    analyzeQuery(url.substring(pos + 1))
                    urlNoQuery = url.substring(0, pos)
                }
            }

            RequestMethod.POST -> body?.let {
                if (!it.isJson() && !it.isXml() && headerMap["Content-Type"].isNullOrEmpty()) {
                    analyzeFields(it)
                }
            }
        }
    }

    /**
     * 解析QueryMap <key>=<value>
     * name=
     * name=name
     * name=<BASE64> eg name=bmFtZQ==
     */
    private fun analyzeFields(fieldsTxt: String) {
        encodedForm = encodeParams(fieldsTxt, charset, false)
    }

    private fun analyzeQuery(query: String) {
        encodedQuery = encodeParams(query, charset, true)
    }

    private fun encodeParams(params: String, charset: String?, isQuery: Boolean): String {
        val checkEncoded = charset.isNullOrEmpty()
        val charset = when {
            charset.isNullOrEmpty() -> Charsets.UTF_8
            charset == "escape" -> null
            else -> charset(charset)
        }
        if (isQuery && charset != null) {
            if (NetworkUtils.encodedQuery(params)) {
                return params
            }
            return EncoderUtils.percentEncode(params, charset, querySafeCharacters)
        }
        val len = params.length
        val sb = StringBuilder()
        var pos = 0
        while (pos <= len) {
            if (sb.isNotEmpty()) {
                sb.append("&")
            }
            var ampOffset = params.indexOf("&", pos)
            if (ampOffset == -1) {
                ampOffset = len
            }
            val eqOffset = params.indexOf("=", pos)
            val key: String
            val value: String?
            if (eqOffset == -1 || eqOffset > ampOffset) {
                key = params.substring(pos, ampOffset)
                value = null
            } else {
                key = params.substring(pos, eqOffset)
                value = params.substring(eqOffset + 1, ampOffset)
            }
            sb.appendEncoded(key, checkEncoded, charset)
            if (value != null) {
                sb.append("=")
                sb.appendEncoded(value, checkEncoded, charset)
            }
            pos = ampOffset + 1
        }
        return sb.toString()
    }

    private fun StringBuilder.appendEncoded(
        value: String,
        checkEncoded: Boolean,
        charset: Charset?
    ) {
        if (checkEncoded && NetworkUtils.encodedForm(value)) {
            append(value)
        } else if (charset == null) {
            append(EncoderUtils.escape(value))
        } else {
            append(URLEncoder.encode(value, charset))
        }
    }

    /**
     * 执行JS
     */
    fun evalJS(jsStr: String, result: Any? = null): Any? {
        val pageStr = get("page")
        val pageValue: Any? = page ?: pageStr.toIntOrNull() ?: pageStr.takeIf { it.isNotBlank() }
        val bindings = buildScriptBindings { bindings ->
            bindings["java"] = this
            bindings["baseUrl"] = baseUrl
            bindings["cookie"] = CookieStore
            bindings["cache"] = CacheManager
            bindings["page"] = pageValue
            bindings["key"] = key
            bindings["speakText"] = speakText
            bindings["speakSpeed"] = speakSpeed
            bindings["book"] = ruleData as? Book
            bindings["source"] = source
            bindings["result"] = result
            bindings["paraIndex"] = get("paraIndex")
            bindings["paraData"] = get("paraData")
        }
        val sharedScope = source?.getShareScope(coroutineContext)
            ?: SharedJsScope.getCryptoScope(coroutineContext)
        val scope = if (sharedScope == null) {
            RhinoScriptEngine.getRuntimeScope(bindings)
        } else {
            bindings.apply {
                chainTo(sharedScope)
            }
        }
        return RhinoScriptEngine.eval(jsStr, scope, coroutineContext)
    }

    fun put(key: String, value: String): String {
        if (key == "bookName" || key == "title") {
            Debug.log("≡变量 $key 在特定情况下会被覆盖，建议使用其他键名")
        }
        chapter?.putVariable(key, value)
            ?: ruleData?.putVariable(key, value)
        return value
    }

    fun get(key: String): String {
        extraParams?.get(key)?.takeIf { it.isNotEmpty() }?.let { return it }
        when (key) {
            "bookName" -> (ruleData as? Book)?.let {
                return it.name
            }

            "title" -> chapter?.let {
                return it.title
            }
        }
        return chapter?.getVariable(key)?.takeIf { it.isNotEmpty() }
            ?: ruleData?.getVariable(key)?.takeIf { it.isNotEmpty() }
            ?: ""
    }

    /**
     * 访问网站,返回StrResponse
     */
    suspend fun getStrResponseAwait(
        jsStr: String? = null,
        sourceRegex: String? = null,
        useWebView: Boolean = true,
    ): StrResponse {
        if (type != null) {
            return StrResponse(url, EncoderUtils.hexEncode(getByteArrayAwait()))
        }
        concurrentRateLimiter.withLimit {
            setCookie()
            val strResponse: StrResponse
            if (this.useWebView && useWebView) {
                strResponse = when (method) {
                    RequestMethod.POST -> {
                        val res = getClient().newCallStrResponse(retry) {
                            addHeaders(headerMap)
                            url(urlNoQuery)
                            if (!encodedForm.isNullOrEmpty() || body.isNullOrBlank()) {
                                postForm(encodedForm ?: "")
                            } else {
                                postJson(body)
                            }
                        }
                        BackstageWebView(
                            url = res.url,
                            html = res.body,
                            tag = source?.getKey(),
                            javaScript = webJs ?: jsStr,
                            sourceRegex = sourceRegex,
                            headerMap = headerMap,
                            delayTime = webViewDelayTime,
                            source = source,
                        ).getStrResponse()
                    }

                    else -> BackstageWebView(
                        url = url,
                        tag = source?.getKey(),
                        javaScript = webJs ?: jsStr,
                        sourceRegex = sourceRegex,
                        headerMap = headerMap,
                        delayTime = webViewDelayTime,
                        source = source,
                    ).getStrResponse()
                }
            } else {
                strResponse = getClient().newCallStrResponse(retry) {
                    addHeaders(headerMap)
                    when (method) {
                        RequestMethod.POST -> {
                            url(urlNoQuery)
                            val contentType = headerMap["Content-Type"]
                            val body = body
                            if (!encodedForm.isNullOrEmpty() || body.isNullOrBlank()) {
                                postForm(encodedForm ?: "")
                            } else if (!contentType.isNullOrBlank()) {
                                val requestBody = body.toRequestBody(contentType.toMediaType())
                                post(requestBody)
                            } else {
                                postJson(body)
                            }
                        }

                        else -> get(urlNoQuery, encodedQuery)
                    }
                }.let {
                    val isXml = it.raw.body.contentType()?.toString()
                        ?.matches(AppPattern.xmlContentTypeRegex) == true
                    if (isXml && it.body?.trim()?.startsWith("<?xml", true) == false) {
                        StrResponse(it.raw, "<?xml version=\"1.0\"?>" + it.body)
                    } else it
                }
            }
            return strResponse
        }
    }

    @JvmOverloads
    fun getStrResponse(
        jsStr: String? = null,
        sourceRegex: String? = null,
        useWebView: Boolean = true,
    ): StrResponse {
        return runBlocking(coroutineContext) {
            getStrResponseAwait(jsStr, sourceRegex, useWebView)
        }
    }

    /**
     * 访问网站,返回Response
     */
    suspend fun getResponseAwait(): Response {
        concurrentRateLimiter.withLimit {
            setCookie()
            val response = getClient().newCallResponse(retry) {
                addHeaders(headerMap)
                when (method) {
                    RequestMethod.POST -> {
                        url(urlNoQuery)
                        val contentType = headerMap["Content-Type"]
                        val body = body
                        if (!encodedForm.isNullOrEmpty() || body.isNullOrBlank()) {
                            postForm(encodedForm ?: "")
                        } else if (!contentType.isNullOrBlank()) {
                            val requestBody = body.toRequestBody(contentType.toMediaType())
                            post(requestBody)
                        } else {
                            postJson(body)
                        }
                    }

                    else -> get(urlNoQuery, encodedQuery)
                }
            }
            return response
        }
    }

    private fun getClient(): OkHttpClient {
        val client = getProxyClient(proxy)
        if (
            readTimeoutMs == null &&
            callTimeoutMs == null &&
            followRedirects == null &&
            resolveIp.isNullOrBlank()
        ) {
            return client
        }
        val targetHost = urlNoQuery.toHttpUrlOrNull()?.host
        val resolveAddresses = resolveIp?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { parseResolveIpLiteral(it) }
        return client.newBuilder().run {
            followRedirects?.let {
                followRedirects(it)
                followSslRedirects(it)
            }
            if (!targetHost.isNullOrBlank() && !resolveAddresses.isNullOrEmpty()) {
                dns(Dns { hostname ->
                    if (hostname.equals(targetHost, true)) {
                        // resolveIp only applies to the current request host.
                        resolveAddresses
                    } else {
                        Dns.SYSTEM.lookup(hostname)
                    }
                })
            }
            readTimeoutMs?.let { timeoutMs ->
                readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                if (callTimeoutMs == null) {
                    callTimeout(max(60 * 1000L, timeoutMs * 2), TimeUnit.MILLISECONDS)
                }
            }
            callTimeoutMs?.let { callTimeout(it, TimeUnit.MILLISECONDS) }
            build()
        }
    }

    fun getResponse(): Response {
        return runBlocking(coroutineContext) {
            getResponseAwait()
        }
    }

    private fun getByteArrayIfDataUri(): ByteArray? {
        if (!urlNoQuery.startsWith("data:")) {
            return null
        }
        val dataUriFindResult = dataUriRegex.find(urlNoQuery)
        if (dataUriFindResult != null) {
            val dataUriBase64 = dataUriFindResult.groupValues[1]
            val byteArray = Base64.decode(dataUriBase64, Base64.DEFAULT)
            return byteArray
        }
        return null
    }

    /**
     * 访问网站,返回ByteArray
     */
    suspend fun getByteArrayAwait(): ByteArray {
        getByteArrayIfDataUri()?.let {
            return it
        }
        return getResponseAwait().body.bytes()
    }

    fun getByteArray(): ByteArray {
        return runBlocking(coroutineContext) {
            getByteArrayAwait()
        }
    }

    /**
     * 访问网站,返回InputStream
     */
    suspend fun getInputStreamAwait(): InputStream {
        getByteArrayIfDataUri()?.let {
            return ByteArrayInputStream(it)
        }
        return getResponseAwait().body.byteStream()
    }

    fun getInputStream(): InputStream {
        return runBlocking(coroutineContext) {
            getInputStreamAwait()
        }
    }

    /**
     * 上传文件
     */
    suspend fun upload(fileName: String, file: Any, contentType: String): StrResponse {
        return getClient().newCallStrResponse(retry) {
            url(urlNoQuery)
            val bodyMap = GSON.fromJsonObject<HashMap<String, Any>>(body).getOrNull()!!
            bodyMap.forEach { entry ->
                if (entry.value.toString() == "fileRequest") {
                    bodyMap[entry.key] = mapOf(
                        Pair("fileName", fileName),
                        Pair("file", file),
                        Pair("contentType", contentType)
                    )
                }
            }
            postMultipart(type, bodyMap)
        }
    }

    /**
     * 设置cookie 优先级
     * urlOption临时cookie > 数据库cookie
     */
    private fun setCookie() {
        val cookie = kotlin.run {
            /* 每次调用getXX cookieJar已经保存过了
            if (enabledCookieJar) {
                val key = "${domain}_cookieJar"
                CacheManager.getFromMemory(key)?.let {
                    return@run it
                }
            }
            */
            CookieStore.getCookie(domain)
        }
        if (cookie.isNotEmpty()) {
            mergeCookies(cookie, headerMap["Cookie"])?.let {
                headerMap.put("Cookie", it)
            }
        }
        if (enabledCookieJar) {
            headerMap[CookieManager.cookieJarHeader] = "1"
        } else {
            headerMap.remove(CookieManager.cookieJarHeader)
        }
    }

    /**
     *获取处理过阅读定义的urlOption和cookie的GlideUrl
     */
    fun getGlideUrl(): GlideUrl {
        setCookie()
        return GlideUrl(url, GlideHeaders(headerMap))
    }

    fun getUserAgent(): String {
        return headerMap.get(UA_NAME, true) ?: AppConfig.userAgent
    }

    fun isPost(): Boolean {
        return method == RequestMethod.POST
    }

    private fun parseResolveIpLiteral(value: String): List<InetAddress>? {
        if (!isIpLiteral(value)) return null
        return kotlin.runCatching { InetAddress.getAllByName(value).toList() }.getOrNull()
    }

    private fun isIpLiteral(value: String): Boolean {
        if (value.isBlank()) return false
        val ipv4Like = value.contains('.') && value.all { it.isDigit() || it == '.' }
        val ipv6Like = value.contains(':') && value.all {
            it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':'
        }
        return ipv4Like || ipv6Like
    }

    override fun getSource(): BaseSource? {
        return source
    }

    companion object {
        val paramPattern: Pattern = Pattern.compile("\\s*,\\s*(?=\\{)")
        private val pagePattern = Pattern.compile("<(.*?)>")
        private const val querySafeCharacters =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~!$%&()*+,/:;=?@[\\]^`{|}"

        fun AnalyzeUrl.getMediaItem(): MediaItem {
            setCookie()
            return ExoPlayerHelper.createMediaItem(url, headerMap)
        }

    }

    @Keep
    data class UrlOption(
        private var method: String? = null,
        private var charset: String? = null,
        private var headers: Any? = null,
        private var body: Any? = null,
        /**
         * 源Url
         **/
        private var origin: String? = null,
        /**
         * 重试次数
         **/
        private var retry: Int? = null,
        /**
         * 类型
         **/
        private var type: String? = null,
        /**
         * 是否使用webView
         **/
        private var webView: Any? = null,
        /**
         * webView中执行的js
         **/
        private var webJs: String? = null,
        /**
         * 请求超时（毫秒）
         */
        private var timeout: Long? = null,
        /**
         * 是否跟随重定向
         */
        private var followRedirects: Any? = null,
        /**
         * 指定当前请求域名解析到的IP，仅对当前URL的host生效
         */
        private var resolveIp: String? = null,
        /**
         * 解析完url参数时执行的js
         * 执行结果会赋值给url
         */
        private var js: String? = null,
        /**
         * 服务器id
         */
        private var serverID: Long? = null,
        /**
         * webview等待页面加载完毕的延迟时间（毫秒）
         */
        private var webViewDelayTime: Long? = null,
        /**
         * 图片样式,仅图片URL生效:
         * 关键词(DEFAULT/FULL/TEXT/SINGLE,不区分大小写)或CSS尺寸(width:50%、width:200px、height:30%等)
         */
        private var style: String? = null,
    ) {
        fun setMethod(value: String?) {
            method = if (value.isNullOrBlank()) null else value
        }

        fun getMethod(): String? {
            return method
        }

        fun setCharset(value: String?) {
            charset = if (value.isNullOrBlank()) null else value
        }

        fun getCharset(): String? {
            return charset
        }

        fun setOrigin(value: String?) {
            origin = if (value.isNullOrBlank()) null else value
        }

        fun getOrigin(): String? {
            return origin
        }

        fun setRetry(value: String?) {
            retry = if (value.isNullOrEmpty()) null else value.toIntOrNull()
        }

        fun getRetry(): Int {
            return retry ?: 0
        }

        fun setType(value: String?) {
            type = if (value.isNullOrBlank()) null else value
        }

        fun getType(): String? {
            return type
        }

        fun useWebView(): Boolean {
            return toBooleanOrNull(webView) ?: false
        }

        fun useWebView(boolean: Boolean) {
            webView = if (boolean) true else null
        }

        fun setTimeout(value: String?) {
            timeout = if (value.isNullOrBlank()) null else value.toLongOrNull()
        }

        fun getTimeout(): Long? {
            return timeout?.takeIf { it > 0 }
        }

        fun setFollowRedirects(value: String?) {
            followRedirects = value
        }

        fun getFollowRedirects(): Boolean? {
            return toBooleanOrNull(followRedirects)
        }

        fun setResolveIp(value: String?) {
            resolveIp = if (value.isNullOrBlank()) null else value.trim()
        }

        fun getResolveIp(): String? {
            return resolveIp
        }

        fun setHeaders(value: String?) {
            headers = if (value.isNullOrBlank()) {
                null
            } else {
                GSON.fromJsonObject<Map<String, Any>>(value).getOrNull()
            }
        }

        fun getHeaderMap(): Map<*, *>? {
            return when (val value = headers) {
                is Map<*, *> -> value
                is String -> GSON.fromJsonObject<Map<String, Any>>(value).getOrNull()
                else -> null
            }
        }

        fun setBody(value: String?) {
            body = when {
                value.isNullOrBlank() -> null
                value.isJsonObject() -> GSON.fromJsonObject<Map<String, Any>>(value).getOrNull()
                value.isJsonArray() -> GSON.fromJsonArray<Map<String, Any>>(value).getOrNull()
                else -> value
            }
        }

        fun getBody(): String? {
            return body?.let {
                it as? String ?: GSON.toJson(it)
            }
        }

        fun setWebJs(value: String?) {
            webJs = if (value.isNullOrBlank()) null else value
        }

        fun getWebJs(): String? {
            return webJs
        }

        fun setJs(value: String?) {
            js = if (value.isNullOrBlank()) null else value
        }

        fun getJs(): String? {
            return js
        }

        fun setServerID(value: String?) {
            serverID = if (value.isNullOrBlank()) null else value.toLong()
        }

        fun getServerID(): Long? {
            return serverID
        }

        fun setWebViewDelayTime(value: String?) {
            webViewDelayTime = if (value.isNullOrBlank()) null else value.toLong()
        }

        fun getWebViewDelayTime(): Long? {
            return webViewDelayTime
        }

        fun getStyle(): String? {
            return style
        }

        private fun toBooleanOrNull(value: Any?): Boolean? {
            return when (value) {
                is Boolean -> value
                is Number -> value.toInt() != 0
                is String -> when (value.trim().lowercase()) {
                    "true", "1" -> true
                    "false", "0", "" -> false
                    else -> null
                }

                else -> null
            }
        }
    }

    data class ConcurrentRecord(
        /**
         * 是否按频率
         */
        val isConcurrent: Boolean,
        /**
         * 开始访问时间
         */
        var time: Long,
        /**
         * 正在访问的个数
         */
        var frequency: Int
    )

}
