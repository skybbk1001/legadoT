package io.legado.app.model.jsSource

import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.exception.ContentEmptyException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.exception.TocEmptyException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.addType
import io.legado.app.help.book.isWebFile
import io.legado.app.help.book.removeAllBookType
import io.legado.app.help.source.getBookType
import io.legado.app.model.Debug
import io.legado.app.model.webBook.BookChapterList
import kotlinx.coroutines.ensureActive
import splitties.init.appCtx
import kotlin.coroutines.coroutineContext

/**
 * JS 源抓取(spec §5),搜索/发现/详情/目录/正文与 WebBook 入口同构;
 * Debug/CheckSource/换源/缓存只经 WebBook,分派后自动继承。
 */
object JsSourceBook {

    suspend fun searchAwait(
        bookSource: BookSource,
        key: String,
        page: Int? = 1,
        filter: ((name: String, author: String) -> Boolean)? = null,
    ): ArrayList<SearchBook> {
        val engine = JsSourceEngine(bookSource, coroutineContext)
        val json = engine.callFunction("search", listOf("key" to key, "page" to (page ?: 1)))
        // 调试页"源码查看"的四个缓冲位(state 10/20/30/40):声明式源存响应体,JS 源无从
        // 拦截函数内请求,存函数返回值——检视 JS 给出的原始数据,与日志列表的编组结果互证
        Debug.log(bookSource.bookSourceUrl, "≡函数执行成功:search")
        Debug.log(bookSource.bookSourceUrl, json ?: "", state = 10)
        val books = JsSourceMarshaller.parseSearchBooks(json, bookSource)
        if (filter != null) {
            books.removeAll { !filter(it.name, it.author) }
        }
        logBookList(bookSource, books)
        return books
    }

    /** 发现:与 exploreUrl 分类成对,url=分类项的 url 段;返回契约同 search */
    suspend fun exploreAwait(
        bookSource: BookSource,
        url: String,
        page: Int? = 1,
    ): ArrayList<SearchBook> {
        val engine = JsSourceEngine(bookSource, coroutineContext)
        val json = engine.callFunction("explore", listOf("url" to url, "page" to (page ?: 1)))
        Debug.log(bookSource.bookSourceUrl, "≡函数执行成功:explore")
        Debug.log(bookSource.bookSourceUrl, json ?: "", state = 10)
        val books = JsSourceMarshaller.parseSearchBooks(json, bookSource)
        logBookList(bookSource, books)
        return books
    }

    /** 与声明式 BookList 同款行文:列表规模 → 首条逐字段 ┌└ → 总数 */
    private fun logBookList(bookSource: BookSource, books: List<SearchBook>) {
        val url = bookSource.bookSourceUrl
        Debug.log(url, "┌获取书籍列表")
        Debug.log(url, "└列表大小:${books.size}")
        books.firstOrNull()?.let {
            logField(url, "书名", it.name)
            logField(url, "作者", it.author)
            logField(url, "分类", it.kind)
            logField(url, "字数", it.wordCount)
            logField(url, "最新章节", it.latestChapterTitle)
            logField(url, "简介", it.intro)
            logField(url, "封面链接", it.coverUrl)
            logField(url, "详情页链接", it.bookUrl)
        }
        Debug.log(url, "◇书籍总数:${books.size}")
    }

    private fun logField(sourceUrl: String, label: String, value: Any?) {
        Debug.log(sourceUrl, "┌获取$label")
        Debug.log(sourceUrl, "└${value ?: ""}")
    }

    suspend fun getBookInfoAwait(bookSource: BookSource, book: Book): Book {
        // 与 WebBook.getBookInfoAwait(:157-158)同款:先注默认 type,JS 可覆写
        book.removeAllBookType()
        book.addType(bookSource.getBookType())
        val engine = JsSourceEngine(bookSource, coroutineContext)
        val json = engine.callFunctionIfExists("getBookInfo", listOf("book" to book))
        if (json == null) {
            Debug.log(bookSource.bookSourceUrl, "≡getBookInfo 未定义或无返回,沿用搜索阶段字段")
        } else {
            Debug.log(bookSource.bookSourceUrl, "≡函数执行成功:getBookInfo")
            Debug.log(bookSource.bookSourceUrl, json, state = 20)
        }
        JsSourceMarshaller.mergeBookInfo(book, json, bookSource)
        val url = bookSource.bookSourceUrl
        logField(url, "书名", book.name)
        logField(url, "作者", book.author)
        logField(url, "分类", book.kind)
        logField(url, "字数", book.wordCount)
        logField(url, "最新章节", book.latestChapterTitle)
        logField(url, "简介", book.intro)
        logField(url, "封面链接", book.coverUrl)
        if (book.isWebFile) {
            // 文件源不走目录/正文:详情页据 downloadUrls 弹下载列表转本地书
            val downloadUrls = book.downloadUrls
            logField(url, "文件下载链接", downloadUrls?.joinToString("，\n"))
            if (downloadUrls.isNullOrEmpty()) {
                throw NoStackTraceException("下载链接为空")
            }
        } else {
            if (book.tocUrl.isBlank()) {
                book.tocUrl = book.bookUrl   // 声明式同款兜底:无目录页则详情页即目录页
            }
            logField(url, "目录链接", book.tocUrl)
        }
        return book
    }

