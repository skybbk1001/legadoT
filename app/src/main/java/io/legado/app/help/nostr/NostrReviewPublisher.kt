package io.legado.app.help.nostr

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

object NostrReviewPublisher {

    /**
     * 应用级发布作用域：不挂在任何界面/宿主的生命周期上，关掉段评弹窗、退回正文都不会取消它，
     * 只有进程结束时才随之取消。
     */
    private val publishScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 外部签名器(如 NIP-46 远端)可能慢，超时按发布失败处理，让上层落到草稿态可重发。 */
    private const val REVIEW_PUBLISH_TIMEOUT_MS = 60_000L

    /**
     * 与 UI 生命周期无关的发布：关弹窗/退回正文也不中断。
     * [block] 在 [publishScope] 内执行，成功把结果交给 [onSuccess]；
     * 失败或超时写一行日志并回调 [onFailure]，不吞异常；取消（进程结束）不当作业务失败。
     */
    fun <T> launchPublish(
        paragraphNum: Int,
        block: suspend () -> T,
        onSuccess: (T) -> Unit = {},
        onFailure: (Throwable) -> Unit = {}
    ): Job = publishScope.launch {
        val result = try {
            withTimeout(REVIEW_PUBLISH_TIMEOUT_MS) { block() }
        } catch (e: TimeoutCancellationException) {
            // withTimeout 抛的也是 CancellationException 子类，必须先于下面那条捕获才不会被当作进程级取消
            AppLog.put("段评发布超时 p=$paragraphNum：${e.localizedMessage}", e, tag = "Nostr")
            onFailure(e)
            return@launch
        } catch (e: CancellationException) {
            // 进程级作用域只在进程结束时取消，此时已无发布/回写意义，直接结束
            throw e
        } catch (e: Throwable) {
            AppLog.put("段评发布失败 p=$paragraphNum：${e.localizedMessage}", e, tag = "Nostr")
            onFailure(e)
            return@launch
        }
        onSuccess(result)
    }

    /** 发布成功的评论事件及其所属摘录，供调用方本地上屏/入缓存，不必等联网回读。 */
    data class PublishedComment(
        val event: JSONObject,
        val rootExcerptId: String,
        val rootExcerptPubkey: String
    )

    suspend fun postParagraphComment(
        book: Book,
        chapterTitle: String,
        paragraphs: List<String>,
        paragraphIndex: Int,
        comment: String
    ): PublishedComment {
        val text = comment.trim()
        require(text.isNotEmpty()) { "Comment cannot be empty" }
        val paragraph = paragraphs.getOrNull(paragraphIndex - 1)
            ?.let(NostrReviewClient::normalizeContent)
            ?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Paragraph is unavailable")
        val signer = NostrSigners.current()
            ?: throw IllegalStateException("Connect a Nostr signer before posting")
        val createdAt = System.currentTimeMillis() / 1000
        val bookIdentifier = NostrReviewClient.bookIdentifierV2(book.name, book.author)
        val title = NostrReviewClient.normalizeForIdentity(chapterTitle)
        val previous = paragraphs.getOrNull(paragraphIndex - 2).orEmpty().takeLast(80)
        val next = paragraphs.getOrNull(paragraphIndex).orEmpty().take(80)
        val context = listOf(title, previous, next)
            .filter { it.isNotBlank() }
            .joinToString(" ")

        val existing = NostrReviewClient.findParagraphExcerpt(book, chapterTitle, paragraphs, paragraphIndex)
        var excerptRelay: String? = null
        val excerptId: String
        if (existing != null) {
            excerptId = existing.id
            excerptRelay = existing.relay
            AppLog.put("段评摘录复用 id=$excerptId relay=${excerptRelay ?: "-"}", tag = "Nostr")
        } else {
            val excerpt = JSONObject()
                .put("kind", 9802)
                .put("content", paragraph)
                .put("created_at", createdAt)
                .put("tags", excerptTags(bookIdentifier, context, paragraph))
            val published = signAndPublish(signer, excerpt)
            excerptId = published.event.getString("id")
            excerptRelay = published.acceptedRelays.firstOrNull()
            AppLog.put("段评摘录新建 id=$excerptId relay=${excerptRelay ?: "-"}", tag = "Nostr")
            ensureExcerptPersisted(signer, published.event, listOfNotNull(excerptRelay))
        }
        val commentEvent = JSONObject()
            .put("kind", 1111)
            .put("content", text)
            .put("created_at", createdAt + 1)
            .put("tags", commentTags(bookIdentifier, excerptId, signer.userPubkey, excerptRelay))
        val publishedComment = signAndPublish(signer, commentEvent, listOfNotNull(excerptRelay))
        return PublishedComment(
            event = publishedComment.event,
            rootExcerptId = excerptId,
            rootExcerptPubkey = signer.userPubkey
        )
    }

