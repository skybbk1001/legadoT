package io.legado.app.help.http

import io.legado.app.constant.AppConst
import io.legado.app.help.CacheManager
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.progress.ProgressManager.LISTENER
import io.legado.app.help.glide.progress.ProgressResponseBody
import io.legado.app.help.http.CookieManager.cookieJarHeader
import io.legado.app.model.ReadManga
import io.legado.app.utils.NetworkUtils
import okhttp3.ConnectionSpec
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

private val proxyClientCache: ConcurrentHashMap<String, OkHttpClient> by lazy {
    ConcurrentHashMap()
}

private data class Socks5Auth(
    val username: String,
    val password: String
)

private class Socks5TunnelSocketFactory(
    private val proxyHost: String,
    private val proxyPort: Int,
    private val auth: Socks5Auth?
) : SocketFactory() {

    override fun createSocket(): Socket {
        return Socks5TunnelSocket(proxyHost, proxyPort, auth)
    }

    override fun createSocket(host: String?, port: Int): Socket {
        return createSocket().apply { connect(InetSocketAddress(host, port)) }
    }

    override fun createSocket(host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int): Socket {
        return createSocket().apply {
            if (localHost != null) {
                bind(InetSocketAddress(localHost, localPort))
            }
            connect(InetSocketAddress(host, port))
        }
    }

    override fun createSocket(host: java.net.InetAddress?, port: Int): Socket {
        return createSocket().apply { connect(InetSocketAddress(host, port)) }
    }

    override fun createSocket(
        address: java.net.InetAddress?,
        port: Int,
        localAddress: java.net.InetAddress?,
        localPort: Int
    ): Socket {
        return createSocket().apply {
            if (localAddress != null) {
                bind(InetSocketAddress(localAddress, localPort))
            }
            connect(InetSocketAddress(address, port))
        }
    }
}

private class Socks5TunnelSocket(
    proxyHost: String,
    proxyPort: Int,
    private val auth: Socks5Auth?
) : Socket() {

    private val proxyAddress = InetSocketAddress(proxyHost, proxyPort)
    private val delegate = Socket()
    @Volatile
    private var connected = false
    @Volatile
    private var closed = false

    override fun connect(endpoint: SocketAddress?) {
        connect(endpoint, 0)
    }

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        if (closed) throw SocketException("Socket is closed")
        if (connected) throw SocketException("Socket is already connected")
        val target = endpoint as? InetSocketAddress
            ?: throw SocketException("Unsupported endpoint: $endpoint")
        delegate.connect(proxyAddress, timeout)
        val originalTimeout = delegate.soTimeout
        val handshakeTimeout = if (timeout > 0) timeout else 15000
        if (handshakeTimeout > 0) {
            delegate.soTimeout = handshakeTimeout
        }
        try {
            Socks5Protocol.connect(delegate, target, auth)
            connected = true
        } catch (e: IOException) {
            runCatching { delegate.close() }
            throw e
        } finally {
            runCatching { delegate.soTimeout = originalTimeout }
        }
    }

    override fun bind(bindpoint: SocketAddress?) {
        delegate.bind(bindpoint)
    }

    override fun getInputStream() = delegate.getInputStream()

    override fun getOutputStream() = delegate.getOutputStream()

    override fun setSoTimeout(timeout: Int) {
        delegate.soTimeout = timeout
    }

    override fun getSoTimeout(): Int = delegate.soTimeout

    override fun setTcpNoDelay(on: Boolean) {
        delegate.tcpNoDelay = on
    }

    override fun getTcpNoDelay(): Boolean = delegate.tcpNoDelay

    override fun setKeepAlive(on: Boolean) {
        delegate.keepAlive = on
    }

    override fun getKeepAlive(): Boolean = delegate.keepAlive

    override fun setReuseAddress(on: Boolean) {
        delegate.reuseAddress = on
    }

    override fun getReuseAddress(): Boolean = delegate.reuseAddress

    override fun shutdownInput() {
        delegate.shutdownInput()
    }

    override fun shutdownOutput() {
        delegate.shutdownOutput()
    }

    override fun close() {
        closed = true
        delegate.close()
    }

    override fun isConnected(): Boolean = connected && delegate.isConnected

    override fun isClosed(): Boolean = closed || delegate.isClosed

    override fun isBound(): Boolean = delegate.isBound

    override fun isInputShutdown(): Boolean = delegate.isInputShutdown

    override fun isOutputShutdown(): Boolean = delegate.isOutputShutdown

    override fun getInetAddress() = delegate.inetAddress

    override fun getLocalAddress() = delegate.localAddress

    override fun getPort(): Int = delegate.port

    override fun getLocalPort(): Int = delegate.localPort

    override fun getRemoteSocketAddress(): SocketAddress? = delegate.remoteSocketAddress

    override fun getLocalSocketAddress(): SocketAddress? = delegate.localSocketAddress
}

