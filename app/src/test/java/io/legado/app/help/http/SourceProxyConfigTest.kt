package io.legado.app.help.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [SourceProxyConfig] 的纯 JVM 单元测试：不依赖 Android、WebView、OkHttp 或网络。
 *
 * 覆盖范围：
 * 1. HTTP / SOCKS5 正常解析与边界端口；
 * 2. SOCKS5 转 AndroidX WebView 代理规则（socks:// 协议名）；
 * 3. IPv6 字面量（保留方括号、规则中不重复加括号）；
 * 4. 带认证代理被 WebView 拒绝，但解析结果仍保留凭据供 OkHttp 使用；
 * 5. SOCKS4 拒绝（解析层与 WebView 层各一条路径）；
 * 6. 错误端口与协议/格式错误；
 * 7. 大小写混写的 proxy 头提取，且凭据不会泄漏到 WebView 请求头或异常文本。
 *
 * 安全约定：全部凭据均为虚构值（demoUser / demoPass123），任何异常文本、
 * toString 与请求头中都不允许出现明文。
 */
class SourceProxyConfigTest {

    private val username = "demoUser"
    private val password = "demoPass123"

    private val errorPrefix = "PROXY_CONFIG_ERROR"

    @Test
    fun `authenticated configurations use relay support validation`() {
        parseProxyConfig("http://demo:secret@127.0.0.1:8080").validateWebViewSupport()
        parseProxyConfig("socks5://demo:secret@127.0.0.1:1080").validateWebViewSupport()
        for (value in listOf(
            "http://us%3Aer:secret@127.0.0.1:8080",
            "http://用户:secret@127.0.0.1:8080",
            "socks5://${"u".repeat(256)}:secret@127.0.0.1:1080"
        )) {
            try { parseProxyConfig(value).validateWebViewSupport(); fail("must reject invalid relay credentials") }
            catch (_: IllegalArgumentException) { }
        }
    }

    // ------------------------------------------------------------ HTTP 基础

    @Test
    fun `http proxy without auth parses host and port`() {
        val config = parseProxyConfig("http://127.0.0.1:8080")
        assertEquals(SourceProxyConfig(ProxyScheme.HTTP, "127.0.0.1", 8080, "", ""), config)
        assertEquals(ProxyScheme.HTTP, config.scheme)
        assertEquals("127.0.0.1", config.host)
        assertEquals(8080, config.port)
        assertFalse(config.hasAuthentication)
    }

    @Test
    fun `scheme matching is case insensitive`() {
        assertEquals(ProxyScheme.HTTP, parseProxyConfig("http://127.0.0.1:8080").scheme)
        assertEquals(ProxyScheme.HTTP, parseProxyConfig("HTTP://127.0.0.1:8080").scheme)
        assertEquals(ProxyScheme.HTTP, parseProxyConfig("HtTp://127.0.0.1:8080").scheme)
        assertEquals(ProxyScheme.SOCKS5, parseProxyConfig("SOCKS5://127.0.0.1:1080").scheme)
        assertEquals(ProxyScheme.SOCKS4, parseProxyConfig("SoCkS4://127.0.0.1:1080").scheme)
    }

    @Test
    fun `surrounding spaces are trimmed`() {
        val config = parseProxyConfig("   socks5://127.0.0.1:1080   ")
        assertEquals(ProxyScheme.SOCKS5, config.scheme)
        assertEquals("127.0.0.1", config.host)
        assertEquals(1080, config.port)
    }

    @Test
    fun `port boundaries one and 65535 are accepted`() {
        assertEquals(1, parseProxyConfig("http://127.0.0.1:1").port)
        assertEquals(65535, parseProxyConfig("http://127.0.0.1:65535").port)
    }

    @Test
    fun `hostname hosts are accepted`() {
        val config = parseProxyConfig("http://proxy.example.com:3128")
        assertEquals("proxy.example.com", config.host)
        assertEquals(3128, config.port)
    }

    // ------------------------------------------------------------- 认证解析

    @Test
    fun `socks5 credentials are parsed and kept`() {
        val config = parseProxyConfig("socks5://$username:$password@127.0.0.1:1080")
        assertEquals(ProxyScheme.SOCKS5, config.scheme)
        assertEquals("127.0.0.1", config.host)
        assertEquals(1080, config.port)
        assertEquals(username, config.username)
        assertEquals(password, config.password)
        assertTrue(config.hasAuthentication)
    }

