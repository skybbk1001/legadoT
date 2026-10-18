package io.legado.app.help.http

import io.legado.app.help.http.api.CookieManagerInterface

/**
 * CookieStore(app, 依赖 Room/android.webkit)桥接:
 * shared 层(AnalyzeRule/AnalyzeUrl)经此读取 cookie 与合并, 由 App.kt 接线。
 */
object CookieBridge {

    const val cookieJarHeader = "CookieJar"

    /** cookie 存取实现, app 接线为 CookieStore */
    var store: CookieManagerInterface? = null

}

/**
 * 合并多个 cookie 字符串(后者覆盖前者),
 * 与 app 侧 CookieManager.mergeCookies 行为一致。
 */
fun mergeCookies(vararg cookies: String?): String? {
    val store = CookieBridge.store ?: return null
    val maps = cookies.filterNotNull().map { store.cookieToMap(it) }
    if (maps.isEmpty()) return null
    val merged = maps.reduce { acc, map -> acc.apply { putAll(map) } }
    return store.mapToCookie(merged)
}
