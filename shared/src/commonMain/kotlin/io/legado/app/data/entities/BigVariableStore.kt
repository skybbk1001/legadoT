package io.legado.app.data.entities

/**
 * 实体大变量存储 seam(对应 app 侧 help/RuleBigDataHelp)。
 * app 启动时注入实现(App.kt),未注入时退化为无操作(如单元测试)。
 */
@Suppress("unused")
object BigVariableStore {

    var putBookVariable: (bookUrl: String, key: String, value: String?) -> Unit =
        { _, _, _ -> }

    var getBookVariable: (bookUrl: String, key: String) -> String? =
        { _, _ -> null }

    var putChapterVariable: (bookUrl: String, chapterUrl: String, key: String, value: String?) -> Unit =
        { _, _, _, _ -> }

    var getChapterVariable: (bookUrl: String, chapterUrl: String, key: String) -> String? =
        { _, _, _ -> null }

    var putRssVariable: (origin: String, link: String, key: String, value: String?) -> Unit =
        { _, _, _, _ -> }

    var getRssVariable: (origin: String, link: String, key: String) -> String? =
        { _, _, _ -> null }

}
