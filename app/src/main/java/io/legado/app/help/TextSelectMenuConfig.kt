package io.legado.app.help

import com.google.gson.Gson

/**
 * 正文文字选择浮动条的按钮配置(纯逻辑,无 Android 依赖,便于单测)。
 * [bar] 为显示在浮动条上的按钮 key(有序),[more] 为收进"更多"的按钮 key(有序)。
 */
data class TextSelectMenuConfig(
    val bar: List<String> = emptyList(),
    val more: List<String> = emptyList()
) {

    fun toJson(): String = gson.toJson(this)

    /**
     * 归一化:丢弃未知 key、跨两区去重(先出现者优先)、把已知但缺失的 key 按
     * 规范顺序补到 [more] 末尾。
     */
    fun normalized(knownKeys: List<String> = ALL_KEYS): TextSelectMenuConfig {
        val known = knownKeys.toHashSet()
        val seen = LinkedHashSet<String>()
        val newBar = ArrayList<String>()
        for (k in bar) if (k in known && seen.add(k)) newBar.add(k)
        val newMore = ArrayList<String>()
        for (k in more) if (k in known && seen.add(k)) newMore.add(k)
        for (k in knownKeys) if (seen.add(k)) newMore.add(k)
        return TextSelectMenuConfig(newBar, newMore)
    }

    companion object {
        const val KEY_REPLACE = "replace"
        const val KEY_COPY = "copy"
        const val KEY_BOOKMARK = "bookmark"
        const val KEY_HIGHLIGHT = "highlight"
        const val KEY_ALOUD = "aloud"
        const val KEY_DICT = "dict"
        const val KEY_SEARCH = "search"
        const val KEY_BROWSER = "browser"
        const val KEY_SHARE = "share"
        const val KEY_REVIEW = "review"

        /** 规范顺序(全新安装/补全缺失项时用) */
        val ALL_KEYS = listOf(
            KEY_REPLACE, KEY_COPY, KEY_BOOKMARK, KEY_HIGHLIGHT, KEY_ALOUD,
            KEY_DICT, KEY_SEARCH, KEY_BROWSER, KEY_SHARE, KEY_REVIEW
        )

        val DEFAULT_BAR = listOf(KEY_REPLACE, KEY_COPY, KEY_BOOKMARK, KEY_HIGHLIGHT, KEY_ALOUD)
        val DEFAULT_MORE = listOf(KEY_DICT, KEY_SEARCH, KEY_BROWSER, KEY_SHARE, KEY_REVIEW)

        private val gson = Gson()

        fun default() = TextSelectMenuConfig(DEFAULT_BAR, DEFAULT_MORE)

        fun fromJson(json: String?): TextSelectMenuConfig {
            if (json.isNullOrBlank()) return default()
            val parsed = runCatching {
                gson.fromJson(json, TextSelectMenuConfig::class.java)
            }.getOrNull() ?: return default()
            @Suppress("USELESS_ELVIS")
            return TextSelectMenuConfig(parsed.bar ?: emptyList(), parsed.more ?: emptyList())
        }

        /** 一次性迁移:从旧的 expandTextMenu 开关播种 */
        fun migrateFrom(expandTextMenu: Boolean): TextSelectMenuConfig {
            return if (expandTextMenu) {
                TextSelectMenuConfig(ALL_KEYS, emptyList())
            } else {
                default()
            }
        }
    }
}
