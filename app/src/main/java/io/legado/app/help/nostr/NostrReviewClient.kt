package io.legado.app.help.nostr

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import fr.acinq.secp256k1.Secp256k1
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.MessageDigest
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object NostrReviewClient {

    private fun relays(): List<String> = NostrRelays.forReading()

    /** 章节标题作匹配信号的最小长度（归一化后），过短/通用的标题不计分。 */
    private const val TITLE_MIN_LENGTH = 4

    /** 段落 `t` 标签前缀：`legado:paragraph:<归一化段落的 sha256>`；写端与 [matchParagraphs] 共用。 */
    internal const val PARAGRAPH_TAG_PREFIX = "legado:paragraph:"

    /** 容错匹配：摘录去空白后的最小尝试长度。 */
    private const val MIN_FUZZY_LENGTH = 16

    /** 容错匹配：整段在章内找不到时，改用中段的定长窗口。 */
    private const val FUZZY_WINDOW = 24

    /** 容错匹配：命中段落所需的最小重叠长度。 */
    private const val MIN_OVERLAP_LENGTH = 16

    data class Summary(
        val counts: Map<Int, Int>,
        /** 属于本章但未能定位到段落的摘录，其顶层评论；供章末汇总展示。 */
        val unlocated: List<Comment> = emptyList(),
        /**
         * 中继全部不可用（如断网）导致摘录查询失败。此时 [counts] 只是「首评占位」的空段统计，
         * 不代表真实数据；调用方不得用它覆盖已缓存统计。
         */
        val relayFailed: Boolean = false
    )

    data class Comment(
        val id: String,
        val name: String,
        val authorPubkey: String,
        val rootExcerptId: String,
        val rootExcerptPubkey: String,
        val content: String,
        val time: String,
        val createdAt: Long,
        val likeCount: Int,
        val likedByMe: Boolean = false,
        val myReactionId: String? = null,
        val replies: List<Comment>
    )

    data class Details(
        val items: List<Comment>,
        val hasMore: Boolean
    )

    suspend fun loadSummary(
        book: Book,
        chapterTitle: String,
        paragraphs: List<String>,
        allowCreatingComments: Boolean = false
    ): Summary {
        if (paragraphs.isEmpty()) return Summary(emptyMap())
        val bookTags = bookIdentifiers(book.name, book.author)
        // 中继全部不可用时降级为「暂无摘录」:已登录用户仍能看到可首评的占位图标,
        // 而不是因为查询异常整章都没有段评入口(发布时的中继错误会单独提示)。
        var relayFailed = false
        val excerpts = runCatching {
            queryEvents(
                JSONObject()
                    .put("kinds", JSONArray().put(9802))
                    .put("#i", JSONArray(bookTags))
                    .put("limit", 1000)
            )
        }.onFailure {
            if (it is CancellationException) throw it
            relayFailed = true
            AppLog.put("查询 Nostr 段落摘录失败\n${it.localizedMessage}", it, tag = "Nostr")
        }.getOrDefault(emptyList())
        val matched = matchParagraphs(excerpts, paragraphs, chapterTitle)
        val unlocated = loadUnlocatedComments(chapterUnlocated(excerpts, matched, chapterTitle))
        val excerptIds = matched.values.flatten().map { it.id }.distinct()
        if (excerptIds.isEmpty()) {
            val writableParagraphs = if (allowCreatingComments) {
                paragraphs.mapIndexedNotNull { index, paragraph ->
                    if (paragraph.isNotBlank()) index + 1 to -1 else null
                }.toMap()
            } else {
                emptyMap()
            }
            return Summary(writableParagraphs, unlocated, relayFailed)
        }

        val comments = queryComments(excerptIds)
        val commentCounts = comments.groupingBy { it.rootId }.eachCount()
        val counts = matched.mapNotNull { (paragraphIndex, events) ->
            val count = events.sumOf { commentCounts[it.id] ?: 0 }
            when {
                count > 0 -> paragraphIndex to count
                allowCreatingComments -> paragraphIndex to -1
                else -> null
            }
        }.toMap()
        if (!allowCreatingComments) return Summary(counts, unlocated, relayFailed)
        val visibleCounts = counts.toMutableMap()
        paragraphs.forEachIndexed { index, paragraph ->
            if (paragraph.isNotBlank()) visibleCounts.putIfAbsent(index + 1, -1)
        }
        return Summary(visibleCounts, unlocated, relayFailed)
    }

    /** 属于本章（context 含本章标题）但没匹配到任何段落的摘录。 */
    private fun chapterUnlocated(
        excerpts: List<NostrEvent>,
        matched: Map<Int, List<NostrEvent>>,
        chapterTitle: String
    ): List<NostrEvent> {
        val identityTitle = normalizeForIdentity(chapterTitle)
            .takeIf { it.length >= TITLE_MIN_LENGTH } ?: return emptyList()
        val matchedIds = matched.values.flatten().mapTo(HashSet()) { it.id }
        return excerpts.filter { event ->
            event.id !in matchedIds &&
                normalizeForIdentity(event.tagValues("context").firstOrNull().orEmpty())
                    .contains(identityTitle)
        }
    }

    /** 拉取未定位摘录的顶层评论（含楼内回复与点赞数），供章末汇总展示。 */
    private suspend fun loadUnlocatedComments(excerpts: List<NostrEvent>): List<Comment> {
        val ids = excerpts.map { it.id }.distinct()
        if (ids.isEmpty()) return emptyList()
        val comments = queryComments(ids)
        if (comments.isEmpty()) return emptyList()
        val reactions = queryReactions(comments.map { it.id })
        val likeCounts = reactions.filter { it.content == "+" }
            .groupBy { it.tagValues("e").firstOrNull().orEmpty() }
            .mapValues { (_, events) -> events.distinctBy { it.pubkey }.size }
        val commentsByExcerpt = comments.groupBy { it.rootId }
        val excerptAuthors = excerpts.associate { it.id to it.pubkey }
        return ids.flatMap { excerptId ->
            buildThread(
                commentsByExcerpt[excerptId].orEmpty(),
                excerptId,
                excerptAuthors[excerptId].orEmpty()
            )
        }.sortedByDescending { it.createdAt }
            .map { it.toComment(likeCounts) }
    }

    suspend fun loadDetails(
        book: Book,
        chapterTitle: String,
        paragraphs: List<String>,
        paragraphIndex: Int,
        page: Int
    ): Details {
        if (paragraphs.getOrNull(paragraphIndex - 1).isNullOrBlank()) {
            return Details(emptyList(), false)
        }
        val excerpts = queryEvents(
            JSONObject()
                .put("kinds", JSONArray().put(9802))
                .put("#i", JSONArray(bookIdentifiers(book.name, book.author)))
                .put("limit", 1000)
        )
        val matching = matchParagraphs(excerpts, paragraphs, chapterTitle)[paragraphIndex].orEmpty()
        if (matching.isEmpty()) return Details(emptyList(), false)
        val excerptIds = matching.map { it.id }.distinct()
        val comments = queryComments(excerptIds)
        val reactions = queryReactions(comments.map { it.id })
        val likeCounts = reactions.filter { it.content == "+" }
            .groupBy { it.tagValues("e").firstOrNull().orEmpty() }
            .mapValues { (_, events) -> events.distinctBy { it.pubkey }.size }
        val myPubkey = NostrSigners.currentPubkey()
        val likedIds: Set<String> = if (myPubkey.isNullOrBlank()) {
            emptySet()
        } else {
            reactions.filter { it.content == "+" && it.pubkey == myPubkey }
                .mapNotNull { it.tagValues("e").firstOrNull() }
                .toSet()
        }
        // 自己那条 reaction 的事件 id，用于再次点击时按 NIP-09 删除
        val myReactionIds: Map<String, String> = if (myPubkey.isNullOrBlank()) {
            emptyMap()
        } else {
            reactions.filter { it.content == "+" && it.pubkey == myPubkey }
                .mapNotNull { event ->
                    event.tagValues("e").firstOrNull()?.let { commentId -> commentId to event.id }
                }
                .toMap()
        }
        val excerptAuthors = matching.associate { it.id to it.pubkey }
        // 建树以「本段全部评论」为上下文：回复的父评论可能属于同段的另一条摘录，
        // 只按单条摘录分组会把这类回复当成孤儿丢掉，出现「计数有、列表无」。
        val roots = excerptIds.flatMap { excerptId ->
            buildThread(comments, excerptId, excerptAuthors[excerptId].orEmpty())
        }
        // 兜底：父评论不在本段评论集合里的（跨摘录/父事件取不到/深于层级上限）挂到顶层，
        // 保证统计里数到的评论都能显示出来。
        val reachableIds = HashSet<String>()
        fun markReachable(list: List<NostrEvent>) {
            list.forEach { event ->
                reachableIds.add(event.id)
                markReachable(event.children)
            }
        }
        markReachable(roots)
        val rescued = comments.filterNot { it.id in reachableIds }
            .map { it.copy(children = emptyList()) }
        val allRoots = (roots + rescued).sortedByDescending { it.createdAt }
        val pageSize = 20
        val pageItems = allRoots.drop((page - 1).coerceAtLeast(0) * pageSize).take(pageSize)
        return Details(
            items = pageItems.map { it.toComment(likeCounts, likedIds, myReactionIds) },
            hasMore = allRoots.size > page.coerceAtLeast(1) * pageSize
        )
    }

    /** 复用已有摘录时同时带回它所在的中继（用于给评论写 relay hint）；没有则 relay 为 null。 */
    internal data class ExistingExcerpt(val id: String, val relay: String?)

    /** 该段落在当前中继上已有的最新摘录；没有返回 null。 */
    internal suspend fun findParagraphExcerpt(
        book: Book,
        chapterTitle: String,
        paragraphs: List<String>,
        paragraphIndex: Int
    ): ExistingExcerpt? = runCatching {
        val (excerpts, provenance) = queryEventsWithProvenance(
            JSONObject()
                .put("kinds", JSONArray().put(9802))
                .put("#i", JSONArray(bookIdentifiers(book.name, book.author)))
                .put("limit", 1000)
        )
        val excerpt = matchParagraphs(excerpts, paragraphs, chapterTitle)[paragraphIndex]
            ?.maxByOrNull { it.createdAt }
            ?: return@runCatching null
        ExistingExcerpt(excerpt.id, provenance[excerpt.id]?.firstOrNull())
    }.onFailure {
        if (it is CancellationException) throw it
    }.getOrNull()

    /** 按事件 id 查得「它由哪些中继返回」的其中一个中继，用于发布时的 relay hint。 */
    internal suspend fun findEventRelays(ids: Collection<String>): Map<String, String> = runCatching {
        val distinctIds = ids.filter { it.isNotBlank() }.distinct()
        if (distinctIds.isEmpty()) return@runCatching emptyMap()
        val (_, provenance) = queryEventsWithProvenance(
            JSONObject()
                .put("ids", JSONArray(distinctIds))
                .put("limit", distinctIds.size)
        )
        provenance.mapNotNull { (id, relays) -> relays.firstOrNull()?.let { id to it } }.toMap()
    }.onFailure {
        if (it is CancellationException) throw it
    }.getOrNull().orEmpty()

    /** 按事件 id 在当前读取中继上取回事件（取回不到返回 null）。 */
    internal suspend fun fetchEventById(id: String): NostrEvent? = runCatching {
        queryEvents(JSONObject().put("ids", JSONArray().put(id)).put("limit", 1))
            .firstOrNull()
    }.onFailure {
        if (it is CancellationException) throw it
    }.getOrNull()

    /**
     * 拉取 kind 0 账号资料（头像/昵称）；同一 pubkey 取 created_at 最新的一条。
     */
    suspend fun loadProfiles(pubkeys: Collection<String>): List<NostrProfileStore.Profile> {
        val authors = pubkeys.asSequence()
            .map { it.lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        if (authors.isEmpty()) return emptyList()
        val latest = HashMap<String, NostrEvent>()
        // 资料（kind 0）不能只依赖「会话+已配置」中继：作者的资料可能只存在于其它公开中继，
        // 这里额外带上内置种子中继；命中情况写日志便于排查。
        val profileRelays = (relays() + NostrRelays.seeds()).distinct()
        authors.chunked(50).forEach { batch ->
            val events = runCatching {
                queryEvents(
                    JSONObject()
                        .put("kinds", JSONArray().put(0))
                        .put("authors", JSONArray(batch))
                        .put("limit", batch.size),
                    activeRelayList = profileRelays
                )
            }.onFailure {
                if (it is CancellationException) throw it
                AppLog.put("加载 Nostr 账号资料失败\n${it.localizedMessage}", it)
            }.getOrDefault(emptyList())
            events.forEach { event ->
                val old = latest[event.pubkey]
                if (old == null || event.createdAt > old.createdAt) {
                    latest[event.pubkey] = event
                }
            }
        }
        val now = System.currentTimeMillis()
        if (latest.isEmpty()) {
            AppLog.put(
                "Nostr 账号资料未命中\n中继=${profileRelays.joinToString(" ")}\n作者=${authors.joinToString(" ")}",
                tag = "Nostr"
            )
        } else {
            AppLog.put(
                "Nostr 账号资料命中 ${latest.size} 条：${latest.keys.joinToString(" ")}",
                tag = "Nostr"
            )
        }
        return latest.values.mapNotNull { event ->
            val content = runCatching { JSONObject(event.content) }.getOrNull()
                ?: return@mapNotNull null
            NostrProfileStore.Profile(
                pubkey = event.pubkey,
                name = content.optString("name").takeIf { it.isNotBlank() },
                displayName = content.optString("display_name").takeIf { it.isNotBlank() },
                picture = content.optString("picture").takeIf { it.isNotBlank() },
                updatedAt = now
            )
        }
    }

    const val BOOK_KIND = "legado:book"

    fun bookIdentifier(name: String, author: String): String {
        val normalizedName = normalize(name).lowercase(Locale.ROOT)
        val normalizedAuthor = normalize(author).lowercase(Locale.ROOT)
        return "$BOOK_KIND:${sha256("v1|$normalizedName|$normalizedAuthor")}"
    }

    /** v2 书标识：作者在前、值带版本、明文可读可纠错。 */
    fun bookIdentifierV2(name: String, author: String): String =
        "$BOOK_KIND:v2|${escapeIdentityPart(normalizeForIdentity(author))}|" +
            escapeIdentityPart(normalizeForIdentity(name))

    /** 查询用：同时覆盖 v1（历史哈希）与 v2。 */
    fun bookIdentifiers(name: String, author: String): List<String> =
        listOf(bookIdentifier(name, author), bookIdentifierV2(name, author))

    private fun escapeIdentityPart(value: String): String =
        value.replace("\\", "\\\\").replace("|", "\\|")

    /**
     * v2 标识归一化：NFKC → 去空白(含全角空格) → 小写；全部处理完后若为纯 ASCII 保留标点，
     * 否则再去标点/符号/分隔/控制字符。发布端与读端必须一致，规则冻结（改动需升版本）。
     */
    internal fun normalizeForIdentity(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace(Regex("[\\s\u3000]+"), "")
            .lowercase(Locale.ROOT)
        if (normalized.all { it.code < 128 }) return normalized
        return normalized.replace(Regex("[\\p{P}\\p{S}\\p{Z}\\p{C}]"), "")
    }

    internal fun matchParagraphs(
        excerpts: List<NostrEvent>,
        paragraphs: List<String>,
        chapterTitle: String
    ): Map<Int, List<NostrEvent>> {
        val normalizedParagraphs = paragraphs.map(::normalizeContent)
        val identityTitle = normalizeForIdentity(chapterTitle)
            .takeIf { it.length >= TITLE_MIN_LENGTH }
        val indexesByText = normalizedParagraphs.mapIndexedNotNull { index, paragraph ->
            paragraph.takeIf { it.isNotEmpty() }?.let { it to index }
        }.groupBy({ it.first }, { it.second })
        val indexesByHash = normalizedParagraphs.mapIndexedNotNull { index, paragraph ->
            paragraph.takeIf { it.isNotEmpty() }?.let { sha256(it) to index }
        }.groupBy({ it.first }, { it.second })
        // 去空白视图：重新分段只动边界、不改字符序，故 compact 文本与分段方式无关，用于容错定位。
        val compactParagraphs = paragraphs.map(::compact)
        val compactFull = compactParagraphs.joinToString("")
        val compactStarts = IntArray(compactParagraphs.size)
        var compactOffset = 0
        compactParagraphs.forEachIndexed { index, text ->
            compactStarts[index] = compactOffset
            compactOffset += text.length
        }
        val matches = mutableMapOf<Int, MutableList<NostrEvent>>()
        excerpts.forEach { event ->
            val content = normalizeContent(event.content)
            val candidateIndexes = linkedSetOf<Int>()
            candidateIndexes.addAll(indexesByText[content].orEmpty())
            val contentHash = content.removePrefix("sha256:")
            candidateIndexes.addAll(indexesByHash[contentHash].orEmpty())
            event.tagValues("t").forEach { tag ->
                val paragraphHash = tag.removePrefix(PARAGRAPH_TAG_PREFIX)
                candidateIndexes.addAll(indexesByHash[paragraphHash].orEmpty())
            }
            val candidates = candidateIndexes.toList()
            val rawContext = event.tagValues("context").firstOrNull().orEmpty()
            val context = normalizeContent(rawContext)
            val titleScore = if (
                identityTitle != null && normalizeForIdentity(rawContext).contains(identityTitle)
            ) 4 else 0
            val contextScore: (Int) -> Int = { index ->
                var score = 0
                normalizedParagraphs.getOrNull(index - 1)?.takeLast(24)
                    ?.takeIf { it.length >= 8 && context.contains(it) }?.let { score++ }
                normalizedParagraphs.getOrNull(index + 1)?.take(24)
                    ?.takeIf { it.length >= 8 && context.contains(it) }?.let { score++ }
                score
            }
            val selected = when {
                // 精确匹配无候选：正文可能被重新分段（合段/拆段），改用去空白的整章文本定位。
                candidates.isEmpty() -> fuzzyParagraphIndex(
                    content = event.content,
                    compactParagraphs = compactParagraphs,
                    compactStarts = compactStarts,
                    compactFull = compactFull,
                    contextScore = contextScore
                )?.let { index -> listOf(index) }.orEmpty()

                candidates.size > 1 -> {
                    val scores = candidates.associateWith { index ->
                        titleScore + contextScore(index)
                    }
                    val bestScore = scores.values.maxOrNull() ?: 0
                    val best = scores.filterValues { it == bestScore }.keys
                    if (bestScore > 0 && best.size == 1) best else candidates
                }

                context.isNotEmpty() && titleScore == 0 &&
                    contextScore(candidates.single()) == 0 -> emptyList()

                else -> candidates
            }
            selected.forEach { index ->
                matches.getOrPut(index + 1) { mutableListOf() }.add(event)
            }
        }
        return matches
    }

    /**
     * 去空白后在整章文本里定位一段正文，返回与之重叠最大的段落下标。
     * 先找整段；整段找不到时退回中段定长窗口（长片段包含）。多处并列时 context 分高者优先，仍并列取靠前；
     * 重叠不足 [MIN_OVERLAP_LENGTH] 视为未命中。
     */
    private fun fuzzyParagraphIndex(
        content: String,
        compactParagraphs: List<String>,
        compactStarts: IntArray,
        compactFull: String,
        contextScore: (Int) -> Int
    ): Int? {
        val condensed = compact(content)
        if (condensed.length < MIN_FUZZY_LENGTH) return null
        val needle = if (compactFull.contains(condensed)) {
            condensed
        } else {
            if (condensed.length <= FUZZY_WINDOW) return null
            val from = (condensed.length - FUZZY_WINDOW) / 2
            condensed.substring(from, from + FUZZY_WINDOW)
                .takeIf { compactFull.contains(it) } ?: return null
        }
        var bestIndex: Int? = null
        var bestOverlap = 0
        var bestContext = 0
        var searchFrom = 0
        while (true) {
            val hit = compactFull.indexOf(needle, searchFrom)
            if (hit < 0) break
            val hitEnd = hit + needle.length
            compactParagraphs.forEachIndexed { index, text ->
                val paragraphStart = compactStarts[index]
                val paragraphEnd = paragraphStart + text.length
                val overlap = minOf(paragraphEnd, hitEnd) - maxOf(paragraphStart, hit)
                if (overlap <= 0) return@forEachIndexed
                val score = contextScore(index)
                if (overlap > bestOverlap ||
                    (overlap == bestOverlap && (bestIndex == null || score > bestContext))
                ) {
                    bestIndex = index
                    bestOverlap = overlap
                    bestContext = score
                }
            }
            searchFrom = hit + 1
        }
        return if (bestIndex != null && bestOverlap >= MIN_OVERLAP_LENGTH) bestIndex else null
    }

    private suspend fun queryComments(excerptIds: List<String>): List<NostrEvent> {
        return excerptIds.distinct().chunked(50).flatMap { batch ->
            queryEvents(
                JSONObject()
                    .put("kinds", JSONArray().put(1111))
                    .put("#E", JSONArray(batch))
                    .put("limit", 1000)
            )
        }.distinctBy { it.id }
    }

    private suspend fun queryReactions(commentIds: List<String>): List<NostrEvent> {
        return commentIds.distinct().chunked(50).flatMap { batch ->
            queryEvents(
                JSONObject()
                    .put("kinds", JSONArray().put(7))
                    .put("#e", JSONArray(batch))
                    .put("limit", 1000)
            )
        }.distinctBy { it.id }
    }

    private suspend fun queryEvents(
        filter: JSONObject,
        activeRelayList: List<String> = relays()
    ): List<NostrEvent> = queryEventsWithProvenance(filter, activeRelayList).first

    /**
     * 与 [queryEvents] 相同的中继语义（失败中继忽略、任一成功即可、失败全无才抛），
     * 但额外带回「每条事件由哪些中继返回」的索引，用于发布时给事件引用写 relay hint。
     */
    private suspend fun queryEventsWithProvenance(
        filter: JSONObject,
        activeRelayList: List<String> = relays()
    ): Pair<List<NostrEvent>, Map<String, Set<String>>> = coroutineScope {
        val activeRelays = activeRelayList
        val expectedKinds = filter.optJSONArray("kinds")?.let { kinds ->
            (0 until kinds.length()).map { kinds.optInt(it) }.toSet()
        }.orEmpty()
        val results = activeRelays.map { relay ->
            async(Dispatchers.IO) {
                runCatching { queryRelay(relay, filter) }
            }
        }.awaitAll()
        val successful = results.filter { it.isSuccess }
        if (successful.isEmpty()) {
            throw results.firstOrNull()?.exceptionOrNull() ?: IllegalStateException("Nostr relay unavailable")
        }
        val events = mutableListOf<NostrEvent>()
        val provenance = mutableMapOf<String, MutableSet<String>>()
        activeRelays.forEachIndexed { index, relay ->
            results[index].getOrNull().orEmpty().forEach { event ->
                if (expectedKinds.isEmpty() || event.kind in expectedKinds) {
                    events.add(event)
                    provenance.getOrPut(event.id) { linkedSetOf() }.add(relay)
                }
            }
        }
        events.distinctBy { it.id } to provenance
    }

    private suspend fun queryRelay(relay: String, filter: JSONObject): List<NostrEvent> =
        withTimeout(10_000) {
            suspendCancellableCoroutine { continuation ->
                val subscriptionId = UUID.randomUUID().toString().replace("-", "")
                val events = mutableListOf<NostrEvent>()
                val finished = AtomicBoolean(false)
                lateinit var socket: WebSocket
                socket = nostrWebSocketClient.newWebSocket(
                    Request.Builder().url(relay).build(),
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send(
                                JSONArray().put("REQ").put(subscriptionId).put(filter).toString()
                            )
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            runCatching {
                                val message = JSONArray(text)
                                when (message.optString(0)) {
                                    "EVENT" -> if (message.optString(1) == subscriptionId) {
                                        if (!finished.get()) {
                                            message.optJSONObject(2)?.let(NostrEvent::fromJson)?.let(events::add)
                                        }
                                    }

                                    "EOSE" -> if (message.optString(1) == subscriptionId &&
                                        finished.compareAndSet(false, true)
                                    ) {
                                        webSocket.send(JSONArray().put("CLOSE").put(subscriptionId).toString())
                                        webSocket.close(1000, "query complete")
                                        continuation.resume(events.toList())
                                    }

                                    "NOTICE" -> if (message.optString(1).contains("error", true) &&
                                        finished.compareAndSet(false, true)
                                    ) {
                                        webSocket.close(1000, "relay notice")
                                        continuation.resumeWithException(
                                            IllegalStateException(message.optString(1))
                                        )
                                    }
                                }
                            }
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            if (finished.compareAndSet(false, true)) {
                                continuation.resumeWithException(t)
                            }
                        }
                    }
                )
                continuation.invokeOnCancellation {
                    if (finished.compareAndSet(false, true)) socket.cancel()
                }
            }
        }

    private fun buildThread(
        events: List<NostrEvent>,
        excerptId: String,
        excerptAuthor: String
    ): List<NostrEvent> {
        val byParent = events.groupBy { event ->
            event.tagValues("e").lastOrNull() ?: event.rootId
        }
        val eventIds = events.mapTo(HashSet()) { it.id }
        fun children(parentId: String, depth: Int): List<NostrEvent> {
            if (depth >= 5) return emptyList()
            return byParent[parentId].orEmpty()
                .sortedBy { it.createdAt }
                .map {
                    it.copy(
                        children = children(it.id, depth + 1),
                        rootExcerptPubkey = excerptAuthor
                    )
                }
        }
        return children(excerptId, 0).filter { it.id in eventIds }
    }

    private fun NostrEvent.toComment(
        likeCounts: Map<String, Int>,
        likedIds: Set<String> = emptySet(),
        myReactionIds: Map<String, String> = emptyMap()
    ): Comment = Comment(
        id = id,
        name = pubkey.take(12).ifBlank { "Nostr" },
        authorPubkey = pubkey,
        rootExcerptId = rootId,
        rootExcerptPubkey = rootExcerptPubkey ?: tagValues("P").firstOrNull().orEmpty(),
        content = content,
        time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(createdAt * 1000)),
        createdAt = createdAt,
        likeCount = likeCounts[id] ?: 0,
        likedByMe = id in likedIds,
        myReactionId = myReactionIds[id],
        replies = children.map { it.toComment(likeCounts, likedIds, myReactionIds) }
    )

    /** v1 书标识专用归一化（仅折叠 ASCII 空白）。历史哈希依赖它，已冻结；正文匹配请用 [normalizeContent]。 */
    private fun normalize(value: String): String = value
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * 正文/上下文归一化：折叠空白（含全角空格 U+3000）。
     * 与 v1 书标识所用的 [normalize] 分离，避免改动历史哈希。
     */
    internal fun normalizeContent(value: String): String = value
        .replace(Regex("[\\s\\u3000]+"), " ")
        .trim()

    /** 去掉全部空白（含 U+3000）：重新分段只动边界、不改字符序，故去空白后与分段方式无关。 */
    private fun compact(value: String): String = value
        .replace(Regex("[\\s\\u3000]+"), "")

    /** 段落 `t` 标签值；其归一化与哈希必须与 [matchParagraphs] 的 `indexesByHash` 一致。 */
    internal fun paragraphTagValue(paragraph: String): String =
        PARAGRAPH_TAG_PREFIX + sha256(normalizeContent(paragraph))

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    internal fun verifyEvent(
        id: String,
        pubkey: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String,
        signature: String
    ): Boolean = runCatching {
        val calculatedId = nostrEventId(pubkey, createdAt, kind, tags, content)
        val suppliedId = id.hexToByteArray() ?: return false
        if (!MessageDigest.isEqual(calculatedId, suppliedId)) return false
        val signatureBytes = signature.hexToByteArray() ?: return false
        val publicKeyBytes = pubkey.hexToByteArray() ?: return false
        if (signatureBytes.size != 64 || publicKeyBytes.size != 32) return false
        Secp256k1.verifySchnorr(
            signatureBytes,
            calculatedId,
            publicKeyBytes
        )
    }.getOrDefault(false)

    private fun String.hexToByteArray(): ByteArray? {
        if (length % 2 != 0 || any { it.digitToIntOrNull(16) == null }) return null
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    internal data class NostrEvent(
        val id: String,
        val pubkey: String,
        val content: String,
        val createdAt: Long,
        val kind: Int,
        val tags: List<List<String>>,
        val children: List<NostrEvent> = emptyList(),
        val rootExcerptPubkey: String? = null
    ) {
        val rootId: String get() = tagValues("E").firstOrNull().orEmpty()

        fun tagValues(name: String): List<String> = tags.mapNotNull { tag ->
            tag.getOrNull(1)?.takeIf { tag.firstOrNull() == name }
        }

        companion object {
            fun fromJson(json: JSONObject): NostrEvent? {
                val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
                val pubkey = json.optString("pubkey").takeIf { it.isNotBlank() } ?: return null
                val createdAt = json.optLong("created_at", -1).takeIf { it >= 0 } ?: return null
                val kind = json.optInt("kind", -1)
                    .takeIf { it == 0 || it == 7 || it == 9802 || it == 1111 || it == 24133 } ?: return null
                val content = json.optString("content")
                val signature = json.optString("sig").takeIf { it.isNotBlank() } ?: return null
                val tags = json.optJSONArray("tags") ?: JSONArray()
                val parsedTags = (0 until tags.length()).mapNotNull { index ->
                    val tag = tags.optJSONArray(index) ?: return@mapNotNull null
                    (0 until tag.length()).map { tag.optString(it) }
                }
                if (id.hexToByteArray()?.size != 32 || pubkey.hexToByteArray()?.size != 32 ||
                    signature.hexToByteArray()?.size != 64
                ) return null
                if (!verifyEvent(id, pubkey, createdAt, kind, parsedTags, content, signature)) return null
                return NostrEvent(id, pubkey, content, createdAt, kind, parsedTags)
            }
        }
    }
}

