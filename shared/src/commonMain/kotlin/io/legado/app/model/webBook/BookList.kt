package io.legado.app.model.webBook

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSourceContract
import io.legado.app.data.entities.SearchBookContract
import io.legado.app.data.entities.rule.BookListRule
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookFormat
import io.legado.app.help.source.getBookType
import io.legado.app.model.DebugBridge
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setRuleData
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.RuleData
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.StringUtils.wordCountFormat
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * 获取书籍列表
 */
object BookList {

    @Throws(Exception::class)
    suspend fun analyzeBookList(
        bookSource: BookSourceContract,
        ruleData: RuleData,
        analyzeUrl: AnalyzeUrl,
        baseUrl: String,
        body: String?,
        isSearch: Boolean = true,
        isRedirect: Boolean = false,
        filter: ((name: String, author: String) -> Boolean)? = null,
        shouldBreak: ((size: Int) -> Boolean)? = null
    ): ArrayList<SearchBookContract> {
        body ?: throw NoStackTraceException(
            WebBookBridge.errorGetWebContent(analyzeUrl.ruleUrl)
        )
        val bookList = ArrayList<SearchBookContract>()
        DebugBridge.log(bookSource.bookSourceUrl, "≡获取成功:${analyzeUrl.ruleUrl}")
        DebugBridge.logFull(bookSource.bookSourceUrl, body, true, 10)
        val analyzeRule = AnalyzeRule(ruleData, bookSource)
        analyzeRule.setContent(body).setBaseUrl(baseUrl)
        analyzeRule.setRedirectUrl(baseUrl)
        analyzeRule.setCoroutineContext(coroutineContext)
        if (!isSearch) {
            WebBookBridge.checkExploreKindsJson(bookSource)
        }
        if (isSearch) bookSource.bookUrlPattern?.let {
            coroutineContext.ensureActive()
            if (baseUrl.matches(it.toRegex())) {
                DebugBridge.log(bookSource.bookSourceUrl, "≡链接为详情页")
                getInfoItem(
                    bookSource,
                    analyzeRule,
                    analyzeUrl,
                    body,
                    baseUrl,
                    ruleData.getVariable(),
                    isRedirect,
                    filter
                )?.let { searchBook ->
                    searchBook.infoHtml = body
                    bookList.add(searchBook)
                }
                return bookList
            }
        }
        val collections: List<Any>
        var reverse = false
        val bookListRule: BookListRule = when {
            isSearch -> bookSource.getSearchRule()
            bookSource.getExploreRule().bookList.isNullOrBlank() -> bookSource.getSearchRule()
            else -> bookSource.getExploreRule()
        }
        var ruleList: String = bookListRule.bookList ?: ""
        if (ruleList.startsWith("-")) {
            reverse = true
            ruleList = ruleList.substring(1)
        }
        if (ruleList.startsWith("+")) {
            ruleList = ruleList.substring(1)
        }
        DebugBridge.log(bookSource.bookSourceUrl, "┌获取书籍列表")
        collections = analyzeRule.getElements(ruleList)
        coroutineContext.ensureActive()
        if (collections.isEmpty() && bookSource.bookUrlPattern.isNullOrEmpty()) {
            DebugBridge.log(bookSource.bookSourceUrl, "└列表为空,按详情页解析")
            getInfoItem(
                bookSource, analyzeRule, analyzeUrl, body, baseUrl, ruleData.getVariable(),
                isRedirect, filter
            )?.let { searchBook ->
                searchBook.infoHtml = body
                bookList.add(searchBook)
            }
        } else {
            val ruleName = analyzeRule.splitSourceRule(bookListRule.name)
            val ruleBookUrl = analyzeRule.splitSourceRule(bookListRule.bookUrl)
            val ruleAuthor = analyzeRule.splitSourceRule(bookListRule.author)
            val ruleCoverUrl = analyzeRule.splitSourceRule(bookListRule.coverUrl)
            val ruleIntro = analyzeRule.splitSourceRule(bookListRule.intro)
            val ruleKind = analyzeRule.splitSourceRule(bookListRule.kind)
            val ruleLastChapter = analyzeRule.splitSourceRule(bookListRule.lastChapter)
            val ruleWordCount = analyzeRule.splitSourceRule(bookListRule.wordCount)
            DebugBridge.log(bookSource.bookSourceUrl, "└列表大小:${collections.size}")
            for ((index, item) in collections.withIndex()) {
                getSearchItem(
                    bookSource, analyzeRule, item, baseUrl, ruleData.getVariable(),
                    index == 0,
                    filter,
                    ruleName = ruleName,
                    ruleBookUrl = ruleBookUrl,
                    ruleAuthor = ruleAuthor,
                    ruleCoverUrl = ruleCoverUrl,
                    ruleIntro = ruleIntro,
                    ruleKind = ruleKind,
                    ruleLastChapter = ruleLastChapter,
                    ruleWordCount = ruleWordCount
                )?.let { searchBook ->
                    if (baseUrl == searchBook.bookUrl) {
                        searchBook.infoHtml = body
                    }
                    bookList.add(searchBook)
                }
                if (shouldBreak?.invoke(bookList.size) == true) {
                    break
                }
            }
            val lh = LinkedHashSet(bookList)
            bookList.clear()
            bookList.addAll(lh)
            if (reverse) {
                bookList.reverse()
            }
        }
        DebugBridge.log(bookSource.bookSourceUrl, "◇书籍总数:${bookList.size}")
        return bookList
    }

