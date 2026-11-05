package io.legado.app.ui.login

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.entities.BaseSource
import io.legado.app.databinding.ActivitySourceLoginBinding
import io.legado.app.help.CacheManager
import io.legado.app.help.http.CookieStore
import io.legado.app.help.http.parseProxyConfig
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.isNightMode
import io.legado.app.utils.isolatedWebLoginProcessSupported
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding
import java.util.UUID

/** 规则、脚本和 CookieStore 始终留在主进程，只有使用代理的网页进入浏览器子进程。 */
class SourceLoginActivity : VMBaseActivity<ActivitySourceLoginBinding, SourceLoginViewModel>() {
    override val binding by viewBinding(ActivitySourceLoginBinding::inflate)
    override val viewModel by viewModels<SourceLoginViewModel>()
    private var proxySession: String? = null
    private var receivedResult = false
    private var proxyResult: Intent? = null
    private val proxyLogin = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        receivedResult = true
        proxyResult = it.data
        consumeProxyResult()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        proxySession = savedInstanceState?.getString(ProxyLoginContract.SESSION)
        viewModel.initData(intent, success = { source ->
            when {
                receivedResult -> consumeProxyResult()
                proxySession != null -> Unit // 系统恢复入口时等待已启动的子 Activity 结果，不能重复启动。
                else -> initView(source)
            }
        }, error = { finish() })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(ProxyLoginContract.SESSION, proxySession)
        super.onSaveInstanceState(outState)
    }

    private fun initView(source: BaseSource) {
        if (!source.loginUi.isNullOrEmpty()) {
            if (supportFragmentManager.findFragmentByTag(SourceLoginDialog::class.simpleName) == null) {
                showDialogFragment<SourceLoginDialog>()
            }
        } else if (!viewModel.proxy.isNullOrBlank()) {
            launchProxyLogin(source)
        } else if (supportFragmentManager.findFragmentByTag("webViewLogin") == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fl_fragment, WebViewLoginFragment(), "webViewLogin")
                .commit()
        }
    }

    private fun launchProxyLogin(source: BaseSource) {
        try {
            check(isolatedWebLoginProcessSupported()) {
                "代理网页登录需要 Android 9 或以上版本。旧系统请使用系统 VPN；不会回退到直连。"
            }
            val proxy = requireNotNull(viewModel.proxy)
            parseProxyConfig(proxy).validateWebViewSupport() // 有认证的 HTTP/SOCKS5 由子进程回环转发。
            val url = NetworkUtils.getAbsoluteURL(source.getKey(), source.loginUrl.orEmpty())
            require(ProxyLoginContract.isWebUrl(url)) { "登录地址必须是有效的 HTTP/HTTPS 地址" }
            val headers = viewModel.headerMap
            require(headers.entries.sumOf { it.key.length + it.value.length } <=
                ProxyLoginContract.MAX_HEADER_BYTES / 2) { "登录请求头过大" }
            val cookies = linkedMapOf<String, String>()
            val scope = source.cookieScopeKey.takeIf { source.enabledCookieJar == true }
            if (scope != null) {
                CookieStore.getTaskCookiesByDomain(scope).forEach { (domain, value) ->
                    val seedUrl = "https://$domain/"
                    if (ProxyLoginContract.isWebUrl(seedUrl)) cookies[seedUrl] = value
                }
            }
            listOf(source.getKey(), url).filter(ProxyLoginContract::isWebUrl).forEach { seedUrl ->
                cookies[seedUrl] = if (scope == null) CookieStore.getCookie(seedUrl)
                else CookieStore.getTaskCookie(scope, seedUrl)
            }
            val session = UUID.randomUUID().toString()
            val request = Intent(this, SourceLoginProxyActivity::class.java).apply {
                putExtra(ProxyLoginContract.SESSION, session)
                putExtra(ProxyLoginContract.URL, url)
                putExtra(ProxyLoginContract.SOURCE_URL, source.getKey())
                putExtra(ProxyLoginContract.TITLE, source.getTag().take(120))
                putExtra(ProxyLoginContract.PROXY, proxy)
                putExtra(ProxyLoginContract.DARK, resources.configuration.isNightMode)
                putExtra(ProxyLoginContract.HEADERS, Bundle().apply {
                    headers.forEach { (name, value) -> putString(name, value) }
                })
                putExtra(ProxyLoginContract.COOKIES, ProxyLoginContract.cookieBundle(cookies))
            }
            proxySession = session
            proxyLogin.launch(request)
        } catch (error: Exception) {
            proxySession = null
            showLoginError(error.message ?: "无法启动代理登录")
        }
    }

    private fun consumeProxyResult() {
        val source = viewModel.source ?: return
        if (!receivedResult) return
        val result = proxyResult
        if (result == null) { // 子进程被系统回收或用户未进入网页，不覆盖原 Cookie。
            finish()
            return
        }
        try {
            check(result.getStringExtra(ProxyLoginContract.SESSION) == proxySession) { "登录会话已失效" }
            result.getStringExtra(ProxyLoginContract.ERROR)?.let { throw IllegalStateException(it) }
            val cookies = ProxyLoginContract.readCookies(result.getBundleExtra(ProxyLoginContract.COOKIES))
            val scope = source.cookieScopeKey.takeIf { source.enabledCookieJar == true }
            // CookieStore 目前按可注册域存储；合并同域多路径快照，避免最后一个空路径
            // 把已登录路径的 Cookie 覆盖掉。全域均为空时才清除旧值。
            val byDomain = cookies.entries.groupBy { NetworkUtils.getSubDomain(it.key) }
            byDomain.forEach { (domain, entries) ->
                val merged = linkedMapOf<String, String>()
                entries.forEach { merged.putAll(CookieStore.cookieToMap(it.value)) }
                val cookie = CookieStore.mapToCookie(merged).orEmpty()
                val url = entries.first().key
                if (scope != null) {
                    CookieStore.setTaskCookie(scope, url, cookie)
                } else {
                    CookieStore.setCookie(url, cookie)
                    CacheManager.deleteMemory("${domain}_session_cookie")
                }
            }
            finish()
        } catch (error: Exception) {
            showLoginError(error.message ?: "保存登录 Cookie 失败")
        }
    }

    private fun showLoginError(message: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("代理网页登录")
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
