package io.legado.app.utils

import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.legado.app.constant.AppConst
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.CookieManager as AppCookieManager
import java.util.Locale
import java.util.WeakHashMap

// 以下 header 由 WebView/Cronet 内部管理，手动设置会导致冲突或暴露 WebView 指纹
private const val TASK_WEBVIEW_PROFILE_PREFIX = "legado_task_"
private val taskWebViewProfiles = WeakHashMap<WebView, String>()

private val blockedWebViewHeaderNames = setOf(
    // UA 由 settings.userAgentString 控制，不应出现在请求头中
    AppConst.UA_NAME.lowercase(Locale.ROOT),
    // WebView/Cronet 自行协商压缩算法，手动覆盖可能导致解码失败
    "accept-encoding",
    // WebView 管理连接池与 Keep-Alive，重写会破坏连接复用
    "connection",
    // 根据请求体自动计算，手动指定可能不匹配导致截断或拒绝
    "content-length",
    // 内部伪 header，控制 OkHttp CookieJar 开关，不是真正的 HTTP 头
    "cookiejar",
    // 任务 Cookie 作用域，仅供应用内 Cookie 路由使用
    "taskcookiescope",
    // 目标主机由 URL 决定，覆盖可能导致虚拟主机路由错误
    "host",
    // 代理连接由平台处理，proxy 是书源内部配置，不应发送给网站
    "proxy",
    "proxy-connection",
    // 传输编码协商，手动设置可能导致响应解析异常
    "te",
    // 与 te 同理，分块传输由 HTTP 栈自动管理
    "transfer-encoding",
    // HTTP/1.1 升级到 h2c/WebSocket，由 WebView 按需发起
    "upgrade",
    // CSP 升级不安全请求，浏览器内部策略
    "upgrade-insecure-requests",
    // 自动附带包名会暴露 WebView 身份，已在上面通过 allowList 消除
    "x-requested-with",
)

fun Map<String, String>?.toWebViewRequestHeaders(): HashMap<String, String> {
    val safeHeaders = hashMapOf<String, String>()
    this?.forEach { (key, value) ->
        val headerName = key.trim()
        if (headerName.isEmpty() || value.isBlank()) return@forEach
        val normalized = headerName.lowercase(Locale.ROOT)
        if (normalized in blockedWebViewHeaderNames) return@forEach
        if (normalized.startsWith("proxy-") || normalized.startsWith("sec-")) return@forEach
        safeHeaders[headerName] = value
    }
    return safeHeaders
}

fun deleteTaskWebViewProfile(taskCookieScope: String) {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
    val profileName = "$TASK_WEBVIEW_PROFILE_PREFIX${taskCookieScope.substringAfter(':')}"
    if (profileName == androidx.webkit.Profile.DEFAULT_PROFILE_NAME) return
    runOnUI {
        runCatching { ProfileStore.getInstance().deleteProfile(profileName) }
    }
}

fun WebView.applyCompatibilitySettings(
    url: String? = null,
    requestHeaders: Map<String, String>? = null,
    applyStoredCookies: Boolean = true,
    taskCookieScope: String? = null,
    onCookiesApplied: (() -> Unit)? = null
): CookieManager {
    val cookieManager = if (taskCookieScope != null) {
        check(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            "Task WebView requires WebView multi-profile support"
        }
        val profileName = "$TASK_WEBVIEW_PROFILE_PREFIX${taskCookieScope.substringAfter(':')}"
        synchronized(taskWebViewProfiles) {
            val boundProfile = taskWebViewProfiles[this]
            if (boundProfile == null) {
                WebViewCompat.setProfile(this, profileName)
                taskWebViewProfiles[this] = profileName
            } else {
                check(boundProfile == profileName) {
                    "WebView is already bound to another task profile"
                }
            }
        }
        WebViewCompat.getProfile(this).cookieManager
    } else {
        CookieManager.getInstance()
    }
    cookieManager.apply {
        setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            setAcceptThirdPartyCookies(this@applyCompatibilitySettings, true)
        }
    }
    settings.userAgentString = resolveUserAgent(requestHeaders)
    if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
        kotlin.runCatching {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
        }.onFailure {
            it.printOnDebug()
        }
    }
    if (applyStoredCookies) {
        if (url != null) {
            AppCookieManager.applyToWebView(
                url,
                taskCookieScope,
                cookieManager,
                onCookiesApplied
            )
        } else {
            onCookiesApplied?.invoke()
        }
    } else {
        onCookiesApplied?.invoke()
    }
    return cookieManager
}

private fun WebView.resolveUserAgent(requestHeaders: Map<String, String>?): String {
    val requestedUserAgent = requestHeaders
        ?.entries
        ?.firstOrNull { it.key.equals(AppConst.UA_NAME, true) }
        ?.value
        ?.trim()
    return requestedUserAgent?.takeIf { it.isNotBlank() } ?: AppConfig.userAgent
}
