package io.legado.app.help.http

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.IDN
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A session-scoped, loopback-only forward proxy for Chromium's missing upstream authentication.
 * HTTPS is CONNECT byte tunnelling (no TLS termination). Target names are resolved by the upstream.
 * One HTTP exchange per client connection; CONNECT/101 remain full duplex. No retries or DIRECT.
 *
 * Loopback is NOT an Android UID boundary: other local apps may access the short-lived random port.
 * Only enable while the user has an authenticated proxy login open. Never log input or credentials.
 */
internal class LocalAuthenticatedProxy(
    private val config: SourceProxyConfig,
    maxConnections: Int = 16,
    private val timeoutMillis: Int = 15000,
) : AutoCloseable {
    private val stopped = AtomicBoolean(false)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val server: ServerSocket
    private val workers: ThreadPoolExecutor
    private val pumps: ThreadPoolExecutor
    private val acceptor: Thread
    private val deadlines = java.util.concurrent.ScheduledThreadPoolExecutor(1) { action ->
        Thread(action, "login-proxy-deadlines").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    @Volatile var lastError: String? = null
        private set
    val port: Int get() = server.localPort
    val proxyRule: String get() = "http://127.0.0.1:$port"

    init {
        config.validateWebViewSupport()
        require(config.hasAuthentication) { "本地转发仅用于带认证的代理" }
        require(maxConnections in 1..32 && timeoutMillis in 100..120000)
        workers = pool(maxConnections, "login-proxy-request")
        pumps = pool(maxConnections, "login-proxy-tunnel")
        server = ServerSocket()
        try {
            server.bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0), maxConnections)
        } catch (_: IOException) {
            server.close()
            workers.shutdownNow()
            pumps.shutdownNow()
            throw IOException("无法启动登录代理本地监听")
        }
        acceptor = Thread({ acceptLoop() }, "login-proxy-listener").apply { isDaemon = true; start() }
    }

    private fun pool(count: Int, name: String) = ThreadPoolExecutor(
        0, count, 30, TimeUnit.SECONDS, SynchronousQueue(),
        { action -> Thread(action, name).apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )

    private fun acceptLoop() {
        while (!stopped.get()) {
            val client = try { server.accept() } catch (_: IOException) { break }
            if (stopped.get() || !client.inetAddress.isLoopbackAddress) {
                client.close()
                continue
            }
            sockets.add(client)
            if (stopped.get()) { forget(client); break }
            try {
                workers.execute { serve(client) }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                // Never block the accept loop writing to an untrusted local client.
                forget(client)
            }
        }
    }

    private fun serve(client: Socket) {
        val upstreamRef = java.util.concurrent.atomic.AtomicReference<Socket?>()
        var upstream: Socket? = null
        var committed = false
        val deadline = try { deadlines.schedule({
            // Absolute lifetime bounds blocked writes as well as reads (Socket.soTimeout is read-only).
            runCatching { client.close() }
            upstreamRef.get()?.let { runCatching { it.close() } }
        }, 5, TimeUnit.MINUTES) } catch (_: java.util.concurrent.RejectedExecutionException) {
            forget(client)
            return
        }
        try {
            client.soTimeout = timeoutMillis
            client.tcpNoDelay = true
            val input = BufferedInputStream(client.getInputStream())
            val output = client.getOutputStream()
            val request = readHead(input)
            val parts = request.first.split(' ')
            checkRequest(parts.size == 3 && TOKEN.matches(parts[0]) && parts[2] in listOf("HTTP/1.1", "HTTP/1.0"))
            val method = parts[0]
            val connect = method == "CONNECT"
            val destination = destination(parts[1], connect)
            val framing = framing(request.headers)
            val hosts = request.values("host")
            checkRequest(hosts.size <= 1)
            if (hosts.isNotEmpty()) {
                val supplied = destination(hosts.single(), true, destination.port)
                checkRequest(supplied.host.equals(destination.host, true) && supplied.port == destination.port)
            }
            val connectionTokens = request.values("connection").flatMap { it.split(',') }.map { it.trim().lowercase(Locale.ROOT) }
            checkRequest(connectionTokens.none { it in setOf("host", "content-length", "transfer-encoding", "expect") })
            val expect = request.values("expect")
            checkRequest(expect.isEmpty() || expect.size == 1 && expect.single().equals("100-continue", true))
            if (connect) checkRequest(!framing.chunked && (framing.length ?: 0) == 0L)
            val socket = openUpstream()
            upstream = socket
            upstreamRef.set(socket)
            if (client.isClosed) throw IOException()
            val remoteInput = BufferedInputStream(socket.getInputStream())
            val remoteOutput = socket.getOutputStream()
            if (config.scheme == ProxyScheme.SOCKS5) socksConnect(remoteInput, remoteOutput, destination)
            if (connect) {
                if (config.scheme == ProxyScheme.HTTP) {
                    writeHead(remoteOutput, "CONNECT ${destination.authority} HTTP/1.1", listOf(
                        "Host" to destination.authority,
                        "Proxy-Authorization" to basicCredential(),
                    ))
                    val reply = readFinalHead(remoteInput)
                    val code = status(reply.first)
                    if (code == 407) throw Failure("代理认证失败，请检查账号密码（HTTP 仅支持 Basic）")
                    if (code !in 200..299) throw Failure("上游代理拒绝 CONNECT 连接")
                }
                committed = true
                writeHead(output, "HTTP/1.1 200 Connection Established", emptyList())
                tunnel(client, input, socket, remoteInput)
                return
            }
            val upgrade = request.values("upgrade").singleOrNull()?.equals("websocket", true) == true &&
                "upgrade" in connectionTokens
            val headers = cleanHeaders(request.headers, connectionTokens).toMutableList()
            headers.add("Host" to destination.authority)
            if (upgrade) {
                headers.add("Connection" to "Upgrade")
                headers.add("Upgrade" to "websocket")
            } else headers.add("Connection" to "close")
            if (config.scheme == ProxyScheme.HTTP) headers.add("Proxy-Authorization" to basicCredential())
            val target = if (config.scheme == ProxyScheme.HTTP) destination.absolute else destination.path
            writeHead(remoteOutput, "$method $target HTTP/1.1", headers)
            if (expect.isNotEmpty()) writeHead(output, "HTTP/1.1 100 Continue", emptyList())
            copyBody(input, remoteOutput, framing, requestBody = true)
            remoteOutput.flush()
            val response = readFinalHead(remoteInput)
            val code = status(response.first)
            if (code == 407) throw Failure("代理认证失败，请检查账号密码（HTTP 仅支持 Basic）")
            val responseFraming = framing(response.headers)
            val responseTokens = response.values("connection").flatMap { it.split(',') }.map { it.trim().lowercase(Locale.ROOT) }
            checkRequest(responseTokens.none { it in setOf("content-length", "transfer-encoding") })
            val responseHeaders = cleanHeaders(response.headers, responseTokens, keepHost = true).toMutableList()
            if (code == 101) {
                checkRequest(upgrade && response.values("upgrade").singleOrNull()?.equals("websocket", true) == true)
                responseHeaders.add("Connection" to "Upgrade")
                responseHeaders.add("Upgrade" to "websocket")
            } else responseHeaders.add("Connection" to "close")
            committed = true
            writeHead(output, response.first, responseHeaders)
            if (code == 101) tunnel(client, input, socket, remoteInput)
            else if (method != "HEAD" && code != 204 && code != 304) {
                copyBody(remoteInput, output, responseFraming, requestBody = false)
            }
            output.flush()
        } catch (error: Exception) {
            if (!stopped.get()) {
                val text = when (error) {
                    is Failure -> error.safeMessage
                    is SocketTimeoutException -> "代理连接或传输超时，未回退直连"
                    else -> "代理连接中断或不可用，未回退直连"
                }
                lastError = text
                if (!committed) runCatching {
                    writeHead(client.getOutputStream(), "HTTP/1.1 ${if (error is BadRequest) "400 Bad Request" else "502 Bad Gateway"}",
                        listOf("Content-Length" to "0", "Connection" to "close"))
                }
            }
        } finally {
            deadline.cancel(false)
            upstream?.let(::forget)
            forget(client)
        }
    }

    private fun openUpstream(): Socket {
        if (stopped.get()) throw IOException()
        val socket = Socket()
        sockets.add(socket)
        try {
            if (stopped.get()) throw IOException()
            // The ONLY TCP destination opened here is the configured upstream proxy.
            socket.connect(InetSocketAddress(config.host.removeSurrounding("[", "]"), config.port), timeoutMillis)
            socket.soTimeout = timeoutMillis
            socket.tcpNoDelay = true
            return socket
        } catch (error: Exception) {
            forget(socket)
            throw error
        }
    }

    private fun basicCredential(): String = "Basic " + Base64.getEncoder().encodeToString(
        "${config.username}:${config.password}".toByteArray(StandardCharsets.ISO_8859_1)
    )

    private fun socksConnect(input: InputStream, output: OutputStream, target: Destination) {
        output.write(byteArrayOf(5, 1, 2)) // Only username/password, never silently downgrade to no-auth.
        output.flush()
        if (byte(input) != 5 || byte(input) != 2) throw Failure("SOCKS5 上游不接受用户名密码认证")
        val user = config.username.toByteArray(StandardCharsets.UTF_8)
        val pass = config.password.toByteArray(StandardCharsets.UTF_8)
        output.write(byteArrayOf(1, user.size.toByte()))
        output.write(user)
        output.write(pass.size)
        output.write(pass)
        output.flush()
        if (byte(input) != 1 || byte(input) != 0) throw Failure("SOCKS5 代理认证失败，请检查账号密码")
        output.write(byteArrayOf(5, 1, 0))
        if (target.host.contains(':')) {
            // Numeric IPv6 literal only. This does not perform a DNS lookup.
            val address = InetAddress.getByName(target.host).address
            checkRequest(address.size == 16)
            output.write(4)
            output.write(address)
        } else {
            val name = IDN.toASCII(target.host).toByteArray(StandardCharsets.US_ASCII)
            checkRequest(name.size in 1..255)
            output.write(3)
            output.write(name.size)
            output.write(name) // RFC1928 domain-name ATYP: upstream resolves even .invalid targets.
        }
        output.write(target.port ushr 8)
        output.write(target.port and 255)
        output.flush()
        if (byte(input) != 5 || byte(input) != 0 || byte(input) != 0) throw Failure("SOCKS5 代理无法连接目标网站")
        val length = when (byte(input)) {
            1 -> 4
            4 -> 16
            3 -> byte(input)
            else -> throw Failure("SOCKS5 上游返回无效响应")
        }
        repeat(length + 2) { byte(input) }
    }

    private fun tunnel(client: Socket, input: InputStream, remote: Socket, remoteInput: InputStream) {
        val upload = pumps.submit {
            try {
                input.copyTo(remote.getOutputStream())
                remote.shutdownOutput()
            } catch (_: Exception) {
                runCatching { remote.close() }
                runCatching { client.close() }
            }
        }
        try {
            remoteInput.copyTo(client.getOutputStream())
            runCatching { client.shutdownOutput() }
        } finally {
            runCatching { remote.close() }
            runCatching { client.close() }
            upload.cancel(true)
        }
    }

    private data class Destination(val host: String, val port: Int, val path: String) {
        val authority: String get() = (if (host.contains(':')) "[$host]" else host) + ":$port"
        val absolute: String get() = "http://$authority$path"
    }

    private fun destination(value: String, connect: Boolean, defaultPort: Int = -1): Destination {
        val uri = try { URI(if (connect) "http://$value" else value) } catch (_: Exception) { throw BadRequest() }
        checkRequest(uri.rawUserInfo == null && uri.rawFragment == null && uri.host != null)
        checkRequest(uri.scheme.equals("http", true) || !connect && uri.scheme.equals("ws", true))
        val host = uri.host.removeSurrounding("[", "]")
        checkRequest(host.isNotBlank() && !host.contains('%'))
        val port = if (uri.port != -1) uri.port else if (connect) defaultPort else 80
        checkRequest(port in 1..65535)
        if (connect) checkRequest(uri.rawPath.isNullOrEmpty() && uri.rawQuery == null)
        val path = uri.rawPath.takeUnless { it.isNullOrEmpty() } ?: "/"
        return Destination(host, port, path + (uri.rawQuery?.let { "?$it" } ?: ""))
    }

    private data class Head(val first: String, val headers: List<Pair<String, String>>) {
        fun values(name: String) = headers.filter { it.first.equals(name, true) }.map { it.second }
    }
    private data class Framing(val length: Long?, val chunked: Boolean)

    private fun readHead(input: InputStream): Head {
        val budget = intArrayOf(MAX_HEADER)
        val first = line(input, budget)
        val headers = mutableListOf<Pair<String, String>>()
        while (true) {
            val row = line(input, budget)
            if (row.isEmpty()) break
            checkRequest(headers.size < 128 && !row.startsWith(' ') && !row.startsWith('\t'))
            val colon = row.indexOf(':')
            checkRequest(colon > 0 && TOKEN.matches(row.substring(0, colon)))
            headers.add(row.substring(0, colon) to row.substring(colon + 1).trim())
        }
        return Head(first, headers)
    }

    private fun line(input: InputStream, budget: IntArray): String {
        val buffer = java.io.ByteArrayOutputStream()
        while (true) {
            checkRequest(--budget[0] >= 0 && buffer.size() <= 8192)
            val value = byte(input)
            if (value == 13) {
                checkRequest(--budget[0] >= 0 && byte(input) == 10)
                return buffer.toString("ISO-8859-1")
            }
            checkRequest(value != 10 && (value >= 32 && value != 127 || value == 9))
            buffer.write(value)
        }
    }

    private fun readFinalHead(input: InputStream): Head {
        repeat(8) {
            val head = readHead(input)
            val code = status(head.first)
            if (code >= 200 || code == 101) return head
        }
        throw Failure("上游代理响应无效")
    }

    private fun status(first: String): Int {
        val parts = first.split(' ', limit = 3)
        checkRequest(parts.size >= 2 && parts[0] in listOf("HTTP/1.0", "HTTP/1.1"))
        val code = parts[1].toIntOrNull() ?: throw BadRequest()
        checkRequest(code in 100..599)
        return code
    }

    private fun framing(headers: List<Pair<String, String>>): Framing {
        val lengths = headers.filter { it.first.equals("content-length", true) }.map { it.second }
        val encodings = headers.filter { it.first.equals("transfer-encoding", true) }.map { it.second }
        checkRequest(lengths.size <= 1 && encodings.size <= 1 && (lengths.isEmpty() || encodings.isEmpty()))
        val length = lengths.singleOrNull()?.let {
            checkRequest(it.isNotEmpty() && it.all(Char::isDigit))
            it.toLongOrNull()?.also { n -> checkRequest(n in 0..MAX_BODY) } ?: throw BadRequest()
        }
        checkRequest(encodings.isEmpty() || encodings.single().equals("chunked", true))
        return Framing(length, encodings.isNotEmpty())
    }

    private fun cleanHeaders(headers: List<Pair<String, String>>, connection: List<String>, keepHost: Boolean = false) =
        headers.filterNot { (name, _) ->
            val key = name.lowercase(Locale.ROOT)
            key.startsWith("proxy-") || key == "proxy" || key in connection ||
                key in setOf("connection", "keep-alive", "te", "trailer", "upgrade", "expect") || (!keepHost && key == "host")
        }

    private fun writeHead(output: OutputStream, first: String, headers: List<Pair<String, String>>) {
        val text = buildString {
            append(first).append("\r\n")
            headers.forEach { (key, value) -> append(key).append(": ").append(value).append("\r\n") }
            append("\r\n")
        }
        output.write(text.toByteArray(StandardCharsets.ISO_8859_1))
        output.flush()
    }

    private fun copyBody(input: InputStream, output: OutputStream, framing: Framing, requestBody: Boolean) {
        if (framing.chunked) {
            var total = 0L
            val overhead = intArrayOf(MAX_HEADER)
            while (true) {
                val row = line(input, overhead)
                val sizeText = row.substringBefore(';')
                checkRequest(sizeText.isNotEmpty() && sizeText.length <= 16 && sizeText.all { it in "0123456789abcdefABCDEF" })
                val size = sizeText.toLongOrNull(16) ?: throw BadRequest()
                checkRequest(size >= 0 && size <= MAX_BODY - total)
                total += size
                if (size == 0L) {
                    // Strip trailers altogether: they must not smuggle proxy authentication or framing.
                    while (line(input, overhead).isNotEmpty()) { /* bounded trailer section */ }
                    output.write("0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                    return
                }
                output.write("${size.toString(16)}\r\n".toByteArray(StandardCharsets.US_ASCII))
                copyExact(input, output, size)
                checkRequest(byte(input) == 13 && byte(input) == 10)
                output.write(byteArrayOf(13, 10))
                output.flush()
            }
        } else if (framing.length != null) copyExact(input, output, framing.length)
        else if (!requestBody) {
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                total += count
                checkRequest(total <= MAX_BODY)
                output.write(buffer, 0, count)
            }
        }
    }

    private fun copyExact(input: InputStream, output: OutputStream, length: Long) {
        var left = length
        val buffer = ByteArray(8192)
        while (left > 0) {
            val count = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            if (count == -1) throw EOFException()
            output.write(buffer, 0, count)
            left -= count
        }
    }

    private fun byte(input: InputStream): Int = input.read().also { if (it == -1) throw EOFException() }
    private fun checkRequest(condition: Boolean) { if (!condition) throw BadRequest() }
    private open class Failure(val safeMessage: String) : IOException(safeMessage)
    private class BadRequest : Failure("登录代理收到无效或超限的 HTTP 数据")
    private fun forget(socket: Socket) { sockets.remove(socket); runCatching { socket.close() } }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        runCatching { server.close() }
        // ConcurrentHashMap 弱一致遍历；不能调用 Kotlin toList()，size==1 快路径
        // 与 worker 并发移除可能产生 NoSuchElementException，跳过后续资源清理。
        sockets.forEach(::forget)
        workers.shutdownNow()
        pumps.shutdownNow()
        deadlines.shutdownNow()
        // Android/Linux may defer the listening FD close while accept() is unwinding.
        // Wait for that one thread so a subsequent session cannot reuse a still-listening relay.
        if (Thread.currentThread() !== acceptor) {
            try { acceptor.join(1000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        sockets.forEach(::forget) // 回收刚从 accept 返回、但在首轮遍历之后登记的连接。
    }

    companion object {
        private const val MAX_HEADER = 32768
        private const val MAX_BODY = 32L * 1024 * 1024
        private val TOKEN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    }
}