    suspend fun getChapterListAwait(
        bookSource: BookSource,
        book: Book,
    ): Result<List<BookChapter>> {
        book.removeAllBookType()
        book.addType(bookSource.getBookType())
        val cc = coroutineContext
        return kotlin.runCatching {
            val engine = JsSourceEngine(bookSource, cc)
            val json = engine.callFunction("getChapters", listOf("book" to book))
            Debug.log(bookSource.bookSourceUrl, "≡函数执行成功:getChapters")
            Debug.log(bookSource.bookSourceUrl, json ?: "", state = 30)
            val chapters = JsSourceMarshaller.parseChapters(json, book, bookSource)
            Debug.log(bookSource.bookSourceUrl, "┌获取目录列表")
            Debug.log(bookSource.bookSourceUrl, "└列表大小:${chapters.size}")
            if (chapters.isEmpty()) {
                // 声明式同款异常型与文案:CheckSource 按 TocEmptyException 归"目录失效"
                throw TocEmptyException(appCtx.getString(R.string.chapter_list_empty))
            }
            // 声明式同款 book 回写(totalChapterNum 等):目录页/书架进度/更新检查都读这些字段
            BookChapterList.updateBookTocInfo(book, chapters)
            Debug.log(bookSource.bookSourceUrl, "◇目录总数:${chapters.size}")
            logChapter(bookSource, "首章", chapters.first())
            if (chapters.size > 1) {
                logChapter(bookSource, "末章", chapters.last())
            }
            chapters
        }.onFailure {
            cc.ensureActive()
        }
    }

    /** 与声明式 BookChapterList 首章信息同款行文,末章为 JS 源附加(检视目录抓全与否) */
    private fun logChapter(bookSource: BookSource, label: String, chapter: BookChapter) {
        val url = bookSource.bookSourceUrl
        Debug.log(url, "≡${label}信息")
        Debug.log(url, "◇章节名称:${chapter.title}")
        Debug.log(url, "◇章节链接:${chapter.url}")
        Debug.log(url, "◇章节信息:${chapter.tag ?: ""}")
        Debug.log(url, "◇是否VIP:${chapter.isVip}")
        Debug.log(url, "◇是否购买:${chapter.isPay}")
    }

    suspend fun getContentAwait(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        nextChapterUrl: String? = null,
        needSave: Boolean = true,
    ): String {
        if (bookChapter.isVolume && bookChapter.url.startsWith(bookChapter.title)) {
            // WebBook.getContentAwait 同款:卷占位章不抓正文,正文恒为空串保排版层卷名居中
            Debug.log(bookSource.bookSourceUrl, "⇒一级目录正文不解析")
            return ""
        }
        val engine = JsSourceEngine(bookSource, coroutineContext)
        val content = engine.callFunction(
            "getContent",
            listOf("chapter" to bookChapter, "book" to book, "nextChapterUrl" to nextChapterUrl),
        ).orEmpty()
        // BookContent 同款空判:卷章空正文放行;异常型与文案对齐声明式,CheckSource 按类型归"正文失效"
        if (!bookChapter.isVolume && content.isBlank()) {
            throw ContentEmptyException("内容为空")
        }
        Debug.log(bookSource.bookSourceUrl, "≡函数执行成功:getContent")
        Debug.log(bookSource.bookSourceUrl, content, state = 40)
        Debug.log(bookSource.bookSourceUrl, "┌获取章节名称")
        Debug.log(bookSource.bookSourceUrl, "└${bookChapter.title}")
        Debug.log(bookSource.bookSourceUrl, "┌获取正文内容")
        Debug.log(bookSource.bookSourceUrl, "└\n$content")
        if (needSave) {
            BookHelp.saveContent(bookSource, book, bookChapter, content)
        }
        return content
    }
}