    @Test
    fun `http credentials are parsed and kept`() {
        val config = parseProxyConfig("http://$username:$password@127.0.0.1:3128")
        assertEquals(ProxyScheme.HTTP, config.scheme)
        assertEquals(username, config.username)
        assertEquals(password, config.password)
        assertTrue(config.hasAuthentication)
    }

    @Test
    fun `password splits on the first colon only`() {
        val config = parseProxyConfig("socks5://$username:pa:ss:word@127.0.0.1:1080")
        assertEquals(username, config.username)
        assertEquals("pa:ss:word", config.password)
    }

    @Test
    fun `percent encoded credentials are decoded for the socket layer`() {
        val config = parseProxyConfig("socks5://demo%40user:pa%3Ass@127.0.0.1:1080")
        assertEquals("demo@user", config.username)
        assertEquals("pa:ss", config.password)
    }

    @Test
    fun `encoded colon in username stays in the username field`() {
        // 用户名里的 %3A 解码后是冒号，不能当成 user/password 分隔符；
        // %40 解码为 @，'+' 在 userinfo 中不是空格，必须原样保留。
        val config = parseProxyConfig("socks5://us%3Aer:p%40ss+word@host:1080")
        assertEquals("us:er", config.username)
        assertEquals("p@ss+word", config.password)
        assertEquals("host", config.host)
        assertEquals(1080, config.port)
        assertTrue(config.hasAuthentication)
    }

    @Test
    fun `hasAuthentication reflects either field being set`() {
        assertFalse(SourceProxyConfig(ProxyScheme.HTTP, "h", 1, "", "").hasAuthentication)
        assertTrue(SourceProxyConfig(ProxyScheme.HTTP, "h", 1, username, "").hasAuthentication)
        assertTrue(SourceProxyConfig(ProxyScheme.HTTP, "h", 1, "", password).hasAuthentication)
        assertTrue(SourceProxyConfig(ProxyScheme.HTTP, "h", 1, username, password).hasAuthentication)
    }

    // ------------------------------------------------ SOCKS5 -> WebView 规则

    @Test
    fun `http proxy renders an http webview rule`() {
        assertEquals(
            "http://127.0.0.1:8080",
            parseProxyConfig("http://127.0.0.1:8080").toWebViewProxyRule()
        )
    }

    @Test
    fun `socks5 proxy renders a socks scheme rule`() {
        // AndroidX ProxyConfig 用 socks:// 表示 SOCKS5，不接受 socks5://
        assertEquals(
            "socks://127.0.0.1:1080",
            parseProxyConfig("socks5://127.0.0.1:1080").toWebViewProxyRule()
        )
    }

    @Test
    fun `rule keeps port and host intact`() {
        assertEquals(
            "socks://proxy.example.com:1080",
            parseProxyConfig("socks5://proxy.example.com:1080").toWebViewProxyRule()
        )
    }

    @Test
    fun `authenticated proxy is rejected by webview rule but parsing keeps credentials`() {
        val config = parseProxyConfig("socks5://$username:$password@127.0.0.1:1080")
        // 解析层保留凭据，供 OkHttp 走 Proxy-Authorization / SOCKS5 握手
        assertEquals(username, config.username)
        assertEquals(password, config.password)
        // WebView 层拒绝
        val error = webViewRuleError(config)
        assertTrue(
            "应提示不支持带认证代理，实际: ${error.message}",
            error.message.orEmpty().contains("原生 WebView 代理接口不接受认证")
        )
        assertNoCredentialLeak(error)
    }

    @Test
    fun `authentication rejection is scheme independent`() {
        for (scheme in listOf("http", "socks5")) {
            val config = parseProxyConfig("$scheme://$username:$password@127.0.0.1:1080")
            val error = webViewRuleError(config)
            assertTrue(
                "scheme=$scheme 应被拒绝，实际: ${error.message}",
                error.message.orEmpty().contains("不接受认证")
            )
            assertNoCredentialLeak(error)
        }
    }

    // ------------------------------------------------------------------ IPv6

