package io.legado.app.help.http

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * [LocalAuthenticatedProxy] 的纯 JVM socket 集成测试：只监听 127.0.0.1，上游全部是伪 ServerSocket，
 * 不访问真实网络、不依赖 Android、不使用长 sleep。
 *
 * 用例清单（14 条）：
 *  1. 构造即在 127.0.0.1 随机端口监听，port/proxyRule/lastError 正确，close 释放端口且幂等；
 *  2. HTTP Basic CONNECT：先向上游发 CONNECT+Proxy-Authorization，然后任意二进制字节双向透传；
 *  3. 明文 HTTP POST：absolute-form + Basic 头 + 请求体，响应体与 Connection: close 回传；
 *  4. 上游 407 -> 客户端 502，lastError 已脱敏（不含用户名/密码）；
 *  5. Expect: 100-continue 由本地应答，不透传给上游，请求体照常转发；
 *  6. chunked 请求体与 chunked 响应体均正确转发；
 *  7. Transfer-Encoding + Content-Length 同时出现 -> 400，且完全不接触上游；
 *  8. 重复 Content-Length -> 400，且完全不接触上游；
 *  9. 无效 host/port/请求行(form) -> 400，且完全不接触上游；
 * 10. 超长头部行 -> 400（头部预算约束）；
 * 11. SOCKS5 上游：只提供 0x02 认证方法、username/password 握手、domain 名(.invalid)交由上游解析；
 * 12. SOCKS5 上游的明文请求改写为 origin-form，并清理客户端 Proxy-* 头；
 * 13. SOCKS5 上游拒绝 0x02 方法 -> 502，不直连目标，lastError 脱敏；
 * 14. close 中断活跃 tunnel 并停止监听，可重复调用。
 *
 * 安全约定：凭据全部为虚构值（demoUser / demoPass123）；测试自身不打印凭据，
 * 并断言 lastError 与回传头部都不含明文凭据。所有跨线程断言通过 [Future] 或
 * [AtomicReference] 回传测试线程，超时统一由 socket soTimeout / Future.get 控制。
 */
class LocalAuthenticatedProxyTest {

    private val username = "demoUser"
    private val password = "demoPass123"

    private val iso = StandardCharsets.ISO_8859_1
    private val utf8 = StandardCharsets.UTF_8

    private fun expectedBasic(): String = "Basic " + Base64.getEncoder()
        .encodeToString("$username:$password".toByteArray(iso))

    private fun httpConfig(port: Int): SourceProxyConfig =
        parseProxyConfig("http://$username:$password@127.0.0.1:$port")

    private fun socksConfig(port: Int): SourceProxyConfig =
        parseProxyConfig("socks5://$username:$password@127.0.0.1:$port")

    // ------------------------------------------------------------ 1. 生命周期