    @Throws(Exception::class)
    private suspend fun getInfoItem(
        bookSource: BookSourceContract,
        analyzeRule: AnalyzeRule,
        analyzeUrl: AnalyzeUrl,
        body: String,
        baseUrl: String,
        variable: String?,
        isRedirect: Boolean,
        filter: ((name: String, author: String) -> Boolean)?
    ): SearchBookContract? {
        val book = Book(variable = variable)
        book.bookUrl = if (isRedirect) {
            baseUrl
        } else {
            NetworkUtils.getAbsoluteURL(analyzeUrl.url, analyzeUrl.ruleUrl)
        }
        book.origin = bookSource.bookSourceUrl
        book.originName = bookSource.bookSourceName
        book.originOrder = bookSource.customOrder
        book.type = bookSource.getBookType()
        analyzeRule.setRuleData(book)
        BookInfo.analyzeBookInfo(
            book,
            body,
            analyzeRule,
            bookSource,
            baseUrl,
            baseUrl,
            false
        )
        if (filter?.invoke(book.name, book.author) == false) {
            return null
        }
        if (book.name.isNotBlank()) {
            return WebBookBridge.bookToSearchBook(book)
        }
        return null
    }

    @Throws(Exception::class)
    private suspend fun getSearchItem(
        bookSource: BookSourceContract,
        analyzeRule: AnalyzeRule,
        item: Any,
        baseUrl: String,
        variable: String?,
        log: Boolean,
        filter: ((name: String, author: String) -> Boolean)?,
        ruleName: List<AnalyzeRule.SourceRule>,
        ruleBookUrl: List<AnalyzeRule.SourceRule>,
        ruleAuthor: List<AnalyzeRule.SourceRule>,
        ruleKind: List<AnalyzeRule.SourceRule>,
        ruleCoverUrl: List<AnalyzeRule.SourceRule>,
        ruleWordCount: List<AnalyzeRule.SourceRule>,
        ruleIntro: List<AnalyzeRule.SourceRule>,
        ruleLastChapter: List<AnalyzeRule.SourceRule>
    ): SearchBookContract? {
        val searchBook = WebBookBridge.newSearchBook(variable)
        searchBook.type = bookSource.getBookType()
        searchBook.origin = bookSource.bookSourceUrl
        searchBook.originName = bookSource.bookSourceName
        searchBook.originOrder = bookSource.customOrder
        analyzeRule.setRuleData(searchBook)
        analyzeRule.setContent(item)
        coroutineContext.ensureActive()
        DebugBridge.logFull(bookSource.bookSourceUrl, "┌获取书名", log, 1)
        searchBook.name = BookFormat.formatBookName(analyzeRule.getString(ruleName))
        DebugBridge.logFull(bookSource.bookSourceUrl, "└${searchBook.name}", log, 1)
        if (searchBook.name.isNotEmpty()) {
            coroutineContext.ensureActive()
            DebugBridge.logFull(bookSource.bookSourceUrl, "┌获取作者", log, 1)
            searchBook.author = BookFormat.formatBookAuthor(analyzeRule.getString(ruleAuthor))
            DebugBridge.logFull(bookSource.bookSourceUrl, "└${searchBook.author}", log, 1)
            if (filter?.invoke(searchBook.name, searchBook.author) == false) {
                return null
            }
            coroutineContext.ensureActive()
            DebugBridge.logFull(bookSource.bookSourceUrl, "┌获取分类", log, 1)
            try {
                searchBook.kind = analyzeRule.getStringList(ruleKind)?.joinToString(",")
                DebugBridge.logFull(bookSource.bookSourceUrl, "└${searchBook.kind ?: ""}", log, 1)
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                DebugBridge.logFull(bookSource.bookSourceUrl, "└${e.localizedMessage}", log, 1)
            }
            coroutineContext.ensureActive()
            DebugBridge.logFull(bookSource.bookSourceUrl, "┌获取字数", log, 1)
            try {
                searchBook.wordCount = wordCountFormat(analyzeRule.getString(ruleWordCount))
                DebugBridge.logFull(bookSource.bookSourceUrl, "└${searchBook.wordCount}", log, 1)
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                DebugBridge.logFull(bookSource.bookSourceUrl, "└${e.localizedMessage}", log, 1)
            }
            coroutineContext.ensureActive()
            DebugBridge.logFull(bookSource.bookSourceUrl, "┌获取最新章节", log, 1)
            try {
                searchBook.latestChapterTitle = analyzeRule.getString(ruleLastChapter)
                DebugBridge.logFull(
                    bookSource.bookSourceUrl, "└${searchBook.latestChapterTitle}", log, 1
                )
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                DebugBridge.logFull(bookSource.bookSourceUrl, "└${e.localizedMessage}", log, 1)
            }
            coroutineContext.ensureActive()
            DebugBridge.logFull(bookSource.bookSourceUrl, "┌获取简介", log, 1)
            try {
                searchBook.intro = HtmlFormatter.formatIntro(analyzeRule.getString(ruleIntro))
                DebugBridge.logFull(bookSource.bookSourceUrl, "└${searchBook.intro}", log, 1)
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                DebugBridge.logFull(bookSource.bookSourceUrl, "└${e.localizedMessage}", log, 1)
            }
            coroutineContext.ensureActive()
            DebugBridge.logFull(bookSource.bookSourceUrl, "┌获取封面链接", log, 1)
            try {
                analyzeRule.getString(ruleCoverUrl).let {
                    if (it.isNotEmpty()) {
                        searchBook.coverUrl = NetworkUtils.getAbsoluteURL(baseUrl, it)
                    }
                }
                DebugBridge.logFull(
                    bookSource.bookSourceUrl, "└${searchBook.coverUrl ?: ""}", log, 1
                )
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                DebugBridge.logFull(bookSource.bookSourceUrl, "└${e.localizedMessage}", log, 1)
            }
            coroutineContext.ensureActive()
            DebugBridge.logFull(bookSource.bookSourceUrl, "┌获取详情页链接", log, 1)
            searchBook.bookUrl = analyzeRule.getString(ruleBookUrl, isUrl = true)
            if (searchBook.bookUrl.isEmpty()) {
                searchBook.bookUrl = baseUrl
            }
            DebugBridge.logFull(bookSource.bookSourceUrl, "└${searchBook.bookUrl}", log, 1)
            return searchBook
        }
        return null
    }

}
