package io.legado.app.model.webBook

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSourceContract
import io.legado.app.data.entities.SearchBookContract
import io.legado.app.exception.NoStackTraceException

/**
 * webBook(app 侧依赖)桥接: JS 源分派/搜索结果工厂/章节库访问/正文保存/
 * 配置与文案等经此转发, 由 app 启动时接线(WebBookAndroidDelegate.install)。
 * 参照 CacheBridge/DebugBridge 先例, 未接线时 no-op/抛出明确异常,
 * app 接线后运行时行为与迁移前一致。
 */
object WebBookBridge {

    /**
     * JS 单文件源抓取代理(app JsSourceBook)。
     * 五入口与声明式管道同构, WebBook 在声明式骨架之前分派(spec §5)。
     */
    interface JsSourceDelegate {
        suspend fun searchAwait(
            source: BookSourceContract,
            key: String,
            page: Int?,
            filter: ((name: String, author: String) -> Boolean)? = null
        ): ArrayList<SearchBookContract>

        suspend fun exploreAwait(
            source: BookSourceContract,
            url: String,
            page: Int? = 1
        ): ArrayList<SearchBookContract>

        suspend fun getBookInfoAwait(
            source: BookSourceContract,
            book: Book
        ): Book

        suspend fun getChapterListAwait(
            source: BookSourceContract,
            book: Book
        ): Result<List<BookChapter>>

        suspend fun getContentAwait(
            source: BookSourceContract,
            book: Book,
            bookChapter: BookChapter,
            nextChapterUrl: String? = null,
            needSave: Boolean = true
        ): String
    }

    /** JS 源分派代理; 未接线时遇到 JS 源抛出明确异常 */
    var jsSource: JsSourceDelegate? = null

    /** 搜索结果工厂: 列表项创建(app SearchBook(variable)) */
    var newSearchBook: (variable: String?) -> SearchBookContract = {
        throw NoStackTraceException("搜索结果工厂未接线")
    }

    /** 搜索结果工厂: Book 转搜索结果(app Book.toSearchBook()) */
    var bookToSearchBook: (book: Book) -> SearchBookContract = {
        throw NoStackTraceException("搜索结果工厂未接线")
    }

    /** 保存正文(app BookHelp.saveContent: 落盘+事件+字数回写) */
    var saveContent: suspend (
        source: BookSourceContract, book: Book, chapter: BookChapter, content: String
    ) -> Unit = { _, _, _, _ -> }

    /** 章节库: 下一章链接兜底(app appDb.bookChapterDao.getChapter 链) */
    var nextChapterUrl: (book: Book, chapter: BookChapter) -> String? = { _, _ -> null }

    /** 章节库: 持久化章节(app appDb.bookChapterDao.update, 标题解析后) */
    var updateChapter: (chapter: BookChapter) -> Unit = {}

    /** 目录字数回填(app getWordCount: AppConfig.tocCountWords + 章节库查询) */
    var fillWordCount: (book: Book, chapters: List<BookChapter>) -> Unit = { _, _ -> }

    /** 章节显示标题(app BookChapterExt.getDisplayTitle + ContentProcessor 标题替换规则) */
    var chapterDisplayTitle: (book: Book, chapter: BookChapter) -> String =
        { _, chapter -> chapter.title }

    /** 并发线程数(app AppConfig.threadCount) */
    var threadCount: () -> Int = { 16 }

    /** 文案: 获取网页内容失败(app R.string.error_get_web_content, 已本地化) */
    var errorGetWebContent: (baseUrl: String) -> String = { "Failed to access website:$it" }

    /** 文案: 目录列表为空(app R.string.chapter_list_empty, 已本地化) */
    var chapterListEmpty: () -> String = { "Chapters is empty" }

    /** 发现地址规则 JSON 规范性检查(app Debug 调试期校验, 仅调试回调存在时生效) */
    var checkExploreKindsJson: (source: BookSourceContract) -> Unit = {}

    /** 调试期错误日志(app DebugLog.e) */
    var logError: (tag: String, throwable: Throwable) -> Unit = { _, _ -> }

}