    @Test
    fun `starts on a random loopback port and releases it on close`() {
        // 上游端口只是占位：本用例不会真的发起代理请求。
        val proxy = LocalAuthenticatedProxy(httpConfig(9), timeoutMillis = TIMEOUT)
        val port: Int
        try {
            port = proxy.port
            assertTrue("端口应在 1..65535，实际 $port", port in 1..65535)
            assertEquals("http://127.0.0.1:$port", proxy.proxyRule)
            assertNull("刚构造时不应有错误", proxy.lastError)
            // 能建立 TCP 连接即证明已在监听。
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 5000) }
        } finally {
            proxy.close()
        }
        proxy.close() // 幂等，不抛异常
        try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1000) }
            throw AssertionError("close 之后端口 $port 仍在监听")
        } catch (_: IOException) {
            // 期望：端口已释放
        }
    }

    // ------------------------------------------------ 2. HTTP CONNECT 二进制透传

    @Test
    fun `connect tunnel sends basic auth then relays arbitrary binary bytes`() {
        val upstream = FakeUpstream { socket ->
            val input = BufferedInputStream(socket.getInputStream())
            val head = input.readHead()
            assertEquals("CONNECT example.com:443 HTTP/1.1", head.startLine)
            assertEquals("example.com:443", head.value("Host"))
            assertEquals(expectedBasic(), head.value("Proxy-Authorization"))
            assertTrue(
                "客户端头部不得泄漏到上游: ${head.names}",
                head.names.none { it.equals("Proxy-Connection", true) }
            )
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(iso))
                flush()
            }
            echoTo(input, socket.getOutputStream())
        }
        runCase(upstream, httpConfig(upstream.port)) { proxy ->
            Client(proxy).use { client ->
                client.write("CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n")
                val response = client.readHead()
                assertTrue("应返回 200: ${response.startLine}", response.startLine.startsWith("HTTP/1.1 200"))
                // 含 0x00、CR/LF、0xFF 的任意字节都要原样透传（TLS 记录就是这种二进制）。
                val payload = ByteArray(1024) { (it * 31 and 0xFF).toByte() }
                client.write(payload)
                assertArrayEquals("二进制字节必须原样往返", payload, client.readBody(payload.size))
            }
        }
    }

    // -------------------------------------------------- 3. 明文 HTTP POST 转发

    @Test
    fun `plain http post forwards absolute form with body and basic auth`() {
        val body = "hello world!"
        val upstream = FakeUpstream { socket ->
            val input = BufferedInputStream(socket.getInputStream())
            val head = input.readHead()
            assertEquals("POST http://origin.invalid:8080/echo?a=1 HTTP/1.1", head.startLine)
            assertEquals("origin.invalid:8080", head.value("Host"))
            assertEquals(expectedBasic(), head.value("Proxy-Authorization"))
            assertEquals(body.length.toString(), head.value("Content-Length"))
            assertEquals("close", head.value("Connection"))
            assertTrue(
                "Proxy-Connection 必须被清理: ${head.names}",
                head.names.none { it.equals("Proxy-Connection", true) }
            )
            assertEquals(body, String(input.readExactly(body.length), utf8))
            socket.getOutputStream().apply {
                write(
                    "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 6\r\n\r\nabcdef"
                        .toByteArray(iso)
                )
                flush()
            }
        }
        runCase(upstream, httpConfig(upstream.port)) { proxy ->
            Client(proxy).use { client ->
                client.write(
                    "POST http://origin.invalid:8080/echo?a=1 HTTP/1.1\r\n" +
                        "Host: origin.invalid:8080\r\n" +
                        "Content-Type: text/plain\r\n" +
                        "Content-Length: ${body.length}\r\n" +
                        "Proxy-Connection: keep-alive\r\n\r\n"
                )
                client.write(body)
                val head = client.readHead()
                assertEquals("HTTP/1.1 200 OK", head.startLine)
                assertEquals("text/plain", head.value("Content-Type"))
                assertEquals("6", head.value("Content-Length"))
                assertEquals("每个普通 HTTP 响应连接都应关闭", "close", head.value("Connection"))
                assertTrue(
                    "不得把代理认证头回传给客户端: ${head.names}",
                    head.names.none { it.startsWith("Proxy-", true) }
                )
                assertEquals("abcdef", String(client.readBody(6), utf8))
            }
        }
    }

    // --------------------------------------------------- 4. 上游 407 的安全失败

    @Test
    fun `upstream proxy authentication failure becomes 502 with a redacted error`() {
        val upstream = FakeUpstream { socket ->
            val input = BufferedInputStream(socket.getInputStream())
            assertEquals("CONNECT example.com:443 HTTP/1.1", input.readHead().startLine)
            socket.getOutputStream().apply {
                write("HTTP/1.1 407 Proxy Authentication Required\r\nContent-Length: 0\r\n\r\n".toByteArray(iso))
                flush()
            }
        }
        runCase(upstream, httpConfig(upstream.port)) { proxy ->
            Client(proxy).use { client ->
                client.write("CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n")
                val head = client.readHead()
                assertTrue("407 应转成 502: ${head.startLine}", head.startLine.startsWith("HTTP/1.1 502"))
                assertEquals("0", head.value("Content-Length"))
                val message = proxy.lastError.orEmpty()
                assertTrue("应说明代理认证失败，实际: $message", message.contains("代理认证失败"))
                assertFalse("lastError 泄漏用户名: $message", message.contains(username))
                assertFalse("lastError 泄漏密码: $message", message.contains(password))
            }
        }
    }

    // ------------------------------------------------------ 5. Expect 由本地处理

    @Test
    fun `expect continue is answered locally and never forwarded`() {
        val upstream = FakeUpstream { socket ->
            val input = BufferedInputStream(socket.getInputStream())
            val head = input.readHead()
            assertEquals("POST http://origin.invalid:8080/upload HTTP/1.1", head.startLine)
            assertFalse(
                "Expect 必须由本地处理，不能透传: ${head.names}",
                head.names.any { it.equals("Expect", true) }
            )
            assertEquals(expectedBasic(), head.value("Proxy-Authorization"))
            assertEquals("data", String(input.readExactly(4), utf8))
            socket.getOutputStream().apply {
                write("HTTP/1.1 201 Created\r\nContent-Length: 2\r\n\r\nok".toByteArray(iso))
                flush()
            }
        }
        runCase(upstream, httpConfig(upstream.port)) { proxy ->
            Client(proxy).use { client ->
                client.write(
                    "POST http://origin.invalid:8080/upload HTTP/1.1\r\n" +
                        "Host: origin.invalid:8080\r\n" +
                        "Content-Length: 4\r\n" +
                        "Expect: 100-continue\r\n\r\n"
                )
                assertEquals(
                    "本地应先行返回 100 Continue",
                    "HTTP/1.1 100 Continue",
                    client.readHead().startLine
                )
                client.write("data")
                val head = client.readHead()
                assertEquals("HTTP/1.1 201 Created", head.startLine)
                assertEquals("ok", String(client.readBody(2), utf8))
            }
        }
    }

    // --------------------------------------------------------- 6. chunked 双向

    @Test
    fun `chunked request and chunked response are forwarded`() {
        val upstream = FakeUpstream { socket ->
            val input = BufferedInputStream(socket.getInputStream())
            val head = input.readHead()
            assertEquals("POST http://origin.invalid:8080/chunked HTTP/1.1", head.startLine)
            assertEquals("chunked", head.value("Transfer-Encoding"))
            assertFalse("chunked 时不应出现 Content-Length", head.has("Content-Length"))
            assertEquals("hello world", input.readChunkedBody())
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\npong\r\n0\r\n\r\n".toByteArray(iso))
                flush()
            }
        }
        runCase(upstream, httpConfig(upstream.port)) { proxy ->
            Client(proxy).use { client ->
                client.write(
                    "POST http://origin.invalid:8080/chunked HTTP/1.1\r\n" +
                        "Host: origin.invalid:8080\r\n" +
                        "Transfer-Encoding: chunked\r\n\r\n" +
                        "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n"
                )
                val head = client.readHead()
                assertEquals("HTTP/1.1 200 OK", head.startLine)
                assertEquals("chunked", head.value("Transfer-Encoding"))
                assertEquals("pong", client.input.readChunkedBody())
            }
        }
    }

    // ------------------------------- 7~10. 请求加固：拒绝且绝不接触上游代理

    @Test
    fun `transfer encoding plus content length is rejected without touching upstream`() {
        val upstream = FakeUpstream { socket -> socket.close() }
        runCase(upstream, httpConfig(upstream.port)) { proxy ->
            Client(proxy).use { client ->
                client.write(
                    "POST http://origin.invalid:8080/smuggle HTTP/1.1\r\n" +
                        "Host: origin.invalid:8080\r\n" +
                        "Content-Length: 5\r\n" +
                        "Transfer-Encoding: chunked\r\n\r\n" +
                        "0\r\n\r\n"
                )
                assertEquals("HTTP/1.1 400 Bad Request", client.readHead().startLine)
            }
            upstream.assertNeverConnected()
        }
    }

    @Test
    fun `duplicate content length is rejected without touching upstream`() {
        val upstream = FakeUpstream { socket -> socket.close() }
        runCase(upstream, httpConfig(upstream.port)) { proxy ->
            Client(proxy).use { client ->
                client.write(
                    "POST http://origin.invalid:8080/dup HTTP/1.1\r\n" +
                        "Host: origin.invalid:8080\r\n" +
                        "Content-Length: 5\r\n" +
                        "Content-Length: 5\r\n\r\nhello"
                )
                assertEquals("HTTP/1.1 400 Bad Request", client.readHead().startLine)
            }
            upstream.assertNeverConnected()
        }
    }

    @Test
    fun `invalid host port and request form are rejected without touching upstream`() {
        val upstream = FakeUpstream { socket -> socket.close() }
        val requests = listOf(
            "CONNECT example.com:0 HTTP/1.1\r\nHost: example.com:0\r\n\r\n",
            "GET http://example.com:70000/ HTTP/1.1\r\nHost: example.com:70000\r\n\r\n",
            "GET /no-authority HTTP/1.1\r\nHost: example.com\r\n\r\n",
            "CONNECT example.com:443/path HTTP/1.1\r\nHost: example.com:443\r\n\r\n",
            "GET http://user:pw@example.com/ HTTP/1.1\r\nHost: example.com\r\n\r\n",
            "GET http://example.com/ HTTP/9.9\r\nHost: example.com\r\n\r\n",
            // 重复 Host、Host 与请求行不一致：可能是请求走私/缓存投毒的构造
            "GET http://example.com/ HTTP/1.1\r\nHost: example.com\r\nHost: example.com\r\n\r\n",
            "GET http://example.com/ HTTP/1.1\r\nHost: evil.invalid\r\n\r\n",
            "GET http://example.com:8080/ HTTP/1.1\r\nHost: example.com:9090\r\n\r\n",
            // Connection 头不得重定义关键帧头
            "POST http://example.com/ HTTP/1.1\r\nHost: example.com\r\nConnection: content-length\r\nContent-Length: 5\r\n\r\nhello",
            "GET http://example.com/ HTTP/1.1\r\nHost: example.com\r\nConnection: transfer-encoding\r\n\r\n",
            // 只接受 100-continue，其它 Expect 视为无效
            "POST http://example.com/ HTTP/1.1\r\nHost: example.com\r\nExpect: something-else\r\nContent-Length: 5\r\n\r\nhello",
            // CONNECT 不得携带消息体
            "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\nContent-Length: 5\r\n\r\nhello"
        )
        runCase(upstream, httpConfig(upstream.port)) { proxy ->
            for (request in requests) {
                Client(proxy).use { client ->
                    client.write(request)
                    val startLine = client.readHead().startLine
                    assertTrue(
                        "无效请求应得到 400，实际 <$startLine>（请求: ${request.lineSequence().first()}）",
                        startLine.startsWith("HTTP/1.1 400")
                    )
                }
            }
            upstream.assertNeverConnected()
        }
    }

    @Test
    fun `oversized header line is rejected`() {
        val upstream = FakeUpstream { socket -> socket.close() }
        runCase(upstream, httpConfig(upstream.port)) { proxy ->
            Client(proxy).use { client ->
                client.write(
                    "GET http://origin.invalid/ HTTP/1.1\r\n" +
                        "Host: origin.invalid\r\n" +
                        "X-Big: " + "a".repeat(9000) + "\r\n\r\n"
                )
                assertEquals("HTTP/1.1 400 Bad Request", client.readHead().startLine)
            }
            upstream.assertNeverConnected()
        }
    }

    // ------------------------------------------ 10b. WebSocket 101 双向透传

    @Test
    fun `websocket upgrade is tunnelled bidirectionally instead of being closed`() {
        val upstream = FakeUpstream { socket ->
            val input = BufferedInputStream(socket.getInputStream())
            val head = input.readHead()
            // 实现用 URI 规范化后的 absolute-form，默认端口 80 会显式出现（语义等价）。
            assertEquals("GET http://origin.invalid:80/ws HTTP/1.1", head.startLine)
            assertEquals("Upgrade", head.value("Connection"))
            assertEquals("websocket", head.value("Upgrade"))
            assertEquals(expectedBasic(), head.value("Proxy-Authorization"))
            socket.getOutputStream().apply {
                write(
                    ("HTTP/1.1 101 Switching Protocols\r\n" +
                        "Connection: Upgrade\r\nUpgrade: websocket\r\n\r\n").toByteArray(iso)
                )
                flush()
            }
            echoTo(input, socket.getOutputStream())
        }
        runCase(upstream, httpConfig(upstream.port)) { proxy ->
            Client(proxy, readTimeoutMillis = 3000).use { client ->
                client.write(
                    "GET http://origin.invalid/ws HTTP/1.1\r\n" +
                        "Host: origin.invalid\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Upgrade: websocket\r\n\r\n"
                )
                val head = client.readHead()
                assertEquals("HTTP/1.1 101 Switching Protocols", head.startLine)
                assertEquals("Upgrade", head.value("Connection"))
                assertEquals("websocket", head.value("Upgrade"))
                // 101 之后不能再补 Connection: close，帧要能双向继续传输。
                val frame = byteArrayOf(0x81.toByte(), 0x03, 0x66, 0x72, 0x6F)
                client.write(frame)
                assertArrayEquals("101 之后必须双向透传", frame, client.readBody(frame.size))
            }
        }
    }

    // ------------------------------------------------------- 11. SOCKS5 上游 CONNECT

    @Test
    fun `socks5 upstream authenticates and sends the domain name for remote dns`() {
        val upstream = FakeUpstream { socket ->
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            // .invalid 永远无法解析：只有把域名交给上游才可能成功。
            input.socks5UpstreamHandshake(output, "secret.invalid", 443)
            echoTo(input, output)
        }
        runCase(upstream, socksConfig(upstream.port)) { proxy ->
            Client(proxy).use { client ->
                client.write("CONNECT secret.invalid:443 HTTP/1.1\r\nHost: secret.invalid:443\r\n\r\n")
                assertTrue(client.readHead().startLine.startsWith("HTTP/1.1 200"))
                val payload = ByteArray(256) { (255 - it).toByte() }
                client.write(payload)
                assertArrayEquals(payload, client.readBody(payload.size))
            }
        }
        assertEquals("只应有一条到上游代理的 TCP 连接", 1, upstream.connectionCount)
    }

    // ------------------------------------------------- 12. SOCKS5 origin-form 清理

    @Test
    fun `socks5 upstream gets origin form without any client proxy header`() {
        val upstream = FakeUpstream { socket ->
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            input.socks5UpstreamHandshake(output, "origin.invalid", 8080)
            val head = input.readHead()
            assertEquals("GET /a/b?x=1 HTTP/1.1", head.startLine)
            assertEquals("origin.invalid:8080", head.value("Host"))
            assertEquals("1", head.value("X-Keep"))
            val proxyHeaders = head.names.filter { it.startsWith("Proxy-", true) }
            assertTrue("SOCKS5 上游不得收到任何代理头: $proxyHeaders", proxyHeaders.isEmpty())
            output.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nhi".toByteArray(iso))
            output.flush()
        }
        runCase(upstream, socksConfig(upstream.port)) { proxy ->
            Client(proxy).use { client ->
                client.write(
                    "GET http://origin.invalid:8080/a/b?x=1 HTTP/1.1\r\n" +
                        "Host: origin.invalid:8080\r\n" +
                        "Proxy-Authorization: Basic ZmFrZQ==\r\n" +
                        "Proxy-Connection: keep-alive\r\n" +
                        "X-Keep: 1\r\n\r\n"
                )
                val head = client.readHead()
                assertEquals("HTTP/1.1 200 OK", head.startLine)
                assertEquals("hi", String(client.readBody(2), utf8))
            }
        }
    }

    // ----------------------------------------- 13. SOCKS5 认证失败不回落直连

    @Test
    fun `socks5 upstream rejecting the user password method fails without direct fallback`() {
        val upstream = FakeUpstream { socket ->
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            // 握手只允许提供一种方法，且必须是 0x02（username/password）。
            val greeting = input.readExactly(3).map { it.toInt() and 0xFF }
            assertEquals(listOf(5, 1, 2), greeting)
            output.write(byteArrayOf(5, -1)) // 05 FF：不接受任何方法
            output.flush()
        }
        runCase(upstream, socksConfig(upstream.port)) { proxy ->
            Client(proxy).use { client ->
                client.write("CONNECT secret.invalid:443 HTTP/1.1\r\nHost: secret.invalid:443\r\n\r\n")
                val startLine = client.readHead().startLine
                assertTrue("认证失败应返回 502，实际 <$startLine>", startLine.startsWith("HTTP/1.1 502"))
                val message = proxy.lastError.orEmpty()
                assertTrue("应说明 SOCKS5 认证问题，实际: $message", message.contains("SOCKS5"))
                assertFalse("lastError 泄漏用户名: $message", message.contains(username))
                assertFalse("lastError 泄漏密码: $message", message.contains(password))
            }
        }
        assertEquals("认证失败后不得尝试直连目标", 1, upstream.connectionCount)
    }

    // ------------------------------------------------------ 14. close 中断 tunnel

    @Test
    fun `close interrupts an active tunnel and stops listening`() {
        val upstream = FakeUpstream { socket ->
            val input = BufferedInputStream(socket.getInputStream())
            assertEquals("CONNECT example.com:443 HTTP/1.1", input.readHead().startLine)
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(iso))
                flush()
            }
            echoTo(input, socket.getOutputStream())
        }
        // 代理空闲超时设得远大于客户端读超时：若 close 没能中断 tunnel，
        // 客户端只会等到自己的读超时（而不是代理空闲超时）而失败，断言才有意义。
        val proxy = LocalAuthenticatedProxy(httpConfig(upstream.port), timeoutMillis = LONG_IDLE)
        val client = Client(proxy, readTimeoutMillis = SHORT_READ)
        try {
            client.write("CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n")
            assertTrue(client.readHead().startLine.startsWith("HTTP/1.1 200"))
            client.write(byteArrayOf(1, 2, 3))
            assertArrayEquals("tunnel 应处于活跃状态", byteArrayOf(1, 2, 3), client.readBody(3))

            val port = proxy.port
            proxy.close()
            proxy.close() // 幂等

            val interrupted = try {
                client.input.read() == -1
            } catch (_: SocketTimeoutException) {
                throw AssertionError("close 之后活动 tunnel 仍可读，连接未被中断")
            } catch (_: IOException) {
                true
            }
            assertTrue("close 应立即中断活动 tunnel", interrupted)
            client.close()

            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1000) }
                throw AssertionError("close 之后端口 $port 仍在监听")
            } catch (_: IOException) {
                // 期望：监听已关闭
            }
            upstream.awaitAndVerify()
        } finally {
            runCatching { client.close() }
            proxy.close()
            upstream.close()
        }
    }

    // --------------------------------------------------------------- 工具方法

    /**
     * 统一用例骨架：起代理、跑断言、关代理，并保证伪上游线程里的断言失败能回传测试线程，
     * 不会被客户端侧的附带异常掩盖。
     */
    private fun runCase(
        upstream: FakeUpstream,
        config: SourceProxyConfig,
        block: (LocalAuthenticatedProxy) -> Unit,
    ) {
        var failure: Throwable? = null
        try {
            val proxy = LocalAuthenticatedProxy(config, timeoutMillis = TIMEOUT)
            try {
                block(proxy)
            } catch (error: Throwable) {
                failure = error
            } finally {
                proxy.close()
            }
            try {
                upstream.awaitAndVerify()
            } catch (error: Throwable) {
                if (failure == null) failure = error
            }
        } finally {
            upstream.close()
        }
        failure?.let { throw it }
    }

    private companion object {
        /** 所有 socket / Future 的统一超时；足够宽松，但绝不允许进程挂死。 */
        const val TIMEOUT = 8000

        /** close 用例：客户端读超时远小于代理空闲超时，用于区分"被 close 中断"和"读超时"。 */
        const val SHORT_READ = 1500
        const val LONG_IDLE = 60000
    }
}

