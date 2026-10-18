package io.legado.app.model.webBook

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSourceContract
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookFormat
import io.legado.app.help.book.isWebFile
import io.legado.app.model.DebugBridge
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.StringUtils.wordCountFormat
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext


/**
 * 获取详情
 */
object BookInfo {

    @Throws(Exception::class)
    suspend fun analyzeBookInfo(
        bookSource: BookSourceContract,
        book: Book,
        baseUrl: String,
        redirectUrl: String,
        body: String?,
        canReName: Boolean,
    ) {
        body ?: throw NoStackTraceException(
            WebBookBridge.errorGetWebContent(baseUrl)
        )
        DebugBridge.log(bookSource.bookSourceUrl, "≡获取成功:${baseUrl}")
        DebugBridge.logFull(bookSource.bookSourceUrl, body, true, 20)
        val analyzeRule = AnalyzeRule(book, bookSource)
        analyzeRule.setContent(body).setBaseUrl(baseUrl)
        analyzeRule.setRedirectUrl(redirectUrl)
        analyzeRule.setCoroutineContext(coroutineContext)
        analyzeBookInfo(book, body, analyzeRule, bookSource, baseUrl, redirectUrl, canReName)
    }

    suspend fun analyzeBookInfo(
        book: Book,
        body: String,
        analyzeRule: AnalyzeRule,
        bookSource: BookSourceContract,
        baseUrl: String,
        redirectUrl: String,
        canReName: Boolean,
    ) {
        val infoRule = bookSource.getBookInfoRule()
        infoRule.init?.let {
            if (it.isNotBlank()) {
                coroutineContext.ensureActive()
                DebugBridge.log(bookSource.bookSourceUrl, "≡执行详情页初始化规则")
                analyzeRule.setContent(analyzeRule.getElement(it))
            }
        }
        val mCanReName = canReName && !infoRule.canReName.isNullOrBlank()
        coroutineContext.ensureActive()
        DebugBridge.log(bookSource.bookSourceUrl, "┌获取书名")
        BookFormat.formatBookName(analyzeRule.getString(infoRule.name)).let {
            if (it.isNotEmpty() && (mCanReName || book.name.isEmpty())) {
                book.name = it
            }
            DebugBridge.log(bookSource.bookSourceUrl, "└${it}")
        }
        coroutineContext.ensureActive()
        DebugBridge.log(bookSource.bookSourceUrl, "┌获取作者")
        BookFormat.formatBookAuthor(analyzeRule.getString(infoRule.author)).let {
            if (it.isNotEmpty() && (mCanReName || book.author.isEmpty())) {
                book.author = it
            }
            DebugBridge.log(bookSource.bookSourceUrl, "└${it}")
        }
        coroutineContext.ensureActive()
        DebugBridge.log(bookSource.bookSourceUrl, "┌获取分类")
        try {
            analyzeRule.getStringList(infoRule.kind)
                ?.joinToString(",")
                ?.let {
                    if (it.isNotEmpty()) book.kind = it
                    DebugBridge.log(bookSource.bookSourceUrl, "└${it}")
                } ?: DebugBridge.log(bookSource.bookSourceUrl, "└")
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            DebugBridge.log(bookSource.bookSourceUrl, "└${e.localizedMessage}")
            WebBookBridge.logError("获取分类出错", e)
        }
        coroutineContext.ensureActive()
        DebugBridge.log(bookSource.bookSourceUrl, "┌获取字数")
        try {
            wordCountFormat(analyzeRule.getString(infoRule.wordCount)).let {
                if (it.isNotEmpty()) book.wordCount = it
                DebugBridge.log(bookSource.bookSourceUrl, "└${it}")
            }
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            DebugBridge.log(bookSource.bookSourceUrl, "└${e.localizedMessage}")
            WebBookBridge.logError("获取字数出错", e)
        }
        coroutineContext.ensureActive()
        DebugBridge.log(bookSource.bookSourceUrl, "┌获取最新章节")
        try {
            analyzeRule.getString(infoRule.lastChapter).let {
                if (it.isNotEmpty()) book.latestChapterTitle = it
                DebugBridge.log(bookSource.bookSourceUrl, "└${it}")
            }
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            DebugBridge.log(bookSource.bookSourceUrl, "└${e.localizedMessage}")
            WebBookBridge.logError("获取最新章节出错", e)
        }
        coroutineContext.ensureActive()
        DebugBridge.log(bookSource.bookSourceUrl, "┌获取简介")
        try {
            HtmlFormatter.formatIntro(analyzeRule.getString(infoRule.intro)).let {
                if (it.isNotEmpty()) book.intro = it
                DebugBridge.log(bookSource.bookSourceUrl, "└${it}")
            }
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            DebugBridge.log(bookSource.bookSourceUrl, "└${e.localizedMessage}")
            WebBookBridge.logError("获取简介出错", e)
        }
        coroutineContext.ensureActive()
        DebugBridge.log(bookSource.bookSourceUrl, "┌获取封面链接")
        try {
            analyzeRule.getString(infoRule.coverUrl).let {
                if (it.isNotEmpty()) {
                    book.coverUrl =
                        NetworkUtils.getAbsoluteURL(redirectUrl, it)
                }
                DebugBridge.log(bookSource.bookSourceUrl, "└${it}")
            }
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            DebugBridge.log(bookSource.bookSourceUrl, "└${e.localizedMessage}")
            WebBookBridge.logError("获取封面出错", e)
        }
        coroutineContext.ensureActive()
        if (!book.isWebFile) {
            DebugBridge.log(bookSource.bookSourceUrl, "┌获取目录链接")
            book.tocUrl = analyzeRule.getString(infoRule.tocUrl, isUrl = true)
            if (book.tocUrl.isEmpty()) book.tocUrl = baseUrl
            if (book.tocUrl == baseUrl) {
                book.tocHtml = body
            }
            DebugBridge.log(bookSource.bookSourceUrl, "└${book.tocUrl}")
        } else {
            DebugBridge.log(bookSource.bookSourceUrl, "┌获取文件下载链接")
            book.downloadUrls = analyzeRule.getStringList(infoRule.downloadUrls, isUrl = true)
            if (book.downloadUrls.isNullOrEmpty()) {
                DebugBridge.log(bookSource.bookSourceUrl, "└")
                throw NoStackTraceException("下载链接为空")
            } else {
                DebugBridge.log(
                    bookSource.bookSourceUrl,
                    "└" + book.downloadUrls!!.joinToString("，\n")
                )
            }
        }
    }

}
