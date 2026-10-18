package io.legado.app.help.nostr

import io.legado.app.constant.AppConst
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Base64
import java.net.URLEncoder

class NostrReviewClientTest {

    @Test
    fun bookIdentifierIsStableAcrossWhitespaceAndCase() {
        val first = NostrReviewClient.bookIdentifier("  The   Book ", "Author")
        val second = NostrReviewClient.bookIdentifier("the book", " author ")

        assertEquals(first, second)
        assertTrue(first.startsWith("legado:book:"))
    }

    @Test
    fun bookIdentifierSeparatesDifferentAuthors() {
        val first = NostrReviewClient.bookIdentifier("The Book", "Author A")
        val second = NostrReviewClient.bookIdentifier("The Book", "Author B")

        assertNotEquals(first, second)
    }

    @Test
    fun paragraphMatchUsesContextWhenChapterReferenceDiffers() {
        val paragraphs = listOf(
            "A distinct preceding sentence establishes the first location.",
            "Repeated paragraph text appears in more than one chapter.",
            "An unmistakable following sentence closes the first context."
        )
        val excerpt = NostrReviewClient.NostrEvent(
            id = "event-id",
            pubkey = "pubkey",
            content = paragraphs[1],
            createdAt = 1,
            kind = 9802,
            tags = listOf(
                listOf("context", "sentence establishes the first location. An unmistakable following")
            )
        )

        val matches = NostrReviewClient.matchParagraphs(
            listOf(excerpt),
            paragraphs,
            chapterTitle = ""
        )

        assertTrue(matches.containsKey(2))
    }

    @Test
    fun paragraphMatchRejectsConflictingContextWithoutChapterReference() {
        val paragraphs = listOf(
            "A current chapter has neighbors that do not match this event.",
            "Repeated paragraph text appears in more than one chapter.",
            "The next paragraph in the current book is also different."
        )
        val excerpt = NostrReviewClient.NostrEvent(
            id = "event-id",
            pubkey = "pubkey",
            content = paragraphs[1],
            createdAt = 1,
            kind = 9802,
            tags = listOf(
                listOf("context", "unrelated words from another chapter's context")
            )
        )

        val matches = NostrReviewClient.matchParagraphs(
            listOf(excerpt),
            paragraphs,
            chapterTitle = ""
        )

        assertFalse(matches.containsKey(2))
    }

    @Test
    fun eventVerificationRejectsMismatchedEventIdBeforeSignatureCheck() {
        val validLengthHex = "00".repeat(32)
        val signatureHex = "00".repeat(64)

        val isValid = NostrReviewClient.verifyEvent(
            id = validLengthHex,
            pubkey = validLengthHex,
            createdAt = 1,
            kind = 9802,
            tags = emptyList(),
            content = "excerpt",
            signature = signatureHex
        )

        assertFalse(isValid)
    }

    @Test
    fun nip44V2MatchesOfficialConversationAndEncryptionVector() {
        val clientSecret = ("00".repeat(31) + "01").hexToBytes()
        val signerSecret = ("00".repeat(31) + "02").hexToBytes()
        val signerPublic = fr.acinq.secp256k1.Secp256k1.pubkeyCreate(signerSecret)
            .copyOfRange(1, 33)
        val expectedConversationKey =
            "c41c775356fd92eadc63ff5a0dc1da211b268cbea22316767095b2871ea1412d".hexToBytes()
        val nonce = ByteArray(32).also { it[31] = 1 }
        val expectedPayload =
            "AgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABee0G5VSK0/9YypIObAtDKfYEAjD35uVkHyB0F4DwrcNaCXlCWZKaArsGrY6M9wnuTMxWfp1RTN9Xga8no+kF5Vsb"

        val conversationKey = NostrNip44.conversationKey(clientSecret, signerPublic)

        assertEquals(expectedConversationKey.toList(), conversationKey.toList())
        assertEquals(
            expectedPayload,
            NostrNip44.encrypt("a".toByteArray(), conversationKey, nonce)
        )
        assertEquals("a", NostrNip44.decrypt(expectedPayload, conversationKey))
    }

