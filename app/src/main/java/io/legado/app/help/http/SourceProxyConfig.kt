package io.legado.app.help.http

import java.net.URI
import java.util.Locale

/** 书源 proxy 配置，OkHttp 与登录 WebView 共用解析器。 */
enum class ProxyScheme { HTTP, SOCKS4, SOCKS5 }

data class SourceProxyConfig(
    val scheme: ProxyScheme,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
) {
    val hasAuthentication: Boolean get() = username.isNotEmpty() || password.isNotEmpty()

    /** 验证登录支持范围；有认证时由回环转发层处理，绝不把凭据塞进 WebKit 地址。 */
    fun validateWebViewSupport() {
        require(scheme != ProxyScheme.SOCKS4) { "登录 WebView 暂不支持 SOCKS4 书源代理，请改用 HTTP 或 SOCKS5" }
        if (!hasAuthentication) return
        require(username.isNotEmpty() && password.isNotEmpty()) { "代理账号和密码不能为空" }
        if (scheme == ProxyScheme.HTTP) {
            require(!username.contains(':') && (username + password).all { it.code in 32..255 && it.code != 127 }) {
                "HTTP Basic 代理用户名不能包含冒号，凭据需使用 ISO-8859-1 可表示的字符"
            }
        } else {
            require(username.toByteArray(Charsets.UTF_8).size in 1..255 &&
                password.toByteArray(Charsets.UTF_8).size in 1..255) { "SOCKS5 账号和密码各不得超过 255 字节" }
        }
    }

    /** AndroidX 使用 socks:// 表示 SOCKS5，不接受带 userinfo 的地址。 */
    fun toWebViewProxyRule(): String {
        require(!hasAuthentication) { "原生 WebView 代理接口不接受认证，请使用本地转发层" }
        require(scheme != ProxyScheme.SOCKS4) { "登录 WebView 暂不支持 SOCKS4 书源代理，请改用 HTTP 或 SOCKS5" }
        val protocol = if (scheme == ProxyScheme.HTTP) "http" else "socks"
        val normalizedHost = host.removePrefix("[").removeSuffix("]")
            .let { if (it.contains(':')) "[$it]" else it }
        return "$protocol://$normalizedHost:$port"
    }

    override fun toString(): String = "SourceProxyConfig($scheme, $host:$port, authenticated=$hasAuthentication)"
}

internal const val PROXY_CONFIG_ERROR = "PROXY_CONFIG_ERROR"

fun parseProxyConfig(proxy: String): SourceProxyConfig {
    // 不附原始 URI 或 URISyntaxException：异常的 cause 也会泄漏代理用户名密码。
    fun invalid(reason: String): Nothing = throw IllegalArgumentException("$PROXY_CONFIG_ERROR: $reason")
    if (proxy.length > 4096 || proxy.any { it.isISOControl() }) invalid("proxy 格式错误")
    val value = proxy.trim()
    val uri = try { URI(value) } catch (_: Exception) { invalid("proxy 格式错误") }
    val scheme = when (uri.scheme?.lowercase(Locale.ROOT)) {
        "http" -> ProxyScheme.HTTP
        "socks4" -> ProxyScheme.SOCKS4
        "socks5" -> ProxyScheme.SOCKS5
        null -> invalid("proxy 缺少协议")
        else -> invalid("不支持的 proxy 协议，仅支持 http/socks4/socks5")
    }
    val host = uri.host?.takeIf { it.isNotBlank() } ?: invalid("proxy 主机或端口错误")
    if (uri.port !in 1..65535) invalid("proxy 主机或端口错误")
    if (!uri.path.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null) {
        invalid("proxy 只允许协议、认证信息、主机和端口，不能包含路径、查询或片段")
    }
    val userInfo = uri.rawUserInfo
    if (userInfo == null) return SourceProxyConfig(scheme, host, uri.port, "", "")
    if (userInfo.contains('@')) invalid("proxy 认证格式错误，不能包含多个 @")
    // 先按原始冒号分隔再解码，用户名中的 %3A 不是密码分隔符；URI 的 + 不是空格。
    val auth = try {
        userInfo.split(':', limit = 2).map {
            java.net.URLDecoder.decode(it.replace("+", "%2B"), "UTF-8")
        }
    } catch (_: Exception) {
        invalid("proxy 认证格式错误，需使用合法的 URL 编码")
    }
    if (auth.size != 2 || auth.any { it.isEmpty() || it.any(Char::isISOControl) }) {
        invalid("proxy 认证格式错误，需使用 username:password")
    }
    if (scheme == ProxyScheme.SOCKS4) invalid("socks4 不支持用户名密码认证，请使用 socks5")
    return SourceProxyConfig(scheme, host, uri.port, auth[0], auth[1])
}

/** 内部 proxy 字段不能作为 HTTP 请求头发送，拒绝多个不同的代理值以免歧义。 */
fun Map<String, String>.withoutProxyHeader(): Pair<String?, Map<String, String>> {
    val proxies = entries.filter { it.key.trim().equals("proxy", true) }
        .map { it.value.trim() }.distinct()
    require(proxies.size <= 1) { "$PROXY_CONFIG_ERROR: 存在冲突的 proxy 请求头" }
    return proxies.firstOrNull()?.takeIf(String::isNotEmpty) to
        filterKeys { !it.trim().equals("proxy", true) }
}
