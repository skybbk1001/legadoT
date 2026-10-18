package io.legado.app.help.nostr

import org.json.JSONObject

/**
 * 签名后端：屏蔽远程签名器（NIP-46）与本机签名器（NIP-55）的差异，只负责产出已签名事件；
 * 字段校验与发布仍由 [NostrReviewPublisher] 统一做。
 */
interface NostrSignerBackend {

    val userPubkey: String

    /** 除本地配置的中继外还需额外投递的中继（远程签名器的会话中继）；本机签名器为空。 */
    val signerRelays: List<String>

    suspend fun sign(unsignedEvent: JSONObject): JSONObject
}

/** NIP-46：事件经中继交给远程签名器（bunker）签名。 */
private class Nip46SignerBackend(private val session: NostrSignerStore.Session) : NostrSignerBackend {

    override val userPubkey: String = session.userPubkey

    override val signerRelays: List<String> = session.relays

    override suspend fun sign(unsignedEvent: JSONObject): JSONObject {
        val keyPair = NostrNip46.ClientKeyPair(session.clientPrivateKey, session.clientPublicKey)
        val connection = NostrNip46.BunkerConnection(
            signerPubkey = session.signerPubkey,
            relays = session.relays,
            secret = null
        )
        val response = NostrNip46Transport.request(
            connection,
            keyPair,
            NostrNip46.buildSignEventRequest(keyPair, session.signerPubkey, unsignedEvent)
        )
        if (response.error != null) throw IllegalStateException(response.error)
        return JSONObject(response.result ?: error("Signer returned an empty signed event"))
    }
}

/** NIP-55：事件经 Intent / ContentResolver 交给同机签名器，不经过中继。 */
private class Nip55SignerBackend(
    private val account: NostrSignerStore.ExternalAccount
) : NostrSignerBackend {

    override val userPubkey: String = account.userPubkey

    override val signerRelays: List<String> = emptyList()

    override suspend fun sign(unsignedEvent: JSONObject): JSONObject {
        val payload = NostrNip55.signPayload(unsignedEvent, account.userPubkey)
        val result = NostrNip55Client.signEvent(
            packageName = account.packageName,
            userPubkey = account.userPubkey,
            payloadJson = payload.toString()
        )
        return NostrNip55.signedEventFrom(result, payload)
    }
}

/**
 * 当前使用的签名器：两个账号可同时保留，以 [NostrSignerStore.activeSigner] 为准（连接谁就用谁）；
 * 记录的账号缺失时回退到另一个。
 */
object NostrSigners {

    fun current(): NostrSignerBackend? {
        val session = NostrSignerStore.load()
        val external = NostrSignerStore.loadExternal()
        val preferExternal = NostrSignerStore.activeSigner == NostrSignerStore.SIGNER_NIP55
        val preferred = if (preferExternal) external else session
        val fallback = if (preferExternal) session else external
        return when (preferred) {
            is NostrSignerStore.ExternalAccount -> Nip55SignerBackend(preferred)
            is NostrSignerStore.Session -> Nip46SignerBackend(preferred)
            else -> when (fallback) {
                is NostrSignerStore.ExternalAccount -> Nip55SignerBackend(fallback)
                is NostrSignerStore.Session -> Nip46SignerBackend(fallback)
                else -> null
            }
        }
    }

    fun currentPubkey(): String? = current()?.userPubkey
}
