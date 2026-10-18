package io.legado.app.model.webBook

import io.legado.app.constant.SharedLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSourceContract
import io.legado.app.data.entities.SearchBookContract
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.addType
import io.legado.app.help.book.removeAllBookType
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.StrResponse
import io.legado.app.help.source.getBookType
import io.legado.app.model.DebugBridge
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.RuleData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

@Suppress("MemberVisibilityCanBePrivate")
object WebBook {

    /**
     * 搜索
     */
    fun searchBook(
        scope: CoroutineScope,
        bookSource: BookSourceContract,
        key: String,
        page: Int? = 1,
        context: CoroutineContext = Dispatchers.IO,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        executeContext: CoroutineContext = Dispatchers.Main,
    ): Coroutine<ArrayList<SearchBookContract>> {
        return Coroutine.async(scope, context, start = start, executeContext = executeContext) {
            searchBookAwait(bookSource, key, page)
        }
    }

    suspend fun searchBookAwait(
        bookSource: BookSourceContract,
        key: String,
        page: Int? = 1,
        filter: ((name: String, author: String) -> Boolean)? = null,
        shouldBreak: ((size: Int) -> Boolean)? = null
    ): ArrayList<SearchBookContract> {
        if (bookSource.isJsSource()) {
            return WebBookBridge.jsSource?.searchAwait(bookSource, key, page, filter)
                ?: throw NoStackTraceException("JS 书源代理未接线")
        }
        val searchUrl = bookSource.searchUrl
        if (searchUrl.isNullOrBlank()) {
            throw NoStackTraceException("搜索url不能为空")
        }
        val ruleData = RuleData()
        val analyzeUrl = AnalyzeUrl(
            mUrl = searchUrl,
            key = key,
            page = page,
            baseUrl = bookSource.bookSourceUrl,
            source = bookSource,
            ruleData = ruleData,
            coroutineContext = coroutineContext
        )
        var res = analyzeUrl.getStrResponseAwait()
        //检测书源是否已登录
        bookSource.loginCheckJs?.let { checkJs ->
            if (checkJs.isNotBlank()) {
                res = analyzeUrl.evalJS(checkJs, res) as StrResponse
            }
        }
        checkRedirect(bookSource, res)
        return BookList.analyzeBookList(
            bookSource = bookSource,
            ruleData = ruleData,
            analyzeUrl = analyzeUrl,
            baseUrl = res.url,
            body = res.body,
            isSearch = true,
            isRedirect = res.raw.priorResponse?.isRedirect == true,
            filter = filter,
            shouldBreak = shouldBreak
        )
    }

    /**
     * 发现
     */
    fun exploreBook(
        scope: CoroutineScope,
        bookSource: BookSourceContract,
        url: String,
        page: Int? = 1,
        context: CoroutineContext = Dispatchers.IO,
    ): Coroutine<List<SearchBookContract>> {
        return Coroutine.async(scope, context) {
            exploreBookAwait(bookSource, url, page)
        }
    }

    suspend fun exploreBookAwait(
        bookSource: BookSourceContract,
        url: String,
        page: Int? = 1,
    ): ArrayList<SearchBookContract> {
        if (bookSource.isJsSource()) {
            return WebBookBridge.jsSource?.exploreAwait(bookSource, url, page)
                ?: throw NoStackTraceException("JS 书源代理未接线")
        }
        val ruleData = RuleData()
        val analyzeUrl = AnalyzeUrl(
            mUrl = url,
            page = page,
            baseUrl = bookSource.bookSourceUrl,
            source = bookSource,
            ruleData = ruleData,
            coroutineContext = coroutineContext
        )
        var res = analyzeUrl.getStrResponseAwait()
        //检测书源是否已登录
        bookSource.loginCheckJs?.let { checkJs ->
            if (checkJs.isNotBlank()) {
                res = analyzeUrl.evalJS(checkJs, result = res) as StrResponse
            }
        }
        checkRedirect(bookSource, res)
        return BookList.analyzeBookList(
            bookSource = bookSource,
            ruleData = ruleData,
            analyzeUrl = analyzeUrl,
            baseUrl = res.url,
            body = res.body,
            isSearch = false
        )
    }

    /**
     * 书籍信息
     */
    fun getBookInfo(
        scope: CoroutineScope,
        bookSource: BookSourceContract,
        book: Book,
        context: CoroutineContext = Dispatchers.IO,
        canReName: Boolean = true,
    ): Coroutine<Book> {
        return Coroutine.async(scope, context) {
            getBookInfoAwait(bookSource, book, canReName)
        }
    }

