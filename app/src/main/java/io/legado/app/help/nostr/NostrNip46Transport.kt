package io.legado.app.help.nostr

import io.legado.app.constant.AppLog
import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal val nostrWebSocketClient by lazy {
    okHttpClient.newBuilder().apply {
        interceptors().clear()
        networkInterceptors().clear()
        // Relay 常在空闲时主动断开连接，默认 OkHttp 不发心跳。
        // NIP-46 等待用户在签名器上确认可能超过一分钟，需要 ping 保活。
        pingInterval(20, TimeUnit.SECONDS)
    }.build()
}

/** 单个中继的发布结果：accepted=true 时 reason 为 null；否则 reason 为拒绝或失败原因。 */
internal data class RelayPublishResult(val relay: String, val accepted: Boolean, val reason: String?)

/** 一次发布的整体结果：被接受的中继集合 + 每个中继的结构化结果（顺序与发布目标一致）。 */
internal data class PublishOutcome(
    val acceptedRelays: Set<String>,
    val results: List<RelayPublishResult>
)

object NostrNip46Transport {

    private const val NIP46_REQUEST_TIMEOUT_MILLIS = 65_000L
    /** 传输层失败重试时额外给的宽限（与 Amethyst 一致）。 */
    private const val RELAY_RETRY_GRACE_MILLIS = 5_000L
    private const val NIP46_MAX_RETRIES = 1
    private const val NIP46_RETRY_DELAY_MILLIS = 2_000L

    /** 薄封装：只关心「是否至少有一个中继接受」。 */
    suspend fun publish(relays: List<String>, event: JSONObject, timeoutMillis: Long = 15_000) {
        publishAndCollect(relays, event, timeoutMillis)
    }

    /**
     * 向各中继发布事件，返回被接受（返回 OK=true）的中继集合，以及每个中继的结构化结果。
     * 语义与 [publish] 一致：失败中继忽略，有任一接受即算成功，全部失败才抛异常。
     * 传输层失败（未连上/掉线/静默/超时）会对该中继多给 [RELAY_RETRY_GRACE_MILLIS] 宽限重试一次；
     * 中继明确回包拒绝（["OK", id, false, ...]）不重试。
     */
    internal suspend fun publishAndCollect(
        relays: List<String>,
        event: JSONObject,
        timeoutMillis: Long = 15_000
    ): PublishOutcome {
        val validRelays = relays.distinct().filter { relay -> relay.startsWith("wss://", true) }
        require(validRelays.isNotEmpty()) { "No valid Relay available for publishing" }
        val eventId = event.optString("id").takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Cannot publish an unsigned event")
        val results = coroutineScope {
            validRelays.map { relay ->
                async(Dispatchers.IO) { publishToRelayWithRetry(relay, event, eventId, timeoutMillis) }
            }.awaitAll()
        }
        val accepted = results.filter { it.accepted }
            .map { it.relay }
            .toSet()
        // 语义不变：有任一接受即算成功，全部不接受才抛。
        if (accepted.isEmpty()) {
            val relayErrors = results.associate { it.relay to it.reasonOrDefault() }
            throw NostrRelayException(
                relayFailureMessage("No Relay accepted the Nostr event", validRelays, relayErrors)
            )
        }
        return PublishOutcome(accepted, results)
    }

    /**
     * 单中继发布（含一次传输层失败重试）。返回结构化结果，不抛（协程取消除外）。
     * 「中继明确拒绝」与「传输层没回应」在重试策略上区分：前者不重试，后者重试一次。
     */
    private suspend fun publishToRelayWithRetry(
        relay: String,
        event: JSONObject,
        eventId: String,
        timeoutMillis: Long
    ): RelayPublishResult {
        val first = runCatching { publishToRelay(relay, event, eventId, timeoutMillis) }
        first.getOrNull()?.let { return RelayPublishResult(relay, accepted = true, reason = null) }
        val failure = first.exceptionOrNull()!!
        // runCatching 连协程取消一起吞掉：真正的取消必须原样重抛（TimeoutCancellationException 是本次超时，不算）。
        failure.rethrowIfCancelled()
        // 中继明确回包拒绝：这是它的真实答复，不重试。
        if (failure is RelayRejectedException) {
            return RelayPublishResult(relay, accepted = false, reason = failure.reason())
        }
        AppLog.put(
            "Nostr publish relay ${relayHost(relay)} 传输层失败，重试一次(+${RELAY_RETRY_GRACE_MILLIS}ms): " +
                failure.publishFailureReason(timeoutMillis),
            tag = "Nostr"
        )
        val retryTimeout = timeoutMillis + RELAY_RETRY_GRACE_MILLIS
        val retry = runCatching { publishToRelay(relay, event, eventId, retryTimeout) }
        retry.getOrNull()?.let { return RelayPublishResult(relay, accepted = true, reason = null) }
        val retryFailure = retry.exceptionOrNull()!!
        retryFailure.rethrowIfCancelled()
        val reason = if (retryFailure is RelayRejectedException) {
            retryFailure.reason()
        } else {
            retryFailure.publishFailureReason(retryTimeout)
        }
        return RelayPublishResult(relay, accepted = false, reason = reason)
    }