    /**
     * 新摘录发布后确认当前读取中继能取回；取不回则原样重发一次已签名事件（不重新签名），
     * 第二次仍取不回即抛异常放弃发布评论，避免产出指向丢失摘录的 1111 悬空引用。
     */
    private suspend fun ensureExcerptPersisted(
        signer: NostrSignerBackend,
        signedExcerpt: JSONObject,
        extraRelays: List<String>
    ) {
        val id = signedExcerpt.getString("id")
        if (excerptReadable(id)) return
        NostrNip46Transport.publish(publishTargets(signer, extraRelays), signedExcerpt)
        if (!excerptReadable(id)) {
            throw IllegalStateException("段评摘录未能持久化，已放弃发布评论")
        }
    }

    private suspend fun excerptReadable(id: String): Boolean {
        val readable = NostrReviewClient.fetchEventById(id) != null
        AppLog.put("段评摘录校验 id=$id ${if (readable) "可读" else "不可读"}", tag = "Nostr")
        return readable
    }

    suspend fun replyToComment(
        book: Book,
        rootExcerptId: String,
        rootExcerptPubkey: String,
        parentCommentId: String,
        parentAuthorPubkey: String,
        content: String
    ): PublishedComment {
        val text = content.trim()
        require(text.isNotEmpty()) { "Comment cannot be empty" }
        val signer = NostrSigners.current()
            ?: throw IllegalStateException("Connect a Nostr signer before replying")
        val createdAt = System.currentTimeMillis() / 1000
        // 被引用的摘录/父评论所在中继：既作为 relay hint 写入标签，也额外投递过去。
        val referencedRelays = NostrReviewClient.findEventRelays(listOf(rootExcerptId, parentCommentId))
        val published = signAndPublish(
            signer,
            JSONObject()
                .put("kind", 1111)
                .put("content", text)
                .put("created_at", createdAt)
                .put(
                    "tags",
                    replyTags(
                        bookIdentifier = NostrReviewClient.bookIdentifierV2(book.name, book.author),
                        rootExcerptId = rootExcerptId,
                        rootExcerptPubkey = rootExcerptPubkey,
                        parentCommentId = parentCommentId,
                        parentAuthorPubkey = parentAuthorPubkey,
                        rootExcerptRelay = referencedRelays[rootExcerptId],
                        parentCommentRelay = referencedRelays[parentCommentId]
                    )
                ),
            extraRelays = referencedRelays.values.toList()
        )
        return PublishedComment(
            event = published.event,
            rootExcerptId = rootExcerptId,
            rootExcerptPubkey = rootExcerptPubkey
        )
    }

    suspend fun reactToComment(commentId: String, authorPubkey: String): String {
        val signer = NostrSigners.current()
            ?: throw IllegalStateException("Connect a Nostr signer before reacting")
        val commentRelay = NostrReviewClient.findEventRelays(listOf(commentId))[commentId]
        val published = signAndPublish(
            signer,
            JSONObject()
                .put("kind", 7)
                .put("content", "+")
                .put("created_at", System.currentTimeMillis() / 1000)
                .put(
                    "tags", reactionTags(commentId, authorPubkey, commentRelay)
                ),
            extraRelays = listOfNotNull(commentRelay)
        )
        return published.event.getString("id")
    }

    /** 取消点赞：按 NIP-09 发 kind 5 删除自己那条 reaction。 */
    suspend fun deleteReaction(reactionEventId: String) {
        require(reactionEventId.isNotBlank()) { "Reaction event id is required" }
        val signer = NostrSigners.current()
            ?: throw IllegalStateException("Connect a Nostr signer before reacting")
        signAndPublish(
            signer,
            JSONObject()
                .put("kind", 5)
                .put("content", "")
                .put("created_at", System.currentTimeMillis() / 1000)
                .put("tags", JSONArray().put(JSONArray().put("e").put(reactionEventId)))
        )
    }

    /** NIP-89 客户端标记；名字取项目现成常量 AppConst.APP_TAG，不写字面量。 */
    private fun clientTag(): JSONArray = JSONArray().put("client").put(AppConst.APP_TAG)

    /**
     * 事件引用标签：id 之后紧跟 relay hint（NIP-10/NIP-22 标准的第三位）。
     * hint 为空时保持原样只写 id，不写空串占位（与 Amethyst 的 arrayOfNotNull 一致）。
     */
    private fun tagWithHint(name: String, id: String, relayHint: String?): JSONArray {
        val tag = JSONArray().put(name).put(id)
        if (!relayHint.isNullOrBlank()) tag.put(relayHint)
        return tag
    }

    internal fun excerptTags(
        bookIdentifier: String,
        context: String,
        paragraph: String
    ): JSONArray = JSONArray()
        .put(JSONArray().put("i").put(bookIdentifier))
        .put(JSONArray().put("k").put(NostrReviewClient.BOOK_KIND))
        .put(JSONArray().put("context").put(context))
        .put(JSONArray().put("t").put(NostrReviewClient.paragraphTagValue(paragraph)))
        .put(clientTag())