    @Test
    fun nip44RejectsTamperedPayload() {
        val conversationKey = ByteArray(32)
        val payload = NostrNip44.encrypt("hello", conversationKey)
        val decoded = Base64.getDecoder().decode(payload)
        decoded[40] = (decoded[40].toInt() xor 1).toByte()

        val failure = runCatching {
            NostrNip44.decrypt(Base64.getEncoder().encodeToString(decoded), conversationKey)
        }

        assertTrue(failure.isFailure)
    }

    @Test
    fun bunkerUriParsesRepeatedRelaysAndSecret() {
        val signerPubkey = "ab".repeat(32)
        val firstRelay = URLEncoder.encode("wss://relay.example", Charsets.UTF_8.name())
        val secondRelay = URLEncoder.encode("wss://relay2.example", Charsets.UTF_8.name())
        val uri = "bunker://$signerPubkey?relay=$firstRelay&relay=$secondRelay&secret=single-use"

        val connection = NostrNip46.parseBunkerUri(uri)

        assertEquals(signerPubkey, connection.signerPubkey)
        assertEquals(listOf("wss://relay.example", "wss://relay2.example"), connection.relays)
        assertEquals("single-use", connection.secret)
    }

    @Test
    fun nip46PermissionsLimitSigningToParagraphReviewKinds() {
        assertEquals(
            "get_public_key,sign_event:9802,sign_event:1111,sign_event:7,sign_event:5",
            NostrNip46.paragraphReviewPermissions
        )
    }

    @Test
    fun nostrConnectTokenRequestsOnlyParagraphReviewPermissions() {
        val clientSecret = ("00".repeat(31) + "01").hexToBytes()
        val clientPublic = fr.acinq.secp256k1.Secp256k1.pubkeyCreate(clientSecret)
            .copyOfRange(1, 33)
            .joinToString("") { "%02x".format(it) }

        val token = NostrNip46.createNostrConnectToken(
            NostrNip46.ClientKeyPair(clientSecret, clientPublic),
            listOf("wss://relay.example")
        )

        assertTrue(token.uri.startsWith("nostrconnect://$clientPublic?"))
        assertTrue(token.uri.contains("perms=get_public_key%2Csign_event%3A9802"))
        assertTrue(token.uri.contains("secret=${token.secret}"))
    }

    @Test
    fun nip46ConnectRequestIsSignedAndEncryptedForTheBunker() {
        val clientSecret = ("00".repeat(31) + "01").hexToBytes()
        val signerSecret = ("00".repeat(31) + "02").hexToBytes()
        val clientPublic = fr.acinq.secp256k1.Secp256k1.pubkeyCreate(clientSecret)
            .copyOfRange(1, 33)
        val signerPublic = fr.acinq.secp256k1.Secp256k1.pubkeyCreate(signerSecret)
            .copyOfRange(1, 33)
        val connection = NostrNip46.BunkerConnection(
            signerPubkey = signerPublic.joinToString("") { "%02x".format(it) },
            relays = listOf("wss://relay.example"),
            secret = "once"
        )

        val request = NostrNip46.buildConnectRequest(
            NostrNip46.ClientKeyPair(clientSecret, clientPublic.joinToString("") { "%02x".format(it) }),
            connection,
            createdAt = 100
        )
        val event = request.event
        val tags = listOf(listOf("p", connection.signerPubkey))
        assertTrue(
            NostrReviewClient.verifyEvent(
                id = event.getString("id"),
                pubkey = event.getString("pubkey"),
                createdAt = event.getLong("created_at"),
                kind = event.getInt("kind"),
                tags = tags,
                content = event.getString("content"),
                signature = event.getString("sig")
            )
        )

        val sharedKey = NostrNip44.conversationKey(clientSecret, signerPublic)
        val rpc = org.json.JSONObject(NostrNip44.decrypt(event.getString("content"), sharedKey))
        val params = rpc.getJSONArray("params")
        assertEquals("connect", rpc.getString("method"))
        assertEquals(connection.signerPubkey, params.getString(0))
        assertEquals("once", params.getString(1))
        assertEquals(NostrNip46.paragraphReviewPermissions, params.getString(2))
    }

    @Test
    fun nip46SignEventRequestRejectsKindsOutsideParagraphReviews() {
        val keyPair = NostrNip46.ClientKeyPair(ByteArray(32), "00".repeat(32))
        val unsignedEvent = org.json.JSONObject().put("kind", 1)

        val failure = runCatching {
            NostrNip46.buildSignEventRequest(keyPair, "11".repeat(32), unsignedEvent)
        }

        assertTrue(failure.isFailure)
    }