    private fun RelayPublishResult.reasonOrDefault(): String = reason ?: "no response"

    /** 只重抛真正的协程取消：超时（TimeoutCancellationException）属于失败，不能当取消处理。 */
    private fun Throwable.rethrowIfCancelled() {
        if (this is CancellationException && this !is TimeoutCancellationException) throw this
    }

    private fun RelayRejectedException.reason(): String = message ?: "relay rejected the event"

    private fun Throwable.publishFailureReason(timeoutMillis: Long): String =
        if (this is TimeoutCancellationException) "timed out after ${timeoutMillis}ms"
        else localizedMessage ?: toString()

    private suspend fun publishToRelay(
        relay: String,
        event: JSONObject,
        eventId: String,
        timeoutMillis: Long
    ): Boolean = withTimeout(timeoutMillis) {
        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            lateinit var socket: WebSocket
            socket = nostrWebSocketClient.newWebSocket(
                Request.Builder().url(relay).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (!webSocket.send(JSONArray().put("EVENT").put(event).toString()) &&
                            completed.compareAndSet(false, true)
                        ) {
                            continuation.resumeWithException(
                                IllegalStateException("Unable to publish to $relay")
                            )
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        runCatching {
                            val message = JSONArray(text)
                            when (message.optString(0)) {
                                "OK" -> {
                                    if (message.optString(1) != eventId) return
                                    if (completed.compareAndSet(false, true)) {
                                        val accepted = message.optBoolean(2)
                                        webSocket.close(1000, "publish acknowledged")
                                        if (accepted) continuation.resume(true)
                                        else continuation.resumeWithException(
                                            RelayRejectedException(
                                                message.optString(3, "Relay rejected event")
                                            )
                                        )
                                    }
                                }
                                "NOTICE", "AUTH" -> AppLog.put(
                                    "Nostr publish relay ${relayHost(relay)} feedback: ${text.take(300)}",
                                    tag = "Nostr"
                                )
                                else -> Unit
                            }
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (completed.compareAndSet(false, true)) continuation.resumeWithException(t)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (completed.compareAndSet(false, true)) {
                            continuation.resumeWithException(
                                IllegalStateException("Relay closed before acknowledging event: $relay")
                            )
                        }
                    }
                }
            )
            continuation.invokeOnCancellation {
                if (completed.compareAndSet(false, true)) socket.cancel()
            }
        }
    }

    suspend fun awaitNostrConnect(
        relays: List<String>,
        keyPair: NostrNip46.ClientKeyPair,
        secret: String,
        timeoutMillis: Long = 120_000
    ): NostrNip46.NostrConnectMessage = withTimeout(timeoutMillis) {
        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            val failedRelays = AtomicInteger(0)
            val sockets = CopyOnWriteArrayList<WebSocket>()
            val validRelays = relays.distinct().filter { it.startsWith("wss://", true) }
            require(validRelays.isNotEmpty()) { "No valid Relay available for nostrconnect" }
            val relayErrors = ConcurrentHashMap<String, String>()

            fun closeSockets() {
                sockets.forEach { it.close(1000, "connection established") }
                sockets.clear()
            }

            validRelays.forEach { relay ->
                if (completed.get()) return@forEach
                val subscriptionId = UUID.randomUUID().toString().replace("-", "")
                val socket = nostrWebSocketClient.newWebSocket(
                    Request.Builder().url(relay).build(),
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            val filter = JSONObject()
                                .put("kinds", JSONArray().put(24133))
                                .put("#p", JSONArray().put(keyPair.publicKey))
                                .put("since", System.currentTimeMillis() / 1000 - 300)
                            if (!webSocket.send(
                                    JSONArray().put("REQ").put(subscriptionId).put(filter).toString()
                                )
                            ) {
                                relayErrors[relay] = "unable to subscribe"
                                if (failedRelays.incrementAndGet() == validRelays.size &&
                                    completed.compareAndSet(false, true)
                                ) {
                                    val message = relayFailureMessage(
                                        "Unable to subscribe on any nostrconnect relay",
                                        validRelays,
                                        relayErrors
                                    )
                                    continuation.resumeWithException(NostrRelayException(message))
                                }
                            }
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            if (completed.get()) return
                            runCatching {
                                val message = JSONArray(text)
                                if (message.optString(0) != "EVENT" ||
                                    message.optString(1) != subscriptionId
                                ) return
                                val event = message.optJSONObject(2) ?: return
                                val connectMessage = NostrNip46.decodeNostrConnectMessage(
                                    eventJson = event,
                                    keyPair = keyPair,
                                    expectedSecret = secret
                                )
                                if (completed.compareAndSet(false, true)) {
                                    closeSockets()
                                    continuation.resume(connectMessage)
                                }
                            }
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            if (completed.get()) return
                            AppLog.put("nostrconnect relay ${relayHost(relay)} failed", t, tag = "Nostr")
                            relayErrors[relay] = t.localizedMessage ?: t.toString()
                            if (failedRelays.incrementAndGet() == validRelays.size &&
                                completed.compareAndSet(false, true)
                            ) {
                                val message = relayFailureMessage(
                                    "All nostrconnect relays failed",
                                    validRelays,
                                    relayErrors
                                )
                                continuation.resumeWithException(NostrRelayException(message, t))
                            }
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            if (completed.get()) return
                            AppLog.put(
                                "nostrconnect relay ${relayHost(relay)} closed before approval: $code $reason",
                                tag = "Nostr"
                            )
                            relayErrors[relay] = "closed before approval: $code $reason"
                            if (failedRelays.incrementAndGet() == validRelays.size &&
                                completed.compareAndSet(false, true)
                            ) {
                                val message = relayFailureMessage(
                                    "All nostrconnect relays closed before approval",
                                    validRelays,
                                    relayErrors
                                )
                                continuation.resumeWithException(NostrRelayException(message))
                            }
                        }
                    }
                )
                sockets.add(socket)
                if (completed.get()) socket.close(1000, "connection already established")
            }

            continuation.invokeOnCancellation {
                if (completed.compareAndSet(false, true)) closeSockets()
            }
        }
    }

    /**
     * 发起一次 NIP-46 请求。超时会换新 request id 重试一次，重试耗尽才作为失败上报。
     * 只按 id 匹配回包：不是当前请求的回包会被丢弃并等下一次，不会串到本轮。
     */
    suspend fun request(
        connection: NostrNip46.BunkerConnection,
        keyPair: NostrNip46.ClientKeyPair,
        request: NostrNip46.RpcRequest,
        timeoutMillis: Long = NIP46_REQUEST_TIMEOUT_MILLIS,
        onAuthUrl: ((String) -> Unit)? = null
    ): NostrNip46.RpcResponse {
        val relays = connection.relays.distinct().filter { it.startsWith("wss://", true) }
        require(relays.isNotEmpty()) { "No valid Relay available for NIP-46 request" }
        var current = request
        for (attempt in 0..NIP46_MAX_RETRIES) {
            if (attempt > 0) {
                delay(NIP46_RETRY_DELAY_MILLIS)
                // 换新 request id 重发：上一轮迟到的回包 id 不同，会被当无关回包丢弃。
                current = NostrNip46.renewRequest(keyPair, connection.signerPubkey, current)
            }
            val outcome = runCatching {
                awaitResponse(keyPair, connection.signerPubkey, current, relays, timeoutMillis, onAuthUrl)
            }
            outcome.getOrNull()?.let { return it }
            val failure = outcome.exceptionOrNull()!!
            // runCatching 也会捕获协程取消：真正的取消必须原样重抛。
            if (failure is CancellationException && !failure.isNip46Timeout()) throw failure
            if (!failure.isNip46Timeout() || attempt == NIP46_MAX_RETRIES) throw failure
            AppLog.put(
                "NIP-46 request timed out after ${timeoutMillis}ms, retrying with a new request id (${attempt + 1}/$NIP46_MAX_RETRIES)",
                failure,
                tag = "Nostr"
            )
        }
        error("NIP-46 request ended without a response")
    }

    private fun Throwable.isNip46Timeout(): Boolean = this is TimeoutCancellationException ||
        (this is NostrRelayException && cause is TimeoutCancellationException)

    private suspend fun awaitResponse(
        keyPair: NostrNip46.ClientKeyPair,
        signerPubkey: String,
        request: NostrNip46.RpcRequest,
        relays: List<String>,
        timeoutMillis: Long,
        onAuthUrl: ((String) -> Unit)?
    ): NostrNip46.RpcResponse = withRelayErrorReporting(
        timeoutMillis = timeoutMillis,
        relays = relays,
        failureLabel = "NIP-46 request"
    ) { relays, relayErrors ->
        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            val failedRelays = AtomicInteger(0)
            val sockets = CopyOnWriteArrayList<WebSocket>()
            val expectedAuthor = signerPubkey
            val authUrlShown = AtomicBoolean(false)

            fun closeSockets(code: Int, reason: String) {
                sockets.forEach { it.close(code, reason) }
                sockets.clear()
            }

            // 记录每个 relay 的失败原因。原实现只在全部 relay 都失败时才抛出，
            // 一旦有 relay 连上却不回应，调用就会一直挂到 withTimeout，
            // 用户最终只看到没有任何信息量的 "Timed out waiting for 90000 ms"。
            fun failRelay(relay: String, error: Throwable) {
                relayErrors[relay] = error.localizedMessage ?: error.toString()
                if (failedRelays.incrementAndGet() == relays.size &&
                    completed.compareAndSet(false, true)
                ) {
                    val message = relayFailureMessage("All NIP-46 relays failed", relays, relayErrors)
                    AppLog.put(message, error, tag = "Nostr")
                    continuation.resumeWithException(NostrRelayException(message, error))
                }
            }

            relays.forEach { relay ->
                if (completed.get()) return@forEach
                val subscriptionId = UUID.randomUUID().toString().replace("-", "")
                val socket = nostrWebSocketClient.newWebSocket(
                    Request.Builder().url(relay).build(),
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            AppLog.put("NIP-46 relay ${relayHost(relay)} connected", tag = "Nostr")
                            // 只用 #p 过滤（与 Amethyst/quartz 一致）；不限定作者：解密 MAC 本身
                            // 就能挡住伪造，也兼容用其它密钥回包的非标准 bunker。
                            val filter = JSONObject()
                                .put("kinds", JSONArray().put(24133))
                                .put("#p", JSONArray().put(keyPair.publicKey))
                            if (!webSocket.send(
                                    JSONArray().put("REQ").put(subscriptionId).put(filter).toString()
                                ) || !webSocket.send(JSONArray().put("EVENT").put(request.event).toString())
                            ) {
                                failRelay(
                                    relay,
                                    IllegalStateException("Unable to send NIP-46 request to $relay")
                                )
                            }
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            if (completed.get()) return
                            val message = runCatching { JSONArray(text) }.getOrNull() ?: return
                            // relay 对订阅/发布的即时反馈(OK/NOTICE/AUTH/CLOSED)：以前被丢弃，
                            // 导致「中继拒绝事件」与「签名器没回应」都表现成「no response」，无法区分。
                            if (message.optString(0) != "EVENT") {
                                AppLog.put(
                                    "NIP-46 relay ${relayHost(relay)} feedback: ${text.take(400)}",
                                    tag = "Nostr"
                                )
                                when (message.optString(0)) {
                                    "AUTH" -> failRelay(
                                        relay,
                                        IllegalStateException("relay requires NIP-42 auth")
                                    )
                                    "CLOSED" -> if (message.optString(1) == subscriptionId) {
                                        failRelay(
                                            relay,
                                            IllegalStateException(
                                                "subscription closed: ${message.optString(2)}"
                                            )
                                        )
                                    }
                                    "OK" -> if (
                                        message.optString(1) == request.event.optString("id") &&
                                        !message.optBoolean(2)
                                    ) {
                                        failRelay(
                                            relay,
                                            IllegalStateException(
                                                message.optString(
                                                    3, "relay rejected the NIP-46 request event"
                                                )
                                            )
                                        )
                                    }
                                }
                                return
                            }
                            if (message.optString(1) != subscriptionId) return
                            val event = message.optJSONObject(2) ?: return
                            val response = NostrNip46.decryptResponseOrNull(
                                eventJson = event,
                                keyPair = keyPair,
                                signerPubkey = expectedAuthor
                            )
                            if (response == null) {
                                // 解不开 / 格式或寻址不符：不是本次请求可用的回包，记日志后忽略。
                                AppLog.put(
                                    "NIP-46 response from ${relayHost(relay)} could not be decrypted (ignored)",
                                    tag = "Nostr"
                                )
                                return
                            }
                            if (response.id != request.id) {
                                // 与本次请求无关或上一轮重试迟到的回包：丢弃，继续等本次结果。
                                AppLog.put(
                                    "NIP-46 收到非当前请求的回包（忽略）: ${response.id} != ${request.id}",
                                    tag = "Nostr"
                                )
                                return
                            }
                            if (response.result == "auth_url" && onAuthUrl != null) {
                                val authUrl = response.error
                                if (authUrl == null) {
                                    AppLog.put(
                                        "NIP-46 auth_url response from ${relayHost(relay)} has no URL (ignored)",
                                        tag = "Nostr"
                                    )
                                } else if (authUrlShown.compareAndSet(false, true)) {
                                    onAuthUrl(authUrl)
                                }
                                return
                            }
                            if (completed.compareAndSet(false, true)) {
                                closeSockets(1000, "response received")
                                continuation.resume(response)
                            }
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            if (!completed.get()) {
                                AppLog.put("NIP-46 relay ${relayHost(relay)} failed", t, tag = "Nostr")
                                failRelay(relay, t)
                            }
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            if (!completed.get()) {
                                AppLog.put(
                                    "NIP-46 relay ${relayHost(relay)} closed before responding: $code $reason",
                                    tag = "Nostr"
                                )
                                failRelay(
                                    relay,
                                    IllegalStateException("NIP-46 relay closed before responding: $relay")
                                )
                            }
                        }
                    }
                )
                sockets.add(socket)
                if (completed.get()) socket.close(1000, "response already received")
            }

            continuation.invokeOnCancellation {
                if (completed.compareAndSet(false, true)) closeSockets(1000, "request cancelled")
            }
        }
    }

    /**
     * 统一处理 relay 级超时。withTimeout 抛出的 TimeoutCancellationException 不携带任何 relay 信息，
     * 这里替换成带上每个 relay 具体原因的异常，便于区分是网络、TLS 还是 relay 本身的问题。
     */
    private suspend fun <T> withRelayErrorReporting(
        timeoutMillis: Long,
        relays: List<String>,
        failureLabel: String,
        block: suspend (List<String>, MutableMap<String, String>) -> T
    ): T {
        require(relays.isNotEmpty()) { "No valid Relay available for $failureLabel" }
        val relayErrors = ConcurrentHashMap<String, String>()
        return try {
            withTimeout(timeoutMillis) { block(relays, relayErrors) }
        } catch (e: TimeoutCancellationException) {
            val message = relayFailureMessage(
                "$failureLabel timed out after ${timeoutMillis}ms",
                relays,
                relayErrors
            )
            AppLog.put(message, e, tag = "Nostr")
            throw NostrRelayException(message, e)
        }
    }

    private fun relayFailureMessage(
        prefix: String,
        relays: List<String>,
        relayErrors: Map<String, String>
    ): String = "$prefix (${relays.joinToString("; ") { describeRelay(it, relayErrors) }})"

    private fun describeRelay(relay: String, relayErrors: Map<String, String>): String {
        val error = relayErrors[relay] ?: return "${relayHost(relay)}: no response"
        return "${relayHost(relay)}: $error"
    }
}

/**
 * NIP-46 relay 层失败：所有 relay 都失败，或等待响应超时。
 * message 中会带上每个 relay 的具体原因，避免只暴露一个 "Timed out waiting for ... ms"。
 */
class NostrRelayException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * 中继对发布事件的明确拒绝：回包 ["OK", id, false, ...]。
 * 与传输层失败（未连上/掉线/静默/超时）区分，用于决定是否重试。
 */
private class RelayRejectedException(message: String) : IllegalStateException(message)