// ----------------------------------------------------------------- 测试用客户端

/**
 * 指向本地代理的回环客户端。soTimeout 让任何协议错误都以异常暴露而不是挂死；
 * 需要区分"代理主动关闭"与"读超时"的用例可以传入更短的 [readTimeoutMillis]。
 */
private class Client(
    proxy: LocalAuthenticatedProxy,
    readTimeoutMillis: Int = 8000,
) : AutoCloseable {
    private val socket = Socket().apply {
        connect(InetSocketAddress("127.0.0.1", proxy.port), 8000)
        soTimeout = readTimeoutMillis
        tcpNoDelay = true
    }
    val input = BufferedInputStream(socket.getInputStream())
    private val output = socket.getOutputStream()

    fun write(text: String) = write(text.toByteArray(StandardCharsets.ISO_8859_1))

    fun write(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    fun readHead(): Head = input.readHead()

    fun readBody(length: Int): ByteArray = input.readExactly(length)

    override fun close() {
        runCatching { socket.close() }
    }
}

// -------------------------------------------------------------- 伪上游代理服务

/**
 * 单端口伪上游：每个 accept 回来的连接跑一次 [handler]。
 * [awaitAndVerify] 把 handler 线程里的断言失败（含超时）回传给测试线程，
 * 因此不需要在测试里用 sleep 等待。
 */
private class FakeUpstream(private val handler: (Socket) -> Unit) : AutoCloseable {
    private val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newCachedThreadPool { action ->
        Thread(action, "fake-upstream-worker").apply { isDaemon = true }
    }
    private val tasks = CopyOnWriteArrayList<Future<*>>()
    private val failure = AtomicReference<Throwable?>(null)
    private val accepted = AtomicInteger(0)
    private val acceptor = Thread({ acceptLoop() }, "fake-upstream-accept").apply {
        isDaemon = true
        start()
    }

    val port: Int get() = server.localPort
    val connectionCount: Int get() = accepted.get()

    private fun acceptLoop() {
        while (true) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                return
            }
            accepted.incrementAndGet()
            tasks += pool.submit {
                try {
                    socket.use(handler)
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                }
            }
        }
    }

    fun awaitAndVerify() {
        for (task in tasks) task.get(8000, TimeUnit.MILLISECONDS)
        failure.get()?.let { throw AssertionError("伪上游断言失败: ${it.message}", it) }
    }

    /** 断言在 [waitMillis] 内始终没有任何连接到上游（用于"拒绝且不接触上游"）。 */
    fun assertNeverConnected(waitMillis: Long = 400) {
        val deadline = System.currentTimeMillis() + waitMillis
        while (System.currentTimeMillis() < deadline) {
            if (accepted.get() > 0) throw AssertionError("被拒绝的请求仍然连接了上游代理")
            Thread.sleep(10)
        }
        assertEquals("被拒绝的请求不应连接上游代理", 0, accepted.get())
    }

    override fun close() {
        runCatching { server.close() }
        pool.shutdownNow()
    }
}

