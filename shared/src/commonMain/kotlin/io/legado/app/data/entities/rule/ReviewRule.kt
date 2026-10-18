package io.legado.app.data.entities.rule

import com.google.gson.JsonDeserializer
import io.legado.app.utils.INITIAL_GSON

data class ReviewRule(
    // 是否启用段评
    var enabled: Boolean = false,
    // 段评数量（章节加载后请求）
    var reviewSummaryUrl: String? = null,
    var summaryListRule: String? = null,
    var summaryParagraphIndexRule: String? = null,
    var summaryParagraphDataRule: String? = null,
    var summaryCountRule: String? = null,

    // 段评详情（点击图标请求，支持 paraIndex / paraData 变量）
    var reviewDetailUrl: String? = null,
    // 段评详情下一页URL（可选，可使用 result 解析响应）
    var reviewDetailNextPageUrl: String? = null,
    var detailListRule: String? = null,
    var detailIdRule: String? = null,
    var detailAvatarRule: String? = null,
    var detailNameRule: String? = null,
    var detailBadgeRule: String? = null,
    var detailContentRule: String? = null,

    // 子评论
    var replyListRule: String? = null,
    var replyIdRule: String? = null,
    var replyAvatarRule: String? = null,
    var replyNameRule: String? = null,
    var replyBadgeRule: String? = null,
    var replyContentRule: String? = null,
) {

    companion object {

        val jsonDeserializer = JsonDeserializer<ReviewRule?> { json, _, _ ->
            when {
                json.isJsonObject -> INITIAL_GSON.fromJson(json, ReviewRule::class.java)
                json.isJsonPrimitive -> INITIAL_GSON.fromJson(json.asString, ReviewRule::class.java)
                else -> null
            }
        }

    }

}