    @Test
    fun canonicalEventSerializationKeepsForwardSlashes() {
        val pubkey = "82341f882b6eabcd2ba7f1ef90aad961cf074af15b9ef44a09f9d2a8fbfbe6a2"
        val tagPubkey = "0000000000000000000000000000000000000000000000000000000000000001"
        val tags = listOf(listOf("p", tagPubkey))
        val content = "a/b+c="

        // 规范串里 "/" 必须原样保留（org.json 会写成 "\/"，那样算出的 id 与中继不一致）。
        assertEquals(
            "[0,\"$pubkey\",1672327989,24133,[[\"p\",\"$tagPubkey\"]],\"$content\"]",
            canonicalEventJson(pubkey, 1672327989, 24133, tags, content)
        )
        // 期望值由外部按 NIP-01 规范（保留 "/"）对上述字节算 SHA-256 得到，非本代码自洽产物。
        assertEquals(
            "07c301306673fb68f4c95e92d4e648532529d15e5f08647c2c6935577fc3903c",
            nostrEventId(pubkey, 1672327989, 24133, tags, content)
                .joinToString("") { "%02x".format(it) }
        )
    }

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun publishedEventTagsCarryNip89ClientTag() {
        val clientTag = listOf("client", AppConst.APP_TAG)
        val tagArrays = listOf(
            NostrReviewPublisher.excerptTags("book-id", "ctx", "paragraph"),
            NostrReviewPublisher.commentTags("book-id", "excerpt-id", "user-pubkey"),
            NostrReviewPublisher.replyTags(
                "book-id", "excerpt-id", "excerpt-pubkey", "parent-id", "parent-pubkey"
            ),
            NostrReviewPublisher.reactionTags("comment-id", "author-pubkey")
        )
        tagArrays.forEach { tags ->
            val last = tags.getJSONArray(tags.length() - 1)
            assertEquals(clientTag, (0 until last.length()).map { last.getString(it) })
        }
    }

    @Test
    fun bookIdentifierV2IsAuthorFirstVersionedAndReadable() {
        val id = NostrReviewClient.bookIdentifierV2("The Book", "Author")

        assertEquals("legado:book:v2|author|thebook", id)
    }

    @Test
    fun bookIdentifiersIncludeLegacyHashAndV2() {
        val ids = NostrReviewClient.bookIdentifiers("The Book", "Author")

        assertEquals(2, ids.size)
        assertTrue(ids[0].startsWith("legado:book:"))
        assertFalse(ids[0].startsWith("legado:book:v2"))
        assertEquals("legado:book:v2|author|thebook", ids[1])
    }

    @Test
    fun normalizeForIdentityKeepsAsciiPunctuationAndStripsNonAscii() {
        assertEquals("ender'sgame", NostrReviewClient.normalizeForIdentity("Ender's Game"))
        assertEquals("三国演义", NostrReviewClient.normalizeForIdentity("《三国演义》"))
        assertEquals("deepwork深度工作", NostrReviewClient.normalizeForIdentity("Deep Work　深度工作"))
    }

    @Test
    fun excerptTagsCarryKindAndTitleContextWithoutLegacyReference() {
        val context = "药王庙夜谈 上一段 下一段"
        val paragraph = "这一段的正文原文。"
        val tags = NostrReviewPublisher.excerptTags("legado:book:v2|author|thebook", context, paragraph)
        val arrays = (0 until tags.length()).map { tags.getJSONArray(it) }

        assertTrue(arrays.none { it.getString(0) == "r" })

        assertEquals("legado:book:v2|author|thebook", arrays.first { it.getString(0) == "i" }.getString(1))
        assertEquals("legado:book", arrays.first { it.getString(0) == "k" }.getString(1))
        assertEquals(context, arrays.first { it.getString(0) == "context" }.getString(1))
    }

