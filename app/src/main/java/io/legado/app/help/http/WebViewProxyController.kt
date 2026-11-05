package io.legado.app.help.http

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import io.legado.app.utils.isWebLoginProcess

/** 只在专用进程使用；单租约防止并发窗口互相改代理，清理失败时保持关闭而非直连。 */
object WebViewProxyController {
    private var owner: Any? = null
    private var releasing = false
    private var relay: LocalAuthenticatedProxy? = null

    fun errorMessage(token: Any): String? = if (owner === token) relay?.lastError else null

    @androidx.annotation.MainThread
    fun acquire(context: Context, token: Any): Boolean {
        check(context.isWebLoginProcess()) { "WebView 代理只能在隔离登录进程配置" }
        if (owner != null) return false
        owner = token
        releasing = false
        return true
    }

    @androidx.annotation.MainThread
    fun apply(context: Context, token: Any, proxy: String, callback: (Exception?) -> Unit) {
        check(owner === token && !releasing)
        try {
            check(WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                "当前 WebView 内核不支持代理，请更新 Android System WebView；不会回退直连。"
            }
            val sourceProxy = parseProxyConfig(proxy)
            sourceProxy.validateWebViewSupport()
            check(relay == null) { "代理登录会话已配置" }
            val rule = if (sourceProxy.hasAuthentication) {
                LocalAuthenticatedProxy(sourceProxy).also { relay = it }.proxyRule
            } else sourceProxy.toWebViewProxyRule()
            val config = ProxyConfig.Builder()
                .addProxyRule(rule)
                .removeImplicitRules() // 连 localhost/link-local 也不能悄悄绕过书源代理。
                .build() // 刻意不加 addDirect：代理失败必须报错。
            ProxyController.getInstance().setProxyOverride(
                config, ContextCompat.getMainExecutor(context)
            ) {
                if (owner === token && !releasing) callback(null)
            }
        } catch (error: Exception) {
            relay?.close()
            relay = null
            callback(error)
        }
    }

    /** 必须先销毁 WebView，再清除覆盖；成功后才允许下一次代理登录。 */
    @androidx.annotation.MainThread
    fun release(context: Context, token: Any) {
        if (owner !== token || releasing) return
        releasing = true
        relay?.close() // 立即关闭监听与所有活跃 Socket，不等待 Chromium 异步回调。
        relay = null
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            owner = null
            releasing = false
            return
        }
        try {
            // 不恢复系统直连：旧内核残留 ServiceWorker 也必须保持 fail-closed。
            // 空闲时切到不可用的回环代理；下次会话在 set 回调后使用自己的出口。
            val parked = ProxyConfig.Builder().addProxyRule("http://127.0.0.1:1")
                .removeImplicitRules().build()
            ProxyController.getInstance().setProxyOverride(parked, ContextCompat.getMainExecutor(context)) {

                if (owner === token) {
                    owner = null
                    releasing = false
                }
            }
        } catch (error: Exception) {
            relay?.close()
            relay = null
            // 内核状态不确定，保留占用直到进程重启，绝不让下个窗口带着旧代理运行。
        }
    }
}