    internal fun commentTags(
        bookIdentifier: String,
        excerptId: String,
        userPubkey: String,
        excerptRelay: String? = null
    ): JSONArray = JSONArray()
        .put(JSONArray().put("I").put(bookIdentifier))
        .put(tagWithHint("E", excerptId, excerptRelay))
        .put(JSONArray().put("K").put("9802"))
        .put(JSONArray().put("P").put(userPubkey))
        .put(tagWithHint("e", excerptId, excerptRelay))
        .put(JSONArray().put("k").put("9802"))
        .put(JSONArray().put("p").put(userPubkey))
        .put(clientTag())

    internal fun replyTags(
        bookIdentifier: String,
        rootExcerptId: String,
        rootExcerptPubkey: String,
        parentCommentId: String,
        parentAuthorPubkey: String,
        rootExcerptRelay: String? = null,
        parentCommentRelay: String? = null
    ): JSONArray = JSONArray()
        .put(JSONArray().put("I").put(bookIdentifier))
        .put(tagWithHint("E", rootExcerptId, rootExcerptRelay))
        .put(JSONArray().put("K").put("9802"))
        .put(JSONArray().put("P").put(rootExcerptPubkey))
        .put(tagWithHint("e", parentCommentId, parentCommentRelay))
        .put(JSONArray().put("k").put("1111"))
        .put(JSONArray().put("p").put(parentAuthorPubkey))
        .put(clientTag())

    internal fun reactionTags(
        commentId: String,
        authorPubkey: String,
        commentRelay: String? = null
    ): JSONArray = JSONArray()
        .put(tagWithHint("e", commentId, commentRelay))
        .put(JSONArray().put("p").put(authorPubkey))
        .put(clientTag())

    /** 已签名并投递的事件，以及哪些中继接受了它（供上层写 relay hint/扩大后续投递范围）。 */
    private data class Published(val event: JSONObject, val acceptedRelays: Set<String>)

    /**
     * 发布目标：签名器会话中继 ∪ 读取中继（会话+已配置/缓存），再并上被引用事件所在中继；
     * 去重且顺序稳定。
     */
    private fun publishTargets(
        signer: NostrSignerBackend,
        extraRelays: List<String>
    ): List<String> = (signer.signerRelays + NostrRelays.forReading() + extraRelays).distinct()

    private suspend fun signAndPublish(
        signer: NostrSignerBackend,
        unsignedEvent: JSONObject,
        extraRelays: List<String> = emptyList()
    ): Published {
        val signed = signer.sign(unsignedEvent)
        val signedTags = signed.optJSONArray("tags") ?: JSONArray()
        val parsedTags = (0 until signedTags.length()).map { index ->
            val tag = signedTags.getJSONArray(index)
            (0 until tag.length()).map { tag.getString(it) }
        }
        val kind = unsignedEvent.getInt("kind")
        require(signed.optInt("kind", -1) == kind) { "Signer changed the requested event kind" }
        require(signed.optString("pubkey") == signer.userPubkey) { "Signer returned an unexpected public key" }
        require(signed.optString("content") == unsignedEvent.getString("content")) {
            "Signer changed the requested event content"
        }
        require(parsedTags == jsonTagsToLists(unsignedEvent.getJSONArray("tags"))) {
            "Signer changed the requested event tags"
        }
        require(
            NostrReviewClient.verifyEvent(
                id = signed.getString("id"),
                pubkey = signed.getString("pubkey"),
                createdAt = signed.getLong("created_at"),
                kind = kind,
                tags = parsedTags,
                content = signed.getString("content"),
                signature = signed.getString("sig")
            )
        ) { "Signer returned an invalid Nostr signature" }
        val outcome = NostrNip46Transport.publishAndCollect(publishTargets(signer, extraRelays), signed)
        logPublishOutcome(kind, signed.getString("id"), outcome)
        return Published(signed, outcome.acceptedRelays)
    }

    /** 发布结果日志：只打 kind/id/中继与简短原因，不涉签名/密文。 */
    private fun logPublishOutcome(kind: Int, eventId: String, outcome: PublishOutcome) {
        val failures = outcome.results.filterNot { it.accepted }
        val failureText = if (failures.isEmpty()) {
            ""
        } else {
            " 失败=[" + failures.joinToString(", ") {
                "${relayHost(it.relay)}:${it.reason ?: "unknown"}"
            } + "]"
        }
        AppLog.put(
            "Nostr 发布 kind=$kind id=$eventId 接受=${outcome.acceptedRelays.size}/" +
                "${outcome.results.size}$failureText",
            tag = "Nostr"
        )
    }

    private fun jsonTagsToLists(tags: JSONArray): List<List<String>> =
        (0 until tags.length()).map { index ->
            val tag = tags.getJSONArray(index)
            (0 until tag.length()).map { tag.getString(it) }
        }
}