/**
 * NIP-01 规范序列化：`[0,pubkey,created_at,kind,tags,content]`。
 *
 * 不能交给 org.json：Android 的 JSONStringer 会把 "/" 转义成 "\/"，而 NIP-44 的 base64 载荷与
 * URL 类标签都常含 "/"，会使本地算出的 id 与中继/签名器重算的结果不一致（事件被拒收）。
 * 故手写 JSON 转义（只处理 "、\ 与控制字符），保留 "/" 原样。
 */
internal fun canonicalEventJson(
    pubkey: String,
    createdAt: Long,
    kind: Int,
    tags: List<List<String>>,
    content: String
): String = buildString {
    append("[0,")
    appendJsonString(pubkey)
    append(',').append(createdAt)
    append(',').append(kind)
    append(",[")
    tags.forEachIndexed { index, tag ->
        if (index > 0) append(',')
        append('[')
        tag.forEachIndexed { tagIndex, value ->
            if (tagIndex > 0) append(',')
            appendJsonString(value)
        }
        append(']')
    }
    append("],")
    appendJsonString(content)
    append(']')
}

internal fun nostrEventId(
    pubkey: String,
    createdAt: Long,
    kind: Int,
    tags: List<List<String>>,
    content: String
): ByteArray = MessageDigest.getInstance("SHA-256")
    .digest(canonicalEventJson(pubkey, createdAt, kind, tags, content).toByteArray(Charsets.UTF_8))

private fun StringBuilder.appendJsonString(value: String) {
    append('"')
    for (c in value) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
    append('"')
}