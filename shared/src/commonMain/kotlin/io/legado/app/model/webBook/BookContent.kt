package io.legado.app.model.webBook

import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSourceContract
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.exception.ContentEmptyException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.model.DebugBridge
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setChapter
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setNextChapterUrl
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.mapAsync
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flow
import org.apache.commons.text.StringEscapeUtils
import kotlin.coroutines.coroutineContext

/**
 * 获取正文
 */
object BookContent {

    @Throws(Exception::class)
    suspend fun analyzeContent(
        bookSource: BookSourceContract,
        book: Book,
        bookChapter: BookChapter,
        baseUrl: String,
        redirectUrl: String,
        body: String?,
        nextChapterUrl: String?,
        needSave: Boolean = true
    ): String {
        body ?: throw NoStackTraceException(
            WebBookBridge.errorGetWebContent(baseUrl)
        )
        DebugBridge.log(bookSource.bookSourceUrl, "≡获取成功:${baseUrl}")
        DebugBridge.logFull(bookSource.bookSourceUrl, body, true, 40)
        val mNextChapterUrl = if (nextChapterUrl.isNullOrEmpty()) {
            WebBookBridge.nextChapterUrl(book, bookChapter)
        } else {
            nextChapterUrl
        }
        val contentList = arrayListOf<String>()
        val nextUrlList = arrayListOf(redirectUrl)
        val contentRule = bookSource.getContentRule()
        val analyzeRule = AnalyzeRule(book, bookSource)
        analyzeRule.setContent(body, baseUrl)
        analyzeRule.setRedirectUrl(redirectUrl)
        analyzeRule.setCoroutineContext(coroutineContext)
        analyzeRule.setChapter(bookChapter)
        analyzeRule.setNextChapterUrl(mNextChapterUrl)
        coroutineContext.ensureActive()
        val titleRule = contentRule.title
        if (!titleRule.isNullOrBlank()) {
            val title = analyzeRule.runCatching {
                getString(titleRule)
            }.onFailure {
                DebugBridge.log(bookSource.bookSourceUrl, "获取标题出错, ${it.localizedMessage}")
            }.getOrNull()
            if (!title.isNullOrBlank()) {
                bookChapter.title = title
                bookChapter.titleMD5 = null
                WebBookBridge.updateChapter(bookChapter)
            }
        }
        var contentData = analyzeContent(
            book, baseUrl, redirectUrl, body, contentRule, bookChapter, bookSource, mNextChapterUrl
        )
        contentList.add(contentData.first)
        if (contentData.second.size == 1) {
            var nextUrl = contentData.second[0]
            while (nextUrl.isNotEmpty() && !nextUrlList.contains(nextUrl)) {
                if (!mNextChapterUrl.isNullOrEmpty()
                    && NetworkUtils.getAbsoluteURL(redirectUrl, nextUrl)
                    == NetworkUtils.getAbsoluteURL(redirectUrl, mNextChapterUrl)
                ) break
                nextUrlList.add(nextUrl)
                coroutineContext.ensureActive()
                val analyzeUrl = AnalyzeUrl(
                    mUrl = nextUrl,
                    source = bookSource,
                    ruleData = book,
                    coroutineContext = coroutineContext
                )
                val res = analyzeUrl.getStrResponseAwait() //控制并发访问
                res.body?.let { nextBody ->
                    contentData = analyzeContent(
                        book, nextUrl, res.url, nextBody, contentRule,
                        bookChapter, bookSource, mNextChapterUrl,
                        printLog = false
                    )
                    nextUrl =
                        if (contentData.second.isNotEmpty()) contentData.second[0] else ""
                    contentList.add(contentData.first)
                    DebugBridge.log(bookSource.bookSourceUrl, "第${contentList.size}页完成")
                }
            }
            DebugBridge.log(bookSource.bookSourceUrl, "◇本章总页数:${nextUrlList.size}")
        } else if (contentData.second.size > 1) {
            DebugBridge.log(bookSource.bookSourceUrl, "◇并发解析正文,总页数:${contentData.second.size}")
            flow {
                for (urlStr in contentData.second) {
                    emit(urlStr)
                }
            }.mapAsync(WebBookBridge.threadCount()) { urlStr ->
                val analyzeUrl = AnalyzeUrl(
                    mUrl = urlStr,
                    source = bookSource,
                    ruleData = book,
                    coroutineContext = coroutineContext
                )
                val res = analyzeUrl.getStrResponseAwait() //控制并发访问
                analyzeContent(
                    book, urlStr, res.url, res.body!!, contentRule,
                    bookChapter, bookSource, mNextChapterUrl,
                    getNextPageUrl = false,
                    printLog = false
                ).first
            }.collect {
                coroutineContext.ensureActive()
                contentList.add(it)
            }
        }
        var contentStr = contentList.joinToString("\n")
        //全文替换
        val replaceRegex = contentRule.replaceRegex
        if (!replaceRegex.isNullOrEmpty()) {
            contentStr = contentStr.split(AppPattern.LFRegex).joinToString("\n") { it.trim() }
            contentStr = analyzeRule.getString(replaceRegex, contentStr)
            contentStr = contentStr.split(AppPattern.LFRegex).joinToString("\n") { "　　$it" }
        }
        DebugBridge.log(bookSource.bookSourceUrl, "┌获取章节名称")
        DebugBridge.log(bookSource.bookSourceUrl, "└${bookChapter.title}")
        DebugBridge.log(bookSource.bookSourceUrl, "┌获取正文内容")
        DebugBridge.log(bookSource.bookSourceUrl, "└\n$contentStr")
        if (!bookChapter.isVolume && contentStr.isBlank()) {
            throw ContentEmptyException("内容为空")
        }
        if (needSave) {
            WebBookBridge.saveContent(bookSource, book, bookChapter, contentStr)
        }
        return contentStr
    }

    @Throws(Exception::class)
    private suspend fun analyzeContent(
        book: Book,
        baseUrl: String,
        redirectUrl: String,
        body: String,
        contentRule: ContentRule,
        chapter: BookChapter,
        bookSource: BookSourceContract,
        nextChapterUrl: String?,
        getNextPageUrl: Boolean = true,
        printLog: Boolean = true
    ): Pair<String, List<String>> {
        val analyzeRule = AnalyzeRule(book, bookSource)
        analyzeRule.setContent(body, baseUrl)
        analyzeRule.setCoroutineContext(coroutineContext)
        val rUrl = analyzeRule.setRedirectUrl(redirectUrl)
        analyzeRule.setNextChapterUrl(nextChapterUrl)
        val nextUrlList = arrayListOf<String>()
        analyzeRule.setChapter(chapter)
        //获取正文
        var content = analyzeRule.getString(contentRule.content, unescape = false)
        content = HtmlFormatter.formatKeepImg(content, rUrl)
        if (content.indexOf('&') > -1) {
            content = StringEscapeUtils.unescapeHtml4(content)
        }
        //获取下一页链接
        if (getNextPageUrl) {
            val nextUrlRule = contentRule.nextContentUrl
            if (!nextUrlRule.isNullOrEmpty()) {
                DebugBridge.logFull(bookSource.bookSourceUrl, "┌获取正文下一页链接", printLog, 1)
                analyzeRule.getStringList(nextUrlRule, isUrl = true)?.let {
                    nextUrlList.addAll(it)
                }
                DebugBridge.logFull(
                    bookSource.bookSourceUrl, "└" + nextUrlList.joinToString("，"), printLog, 1
                )
            }
        }
        return Pair(content, nextUrlList)
    }
}
