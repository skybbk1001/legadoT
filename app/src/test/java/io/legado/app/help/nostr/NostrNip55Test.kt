package io.legado.app.help.nostr

import fr.acinq.secp256k1.Secp256k1
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NostrNip55Test {

    private val secret = ByteArray(32) { (it + 1).toByte() }
    private val pubkey = Secp256k1.pubkeyCreate(secret).copyOfRange(1, 33).toHex()

    private fun unsignedEvent(): JSONObject = JSONObject()
        .put("kind", 1111)
        .put("content", "段落评论")
        .put("created_at", 1_700_000_000L)
        .put("tags", JSONArray().put(JSONArray().put("E").put("excerpt-id")))

    private fun result(
        result: String? = null,
        event: String? = null,
        packageName: String? = null
    ) = NostrNip55.Result(result = result, event = event, packageName = packageName, rejected = false)

    @Test
    fun signPayloadAddsPubkeyWithoutMutatingSource() {
        val unsigned = unsignedEvent()
        val payload = NostrNip55.signPayload(unsigned, pubkey)

        assertEquals(pubkey, payload.getString("pubkey"))
        assertEquals(unsigned.getInt("kind"), payload.getInt("kind"))
        assertTrue("原始事件不应被写入 pubkey", unsigned.optString("pubkey").isEmpty())
    }

    @Test
    fun signedEventFromPrefersSignedEventPayload() {
        val signedPayload = unsignedEvent()
            .put("pubkey", pubkey)
            .put("id", "a".repeat(64))
            .put("sig", "b".repeat(128))

        val signed = NostrNip55.signedEventFrom(
            result(event = signedPayload.toString()),
            NostrNip55.signPayload(unsignedEvent(), pubkey)
        )

        assertEquals("a".repeat(64), signed.getString("id"))
        assertEquals("b".repeat(128), signed.getString("sig"))
    }

    @Test
    fun signedEventFromAssemblesVerifiableEventFromSignatureOnly() {
        val payload = NostrNip55.signPayload(unsignedEvent(), pubkey)
        val tags = listOf(listOf("E", "excerpt-id"))
        val eventId = nostrEventId(
            pubkey = pubkey,
            createdAt = payload.getLong("created_at"),
            kind = payload.getInt("kind"),
            tags = tags,
            content = payload.getString("content")
        )
        val signature = Secp256k1.signSchnorr(eventId, secret, null).toHex()

        // 部分签名器只回签名，且大小写不定；拼装出的 id/sig 必须仍能通过校验。
        val signed = NostrNip55.signedEventFrom(
            result(result = signature.uppercase()),
            payload
        )

        assertEquals(eventId.toHex(), signed.getString("id"))
        assertEquals(signature, signed.getString("sig"))
        assertTrue(
            NostrReviewClient.verifyEvent(
                id = signed.getString("id"),
                pubkey = signed.getString("pubkey"),
                createdAt = signed.getLong("created_at"),
                kind = signed.getInt("kind"),
                tags = tags,
                content = signed.getString("content"),
                signature = signed.getString("sig")
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun signedEventFromRejectsResultWithoutEventOrSignature() {
        NostrNip55.signedEventFrom(
            result(result = "not-a-signature"),
            NostrNip55.signPayload(unsignedEvent(), pubkey)
        )
    }

    @Test
    fun publicKeyFromAcceptsOnlyHexPubkeys() {
        assertEquals(pubkey, NostrNip55.publicKeyFrom(result(result = pubkey.uppercase())))
        assertNull(NostrNip55.publicKeyFrom(result(result = "npub1notahexkey")))
        assertNull(NostrNip55.publicKeyFrom(result()))
    }

    @Test
    fun paragraphReviewPermissionsCoverParagraphReviewKinds() {
        val permissions = NostrNip55.paragraphReviewPermissions

        assertTrue(permissions.all { it.type == NostrNip55.COMMAND_SIGN_EVENT })
        assertEquals(
            NostrNip46.paragraphReviewKinds.sorted(),
            permissions.mapNotNull { it.kind }
        )
        assertNotNull(NostrNip55.signerPackageFrom(result(packageName = "com.example.signer")))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
