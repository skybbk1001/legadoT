package io.legado.app.help.nostr

import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 中继健康检查。
 *
 * 1. NIP-11：向 relay 的 https 地址发 GET 并带 `Accept: application/nostr+json`，
 *    拿到 relay 信息文档，据此过滤掉要求鉴权、付费或禁止写入的中继。
 * 2. WebSocket 探活：真正连一次并订阅，收到 `EOSE` 才算读通道可用。
 *
 * 只有两步都通过的中继才会被缓存。
 */
object NostrRelayHealth {

    /** NIP-11 relay information document 中我们关心的字段。 */
    data class RelayInfo(
        val name: String?,
        val software: String?,
        val supportedNips: Set<Int>,
        val authRequired: Boolean,
        val paymentRequired: Boolean,
        val restrictedWrites: Boolean
    ) {
        /**
         * 本客户端未实现 NIP-42 鉴权，也不支持付费中继；
         * 同时段评/NIP-46 都需要写入能力，因此这三项任一为真即视为不兼容。
         */
        val compatible: Boolean
            get() = !authRequired && !paymentRequired && !restrictedWrites

        val incompatibleReason: String?
            get() = when {
                authRequired -> "auth required"
                paymentRequired -> "payment required"
                restrictedWrites -> "writes restricted"
                else -> null
            }
    }

    /**
     * 读取 NIP-11 文档。部分中继未实现 NIP-11 或会被 CDN 拦截，
     * 此时返回 null，是否可用交给 WebSocket 探活判断。
     */
    suspend fun fetchInfo(relay: String): RelayInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val response = okHttpClient.newCallStrResponse {
                url(relay.toHttpSchemeUrl())
                header("Accept", "application/nostr+json")
            }
            if (!response.isSuccessful()) return@runCatching null
            val body = response.body?.takeIf { it.isNotBlank() } ?: return@runCatching null
            val json = JSONObject(body)
            val limitation = json.optJSONObject("limitation")
            RelayInfo(
                name = json.optString("name").takeIf { it.isNotBlank() },
                software = json.optString("software").takeIf { it.isNotBlank() },
                supportedNips = json.optJSONArray("supported_nips")?.let { nips ->
                    (0 until nips.length()).mapNotNull { index ->
                        nips.optInt(index, -1).takeIf { it > 0 }
                    }
                }.orEmpty().toSet(),
                authRequired = limitation?.optBoolean("auth_required") == true,
                paymentRequired = limitation?.optBoolean("payment_required") == true,
                restrictedWrites = limitation?.optBoolean("restricted_writes") == true
            )
        }.getOrNull()
    }

    /** 连一次 WebSocket 并订阅，收到 EOSE 视为可用。 */
    suspend fun checkWebSocket(relay: String, timeoutMillis: Long = 10_000): Boolean =
        runCatching {
            withTimeout(timeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    val subscriptionId = UUID.randomUUID().toString().replace("-", "")
                    val finished = AtomicBoolean(false)
                    lateinit var socket: WebSocket
                    socket = nostrWebSocketClient.newWebSocket(
                        Request.Builder().url(relay).build(),
                        object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                // 只取一条最新事件，EOSE 就足以证明读通道可用。
                                webSocket.send(
                                    JSONArray().put("REQ").put(subscriptionId)
                                        .put(JSONObject().put("limit", 1)).toString()
                                )
                            }

                            override fun onMessage(webSocket: WebSocket, text: String) {
                                val type = runCatching { JSONArray(text).optString(0) }.getOrNull()
                                    ?: return
                                if (type != "EOSE" && type != "CLOSED" && type != "NOTICE") return
                                if (finished.compareAndSet(false, true)) {
                                    webSocket.close(1000, "health check done")
                                    continuation.resume(type == "EOSE")
                                }
                            }

                            override fun onFailure(
                                webSocket: WebSocket,
                                t: Throwable,
                                response: Response?
                            ) {
                                if (finished.compareAndSet(false, true)) {
                                    continuation.resumeWithException(t)
                                }
                            }

                            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                                if (finished.compareAndSet(false, true)) {
                                    continuation.resumeWithException(
                                        IllegalStateException("closed before EOSE: $code $reason")
                                    )
                                }
                            }
                        }
                    )
                    continuation.invokeOnCancellation {
                        if (finished.compareAndSet(false, true)) socket.cancel()
                    }
                }
            }
        }.getOrDefault(false)
}

/** `wss://host` → `https://host`，供 NIP-11 文档请求使用。 */
internal fun String.toHttpSchemeUrl(): String = when {
    startsWith("wss://", ignoreCase = true) -> "https://" + substring(6)
    startsWith("ws://", ignoreCase = true) -> "http://" + substring(5)
    else -> this
}

internal fun relayHost(relay: String): String =
    runCatching { java.net.URI(relay).host }.getOrNull() ?: relay
