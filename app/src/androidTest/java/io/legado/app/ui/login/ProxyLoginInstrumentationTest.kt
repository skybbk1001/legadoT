package io.legado.app.ui.login

import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.http.CookieStore
import io.legado.app.utils.toWebViewRequestHeaders
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** 用不可直连的 .invalid 域名验证 Chromium 原生代理、POST、子资源和主进程隔离。 */
@RunWith(AndroidJUnit4::class)
class ProxyLoginInstrumentationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun webViewHeadersNeverContainProxyCredentials() {
        val headers = mapOf("Proxy" to "http://test:secret@127.0.0.1:8080", "proxy-authorization" to "secret", "Accept" to "text/html")
        assertEquals(mapOf("Accept" to "text/html"), headers.toWebViewRequestHeaders())
    }

    @Test
    fun proxyLoginPostsAndReturnsCookiesWithoutChangingMainProcessProxy() = verifyLogin(false)

    @Test
    fun authenticatedHttpProxyPostsAndReturnsCookies() = verifyLogin(true)

    @Test
    fun authenticatedSocksProxyPostsAndReturnsCookies() = verifyLogin(true, socks = true)

    private fun verifyLogin(authenticated: Boolean, socks: Boolean = false) {
        val host = "proxy-login-${UUID.randomUUID()}.invalid"
        val key = "http://$host/"
        val posted = CountDownLatch(1)
        val directLoaded = CountDownLatch(1)
        val requests = CopyOnWriteArrayList<String>()
        val proxy = TestServer(socks = socks) { first, headers, body ->
            requests.add(first)
            assertFalse(headers.any { it.startsWith("proxy:", true) })
            if (authenticated && !socks) assertTrue(headers.any { it.equals("Proxy-Authorization: Basic ZGVtbzpzZWNyZXQ=", true) })
            if (socks) assertFalse(headers.any { it.startsWith("proxy-", true) })
            when {
                first.contains("/done ") -> {
                    posted.countDown()
                    "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                }
                first.startsWith("POST ") -> {
                    assertEquals("login=ok", body)
                    "HTTP/1.1 200 OK\r\nSet-Cookie: login_test=accepted; Path=/\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                }
                else -> {
                    val html = """<html><body><h1>Proxy login test</h1><script>
                        fetch('/submit',{method:'POST',body:'login=ok'}).then(r=>r.text()).then(()=>fetch('/done'));
                        </script></body></html>""".trimIndent()
                    "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.toByteArray().size}\r\nConnection: close\r\n\r\n$html"
                }
            }
        }
        val origin = TestServer { _, _, _ ->
            directLoaded.countDown()
            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
        }
        var directWebView: WebView? = null
        val source = BookSource(bookSourceUrl = key, bookSourceName = "Proxy login integration test", loginUrl = key,
            header = """{"proxy":"${if (socks) "socks5" else "http"}://${if (authenticated) "demo:secret@" else ""}127.0.0.1:${proxy.port}"}""")
        try {
            appDb.bookSourceDao.insert(source)
            CookieStore.setCookie(key, "login_test=old") // 主进程预热缓存，必须被回传的新值替换。
            assertTrue(CookieStore.getCookie(key).contains("login_test=old"))
            instrumentation.runOnMainSync {
                context.startActivity(Intent(context, SourceLoginActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("key", key).putExtra("type", "bookSource"))
            }
            assertTrue("login page POST must reach proxy", posted.await(30, TimeUnit.SECONDS))
            // 同时在主进程创建 WebView，确保不被子进程的 ProxyController 改出口。
            instrumentation.runOnMainSync {
                directWebView = WebView(context).apply { loadUrl("http://127.0.0.1:${origin.port}/direct") }
            }
            assertTrue("main WebView should connect directly to origin", directLoaded.await(15, TimeUnit.SECONDS))
            assertFalse(requests.any { it.contains("/direct") })
            // 等待 Cookie 被 Chromium 接收；不用依赖跨进程 ActivityScenario。
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var clicked = false
            while (!clicked && System.nanoTime() < deadline) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                val buttons = root?.findAccessibilityNodeInfosByText("完成").orEmpty()
                clicked = buttons.any { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                if (!clicked) Thread.sleep(100)
            }
            assertTrue("finish button should be accessible", clicked)
            val cookieDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!CookieStore.getCookie(key).contains("login_test=accepted") && System.nanoTime() < cookieDeadline) Thread.sleep(100)
            assertTrue("cookie should return to main-process CookieStore", CookieStore.getCookie(key).contains("login_test=accepted"))
        } finally {
            instrumentation.runOnMainSync { directWebView?.destroy() }
            appDb.bookSourceDao.delete(source)
            CookieStore.removeCookie(key)
            proxy.close()
            origin.close()
        }
    }

    @Test
    fun httpsLoginUsesConnectTunnel() = verifyConnect(false)

    @Test
    fun authenticatedHttpsLoginUsesConnectTunnel() = verifyConnect(true)

    private fun verifyConnect(authenticated: Boolean) {
        val connected = CountDownLatch(1)
        val host = "https-${UUID.randomUUID()}.invalid"
        TestServer { first, headers, _ ->
            if (authenticated) assertTrue(headers.any { it.equals("Proxy-Authorization: Basic ZGVtbzpzZWNyZXQ=", true) })
            if (first.startsWith("CONNECT $host:443 ")) connected.countDown()
            "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        }.use { proxy ->
            try {
                launchBrowser("https://$host/", "http://${if (authenticated) "demo:secret@" else ""}127.0.0.1:${proxy.port}")
                assertTrue("HTTPS should issue CONNECT to configured proxy", connected.await(20, TimeUnit.SECONDS))
            } finally { clickFinish() }
        }
    }

    @Test
    fun failedProxyDoesNotFallBackToDirectEvenForLoopback() {
        val direct = CountDownLatch(1)
        TestServer { _, _, _ ->
            direct.countDown()
            "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        }.use { origin ->
            val deadPort = ServerSocket(0).use { it.localPort }
            try {
                launchBrowser("http://127.0.0.1:${origin.port}/no-direct", "http://127.0.0.1:$deadPort")
                assertFalse("failed proxy must never bypass to localhost", direct.await(5, TimeUnit.SECONDS))
            } finally { clickFinish() }
        }
    }

    private fun launchBrowser(url: String, proxy: String) {
        instrumentation.runOnMainSync {
            context.startActivity(Intent(context, SourceLoginProxyActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("session", UUID.randomUUID().toString())
                .putExtra("loginUrl", url)
                .putExtra("sourceUrl", url)
                .putExtra("title", "Proxy integration test")
                .putExtra("proxy", proxy)
                .putExtra("cookies", Bundle()))
        }
    }

    private fun clickFinish() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val buttons = instrumentation.uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByText("完成").orEmpty()
            if (buttons.any { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }) {
                instrumentation.waitForIdleSync()
                return
            }
            Thread.sleep(100)
        }
        fail("finish button should be accessible")
    }

    private class TestServer(val socks: Boolean = false, val response: (String, List<String>, String) -> String) : AutoCloseable {
        private val server = ServerSocket(0)
        val port get() = server.localPort
        private val worker = thread(isDaemon = true, name = "proxy-login-test-server") {
            while (!server.isClosed) {
                try {
                    val socket = server.accept()
                    thread(isDaemon = true) { handle(socket) }
                } catch (_: java.io.IOException) { break }
            }
        }
        private fun handle(socket: Socket) = socket.use {
            it.soTimeout = 10000
            if (socks) {
                val input = it.getInputStream()
                val output = it.getOutputStream()
                fun bytes(count: Int) = ByteArray(count) {
                    val value = input.read()
                    check(value >= 0)
                    value.toByte()
                }
                assertArrayEquals(byteArrayOf(5, 1, 2), bytes(3))
                output.write(byteArrayOf(5, 2)); output.flush()
                assertEquals(1, input.read())
                assertEquals("demo", String(bytes(input.read()), Charsets.UTF_8))
                assertEquals("secret", String(bytes(input.read()), Charsets.UTF_8))
                output.write(byteArrayOf(1, 0)); output.flush()
                assertArrayEquals(byteArrayOf(5, 1, 0, 3), bytes(4))
                assertTrue(String(bytes(input.read()), Charsets.US_ASCII).endsWith(".invalid"))
                bytes(2)
                output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 80)); output.flush()
            }
            val reader = it.getInputStream().bufferedReader()
            val first = reader.readLine() ?: return@use
            val headers = mutableListOf<String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                headers.add(line)
            }
            val length = headers.firstOrNull { it.startsWith("Content-Length:", true) }
                ?.substringAfter(':')?.trim()?.toInt() ?: 0
            val body = CharArray(length)
            var offset = 0
            while (offset < length) {
                val count = reader.read(body, offset, length - offset)
                if (count < 0) break
                offset += count
            }
            it.getOutputStream().write(response(first, headers, String(body)).toByteArray())
        }
        override fun close() { server.close(); worker.join(1000) }
    }
}
