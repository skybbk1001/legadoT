package io.legado.app.ui.login

import android.os.Bundle
import java.net.URI

/** 私有 Activity 的有界 IPC 协议；不传书源脚本、数据库对象或 WebView Cookie 数据库。 */
internal object ProxyLoginContract {
    const val URL = "loginUrl"
    const val SOURCE_URL = "sourceUrl"
    const val TITLE = "title"
    const val PROXY = "proxy"
    const val HEADERS = "headers"
    const val COOKIES = "cookies"
    const val SESSION = "session"
    const val DARK = "dark"
    const val ERROR = "error"
    const val MAX_COOKIE_URLS = 64
    const val MAX_COOKIE_LENGTH = 4096
    const val MAX_URL_LENGTH = 4096
    const val MAX_HEADER_BYTES = 32768

    fun isWebUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        value.length <= MAX_URL_LENGTH && uri.host != null && uri.userInfo == null &&
            (uri.scheme.equals("http", true) || uri.scheme.equals("https", true))
    }.getOrDefault(false)

    fun cookieBundle(cookies: Map<String, String>): Bundle = Bundle().apply {
        require(cookies.size <= MAX_COOKIE_URLS) { "登录 Cookie 域名数量过多" }
        require(cookies.entries.sumOf { it.key.length + it.value.length } <= 160_000) { "登录 Cookie 总大小过大" }
        cookies.forEach { (url, cookie) ->
            require(isWebUrl(url) && cookie.length <= MAX_COOKIE_LENGTH) { "登录 Cookie 超出安全传输限制" }
            putString(url, cookie)
        }
    }

    fun readCookies(bundle: Bundle?): Map<String, String> {
        if (bundle == null) return emptyMap()
        require(bundle.size() <= MAX_COOKIE_URLS) { "登录 Cookie 域名数量过多" }
        return bundle.keySet().associateWith { url ->
            val cookie = requireNotNull(bundle.getString(url))
            require(isWebUrl(url) && cookie.length <= MAX_COOKIE_LENGTH) { "登录 Cookie 超出安全传输限制" }
            cookie
        }.also { cookies ->
            require(cookies.entries.sumOf { it.key.length + it.value.length } <= 160_000) { "登录 Cookie 总大小过大" }
        }
    }
}