    @Test
    fun `ipv6 loopback parses with brackets preserved`() {
        val config = parseProxyConfig("socks5://[::1]:1080")
        assertEquals(ProxyScheme.SOCKS5, config.scheme)
        assertEquals("[::1]", config.host)
        assertEquals(1080, config.port)
        assertFalse(config.hasAuthentication)
    }

    @Test
    fun `ipv6 global address parses`() {
        val config = parseProxyConfig("http://[2001:db8::1]:8080")
        assertEquals(ProxyScheme.HTTP, config.scheme)
        assertEquals("[2001:db8::1]", config.host)
        assertEquals(8080, config.port)
    }

    @Test
    fun `ipv6 proxy keeps credentials`() {
        val config = parseProxyConfig("socks5://$username:$password@[2001:db8::5]:1080")
        assertEquals("[2001:db8::5]", config.host)
        assertEquals(username, config.username)
        assertEquals(password, config.password)
    }

    @Test
    fun `ipv6 webview rule has exactly one bracket pair`() {
        assertEquals(
            "socks://[::1]:1080",
            parseProxyConfig("socks5://[::1]:1080").toWebViewProxyRule()
        )
        assertEquals(
            "http://[2001:db8::1]:8080",
            parseProxyConfig("http://[2001:db8::1]:8080").toWebViewProxyRule()
        )
    }

    @Test
    fun `webview rule adds brackets to a bare ipv6 host`() {
        assertEquals(
            "http://[2001:db8::1]:8080",
            SourceProxyConfig(ProxyScheme.HTTP, "2001:db8::1", 8080, "", "").toWebViewProxyRule()
        )
        assertEquals(
            "socks://[::1]:1080",
            SourceProxyConfig(ProxyScheme.SOCKS5, "::1", 1080, "", "").toWebViewProxyRule()
        )
    }

    @Test
    fun `unbracketed ipv6 literal is rejected`() {
        assertErrorContains("proxy 主机或端口错误") { parseProxyConfig("socks5://::1:1080") }
    }

    @Test
    fun `ipv6 literal without port is rejected`() {
        assertErrorContains("proxy 主机或端口错误") { parseProxyConfig("socks5://[::1]") }
    }

    // ---------------------------------------------------------------- SOCKS4

    @Test
    fun `socks4 without credentials parses`() {
        val config = parseProxyConfig("socks4://127.0.0.1:1080")
        assertEquals(ProxyScheme.SOCKS4, config.scheme)
        assertEquals("127.0.0.1", config.host)
        assertEquals(1080, config.port)
        assertFalse(config.hasAuthentication)
    }

    @Test
    fun `socks4 with credentials is rejected`() {
        val error = parseError("socks4://$username:$password@127.0.0.1:1080")
        assertTrue(
            "应提示 socks4 不支持认证，实际: ${error.message}",
            error.message.orEmpty().contains("socks4 不支持用户名密码认证")
        )
        assertNoCredentialLeak(error)
    }

    @Test
    fun `socks4 is rejected by webview rule with a fallback hint`() {
        val error = webViewRuleError(parseProxyConfig("socks4://127.0.0.1:1080"))
        val message = error.message.orEmpty()
        assertTrue("应提到 SOCKS4: $message", message.contains("SOCKS4"))
        assertTrue("应给出 HTTP 备选: $message", message.contains("HTTP"))
        assertTrue("应给出 SOCKS5 备选: $message", message.contains("SOCKS5"))
        assertFalse(message.contains(password))
    }

    // ------------------------------------------------- 错误端口与协议格式错误

    @Test
    fun `port zero is rejected`() {
        assertErrorContains("proxy 主机或端口错误") { parseProxyConfig("http://127.0.0.1:0") }
    }

    @Test
    fun `ports above 65535 are rejected`() {
        for (proxy in listOf(
            "http://127.0.0.1:65536",
            "http://127.0.0.1:70000",
            "socks5://127.0.0.1:2147483648"
        )) {
            assertErrorContains("proxy 主机或端口错误") { parseProxyConfig(proxy) }
        }
    }

    @Test
    fun `missing port is rejected`() {
        assertErrorContains("proxy 主机或端口错误") { parseProxyConfig("http://127.0.0.1") }
        assertErrorContains("proxy 主机或端口错误") {
            parseProxyConfig("http://$username:$password@127.0.0.1")
        }
    }

