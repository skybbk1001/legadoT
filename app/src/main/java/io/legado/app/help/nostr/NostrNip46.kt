package io.legado.app.help.nostr

import fr.acinq.secp256k1.Secp256k1
import io.legado.app.constant.AppConst
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

object NostrNip46 {

    val paragraphReviewKinds = setOf(9802, 1111, 7, 5)
    private val secureRandom = SecureRandom()

    data class BunkerConnection(
        val signerPubkey: String,
        val relays: List<String>,
        val secret: String?
    )

    data class NostrConnectToken(
        val uri: String,
        val secret: String,
        val relays: List<String>
    )

    data class ClientKeyPair(
        val privateKey: ByteArray,
        val publicKey: String
    )

    data class RpcRequest(
        val id: String,
        val event: JSONObject,
        val method: String,
        val params: List<String>
    )

    data class RpcResponse(
        val id: String,
        val result: String?,
        val error: String?
    )

    data class NostrConnectApproval(
        val signerPubkey: String,
        val requestId: String
    )

    /**
     * nostrconnect 阶段的两种消息变体：
     * - 签名器直接回 connect 响应（result == secret）；
     * - 签名器发 connect 请求（method == "connect"），需要客户端回 ack 才会继续。
     */
    data class NostrConnectMessage(
        val approval: NostrConnectApproval,
        val isConnectRequest: Boolean
    )

    fun parseBunkerUri(value: String): BunkerConnection {
        val uri = URI(value.trim())
        require(uri.scheme.equals("bunker", ignoreCase = true)) { "Expected a bunker:// URI" }
        val signerPubkey = uri.host.orEmpty().lowercase(Locale.ROOT)
        require(signerPubkey.isHexKey()) { "Invalid remote signer public key" }
        val queryParameters = uri.rawQuery.orEmpty()
            .split('&')
            .filter { it.isNotBlank() }
            .mapNotNull { parameter ->
                val separator = parameter.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                URLDecoder.decode(parameter.substring(0, separator), Charsets.UTF_8.name()) to
                    URLDecoder.decode(parameter.substring(separator + 1), Charsets.UTF_8.name())
            }
        val relays = queryParameters.asSequence()
            .filter { it.first == "relay" }
            .map { it.second }
            .map(String::trim)
            .filter { it.startsWith("wss://", ignoreCase = true) }
            .distinct()
            .toList()
        require(relays.isNotEmpty()) { "Bunker URI must include at least one wss:// relay" }
        return BunkerConnection(
            signerPubkey = signerPubkey,
            relays = relays,
            secret = queryParameters.firstOrNull { it.first == "secret" }?.second
                ?.takeIf { it.isNotBlank() }
        )
    }

    fun newClientKeyPair(): ClientKeyPair {
        val privateKey = ByteArray(32)
        do {
            secureRandom.nextBytes(privateKey)
        } while (!Secp256k1.secKeyVerify(privateKey))
        val publicKey = Secp256k1.pubkeyCreate(privateKey).copyOfRange(1, 33).toHex()
        return ClientKeyPair(privateKey, publicKey)
    }

    fun createNostrConnectToken(keyPair: ClientKeyPair, relays: List<String>): NostrConnectToken {
        val validRelays = relays.map(String::trim)
            .filter { it.startsWith("wss://", ignoreCase = true) }
            .distinct()
        require(validRelays.isNotEmpty()) { "At least one wss:// relay is required" }
        val secret = ByteArray(16).also(secureRandom::nextBytes).toHex()
        val query = buildList {
            validRelays.forEach { add("relay=${it.urlEncoded()}") }
            add("secret=${secret.urlEncoded()}")
            add("perms=${paragraphReviewPermissions.urlEncoded()}")
            add("name=${AppConst.APP_TAG.urlEncoded()}")
        }.joinToString("&")
        return NostrConnectToken(
            uri = "nostrconnect://${keyPair.publicKey}?$query",
            secret = secret,
            relays = validRelays
        )
    }