// ------------------------------------------------------------------ 协议工具

private class Head(val startLine: String, private val lines: List<Pair<String, String>>) {
    val names: List<String> get() = lines.map { it.first }

    fun value(name: String): String? =
        lines.firstOrNull { it.first.equals(name, true) }?.second

    fun has(name: String): Boolean = value(name) != null
}

private fun InputStream.readHead(): Head {
    val start = readLineOrNull() ?: throw AssertionError("连接在读取头部之前就被关闭")
    val lines = mutableListOf<Pair<String, String>>()
    while (true) {
        val row = readLineOrNull() ?: throw AssertionError("头部未结束连接就被关闭")
        if (row.isEmpty()) break
        val colon = row.indexOf(':')
        if (colon <= 0) throw AssertionError("非法头部行: $row")
        lines += row.substring(0, colon) to row.substring(colon + 1).trim()
    }
    return Head(start, lines)
}

private fun InputStream.readLineOrNull(): String? {
    val text = StringBuilder()
    while (true) {
        val value = read()
        if (value == -1) return if (text.isEmpty()) null else text.toString()
        if (value == 13) {
            if (read() != 10) throw AssertionError("CR 之后不是 LF")
            return text.toString()
        }
        text.append(value.toChar())
    }
}

private fun InputStream.readExactly(count: Int): ByteArray {
    val buffer = ByteArray(count)
    var offset = 0
    while (offset < count) {
        val read = read(buffer, offset, count - offset)
        if (read == -1) throw AssertionError("期望 $count 字节，只收到 $offset 字节")
        offset += read
    }
    return buffer
}