    @Test
    fun `non numeric and negative ports are rejected`() {
        assertErrorContains("proxy 主机或端口错误") { parseProxyConfig("http://127.0.0.1:abc") }
        assertErrorContains("proxy 主机或端口错误") { parseProxyConfig("http://127.0.0.1:8080extra") }
        assertErrorContains("proxy 主机或端口错误") { parseProxyConfig("http://127.0.0.1:-1") }
    }

    @Test
    fun `missing or invalid host is rejected`() {
        assertErrorContains("proxy 主机或端口错误") { parseProxyConfig("http://:8080") }
        assertErrorContains("proxy 主机或端口错误") { parseProxyConfig("http://my_proxy:8080") }
        // 认证信息里含未编码空格 -> URI 语法错误
        assertErrorContains("proxy 格式错误") {
            parseProxyConfig("http://$username:$password@ho st:1080")
        }
    }

    @Test
    fun `unsupported scheme is rejected without echoing input`() {
        for (proxy in listOf(
            "ftp://127.0.0.1:8080",
            "https://127.0.0.1:8080",
            "socks6://127.0.0.1:1080",
            "ftp://$username:$password@127.0.0.1:8080"
        )) {
            val error = parseError(proxy)
            val message = error.message.orEmpty()
            assertTrue("应提示不支持的协议，实际: $message", message.contains("不支持的 proxy 协议"))
            assertTrue("应列出支持的协议，实际: $message", message.contains("http/socks4/socks5"))
            assertNoCredentialLeak(error)
        }
    }

    @Test
    fun `missing scheme is rejected`() {
        assertErrorContains("proxy 缺少协议") { parseProxyConfig("//127.0.0.1:8080") }
        assertErrorContains("proxy 缺少协议") { parseProxyConfig("") }
        assertErrorContains("proxy 缺少协议") { parseProxyConfig("   ") }
        assertErrorContains("proxy 缺少协议") { parseProxyConfig("127.0.0.1a") }
        assertErrorContains("proxy 缺少协议") { parseProxyConfig("not-a-proxy") }
    }

    @Test
    fun `unparseable proxy text is a format error`() {
        assertErrorContains("proxy 格式错误") { parseProxyConfig("127.0.0.1:8080") }
        assertErrorContains("proxy 格式错误") { parseProxyConfig("h\u0000ost:8080") }
    }

    @Test
    fun `malformed credentials are rejected`() {
        for (proxy in listOf(
            "http://$username@127.0.0.1:8080", // 只有用户名
            "http://$username:@127.0.0.1:8080", // 空密码
            "http://:$password@127.0.0.1:8080" // 空用户名
        )) {
            val error = parseError(proxy)
            assertTrue(
                "应提示认证格式错误，实际: ${error.message}",
                error.message.orEmpty().contains("proxy 认证格式错误")
            )
            assertNoCredentialLeak(error)
        }
    }

    @Test
    fun `multiple at signs make the host unparsable`() {
        assertErrorContains("proxy 主机或端口错误") {
            parseProxyConfig("http://$username:p@ss@127.0.0.1:8080")
        }
    }

    @Test
    fun `every parser failure uses the stable error prefix`() {
        val inputs = listOf(
            "127.0.0.1:8080",
            "//127.0.0.1:8080",
            "ftp://127.0.0.1:8080",
            "http://:8080",
            "http://127.0.0.1:0",
            "http://127.0.0.1:8080/path",
            "http://127.0.0.1:8080?x=1",
            "http://127.0.0.1:8080#f",
            "http://127.0.0.1\n:8080",
            "http://$username@127.0.0.1:8080",
            "socks4://$username:$password@127.0.0.1:1080"
        )
        for (proxy in inputs) {
            assertTrue(
                "缺少错误前缀: ${parseError(proxy).message}",
                parseError(proxy).message.orEmpty().startsWith(errorPrefix)
            )
        }
    }

    // ------------------------------------------------------------- 解析加固

    @Test
    fun `proxy with a path is rejected`() {
        for (proxy in listOf(
            "http://127.0.0.1:8080/",
            "http://127.0.0.1:8080/path",
            "socks5://127.0.0.1:1080/a/b",
            "http://$username:$password@127.0.0.1:8080/path"
        )) {
            val error = parseError(proxy)
            assertTrue(
                "应拒绝路径，实际: ${error.message}",
                error.message.orEmpty().contains("不能包含路径、查询或片段")
            )
            assertNoCredentialLeak(error)
        }
    }