    suspend fun getBookInfoAwait(
        bookSource: BookSourceContract,
        book: Book,
        canReName: Boolean = true,
    ): Book {
        if (bookSource.isJsSource()) {
            return WebBookBridge.jsSource?.getBookInfoAwait(bookSource, book)
                ?: throw NoStackTraceException("JS 书源代理未接线")
        }
        book.removeAllBookType()
        book.addType(bookSource.getBookType())
        if (!book.infoHtml.isNullOrEmpty()) {
            BookInfo.analyzeBookInfo(
                bookSource = bookSource,
                book = book,
                baseUrl = book.bookUrl,
                redirectUrl = book.bookUrl,
                body = book.infoHtml,
                canReName = canReName
            )
        } else {
            val analyzeUrl = AnalyzeUrl(
                mUrl = book.bookUrl,
                baseUrl = bookSource.bookSourceUrl,
                source = bookSource,
                ruleData = book,
                coroutineContext = coroutineContext
            )
            var res = analyzeUrl.getStrResponseAwait()
            //检测书源是否已登录
            bookSource.loginCheckJs?.let { checkJs ->
                if (checkJs.isNotBlank()) {
                    res = analyzeUrl.evalJS(checkJs, result = res) as StrResponse
                }
            }
            checkRedirect(bookSource, res)
            BookInfo.analyzeBookInfo(
                bookSource = bookSource,
                book = book,
                baseUrl = book.bookUrl,
                redirectUrl = res.url,
                body = res.body,
                canReName = canReName
            )
        }
        return book
    }

    /**
     * 目录
     */
    fun getChapterList(
        scope: CoroutineScope,
        bookSource: BookSourceContract,
        book: Book,
        runPerJs: Boolean = false,
        context: CoroutineContext = Dispatchers.IO
    ): Coroutine<List<BookChapter>> {
        return Coroutine.async(scope, context) {
            getChapterListAwait(bookSource, book, runPerJs).getOrThrow()
        }
    }

    suspend fun runPreUpdateJs(bookSource: BookSourceContract, book: Book): Result<Unit> {
        return kotlin.runCatching {
            val preUpdateJs = bookSource.ruleToc?.preUpdateJs
            if (!preUpdateJs.isNullOrBlank()) {
                AnalyzeRule(book, bookSource, true)
                    .setCoroutineContext(coroutineContext)
                    .evalJS(preUpdateJs)
            }
        }.onFailure {
            coroutineContext.ensureActive()
            SharedLog.put("执行preUpdateJs规则失败 书源:${bookSource.bookSourceName}", it)
        }
    }

    suspend fun getChapterListAwait(
        bookSource: BookSourceContract,
        book: Book,
        runPerJs: Boolean = false
    ): Result<List<BookChapter>> {
        if (bookSource.isJsSource()) {
            return WebBookBridge.jsSource?.getChapterListAwait(bookSource, book)
                ?: throw NoStackTraceException("JS 书源代理未接线")
        }
        book.removeAllBookType()
        book.addType(bookSource.getBookType())
        return kotlin.runCatching {
            if (runPerJs) {
                runPreUpdateJs(bookSource, book).getOrThrow()
            }
            if (book.bookUrl == book.tocUrl && !book.tocHtml.isNullOrEmpty()) {
                BookChapterList.analyzeChapterList(
                    bookSource = bookSource,
                    book = book,
                    baseUrl = book.tocUrl,
                    redirectUrl = book.tocUrl,
                    body = book.tocHtml
                )
            } else {
                val analyzeUrl = AnalyzeUrl(
                    mUrl = book.tocUrl,
                    baseUrl = book.bookUrl,
                    source = bookSource,
                    ruleData = book,
                    coroutineContext = coroutineContext
                )
                var res = analyzeUrl.getStrResponseAwait()
                //检测书源是否已登录
                bookSource.loginCheckJs?.let { checkJs ->
                    if (checkJs.isNotBlank()) {
                        res = analyzeUrl.evalJS(checkJs, result = res) as StrResponse
                    }
                }
                checkRedirect(bookSource, res)
                BookChapterList.analyzeChapterList(
                    bookSource = bookSource,
                    book = book,
                    baseUrl = book.tocUrl,
                    redirectUrl = res.url,
                    body = res.body
                )
            }
        }.onFailure {
            coroutineContext.ensureActive()
        }
    }

