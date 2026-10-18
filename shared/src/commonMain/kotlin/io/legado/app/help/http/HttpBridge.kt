package io.legado.app.help.http

import okhttp3.OkHttpClient

/**
 * HttpHelper(app, 依赖 Cronet/glide)桥接:
 * shared 层(AnalyzeUrl/SharedJsScope)经此获取 OkHttpClient, 由 App.kt 接线。
 * 未接线时返回裸 OkHttpClient(无拦截器/代理配置)。
 */
object HttpBridge {

    /** 代理客户端提供器, app 接线为 getProxyClient */
    var proxyClient: (proxy: String?) -> OkHttpClient = { OkHttpClient() }

}
