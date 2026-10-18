package io.legado.app.data.entities.rule

import com.google.gson.JsonDeserializer
import io.legado.app.utils.INITIAL_GSON

data class TocRule(
    var preUpdateJs: String? = null,
    var chapterList: String? = null,
    var chapterName: String? = null,
    var chapterUrl: String? = null,
    var formatJs: String? = null,
    var isVolume: String? = null,
    var isVip: String? = null,
    var isPay: String? = null,
    var updateTime: String? = null,
    var nextTocUrl: String? = null
) {

    companion object {

        val jsonDeserializer = JsonDeserializer<TocRule?> { json, _, _ ->
            when {
                json.isJsonObject -> INITIAL_GSON.fromJson(json, TocRule::class.java)
                json.isJsonPrimitive -> INITIAL_GSON.fromJson(json.asString, TocRule::class.java)
                else -> null
            }
        }

    }

}