    fun buildConnectRequest(
        keyPair: ClientKeyPair,
        connection: BunkerConnection,
        createdAt: Long = System.currentTimeMillis() / 1000
    ): RpcRequest = buildRequest(
        keyPair = keyPair,
        signerPubkey = connection.signerPubkey,
        method = "connect",
        params = listOf(
            connection.signerPubkey,
            connection.secret.orEmpty(),
            paragraphReviewPermissions,
            JSONObject().put("name", AppConst.APP_TAG).toString()
        ),
        createdAt = createdAt
    )

    fun buildGetPublicKeyRequest(
        keyPair: ClientKeyPair,
        signerPubkey: String,
        createdAt: Long = System.currentTimeMillis() / 1000
    ): RpcRequest = buildRequest(keyPair, signerPubkey, "get_public_key", emptyList(), createdAt)

    fun buildSwitchRelaysRequest(
        keyPair: ClientKeyPair,
        signerPubkey: String,
        createdAt: Long = System.currentTimeMillis() / 1000
    ): RpcRequest = buildRequest(keyPair, signerPubkey, "switch_relays", emptyList(), createdAt)

    fun buildSignEventRequest(
        keyPair: ClientKeyPair,
        signerPubkey: String,
        unsignedEvent: JSONObject,
        createdAt: Long = System.currentTimeMillis() / 1000
    ): RpcRequest {
        val kind = unsignedEvent.optInt("kind", -1)
        require(kind in paragraphReviewKinds) { "Signing kind $kind is not permitted for paragraph review" }
        require(unsignedEvent.optString("pubkey").isBlank()) {
            "The signer must supply the user public key"
        }
        return buildRequest(
            keyPair,
            signerPubkey,
            "sign_event",
            listOf(unsignedEvent.toString()),
            createdAt
        )
    }

    /**
     * 解出 NIP-46 响应，并保留响应自身的 id（不再与期望 id 比较）。
     *
     * 事件格式、寻址或解密任一不符时返回 null，调用方据此记日志并忽略该回包；
     * id 由调用方比对，未知/迟到的回包静默丢弃，不再把“id 不匹配”当成请求失败。
     * 不强制响应作者 == 签名器公钥：会话密钥由签名器公钥派生，MAC 不过就会被拒，
     * 去掉硬校验可兼容“用另一个密钥回包”的非标准 bunker。
     */
    fun decryptResponseOrNull(
        eventJson: JSONObject,
        keyPair: ClientKeyPair,
        signerPubkey: String
    ): RpcResponse? {
        val event = NostrReviewClient.NostrEvent.fromJson(eventJson) ?: return null
        if (event.kind != 24133) return null
        if (keyPair.publicKey !in event.tagValues("p")) return null
        val signerKey = runCatching { signerPubkey.hexToByteArray() }.getOrNull() ?: return null
        val conversationKey = NostrNip44.conversationKey(keyPair.privateKey, signerKey)
        val payload = runCatching { NostrNip44.decrypt(event.content, conversationKey) }
            .getOrNull() ?: return null
        val response = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        return RpcResponse(
            id = response.optString("id"),
            result = response.optString("result").takeIf { it.isNotBlank() },
            error = response.optString("error").takeIf { it.isNotBlank() }
        )
    }

    fun decodeNostrConnectMessage(
        eventJson: JSONObject,
        keyPair: ClientKeyPair,
        expectedSecret: String
    ): NostrConnectMessage {
        val event = NostrReviewClient.NostrEvent.fromJson(eventJson)
            ?: throw IllegalArgumentException("Invalid signed nostrconnect message")
        require(event.kind == 24133) { "Unexpected nostrconnect message kind" }
        require(keyPair.publicKey in event.tagValues("p")) { "Message is not addressed to this client" }
        val conversationKey = NostrNip44.conversationKey(
            keyPair.privateKey,
            event.pubkey.hexToByteArray()
        )
        val payload = JSONObject(NostrNip44.decrypt(event.content, conversationKey))
        payload.optString("error").takeIf { it.isNotBlank() }
            ?.let { throw IllegalArgumentException(it) }
        val id = payload.optString("id").takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Nostrconnect message has no id")
        return if (payload.has("method")) {
            require(payload.optString("method") == "connect") {
                "Unsupported nostrconnect method ${payload.optString("method")}"
            }
            val params = payload.optJSONArray("params") ?: JSONArray()
            require(params.optString(1) == expectedSecret) { "Nostr connect secret did not match" }
            NostrConnectMessage(NostrConnectApproval(event.pubkey, id), isConnectRequest = true)
        } else {
            require(payload.optString("result") == expectedSecret) {
                "Nostr connect secret did not match"
            }
            NostrConnectMessage(NostrConnectApproval(event.pubkey, id), isConnectRequest = false)
        }
    }

