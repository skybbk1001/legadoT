package io.legado.app.help.nostr

/**
 * 段评详情列表条目。原为段评弹窗的私有模型，提升为共享类型以便详情缓存被章节预热复用。
 */
internal data class ReviewDetailItem(
    val id: String?,
    val avatar: String?,
    val name: String?,
    val nostrAuthorPubkey: String? = null,
    val nostrRootExcerptId: String? = null,
    val nostrRootExcerptPubkey: String? = null,
    val badges: List<String>,
    val content: String?,
    val imageUrl: String?,
    val audioUrl: String?,
    val time: String?,
    val likeCount: Int?,
    val likedByMe: Boolean = false,
    val myReactionId: String? = null,
    val replyCount: Int?,
    val replies: List<ReviewDetailItem>,
    /** 本地乐观插入的发布态：sending/failed；null=正常(已落库或来自网络)。 */
    val pendingState: String? = null,
    /** 事件原始时间戳(秒)；书源条目无此数据，为 null。 */
    val createdAt: Long? = null
)