private object Socks5Protocol {
    private const val VERSION = 0x05
    private const val METHOD_NO_AUTH = 0x00
    private const val METHOD_USER_PASS = 0x02

    private const val CMD_CONNECT = 0x01

    private const val ATYP_IPV4 = 0x01
    private const val ATYP_DOMAIN = 0x03
    private const val ATYP_IPV6 = 0x04

    fun connect(socket: Socket, target: InetSocketAddress, auth: Socks5Auth?) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        negotiateMethod(input, output, auth)
        sendConnect(input, output, target)
    }

    private fun negotiateMethod(input: InputStream, output: java.io.OutputStream, auth: Socks5Auth?) {
        val methods = if (auth == null) {
            byteArrayOf(METHOD_NO_AUTH.toByte())
        } else {
            byteArrayOf(METHOD_USER_PASS.toByte())
        }
        output.write(byteArrayOf(VERSION.toByte(), methods.size.toByte()))
        output.write(methods)
        output.flush()

        val serverChoice = readFully(input, 2)
        if (serverChoice[0].toInt() != VERSION) {
            throw IOException("SOCKS5 握手失败: 非法版本")
        }
        when (serverChoice[1].toInt() and 0xFF) {
            METHOD_NO_AUTH -> {
                if (auth != null) {
                    throw IOException("SOCKS5 代理未按要求使用用户名密码认证")
                }
                return
            }
            METHOD_USER_PASS -> {
                if (auth == null) {
                    throw IOException("SOCKS5 代理要求认证")
                }
                authUserPass(input, output, auth)
            }

            0xFF -> throw IOException("SOCKS5 代理不接受认证方式")
            else -> throw IOException("SOCKS5 代理返回不支持的认证方式")
        }
    }

    private fun authUserPass(input: InputStream, output: java.io.OutputStream, auth: Socks5Auth) {
        val usernameBytes = auth.username.toByteArray(Charsets.UTF_8)
        val passwordBytes = auth.password.toByteArray(Charsets.UTF_8)
        if (usernameBytes.isEmpty() || usernameBytes.size > 255 || passwordBytes.isEmpty() || passwordBytes.size > 255) {
            throw IOException("$PROXY_CONFIG_ERROR: SOCKS5 认证用户名/密码长度必须在1..255字节")
        }

        output.write(byteArrayOf(0x01, usernameBytes.size.toByte()))
        output.write(usernameBytes)
        output.write(byteArrayOf(passwordBytes.size.toByte()))
        output.write(passwordBytes)
        output.flush()

        val authResp = readFully(input, 2)
        if (authResp[1].toInt() != 0x00) {
            throw IOException("SOCKS : authentication failed")
        }
    }

    private fun sendConnect(input: InputStream, output: java.io.OutputStream, target: InetSocketAddress) {
        val host = target.hostString ?: target.address?.hostAddress
            ?: throw IOException("SOCKS5 目标主机为空")
        val port = target.port
        if (port !in 1..65535) {
            throw IOException("SOCKS5 目标端口非法: $port")
        }

        val req = java.io.ByteArrayOutputStream()
        req.write(VERSION)
        req.write(CMD_CONNECT)
        req.write(0x00)
        val targetAddress = target.address
        when (targetAddress) {
            is Inet4Address -> {
                req.write(ATYP_IPV4)
                req.write(targetAddress.address)
            }

            is Inet6Address -> {
                req.write(ATYP_IPV6)
                req.write(targetAddress.address)
            }

            else -> {
                val hostBytes = host.toByteArray(Charsets.UTF_8)
                if (hostBytes.isEmpty() || hostBytes.size > 255) {
                    throw IOException("SOCKS5 域名长度非法")
                }
                req.write(ATYP_DOMAIN)
                req.write(hostBytes.size)
                req.write(hostBytes)
            }
        }
        req.write((port shr 8) and 0xFF)
        req.write(port and 0xFF)
        output.write(req.toByteArray())
        output.flush()

        val respHead = readFully(input, 4)
        if (respHead[0].toInt() != VERSION) {
            throw IOException("SOCKS5 CONNECT 失败: 非法版本")
        }
        val rep = respHead[1].toInt() and 0xFF
        if (rep != 0x00) {
            throw IOException("SOCKS5 CONNECT 失败: ${replyMessage(rep)}")
        }
        val atyp = respHead[3].toInt() and 0xFF
        when (atyp) {
            ATYP_IPV4 -> readFully(input, 4)
            ATYP_IPV6 -> readFully(input, 16)
            ATYP_DOMAIN -> {
                val len = readFully(input, 1)[0].toInt() and 0xFF
                readFully(input, len)
            }

            else -> throw IOException("SOCKS5 CONNECT 失败: 非法地址类型")
        }
        readFully(input, 2)
    }

    private fun readFully(input: InputStream, length: Int): ByteArray {
        val out = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(out, offset, length - offset)
            if (count < 0) throw EOFException("SOCKS5 握手响应不完整")
            offset += count
        }
        return out
    }

    private fun replyMessage(rep: Int): String {
        return when (rep) {
            0x01 -> "general SOCKS server failure"
            0x02 -> "connection not allowed by ruleset"
            0x03 -> "network unreachable"
            0x04 -> "host unreachable"
            0x05 -> "connection refused"
            0x06 -> "TTL expired"
            0x07 -> "command not supported"
            0x08 -> "address type not supported"
            else -> "unknown error($rep)"
        }
    }
}