    @Test
    fun `proxy with a query is rejected`() {
        for (proxy in listOf(
            "http://127.0.0.1:8080?",
            "http://127.0.0.1:8080?x=1",
            "socks5://127.0.0.1:1080?cache=1",
            "http://$username:$password@127.0.0.1:8080?x=1"
        )) {
            val error = parseError(proxy)
            assertTrue(
                "应拒绝查询串，实际: ${error.message}",
                error.message.orEmpty().contains("不能包含路径、查询或片段")
            )
            assertNoCredentialLeak(error)
        }
    }

    @Test
    fun `proxy with a fragment is rejected`() {
        for (proxy in listOf(
            "http://127.0.0.1:8080#",
            "http://127.0.0.1:8080#frag",
            "http://$username:$password@127.0.0.1:8080#frag"
        )) {
            val error = parseError(proxy)
            assertTrue(
                "应拒绝片段，实际: ${error.message}",
                error.message.orEmpty().contains("不能包含路径、查询或片段")
            )
            assertNoCredentialLeak(error)
        }
    }

    @Test
    fun `control characters anywhere in the proxy are rejected`() {
        for (proxy in listOf(
            "http://127.0.0.1\n:8080",
            "http://127.0.0.1:8080\u0000",
            "http://127.0.0.1:8080\t",
            "\nhttp://127.0.0.1:8080",
            "http://$username:$password@127.0.0.1:8080\r\nX-Injected: 1",
            "http://127.0.0.1:8080\u007f"
        )) {
            val error = parseError(proxy)
            assertTrue(
                "应拒绝控制字符，实际: ${error.message}",
                error.message.orEmpty().contains("proxy 格式错误")
            )
            assertSingleLine(error.message.orEmpty())
            assertNoCredentialLeak(error)
        }
    }

    @Test
    fun `percent encoded control characters in credentials are rejected`() {
        for (proxy in listOf(
            "socks5://demo%0AUser:$password@127.0.0.1:1080",
            "socks5://$username:demoPass%0D123@127.0.0.1:1080",
            "socks5://demo%00User:$password@127.0.0.1:1080"
        )) {
            val error = parseError(proxy)
            assertTrue(
                "应拒绝凭据中的控制字符，实际: ${error.message}",
                error.message.orEmpty().contains("proxy 认证格式错误")
            )
            assertNoCredentialLeak(error)
        }
    }

    @Test
    fun `oversized proxy text is rejected`() {
        val huge = "http://127.0.0.1:8080" + "a".repeat(5000)
        assertErrorContains("proxy 格式错误") { parseProxyConfig(huge) }
    }

    @Test
    fun `error text never contains credentials`() {
        val inputs = listOf(
            "http://$username:$password@ho st:1080",
            "http://$username:$password@127.0.0.1:8080/path",
            "http://$username:$password@127.0.0.1:8080?x=1",
            "http://$username:$password@127.0.0.1:8080#frag",
            "http://$username:$password@127.0.0.1:0",
            "http://$username:$password@127.0.0.1:8080\n",
            "http://$username:$password@127.0.0.1",
            "http://$username@127.0.0.1:8080",
            "http://:$password@127.0.0.1:8080",
            "ftp://$username:$password@127.0.0.1:8080",
            "socks4://$username:$password@127.0.0.1:1080",
            "http://$username:p@ss@127.0.0.1:8080",
            "$username:$password@127.0.0.1:8080",
            "socks5://demo%0AUser:$password@127.0.0.1:1080"
        )
        for (proxy in inputs) {
            assertNoCredentialLeak(parseError(proxy))
        }
    }

    @Test
    fun `toString redacts credentials`() {
        val config = parseProxyConfig("socks5://$username:$password@127.0.0.1:1080")
        val text = config.toString()
        assertFalse("toString 泄漏密码: $text", text.contains(password))
        assertFalse("toString 泄漏用户名: $text", text.contains(username))
        assertTrue("toString 应标记已认证: $text", text.contains("authenticated=true"))
        assertTrue("toString 应保留主机端口: $text", text.contains("127.0.0.1:1080"))
    }

