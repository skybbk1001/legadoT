package io.legado.app.ui.login

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.legado.app.help.http.WebViewProxyController
import io.legado.app.help.http.parseProxyConfig
import io.legado.app.utils.isolatedWebLoginProcessSupported
import io.legado.app.utils.toWebViewRequestHeaders
import java.net.URI

/**
 * :web_login 进程中的最小浏览器。不加载 BaseActivity、书源脚本、数据库或主进程缓存。
 * 私有 Intent 输入初始 Cookie，ActivityResult 把最终 Cookie 交回主进程处理。
 */
class SourceLoginProxyActivity : ComponentActivity() {
    private val lease = Any()
    private val handler = Handler(Looper.getMainLooper())
    private var leased = false
    private var closed = false
    private var proxyReady = false
    private var cookiesReady = false
    private var timedOut = false
    private var navigated = false
    private var browser: WebView? = null
    private var cookieManager: CookieManager? = null
    private var profileName: String? = null
    private var authenticatedProxy = false
    private val navigationHosts = linkedSetOf<String>()
    private val allowedSeedHosts = linkedSetOf<String>()
    private lateinit var container: LinearLayout
    private lateinit var message: TextView
    private lateinit var progress: ProgressBar
    private lateinit var retry: Button
    private var initialUrl = ""
    private var requestHeaders = emptyMap<String, String>()
    private var seedCookies = emptyMap<String, String>()
    private val cookieUrls = linkedSetOf<String>()
    private val timeout = Runnable {
        if (!proxyReady || !cookiesReady) {
            timedOut = true // 晚到的代理/Cookie 回调不可再触发导航。
            showError("代理或 Cookie 初始化超时，请关闭此窗口后重试；没有发起直连。")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val dark = intent.getBooleanExtra(ProxyLoginContract.DARK, false)
        setTheme(if (dark) android.R.style.Theme_Material_NoActionBar
            else android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        createChrome()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val webView = browser
                if (!closed && proxyReady && webView?.canGoBack() == true) webView.goBack()
                else finish()
            }
        })
        try {
            check(isolatedWebLoginProcessSupported()) { "代理网页登录需要 Android 9 或以上版本" }
            require(!intent.getStringExtra(ProxyLoginContract.SESSION).isNullOrBlank()) { "登录会话无效" }
            initialUrl = intent.getStringExtra(ProxyLoginContract.URL).orEmpty()
            require(ProxyLoginContract.isWebUrl(initialUrl)) { "登录地址无效" }
            val headers = intent.getBundleExtra(ProxyLoginContract.HEADERS) ?: Bundle()
            requestHeaders = headers.keySet().associateWith { headers.getString(it).orEmpty() }
            require(requestHeaders.entries.sumOf { it.key.length + it.value.length } <=
                ProxyLoginContract.MAX_HEADER_BYTES / 2) { "登录请求头过大" }
            seedCookies = ProxyLoginContract.readCookies(intent.getBundleExtra(ProxyLoginContract.COOKIES))
            seedCookies.keys.forEach { seed ->
                URI(seed).host?.lowercase()?.let(allowedSeedHosts::add)
                rememberCookieUrl(seed)
            }
            URI(initialUrl).host?.lowercase()?.let(navigationHosts::add)
            rememberCookieUrl(initialUrl)
            intent.getStringExtra(ProxyLoginContract.SOURCE_URL)?.let { sourceUrl ->
                if (ProxyLoginContract.isWebUrl(sourceUrl)) {
                    URI(sourceUrl).host?.lowercase()?.let(allowedSeedHosts::add)
                    rememberCookieUrl(sourceUrl)
                }
            }
            check(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                "当前 WebView 内核不支持独立登录会话，请更新 Android System WebView"
            }
            authenticatedProxy = parseProxyConfig(intent.getStringExtra(ProxyLoginContract.PROXY).orEmpty()).hasAuthentication
            leased = WebViewProxyController.acquire(this, lease)
            check(leased) { "另一个代理登录正在进行或内核尚未完成清理。请关闭它后重试；若持续出现，请重启阅读T。" }
            handler.postDelayed(timeout, 15000)
            WebViewProxyController.apply(this, lease, intent.getStringExtra(ProxyLoginContract.PROXY).orEmpty()) { error ->
                if (!closed && !timedOut && !isFinishing && !isDestroyed) {
                    if (error != null) {
                        closed = true
                        handler.removeCallbacks(timeout)
                        showError(error.message ?: "代理初始化失败；没有发起直连")
                    } else {
                        proxyReady = true
                        createBrowser()
                    }
                }
            }
        } catch (error: Exception) {
            closed = true
            showError(error.message ?: "无法启动代理登录")
        }
    }

    private fun createChrome() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        toolbar.addView(Button(this).apply {
            text = "返回"
            minHeight = dp(48)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(72), dp(56)))
        toolbar.addView(TextView(this).apply {
            text = "代理登录 · " + intent.getStringExtra(ProxyLoginContract.TITLE).orEmpty().take(120)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            textSize = 16f
            setPadding(dp(8), 0, dp(8), 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        toolbar.addView(Button(this).apply {
            text = "完成"
            minHeight = dp(48)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(72), dp(56)))
        container.addView(toolbar)
        message = TextView(this).apply {
            text = "正在配置书源代理…"
            setPadding(dp(16), dp(8), dp(16), dp(8))
            textSize = 14f
        }
        container.addView(message)
        retry = Button(this).apply {
            text = "重新加载登录页"
            minHeight = dp(48)
            visibility = View.GONE
            setOnClickListener {
                if (proxyReady && cookiesReady && !closed) {
                    visibility = View.GONE
                    browser?.loadUrl(initialUrl, requestHeaders.toWebViewRequestHeaders())
                }
            }
        }
        container.addView(retry)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        container.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))
        setContentView(container)
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(container)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createBrowser() {
        try {
            val webView = WebView(this)
            browser = webView
            val name = "proxy_login_${java.util.UUID.randomUUID()}"
            WebViewCompat.setProfile(webView, name)
            profileName = name
            container.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                requestHeaders.entries.firstOrNull { it.key.equals("User-Agent", true) }
                    ?.value?.takeIf { it.isNotBlank() }?.let { userAgentString = it }
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            webView.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progress.progress = newProgress
                    progress.visibility = if (newProgress == 100) View.GONE else View.VISIBLE
                }
            }
            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (ProxyLoginContract.isWebUrl(request.url.toString())) return false
                    // 外部应用/浏览器不能继承代理，也不能交回 Cookie，因此不自动跳出隔离会话。
                    showError("此登录页尝试打开外部应用，已阻止。外部应用不会继承书源代理。")
                    return true
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    url?.let {
                        if (ProxyLoginContract.isWebUrl(it)) URI(it).host?.lowercase()?.let(navigationHosts::add)
                        rememberCookieUrl(it)
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    url?.let(::rememberCookieUrl)
                    cookieManager?.flush()
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                    // 仅记录地址以导出 API/iframe 域 Cookie，绝不接管网络或 POST 请求体。
                    request?.url?.let { uri ->
                        val url = uri.toString()
                        handler.post { if (!closed) rememberCookieUrl(url) }
                    }
                    return null
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    if (request?.isForMainFrame == true) {
                        showError(WebViewProxyController.errorMessage(lease)
                            ?: "页面加载失败（${error?.errorCode}），请检查代理和网络。未回退到直连。")
                    }
                }

                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: android.webkit.WebResourceResponse?) {
                    if (request?.isForMainFrame == true) {
                        WebViewProxyController.errorMessage(lease)?.let(::showError)
                    }
                }

                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    handler?.cancel()
                    showError("网站证书验证失败，已停止连接。不会忽略证书错误。")
                }
            }
            val manager = WebViewCompat.getProfile(webView).cookieManager
            cookieManager = manager
            manager.setAcceptCookie(true)
            manager.setAcceptThirdPartyCookies(webView, true)
            // 每个会话从主进程快照重建 Cookie，不能让上一次登录残留 Cookie 串入新书源。
            manager.removeAllCookies {
                if (closed || timedOut || isFinishing || isDestroyed) return@removeAllCookies
                val seeds = seedCookies.flatMap { (url, cookie) ->
                    cookie.split(';').map(String::trim).filter { it.contains('=') }.map { url to it }
                }
                var remaining = seeds.size
                if (remaining == 0) onCookiesReady()
                else seeds.forEach { (url, cookie) ->
                    manager.setCookie(url, "$cookie; Path=/") {
                        remaining--
                        if (remaining == 0) onCookiesReady()
                    }
                }
            }
        } catch (_: Exception) {
            closed = true
            handler.removeCallbacks(timeout)
            showError("浏览器初始化失败，请更新 Android System WebView 后重试")
        }
    }

    private fun onCookiesReady() {
        if (closed || timedOut || isFinishing || isDestroyed) return
        cookiesReady = true
        handler.removeCallbacks(timeout)
        cookieManager?.flush()
        message.text = if (authenticatedProxy) "已启用认证代理转发 · 完成后请关闭窗口"
            else "已使用书源代理 · 登录完成后点“完成”"
        navigated = true
        browser?.loadUrl(initialUrl, requestHeaders.toWebViewRequestHeaders())
    }

    private fun rememberCookieUrl(url: String) {
        if (!ProxyLoginContract.isWebUrl(url)) return
        // 不保留 query/fragment，避免令牌进入结果 Intent；按站点及路径提取 Cookie。
        val uri = URI(url)
        val host = uri.host.lowercase()
        if ((navigationHosts + allowedSeedHosts).none { host == it || host.endsWith(".$it") }) return
        val origin = URI(uri.scheme, null, uri.host, uri.port, "/", null, null).toString()
        val normalized = URI(uri.scheme, null, uri.host, uri.port, uri.path.ifEmpty { "/" }, null, null).toString()
        // 为新域保留容量；CDN 图片等资源的随机路径不应挤满 IPC 配额。
        if (cookieUrls.size < ProxyLoginContract.MAX_COOKIE_URLS) cookieUrls.add(origin)
        if (cookieUrls.size < ProxyLoginContract.MAX_COOKIE_URLS / 2) cookieUrls.add(normalized)
    }

    private fun showError(text: String) {
        message.text = text
        progress.visibility = View.GONE
        retry.visibility = if (proxyReady && cookiesReady && !closed) View.VISIBLE else View.GONE
    }

    override fun finish() {
        if (!isFinishing) {
            val result = Intent().putExtra(ProxyLoginContract.SESSION, intent.getStringExtra(ProxyLoginContract.SESSION))
            if (navigated) {
                try {
                    browser?.stopLoading()
                    val cookies = cookieUrls.associateWith { cookieManager?.getCookie(it).orEmpty() }
                    result.putExtra(ProxyLoginContract.COOKIES, ProxyLoginContract.cookieBundle(cookies))
                    cookieManager?.flush()
                } catch (_: Exception) {
                    result.putExtra(ProxyLoginContract.ERROR, "登录 Cookie 过大或导出失败，未覆盖已保存的登录信息")
                }
            }
            setResult(Activity.RESULT_OK, result)
        }
        disposeBrowser()
        super.finish()
    }

    /** 在 finish 时释放，而非等转场动画结束后的 onDestroy，避免下一次登录误判占用。 */
    private fun disposeBrowser() {
        if (closed && browser == null && !leased) return
        closed = true
        handler.removeCallbacksAndMessages(null)
        browser?.let { webView ->
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        browser = null
        cookieManager = null
        profileName?.let { name -> runCatching { ProfileStore.getInstance().deleteProfile(name) } }
        profileName = null
        if (leased) {
            WebViewProxyController.release(applicationContext, lease)
            leased = false
        }
    }

    override fun onDestroy() {
        disposeBrowser()
        super.onDestroy()
    }

    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
}