val cookieJar by lazy {
    object : CookieJar {

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return emptyList()
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            //临时保存 书源启用cookie选项再添加到数据库
            val cookieBuilder = StringBuilder()
            cookies.forEachIndexed { index, cookie ->
                if (index > 0) cookieBuilder.append(";")
                cookieBuilder.append(cookie.name).append('=').append(cookie.value)
            }
            val domain = NetworkUtils.getSubDomain(url.toString())
            CacheManager.putMemory("${domain}_cookieJar", cookieBuilder.toString())
        }

    }
}

val okHttpClient: OkHttpClient by lazy {
    val specs = arrayListOf(
        ConnectionSpec.MODERN_TLS,
        ConnectionSpec.COMPATIBLE_TLS,
        ConnectionSpec.CLEARTEXT
    )

    val builder = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        //.cookieJar(cookieJar = cookieJar)
        .sslSocketFactory(SSLHelper.unsafeSSLSocketFactory, SSLHelper.unsafeTrustManager)
        .retryOnConnectionFailure(true)
        .hostnameVerifier(SSLHelper.unsafeHostnameVerifier)
        .connectionSpecs(specs)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor(OkHttpExceptionInterceptor)
        .addInterceptor { chain ->
            val request = chain.request()
            val builder = request.newBuilder()
            if (request.header(AppConst.UA_NAME) == null) {
                builder.addHeader(AppConst.UA_NAME, AppConfig.userAgent)
            } else if (request.header(AppConst.UA_NAME) == "null") {
                builder.removeHeader(AppConst.UA_NAME)
            }
            builder.addHeader("Keep-Alive", "300")
            builder.addHeader("Connection", "Keep-Alive")
            builder.addHeader("Cache-Control", "no-cache")
            chain.proceed(builder.build())
        }
        .addNetworkInterceptor { chain ->
            var request = chain.request()
            val requestTag = request.tag(CookieJarRequestTag::class.java)
            val hasCookieJarHeader = request.header(cookieJarHeader) != null
            val taskScope = request.header(CookieManager.taskCookieScopeHeader) ?: requestTag?.taskScope
            val enableCookieJar = hasCookieJarHeader || taskScope != null || requestTag != null
            if (enableCookieJar) {
                request = request.newBuilder()
                    .tag(
                        CookieJarRequestTag::class.java,
                        requestTag?.copy(taskScope = taskScope)
                            ?: CookieJarRequestTag(taskScope)
                    )
                    .build()
                request = CookieManager.loadRequest(request)
            } else if (request.header(CookieManager.taskCookieScopeHeader) != null) {
                request = request.newBuilder()
                    .removeHeader(cookieJarHeader)
                    .removeHeader(CookieManager.taskCookieScopeHeader)
                    .build()
            }

            val networkResponse = chain.proceed(request)

            if (enableCookieJar) {
                CookieManager.saveResponse(networkResponse)
            }
            networkResponse
        }
    if (AppConfig.isCronet) {
        if (Cronet.loader?.install() == true) {
            Cronet.interceptor?.let {
                builder.addInterceptor(it)
            }
        }
    }
    builder.addInterceptor(HttpLogInterceptor)
    builder.addInterceptor(DecompressInterceptor)
    builder.build().apply {
        val okHttpName =
            OkHttpClient::class.java.name.removePrefix("okhttp3.").removeSuffix("Client")
        val executor = dispatcher.executorService as ThreadPoolExecutor
        val threadName = "$okHttpName Dispatcher"
        executor.threadFactory = ThreadFactory { runnable ->
            Thread(runnable, threadName).apply {
                isDaemon = false
                uncaughtExceptionHandler = OkhttpUncaughtExceptionHandler
            }
        }
    }
}