    @Test
    fun excerptParagraphTagMatchesReadSideMatching() {
        val paragraph = "Repeated paragraph text appears in more than one chapter."
        val tags = NostrReviewPublisher.excerptTags("book-id", "ctx", paragraph)
        val tTag = (0 until tags.length()).map { tags.getJSONArray(it) }
            .first { it.getString(0) == "t" }.getString(1)

        assertTrue(tTag.startsWith(NostrReviewClient.PARAGRAPH_TAG_PREFIX))

        // 只带 t 标签（content 故意写成不相关文本）：读端只能靠 t 标签命中。
        val excerpt = NostrReviewClient.NostrEvent(
            id = "event-id",
            pubkey = "pubkey",
            content = "unrelated content",
            createdAt = 1,
            kind = 9802,
            tags = listOf(listOf("t", tTag))
        )
        val matches = NostrReviewClient.matchParagraphs(
            listOf(excerpt),
            listOf("first paragraph.", paragraph, "third paragraph."),
            chapterTitle = ""
        )

        assertTrue(matches.containsKey(2))
    }

    @Test
    fun nip46ResponseWithForeignIdIsReturnedInsteadOfRejected() {
        val clientSecret = ("00".repeat(31) + "01").hexToBytes()
        val signerSecret = ("00".repeat(31) + "02").hexToBytes()
        val clientPublic = fr.acinq.secp256k1.Secp256k1.pubkeyCreate(clientSecret).copyOfRange(1, 33)
        val signerPublic = fr.acinq.secp256k1.Secp256k1.pubkeyCreate(signerSecret).copyOfRange(1, 33)
        val clientPublicHex = clientPublic.joinToString("") { "%02x".format(it) }
        val signerPublicHex = signerPublic.joinToString("") { "%02x".format(it) }

        val conversationKey = NostrNip44.conversationKey(clientSecret, signerPublic)
        val content = NostrNip44.encrypt(
            org.json.JSONObject().put("id", "late-request-id").put("result", "ack").toString(),
            conversationKey
        )
        val tags = listOf(listOf("p", clientPublicHex))
        val createdAt = 100L
        val eventId = nostrEventId(signerPublicHex, createdAt, 24133, tags, content)
        val event = org.json.JSONObject()
            .put("id", eventId.joinToString("") { "%02x".format(it) })
            .put("pubkey", signerPublicHex)
            .put("created_at", createdAt)
            .put("kind", 24133)
            .put("tags", org.json.JSONArray().put(org.json.JSONArray().put("p").put(clientPublicHex)))
            .put("content", content)
            .put(
                "sig",
                fr.acinq.secp256k1.Secp256k1.signSchnorr(eventId, signerSecret, null)
                    .joinToString("") { "%02x".format(it) }
            )

        val response = NostrNip46.decryptResponseOrNull(
            eventJson = event,
            keyPair = NostrNip46.ClientKeyPair(clientSecret, clientPublicHex),
            signerPubkey = signerPublicHex
        )

        // 不再因 id 不匹配抛异常：把响应连同自身 id 交回调用方，由调用方比对后丢弃。
        assertEquals("late-request-id", response?.id)
        assertEquals("ack", response?.result)
    }

    @Test
    fun renewRequestKeepsMethodAndParamsButChangesRequestId() {
        val clientSecret = ("00".repeat(31) + "01").hexToBytes()
        val clientPublic = fr.acinq.secp256k1.Secp256k1.pubkeyCreate(clientSecret)
            .copyOfRange(1, 33)
            .joinToString("") { "%02x".format(it) }
        val keyPair = NostrNip46.ClientKeyPair(clientSecret, clientPublic)
        val signerPubkey = "11".repeat(32)

        val first = NostrNip46.buildGetPublicKeyRequest(keyPair, signerPubkey, createdAt = 100)
        val retry = NostrNip46.renewRequest(keyPair, signerPubkey, first)

        assertNotEquals(first.id, retry.id)
        assertEquals(first.method, retry.method)
        assertEquals(first.params, retry.params)
    }

    @Test
    fun normalizeContentCollapsesAsciiAndFullWidthSpaces() {
        assertEquals("a b", NostrReviewClient.normalizeContent("a\u3000\u3000b"))
        assertEquals("a b", NostrReviewClient.normalizeContent("  a\tb  "))
    }

    @Test
    fun paragraphTagValueIgnoresFullWidthSpace() {
        assertEquals(
            NostrReviewClient.paragraphTagValue("a\u3000b"),
            NostrReviewClient.paragraphTagValue("a b")
        )
    }