    // -------------------------------------------- 大小写混写的 proxy 头提取

    @Test
    fun `proxy header is extracted case insensitively`() {
        for (key in listOf("proxy", "Proxy", "PROXY", "pRoXy")) {
            val (proxy, _) = mapOf(key to "socks5://127.0.0.1:1080").withoutProxyHeader()
            assertEquals("header key $key 未被提取", "socks5://127.0.0.1:1080", proxy)
        }
    }

    @Test
    fun `proxy header value is trimmed`() {
        val (proxy, headers) = mapOf(
            "Proxy" to "   http://127.0.0.1:8080  ",
            "X-Test" to "ok"
        ).withoutProxyHeader()
        assertEquals("http://127.0.0.1:8080", proxy)
        assertEquals(mapOf("X-Test" to "ok"), headers)
    }

    @Test
    fun `padded proxy header key is also recognized and removed`() {
        val (proxy, headers) = mapOf(
            " proxy " to "http://127.0.0.1:8080",
            "X-Test" to "ok"
        ).withoutProxyHeader()
        assertEquals("http://127.0.0.1:8080", proxy)
        assertEquals(mapOf("X-Test" to "ok"), headers)
    }

    @Test
    fun `proxy header never reaches webview headers`() {
        val proxyValue = "socks5://$username:$password@127.0.0.1:1080"
        val (proxy, headers) = mapOf(
            "pRoXy" to proxyValue,
            "content-type" to "text/html",
            "User-Agent" to "demo-agent"
        ).withoutProxyHeader()

        assertEquals(proxyValue, proxy)
        assertEquals(
            mapOf("content-type" to "text/html", "User-Agent" to "demo-agent"),
            headers
        )
        assertTrue(
            "WebView 头中仍有 proxy 键: ${headers.keys}",
            headers.keys.none { it.trim().equals("proxy", ignoreCase = true) }
        )
        for (key in headers.keys) {
            assertFalse("仅大小写不同的 proxy 键未被移除: $key", key.equals("proxy", true))
        }
    }

    @Test
    fun `intact headers never carry the proxy value or credentials`() {
        val proxyValue = "socks5://$username:$password@127.0.0.1:1080"
        val (_, headers) = mapOf(
            "PROXY" to proxyValue,
            "Accept" to "application/json",
            "Referer" to "https://example.com/read"
        ).withoutProxyHeader()

        assertEquals(
            mapOf("Accept" to "application/json", "Referer" to "https://example.com/read"),
            headers
        )
        for ((key, value) in headers) {
            assertFalse("键泄漏代理: $key", key.contains(proxyValue))
            assertFalse("值泄漏代理: $key", value.contains(proxyValue))
            assertFalse("值泄漏凭据: $key", value.contains(password))
            assertFalse("值泄漏用户名: $key", value.contains(username))
        }
    }

    @Test
    fun `blank proxy header yields null and is removed`() {
        for (blank in listOf("", "   ", "\t \n")) {
            val (proxy, headers) = mapOf("Proxy" to blank, "X-Test" to "ok").withoutProxyHeader()
            assertNull("空白值不应视为代理: [$blank]", proxy)
            assertEquals(mapOf("X-Test" to "ok"), headers)
        }
    }

    @Test
    fun `missing proxy header keeps headers untouched`() {
        val original = mapOf("X-Test" to "ok", "Accept" to "*/*")
        val (proxy, headers) = original.withoutProxyHeader()
        assertNull(proxy)
        assertEquals(original, headers)
    }

    @Test
    fun `header extraction does not mutate the source map`() {
        val original = mapOf("Proxy" to "http://127.0.0.1:8080", "X-Test" to "ok")
        val before = LinkedHashMap(original)
        original.withoutProxyHeader()
        assertEquals(before, original)
        assertTrue(original.containsKey("Proxy"))
    }

    @Test
    fun `duplicate proxy headers with the same value are accepted`() {
        val value = "http://127.0.0.1:8080"
        val (proxy, headers) = mapOf(
            "Proxy" to value,
            "proxy" to "  $value  ",
            "X-Test" to "ok"
        ).withoutProxyHeader()
        assertEquals(value, proxy)
        assertEquals(mapOf("X-Test" to "ok"), headers)
    }