val okHttpClientManga by lazy {
    okHttpClient.newBuilder().run {
        val interceptors = interceptors()
        interceptors.add(1) { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            val url = request.url.toString()
            response.newBuilder()
                .body(ProgressResponseBody(url, LISTENER, response.body))
                .build()
        }
        interceptors.add(1) { chain ->
            ReadManga.rateLimiter.withLimitBlocking {
                chain.proceed(chain.request())
            }
        }
        build()
    }
}

/**
 * 缓存代理okHttp
 */
fun getProxyClient(proxy: String? = null): OkHttpClient {
    val proxyValue = proxy?.trim()
    if (proxyValue.isNullOrBlank()) {
        return okHttpClient
    }
    proxyClientCache[proxyValue]?.let {
        return it
    }
    val config = parseProxyConfig(proxyValue)
    val builder = okHttpClient.newBuilder()
    when (config.scheme) {
        ProxyScheme.HTTP -> {
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(config.host, config.port)))
            if (config.username.isNotEmpty()) {
                builder.proxyAuthenticator { _, response ->
                    val credential = Credentials.basic(config.username, config.password)
                    response.request.newBuilder().header("Proxy-Authorization", credential).build()
                }
            }
        }

        ProxyScheme.SOCKS4 -> {
            builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(config.host, config.port)))
        }

        ProxyScheme.SOCKS5 -> {
            val auth = if (config.username.isNotEmpty()) {
                Socks5Auth(config.username, config.password)
            } else {
                null
            }
            builder.proxy(Proxy.NO_PROXY)
            builder.socketFactory(Socks5TunnelSocketFactory(config.host, config.port, auth))
        }
    }
    val proxyClient = builder.build()
    proxyClientCache[proxyValue] = proxyClient
    return proxyClient
}