    /**
     * 章节内容
     */
    fun getContent(
        scope: CoroutineScope,
        bookSource: BookSourceContract,
        book: Book,
        bookChapter: BookChapter,
        nextChapterUrl: String? = null,
        needSave: Boolean = true,
        context: CoroutineContext = Dispatchers.IO,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        executeContext: CoroutineContext = Dispatchers.Main,
        semaphore: Semaphore? = null,
    ): Coroutine<String> {
        return Coroutine.async(
            scope,
            context,
            start = start,
            executeContext = executeContext,
            semaphore = semaphore
        ) {
            getContentAwait(bookSource, book, bookChapter, nextChapterUrl, needSave)
        }
    }

    suspend fun getContentAwait(
        bookSource: BookSourceContract,
        book: Book,
        bookChapter: BookChapter,
        nextChapterUrl: String? = null,
        needSave: Boolean = true
    ): String {
        if (bookSource.isJsSource()) {
            return WebBookBridge.jsSource?.getContentAwait(bookSource, book, bookChapter, nextChapterUrl, needSave)
                ?: throw NoStackTraceException("JS 书源代理未接线")
        }
        if (bookSource.getContentRule().content.isNullOrEmpty()) {
            DebugBridge.log(bookSource.bookSourceUrl, "⇒正文规则为空,使用章节链接:${bookChapter.url}")
            return bookChapter.url
        }
        if (bookChapter.isVolume && bookChapter.url.startsWith(bookChapter.title)) {
            DebugBridge.log(bookSource.bookSourceUrl, "⇒一级目录正文不解析规则")
            // 卷占位章正文恒为空串,排版层按空正文垂直居中卷名;tag(updateTime 规则结果)不作正文
            return ""
        }
        return if (bookChapter.url == book.bookUrl && !book.tocHtml.isNullOrEmpty()) {
            BookContent.analyzeContent(
                bookSource = bookSource,
                book = book,
                bookChapter = bookChapter,
                baseUrl = bookChapter.getAbsoluteURL(),
                redirectUrl = bookChapter.getAbsoluteURL(),
                body = book.tocHtml,
                nextChapterUrl = nextChapterUrl,
                needSave = needSave
            )
        } else {
            val analyzeUrl = AnalyzeUrl(
                mUrl = bookChapter.getAbsoluteURL(),
                baseUrl = book.tocUrl,
                source = bookSource,
                ruleData = book,
                chapter = bookChapter,
                coroutineContext = coroutineContext
            )
            var res = analyzeUrl.getStrResponseAwait(
                jsStr = bookSource.getContentRule().webJs,
                sourceRegex = bookSource.getContentRule().sourceRegex
            )
            //检测书源是否已登录
            bookSource.loginCheckJs?.let { checkJs ->
                if (checkJs.isNotBlank()) {
                    res = analyzeUrl.evalJS(checkJs, result = res) as StrResponse
                }
            }
            checkRedirect(bookSource, res)
            BookContent.analyzeContent(
                bookSource = bookSource,
                book = book,
                bookChapter = bookChapter,
                baseUrl = bookChapter.getAbsoluteURL(),
                redirectUrl = res.url,
                body = res.body,
                nextChapterUrl = nextChapterUrl,
                needSave = needSave
            )
        }
    }

    /**
     * 精准搜索
     */
    suspend fun preciseSearchAwait(
        bookSource: BookSourceContract,
        name: String,
        author: String,
    ): Result<Book> {
        return kotlin.runCatching {
            coroutineContext.ensureActive()
            searchBookAwait(
                bookSource, name,
                filter = { fName, fAuthor -> fName == name && fAuthor == author },
                shouldBreak = { it > 0 }
            ).firstOrNull()?.let { searchBook ->
                coroutineContext.ensureActive()
                return@runCatching searchBook.toBook()
            }
            throw NoStackTraceException("未搜索到 $name($author) 书籍")
        }.onFailure {
            coroutineContext.ensureActive()
        }
    }

    /**
     * 检测重定向
     */
    private fun checkRedirect(bookSource: BookSourceContract, response: StrResponse) {
        response.raw.priorResponse?.let {
            if (it.isRedirect) {
                DebugBridge.log(bookSource.bookSourceUrl, "≡检测到重定向(${it.code})")
                DebugBridge.log(bookSource.bookSourceUrl, "┌重定向后地址")
                DebugBridge.log(bookSource.bookSourceUrl, "└${response.url}")
            }
        }
    }

}