    @Test
    fun `conflicting proxy header values are rejected`() {
        val headers = mapOf(
            "Proxy" to "http://127.0.0.1:8080",
            "proxy" to "socks5://127.0.0.1:1080"
        )
        try {
            val (proxy, _) = headers.withoutProxyHeader()
            fail("冲突的 proxy 头应被拒绝，但得到 $proxy")
        } catch (error: IllegalArgumentException) {
            assertTrue(
                "应给出冲突提示，实际: ${error.message}",
                error.message.orEmpty().contains("存在冲突的 proxy 请求头")
            )
            assertNoCredentialLeak(error)
        }
    }

    @Test
    fun `extracted proxy header feeds the parser`() {
        val (proxy, headers) = mapOf(
            "Proxy" to "  socks5://$username:$password@127.0.0.1:1080\n"
        ).withoutProxyHeader()

        // withoutProxyHeader 先 trim，所以带尾随换行的头仍可解析
        assertEquals("socks5://$username:$password@127.0.0.1:1080", proxy)
        assertNull("proxy 头必须从 WebView 头中移除", headers["Proxy"])

        val config = parseProxyConfig(requireNotNull(proxy))
        assertEquals(ProxyScheme.SOCKS5, config.scheme)
        assertEquals(username, config.username)
        assertEquals(password, config.password)

        // 带认证代理不能下发给 WebView，且失败信息不含凭据
        assertNoCredentialLeak(webViewRuleError(config))
    }

    @Test
    fun `extracted unauthenticated proxy header converts to a webview rule`() {
        val (proxy, _) = mapOf("Proxy" to " socks5://[::1]:1080 ").withoutProxyHeader()
        assertEquals("socks://[::1]:1080", parseProxyConfig(requireNotNull(proxy)).toWebViewProxyRule())
    }

    // --------------------------------------------------------------- 工具方法

    /**
     * 只捕获 [IllegalArgumentException]；断言失败用 [AssertionError] 抛出，
     * 避免"实现未抛异常"被误当成预期异常而静默通过。
     */
    private fun parseError(proxy: String): IllegalArgumentException {
        try {
            val config = parseProxyConfig(proxy)
            throw AssertionError("期望解析失败，但得到 $config（输入: $proxy）")
        } catch (error: IllegalArgumentException) {
            return error
        }
    }

    private fun webViewRuleError(config: SourceProxyConfig): IllegalArgumentException {
        try {
            val rule = config.toWebViewProxyRule()
            throw AssertionError("期望 WebView 规则生成失败，但得到 $rule")
        } catch (error: IllegalArgumentException) {
            return error
        }
    }

    private fun assertErrorContains(expected: String, block: () -> Unit) {
        try {
            block()
        } catch (error: IllegalArgumentException) {
            val message = error.message.orEmpty()
            assertTrue(
                "期望错误信息包含 <$expected>，实际为 <$message>",
                message.contains(expected)
            )
            return
        }
        fail("期望抛出 IllegalArgumentException（包含 <$expected>）")
    }

    /** 异常自身、cause 链与堆栈文本都不得出现明文凭据。 */
    private fun assertNoCredentialLeak(error: Throwable) {
        val seen = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Throwable, Boolean>()
        )
        var current: Throwable? = error
        while (current != null && seen.add(current)) {
            val message = current.message.orEmpty()
            assertFalse("错误信息泄漏密码: $message", message.contains(password))
            assertFalse("错误信息泄漏用户名: $message", message.contains(username))
            assertFalse(
                "错误信息泄漏用户名密码组合: $message",
                message.contains("$username:$password")
            )
            current = current.cause
        }
        val stackTrace = error.stackTraceToString()
        assertFalse("堆栈文本泄漏密码", stackTrace.contains(password))
        assertFalse("堆栈文本泄漏用户名", stackTrace.contains(username))
    }

    private fun assertSingleLine(message: String) {
        assertFalse("错误信息不应包含换行: $message", message.contains('\n'))
        assertFalse("错误信息不应包含回车: $message", message.contains('\r'))
        assertFalse("错误信息不应包含 NUL", message.contains('\u0000'))
        assertFalse("错误信息不应包含制表符", message.contains('\t'))
    }
}
