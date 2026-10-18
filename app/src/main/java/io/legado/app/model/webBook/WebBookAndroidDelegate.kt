package io.legado.app.model.webBook

import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourceContract
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.SearchBookContract
import io.legado.app.data.entities.getDisplayTitle
import io.legado.app.data.entities.getFileName
import io.legado.app.data.entities.getUseReplaceRule
import io.legado.app.data.entities.toSearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.exploreKindsJson
import io.legado.app.model.Debug
import io.legado.app.model.DebugBridge
import io.legado.app.model.jsSource.JsSourceBook
import io.legado.app.utils.DebugLog
import io.legado.app.utils.GSON
import io.legado.app.utils.GSONStrict
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Semaphore
import splitties.init.appCtx
import kotlin.coroutines.CoroutineContext

/**
 * webBook 迁入 shared 后的 app 侧桥接层(参照 AnalyzeRuleAndroidDelegate 先例):
 * App.kt 调用 install() 完成 WebBookBridge 全部接线, 接线后运行时行为与迁移前一致。
 */
object WebBookAndroidDelegate {

    /** JS 单文件源分派适配: JsSourceBook 以 BookSource 强类型实现, 契约侧安全下转型 */
    private object JsSourceBookAdapter : WebBookBridge.JsSourceDelegate {
        override suspend fun searchAwait(
            source: BookSourceContract,
            key: String,
            page: Int?,
            filter: ((name: String, author: String) -> Boolean)?
        ): ArrayList<SearchBookContract> {
            @Suppress("UNCHECKED_CAST")
            return JsSourceBook.searchAwait(source as BookSource, key, page, filter)
                as ArrayList<SearchBookContract>
        }

        override suspend fun exploreAwait(
            source: BookSourceContract,
            url: String,
            page: Int?
        ): ArrayList<SearchBookContract> {
            @Suppress("UNCHECKED_CAST")
            return JsSourceBook.exploreAwait(source as BookSource, url, page)
                as ArrayList<SearchBookContract>
        }

        override suspend fun getBookInfoAwait(source: BookSourceContract, book: Book): Book {
            return JsSourceBook.getBookInfoAwait(source as BookSource, book)
        }

        override suspend fun getChapterListAwait(
            source: BookSourceContract,
            book: Book
        ): Result<List<BookChapter>> {
            return JsSourceBook.getChapterListAwait(source as BookSource, book)
        }

        override suspend fun getContentAwait(
            source: BookSourceContract,
            book: Book,
            bookChapter: BookChapter,
            nextChapterUrl: String?,
            needSave: Boolean
        ): String {
            return JsSourceBook.getContentAwait(
                source as BookSource, book, bookChapter, nextChapterUrl, needSave
            )
        }
    }

    fun install() {
        //源调试日志全参(state 调试缓冲位/print 并发页静默)
        DebugBridge.logFull = { sourceUrl, msg, print, state ->
            Debug.log(sourceUrl, msg, print, state = state)
        }
        //JS 源分派
        WebBookBridge.jsSource = JsSourceBookAdapter
        //搜索结果工厂(app SearchBook)
        WebBookBridge.newSearchBook = { variable -> SearchBook(variable = variable) }
        WebBookBridge.bookToSearchBook = { book -> book.toSearchBook() }
        //正文保存(BookHelp: 落盘+SAVE_CONTENT事件+在线txt字数回写)
        WebBookBridge.saveContent = { source, book, chapter, content ->
            BookHelp.saveContent(source as BookSource, book, chapter, content)
        }
        //章节库访问
        WebBookBridge.nextChapterUrl = { book, chapter ->
            appDb.bookChapterDao.getChapter(book.bookUrl, chapter.index + 1)?.url
                ?: appDb.bookChapterDao.getChapter(book.bookUrl, 0)?.url
        }
        WebBookBridge.updateChapter = { chapter ->
            appDb.bookChapterDao.update(chapter)
        }
        //目录字数回填(原 BookChapterList.getWordCount 逻辑)
        WebBookBridge.fillWordCount = { book, list ->
            if (AppConfig.tocCountWords) {
                val chapterList = appDb.bookChapterDao.getChapterList(book.bookUrl)
                if (chapterList.isNotEmpty()) {
                    val map = chapterList.associateBy({ it.getFileName() }, { it.wordCount })
                    for (bookChapter in list) {
                        val wordCount = map[bookChapter.getFileName()]
                        if (wordCount != null) {
                            bookChapter.wordCount = wordCount
                        }
                    }
                }
            }
        }
        //章节显示标题(ContentProcessor 标题替换规则+简繁转换+净化开关)
        WebBookBridge.chapterDisplayTitle = { book, chapter ->
            chapter.getDisplayTitle(
                ContentProcessor.get(book).getTitleReplaceRules(),
                book.getUseReplaceRule()
            )
        }
        //并发线程数
        WebBookBridge.threadCount = { AppConfig.threadCount }
        //本地化文案
        WebBookBridge.errorGetWebContent = { baseUrl ->
            appCtx.getString(R.string.error_get_web_content, baseUrl)
        }
        WebBookBridge.chapterListEmpty = {
            appCtx.getString(R.string.chapter_list_empty)
        }
        //发现地址规则 JSON 规范性检查(原 BookList.checkExploreJson, 仅调试回调存在时生效)
        WebBookBridge.checkExploreKindsJson = { source ->
            if (Debug.callback != null) {
                val json = (source as BookSource).exploreKindsJson()
                if (json.isNotEmpty()) {
                    val kinds = GSONStrict.fromJsonArray<ExploreKind>(json).getOrNull()
                    if (kinds == null) {
                        GSON.fromJsonArray<ExploreKind>(json).getOrNull()?.let {
                            Debug.log("≡发现地址规则 JSON 格式不规范，请改为规范格式")
                        }
                    }
                }
            }
        }
        //调试期错误日志(原 BookInfo 中 DebugLog.e)
        WebBookBridge.logError = { tag, throwable ->
            DebugLog.e(tag, throwable)
        }
    }

}

/** 契约列表到具体 SearchBook 的收窄(工厂恒创建 app SearchBook, 运行时安全) */
@Suppress("UNCHECKED_CAST")
fun List<SearchBookContract>.asSearchBooks(): List<SearchBook> {
    return this as List<SearchBook>
}

/**
 * 精准搜索(并发版): 依赖 app 的 BookSourcePart(数据库视图), 留 app 同包顶层函数。
 * 原为 WebBook.preciseSearch, 迁移后调用点改为本函数。
 */
fun preciseSearch(
    scope: CoroutineScope,
    bookSourceParts: List<BookSourcePart>,
    name: String,
    author: String,
    context: CoroutineContext = kotlinx.coroutines.Dispatchers.IO,
    semaphore: Semaphore? = null,
): Coroutine<Pair<Book, BookSource>> {
    return Coroutine.async(scope, context, semaphore = semaphore) {
        for (s in bookSourceParts) {
            val source = s.getBookSource() ?: continue
            val book = WebBook.preciseSearchAwait(source, name, author).getOrNull()
            if (book != null) {
                return@async Pair(book, source)
            }
        }
        throw NoStackTraceException("没有搜索到<$name>$author")
    }
}
