package io.legado.app.help.nostr

import io.legado.app.utils.ACache
import org.json.JSONArray
import org.json.JSONObject

/**
 * 段评详情首页缓存（内存 LRU + 落盘），供段评弹窗打开时先渲染、章节打开时预热。
 */
internal object NostrReviewDetailCache {

    private const val DETAIL_CACHE_TTL_SECONDS = 24 * 60 * 60

    /** 详情首页落盘缓存：手写编解码（不用 Gson 反射，避开 R8 改名后读出空白项）。 */
    private val diskCache by lazy { ACache.get("review_details") }

    /** 详情首页的内存缓存(跨弹窗实例)：重开时先显已加载的，再联网刷新。 */
    private val memoryCache = object :
        LinkedHashMap<String, List<ReviewDetailItem>>(8, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, List<ReviewDetailItem>>
        ): Boolean = size > 8
    }

    fun key(bookUrl: String, chapterIndex: Int, paragraphNum: Int): String =
        "$bookUrl|$chapterIndex|$paragraphNum"

    fun contains(key: String): Boolean = synchronized(memoryCache) {
        memoryCache.containsKey(key)
    } || diskCache.getAsString(key) != null

    fun get(key: String): List<ReviewDetailItem>? {
        synchronized(memoryCache) {
            memoryCache[key]?.let { cached ->
                val filtered = cached.withoutPendingItems()
                if (filtered.isNotEmpty()) return filtered
            }
        }
        val json = diskCache.getAsString(key) ?: return null
        val items = detailItemsFromJson(json)?.withoutPendingItems() ?: return null
        if (items.isEmpty()) return null
        synchronized(memoryCache) { memoryCache[key] = items }
        return items
    }

    fun put(key: String, items: List<ReviewDetailItem>) {
        val snapshot = items.withoutPendingItems()
        if (snapshot.isEmpty()) return
        synchronized(memoryCache) { memoryCache[key] = snapshot }
        runCatching {
            diskCache.put(key, detailItemsToJson(snapshot), DETAIL_CACHE_TTL_SECONDS)
        }
    }

    /**
     * 本地乐观占位项(pendingState != null)只在当前弹窗内有效，发布成功后会被链上真实评论取代；
     * 一旦被写进缓存，重开该段落时就会与真实评论重复上屏，故读写两侧都要剔除。递归处理 replies。
     */
    private fun List<ReviewDetailItem>.withoutPendingItems(): List<ReviewDetailItem> =
        mapNotNull { item ->
            when {
                item.pendingState != null -> null
                item.replies.isEmpty() -> item
                else -> item.copy(replies = item.replies.withoutPendingItems())
            }
        }

    fun toItems(items: List<NostrReviewClient.Comment>): List<ReviewDetailItem> =
        items.map { it.toDetailItem() }

    private fun NostrReviewClient.Comment.toDetailItem(): ReviewDetailItem {
        val profile = NostrProfileStore.get(authorPubkey)
        return ReviewDetailItem(
            id = id,
            avatar = profile?.picture,
            name = profile?.bestName ?: name,
            nostrAuthorPubkey = authorPubkey,
            nostrRootExcerptId = rootExcerptId,
            nostrRootExcerptPubkey = rootExcerptPubkey,
            badges = emptyList(),
            content = content,
            imageUrl = null,
            audioUrl = null,
            time = time,
            createdAt = createdAt,
            likeCount = likeCount,
            likedByMe = likedByMe,
            myReactionId = myReactionId,
            replyCount = replies.size,
            replies = replies.map { it.toDetailItem() }
        )
    }

    private fun detailItemsToJson(items: List<ReviewDetailItem>): String =
        JSONArray().apply { items.forEach { put(detailItemToJson(it)) } }.toString()

    private fun detailItemsFromJson(json: String): List<ReviewDetailItem>? = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { detailItemFromJson(it) }
        }
    }.getOrNull()

    private fun detailItemToJson(item: ReviewDetailItem): JSONObject = JSONObject().apply {
        item.id?.let { put("id", it) }
        item.avatar?.let { put("avatar", it) }
        item.name?.let { put("name", it) }
        item.nostrAuthorPubkey?.let { put("author", it) }
        item.nostrRootExcerptId?.let { put("excerpt", it) }
        item.nostrRootExcerptPubkey?.let { put("excerptAuthor", it) }
        put("badges", JSONArray(item.badges))
        item.content?.let { put("content", it) }
        item.imageUrl?.let { put("image", it) }
        item.audioUrl?.let { put("audio", it) }
        item.time?.let { put("time", it) }
        item.createdAt?.let { put("createdAt", it) }
        item.likeCount?.let { put("like", it) }
        if (item.likedByMe) put("liked", true)
        item.myReactionId?.let { put("myReaction", it) }
        item.replyCount?.let { put("replyCount", it) }
        item.pendingState?.let { put("pending", it) }
        put("replies", JSONArray().apply { item.replies.forEach { put(detailItemToJson(it)) } })
    }

    private fun detailItemFromJson(json: JSONObject): ReviewDetailItem {
        val badgesJson = json.optJSONArray("badges") ?: JSONArray()
        val repliesJson = json.optJSONArray("replies") ?: JSONArray()
        return ReviewDetailItem(
            id = json.optString("id").takeIf { it.isNotEmpty() },
            avatar = json.optString("avatar").takeIf { it.isNotEmpty() },
            name = json.optString("name").takeIf { it.isNotEmpty() },
            nostrAuthorPubkey = json.optString("author").takeIf { it.isNotEmpty() },
            nostrRootExcerptId = json.optString("excerpt").takeIf { it.isNotEmpty() },
            nostrRootExcerptPubkey = json.optString("excerptAuthor").takeIf { it.isNotEmpty() },
            badges = (0 until badgesJson.length()).map { badgesJson.optString(it) },
            content = json.optString("content").takeIf { it.isNotEmpty() },
            imageUrl = json.optString("image").takeIf { it.isNotEmpty() },
            audioUrl = json.optString("audio").takeIf { it.isNotEmpty() },
            time = json.optString("time").takeIf { it.isNotEmpty() },
            createdAt = if (json.has("createdAt")) json.optLong("createdAt") else null,
            likeCount = if (json.has("like")) json.optInt("like") else null,
            likedByMe = json.optBoolean("liked"),
            myReactionId = json.optString("myReaction").takeIf { it.isNotEmpty() },
            replyCount = if (json.has("replyCount")) json.optInt("replyCount") else null,
            pendingState = json.optString("pending").takeIf { it.isNotEmpty() },
            replies = (0 until repliesJson.length()).mapNotNull { index ->
                repliesJson.optJSONObject(index)?.let { detailItemFromJson(it) }
            }
        )
    }
}