    @Test
    fun legacyBookIdentifierStaysBlindToFullWidthSpace() {
        // v1 书标识沿用旧 normalize（\s 不含 U+3000），不应被新的内容归一化波及。
        assertNotEquals(
            NostrReviewClient.bookIdentifier("三\u3000体", "刘慈欣"),
            NostrReviewClient.bookIdentifier("三 体", "刘慈欣")
        )
    }

    @Test
    fun paragraphMatchIgnoresFullWidthSpaceDifference() {
        val paragraphs = listOf(
            "上一段结尾。",
            "这是\u3000全角空格\u3000的正文。",
            "下一段开头。"
        )
        val excerpt = NostrReviewClient.NostrEvent(
            id = "event-id",
            pubkey = "pubkey",
            content = "这是 全角空格 的正文。",
            createdAt = 1,
            kind = 9802,
            tags = emptyList()
        )

        val matches = NostrReviewClient.matchParagraphs(listOf(excerpt), paragraphs, chapterTitle = "")

        assertTrue(matches.containsKey(2))
    }

    @Test
    fun paragraphMatchFindsMergedParagraph() {
        // 旧的一段被并入更长的一段：精确不相等，靠长片段包含定位。
        val paragraphs = listOf(
            "开头段落。",
            "前半句被并入更长的一段；这是原来的段落正文。后面还有别的话。",
            "结尾段落。"
        )
        val excerpt = NostrReviewClient.NostrEvent(
            id = "event-id",
            pubkey = "pubkey",
            content = "前半句被并入更长的一段；这是原来的段落正文。",
            createdAt = 1,
            kind = 9802,
            tags = emptyList()
        )

        val matches = NostrReviewClient.matchParagraphs(listOf(excerpt), paragraphs, chapterTitle = "")

        assertTrue(matches.containsKey(2))
    }

    @Test
    fun paragraphMatchOnSplitAttachesToLargestOverlapParagraph() {
        // 旧的一段被拆成两段：只挂重叠最大的一段。
        val first = "上半段的文字足够长以便达到重叠阈值的下限要求。"
        val second = "下半段的文字同样够长但比上半段短一些。"
        val paragraphs = listOf("无关的开头段落。", first, second, "无关的结尾段落。")
        val excerpt = NostrReviewClient.NostrEvent(
            id = "event-id",
            pubkey = "pubkey",
            content = first + second,
            createdAt = 1,
            kind = 9802,
            tags = emptyList()
        )

        val matches = NostrReviewClient.matchParagraphs(listOf(excerpt), paragraphs, chapterTitle = "")

        assertEquals(setOf(2), matches.keys)
    }

    @Test
    fun paragraphMatchUsesContextWhenFragmentRepeats() {
        // 同一段文字在本章出现两次且重叠相等：按 context 就近消歧。
        val repeated = "重复出现的一段文字需要足够长才能进入容错匹配流程。"
        val paragraphs = listOf(
            "第一处的前一段内容足够长。", repeated, "第一处的后一段内容足够长。",
            "第二处的前一段内容足够长。", repeated, "第二处的后一段内容足够长。"
        )
        val excerpt = NostrReviewClient.NostrEvent(
            id = "event-id",
            pubkey = "pubkey",
            content = repeated + "尾巴",
            createdAt = 1,
            kind = 9802,
            tags = listOf(listOf("context", "第二处的前一段内容足够长。第二处的后一段内容足够长。"))
        )

        val matches = NostrReviewClient.matchParagraphs(listOf(excerpt), paragraphs, chapterTitle = "")

        assertEquals(setOf(5), matches.keys)
    }

    @Test
    fun paragraphMatchRejectsFragmentBelowThreshold() {
        // 被拆成两段且每段重叠都不足阈值：宁缺毋滥。
        val first = "aaaabbbbccccddd"
        val second = "eeeeffffgggghhh"
        val excerpt = NostrReviewClient.NostrEvent(
            id = "event-id",
            pubkey = "pubkey",
            content = first + second,
            createdAt = 1,
            kind = 9802,
            tags = emptyList()
        )

        val matches = NostrReviewClient.matchParagraphs(
            listOf(excerpt),
            listOf(first, second),
            chapterTitle = ""
        )

        assertTrue(matches.isEmpty())
    }
}