    private fun buildRequest(
        keyPair: ClientKeyPair,
        signerPubkey: String,
        method: String,
        params: List<String>,
        createdAt: Long
    ): RpcRequest {
        val requestId = ByteArray(16).also(secureRandom::nextBytes).toHex()
        val payload = JSONObject()
            .put("id", requestId)
            .put("method", method)
            .put("params", JSONArray(params))
        return RpcRequest(
            id = requestId,
            event = buildMessageEvent(keyPair, signerPubkey, payload, createdAt),
            method = method,
            params = params
        )
    }

    /**
     * 用同一 method/params 生成一个带新 request id 的请求事件。
     * NIP-46 按 id 匹配回包，重试必须换新 id，否则上一轮迟到的回包会被误算到本轮。
     */
    internal fun renewRequest(
        keyPair: ClientKeyPair,
        signerPubkey: String,
        previous: RpcRequest,
        createdAt: Long = System.currentTimeMillis() / 1000
    ): RpcRequest = buildRequest(keyPair, signerPubkey, previous.method, previous.params, createdAt)

    /** 对 nostrconnect 阶段收到的 connect 请求回 an acknowledgment，签名器才会继续。 */
    fun buildConnectAck(
        keyPair: ClientKeyPair,
        signerPubkey: String,
        requestId: String,
        createdAt: Long = System.currentTimeMillis() / 1000
    ): JSONObject = buildMessageEvent(
        keyPair,
        signerPubkey,
        JSONObject().put("id", requestId).put("result", "ack"),
        createdAt
    )

    /** NIP-46 消息事件：kind 24133、p 标签为 signerPubkey、content 为 NIP-44 加密的 payload。 */
    private fun buildMessageEvent(
        keyPair: ClientKeyPair,
        signerPubkey: String,
        payload: JSONObject,
        createdAt: Long
    ): JSONObject {
        require(signerPubkey.isHexKey()) { "Invalid remote signer public key" }
        val conversationKey = NostrNip44.conversationKey(
            keyPair.privateKey,
            signerPubkey.hexToByteArray()
        )
        val content = NostrNip44.encrypt(payload.toString(), conversationKey)
        val tags = listOf(listOf("p", signerPubkey))
        val id = eventId(keyPair.publicKey, createdAt, 24133, tags, content)
        val signature = Secp256k1.signSchnorr(id, keyPair.privateKey, null).toHex()
        return JSONObject()
            .put("id", id.toHex())
            .put("pubkey", keyPair.publicKey)
            .put("created_at", createdAt)
            .put("kind", 24133)
            .put("tags", JSONArray().put(JSONArray().put("p").put(signerPubkey)))
            .put("content", content)
            .put("sig", signature)
    }

    private fun eventId(
        pubkey: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String
    ): ByteArray = nostrEventId(pubkey, createdAt, kind, tags, content)

    internal val paragraphReviewPermissions: String = buildList {
        add("get_public_key")
        paragraphReviewKinds.forEach { add("sign_event:$it") }
    }.joinToString(",")

    private fun String.isHexKey(): Boolean = length == 64 && all { it.digitToIntOrNull(16) != null }

    private fun String.hexToByteArray(): ByteArray {
        require(isHexKey()) { "Expected 32-byte hex public key" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.urlEncoded(): String = URLEncoder.encode(this, Charsets.UTF_8.name())
}