/** 读取 chunked 实体（丢弃 trailer 段），返回解码后的文本。 */
private fun InputStream.readChunkedBody(): String {
    val text = StringBuilder()
    while (true) {
        val sizeLine = readLineOrNull() ?: throw AssertionError("缺少 chunk 大小行")
        val sizeText = sizeLine.substringBefore(';').trim()
        val size = sizeText.toIntOrNull(16) ?: throw AssertionError("非法 chunk 大小: $sizeLine")
        if (size == 0) {
            while (true) {
                val trailer = readLineOrNull() ?: return text.toString()
                if (trailer.isEmpty()) return text.toString()
            }
        }
        text.append(String(readExactly(size), StandardCharsets.UTF_8))
        if (readLineOrNull() != "") throw AssertionError("chunk 数据之后缺少 CRLF")
    }
}

/** 原样回显：双向透传断言的基础，读到 EOF 即结束。 */
private fun echoTo(input: InputStream, output: OutputStream) {
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count == -1) return
        output.write(buffer, 0, count)
        output.flush()
    }
}

/**
 * 伪 SOCKS5 上游：断言本地代理只提供 0x02 认证方法、正确发送 username/password，
 * 并用 ATYP=0x03（domain）把目标域名交给上游解析。
 */
private fun InputStream.socks5UpstreamHandshake(
    output: OutputStream,
    expectedHost: String,
    expectedPort: Int,
) {
    val greeting = readExactly(2).map { it.toInt() and 0xFF }
    assertEquals("SOCKS5 版本错误", 5, greeting[0])
    assertEquals("只应提供一种认证方法", 1, greeting[1])
    val methods = readExactly(1).map { it.toInt() and 0xFF }
    assertEquals("只允许 username/password(0x02)，不得静默回落无认证", listOf(2), methods)
    output.write(byteArrayOf(5, 2))
    output.flush()

    val version = readExactly(1).map { it.toInt() and 0xFF }
    assertEquals("用户名密码子协商版本错误", 1, version[0])
    val userLength = readExactly(1)[0].toInt() and 0xFF
    val user = String(readExactly(userLength), StandardCharsets.UTF_8)
    val passLength = readExactly(1)[0].toInt() and 0xFF
    val pass = String(readExactly(passLength), StandardCharsets.UTF_8)
    assertEquals("上游收到的用户名不符", USERNAME, user)
    assertEquals("上游收到的密码不符", PASSWORD, pass)
    output.write(byteArrayOf(1, 0))
    output.flush()

    val request = readExactly(4).map { it.toInt() and 0xFF }
    assertEquals(5, request[0])
    assertEquals("应为 CONNECT", 1, request[1])
    assertEquals(0, request[2])
    assertEquals("远程 DNS 必须使用 domain 名(ATYP=3)", 3, request[3])
    val hostLength = readExactly(1)[0].toInt() and 0xFF
    val host = String(readExactly(hostLength), StandardCharsets.US_ASCII)
    val portBytes = readExactly(2).map { it.toInt() and 0xFF }
    val port = (portBytes[0] shl 8) or portBytes[1]
    assertEquals("上游收到的目标域名不符", expectedHost, host)
    assertEquals("上游收到的目标端口不符", expectedPort, port)
    // VER REP RSV ATYP BND.ADDR(4) BND.PORT(2)
    output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
    output.flush()
}

/** 与测试类内的私有用例常量保持一致（顶层函数无法访问实例字段）。 */
private const val USERNAME = "demoUser"
private const val PASSWORD = "demoPass123"
