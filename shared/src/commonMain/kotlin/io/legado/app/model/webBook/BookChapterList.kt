package io.legado.app.model.webBook

import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSourceContract
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.exception.NoStackTraceException
import io.legado.app.exception.TocEmptyException
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.model.DebugBridge
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setChapter
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.isTrue
import io.legado.app.utils.mapAsync
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flow
import org.htmlunit.corejs.javascript.Context
import kotlin.coroutines.coroutineContext

/**
 * 获取目录
 */
object BookChapterList {

    suspend fun analyzeChapterList(
        bookSource: BookSourceContract,
        book: Book,
        baseUrl: String,
        redirectUrl: String,
        body: String?
    ): List<BookChapter> {
        body ?: throw NoStackTraceException(
            WebBookBridge.errorGetWebContent(baseUrl)
        )
        val chapterList = ArrayList<BookChapter>()
        DebugBridge.log(bookSource.bookSourceUrl, "≡获取成功:${baseUrl}")
        DebugBridge.logFull(bookSource.bookSourceUrl, body, true, 30)
        val tocRule = bookSource.getTocRule()
        val nextUrlList = arrayListOf(redirectUrl)
        var reverse = false
        var listRule = tocRule.chapterList ?: ""
        if (listRule.startsWith("-")) {
            reverse = true
            listRule = listRule.substring(1)
        }
        if (listRule.startsWith("+")) {
            listRule = listRule.substring(1)
        }
        var chapterData =
            analyzeChapterList(
                book, baseUrl, redirectUrl, body,
                tocRule, listRule, bookSource, log = true
            )
        chapterList.addAll(chapterData.first)
        when (chapterData.second.size) {
            0 -> Unit
            1 -> {
                var nextUrl = chapterData.second[0]
                while (nextUrl.isNotEmpty() && !nextUrlList.contains(nextUrl)) {
                    nextUrlList.add(nextUrl)
                    val analyzeUrl = AnalyzeUrl(
                        mUrl = nextUrl,
                        source = bookSource,
                        ruleData = book,
                        coroutineContext = coroutineContext
                    )
                    val res = analyzeUrl.getStrResponseAwait() //控制并发访问
                    res.body?.let { nextBody ->
                        chapterData = analyzeChapterList(
                            book, nextUrl, nextUrl,
                            nextBody, tocRule, listRule, bookSource
                        )
                        nextUrl = chapterData.second.firstOrNull() ?: ""
                        chapterList.addAll(chapterData.first)
                    }
                }
                DebugBridge.log(bookSource.bookSourceUrl, "◇目录总页数:${nextUrlList.size}")
            }

            else -> {
                DebugBridge.log(
                    bookSource.bookSourceUrl,
                    "◇并发解析目录,总页数:${chapterData.second.size}"
                )
                flow {
                    for (urlStr in chapterData.second) {
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
                    analyzeChapterList(
                        book, urlStr, res.url,
                        res.body!!, tocRule, listRule, bookSource, false
                    ).first
                }.collect {
                    chapterList.addAll(it)
                }
            }
        }
        if (chapterList.isEmpty()) {
            throw TocEmptyException(WebBookBridge.chapterListEmpty())
        }
        if (!reverse) {
            chapterList.reverse()
        }
        coroutineContext.ensureActive()
        //去重
        val lh = LinkedHashSet(chapterList)
        val list = ArrayList(lh)
        if (!book.getReverseToc()) {
            list.reverse()
        }
        DebugBridge.log(book.origin, "◇目录总数:${list.size}")
        coroutineContext.ensureActive()
        list.forEachIndexed { index, bookChapter ->
            bookChapter.index = index
        }
        val formatJs = tocRule.formatJs
        if (!formatJs.isNullOrBlank()) {
            Context.enter().use {
                val bindings = ScriptBindings()
                bindings["gInt"] = 0
                list.forEachIndexed { index, bookChapter ->
                    bindings["index"] = index + 1
                    bindings["chapter"] = bookChapter
                    bindings["title"] = bookChapter.title
                    RhinoScriptEngine.runCatching {
                        eval(formatJs, bindings)?.toString()?.let {
                            bookChapter.title = it
                        }
                    }.onFailure {
                        DebugBridge.log(book.origin, "格式化标题出错, ${it.localizedMessage}")
                    }
                }
            }
        }
        updateBookTocInfo(book, list)
        return list
    }

    /**
     * 目录解析成功后的 book 字段回写:durChapterTitle/lastCheckCount/lastCheckTime/
     * totalChapterNum/latestChapterTitle/章节字数。声明式与 JS 源(JsSourceBook)共用,
     * 两类源在目录侧对 book 的契约保持一致;list 须非空,两侧调用前均已空判抛出。
     * 标题显示格式化(ContentProcessor 替换规则/简繁转换)与目录字数回填为 app 侧行为,
     * 经 WebBookBridge 钩子转发, 未接线时退化为原始标题/不回填。
     */
    suspend fun updateBookTocInfo(book: Book, list: List<BookChapter>) {
        book.durChapterTitle =
            WebBookBridge.chapterDisplayTitle(book, list.getOrElse(book.durChapterIndex) { list.last() })
        if (book.totalChapterNum < list.size) {
            book.lastCheckCount = list.size - book.totalChapterNum
            book.latestChapterTime = System.currentTimeMillis()
        }
        book.lastCheckTime = System.currentTimeMillis()
        book.totalChapterNum = list.size
        book.latestChapterTitle =
            WebBookBridge.chapterDisplayTitle(
                book,
                list.getOrElse(book.simulatedTotalChapterNum() - 1) { list.last() }
            )
        coroutineContext.ensureActive()
        WebBookBridge.fillWordCount(book, list)
    }

    private suspend fun analyzeChapterList(
        book: Book,
        baseUrl: String,
        redirectUrl: String,
        body: String,
        tocRule: TocRule,
        listRule: String,
        bookSource: BookSourceContract,
        getNextUrl: Boolean = true,
        log: Boolean = false
    ): Pair<List<BookChapter>, List<String>> {
        val analyzeRule = AnalyzeRule(book, bookSource)
        analyzeRule.setContent(body).setBaseUrl(baseUrl)
        analyzeRule.setRedirectUrl(redirectUrl)
        analyzeRule.setCoroutineContext(coroutineContext)
        //获取目录列表
        val chapterList = arrayListOf<BookChapter>()
        DebugBridge.logFull(bookSource.bookSourceUrl, "┌获取目录列表", log, 1)
        val elements = analyzeRule.getElements(listRule)
        DebugBridge.logFull(bookSource.bookSourceUrl, "└列表大小:${elements.size}", log, 1)
        //获取下一页链接
        val nextUrlList = arrayListOf<String>()
        val nextTocRule = tocRule.nextTocUrl
        if (getNextUrl && !nextTocRule.isNullOrEmpty()) {
            DebugBridge.logFull(bookSource.bookSourceUrl, "┌获取目录下一页列表", log, 1)
            analyzeRule.getStringList(nextTocRule, isUrl = true)?.let {
                for (item in it) {
                    if (item != redirectUrl) {
                        nextUrlList.add(item)
                    }
                }
            }
            DebugBridge.logFull(
                bookSource.bookSourceUrl,
                "└" + nextUrlList.joinToString("，\n"),
                log,
                1
            )
        }
        coroutineContext.ensureActive()
        if (elements.isNotEmpty()) {
            DebugBridge.logFull(bookSource.bookSourceUrl, "┌解析目录列表", log, 1)
            val nameRule = analyzeRule.splitSourceRule(tocRule.chapterName)
            val urlRule = analyzeRule.splitSourceRule(tocRule.chapterUrl)
            val vipRule = analyzeRule.splitSourceRule(tocRule.isVip)
            val payRule = analyzeRule.splitSourceRule(tocRule.isPay)
            val upTimeRule = analyzeRule.splitSourceRule(tocRule.updateTime)
            val isVolumeRule = analyzeRule.splitSourceRule(tocRule.isVolume)
            elements.forEachIndexed { index, item ->
                coroutineContext.ensureActive()
                analyzeRule.setContent(item)
                val bookChapter = BookChapter(bookUrl = book.bookUrl, baseUrl = redirectUrl)
                analyzeRule.setChapter(bookChapter)
                bookChapter.title = analyzeRule.getString(nameRule)
                bookChapter.url = analyzeRule.getString(urlRule)
                bookChapter.tag = analyzeRule.getString(upTimeRule)
                val isVolume = analyzeRule.getString(isVolumeRule)
                bookChapter.isVolume = false
                if (isVolume.isTrue()) {
                    bookChapter.isVolume = true
                }
                if (bookChapter.url.isEmpty()) {
                    if (bookChapter.isVolume) {
                        bookChapter.url = bookChapter.title + index
                        DebugBridge.log(
                            bookSource.bookSourceUrl,
                            "⇒一级目录${index}未获取到url,使用标题替代"
                        )
                    } else {
                        bookChapter.url = baseUrl
                        DebugBridge.log(
                            bookSource.bookSourceUrl,
                            "⇒目录${index}未获取到url,使用baseUrl替代"
                        )
                    }
                }
                if (bookChapter.title.isNotEmpty()) {
                    val isVip = analyzeRule.getString(vipRule)
                    val isPay = analyzeRule.getString(payRule)
                    if (isVip.isTrue()) {
                        bookChapter.isVip = true
                    }
                    if (isPay.isTrue()) {
                        bookChapter.isPay = true
                    }
                    chapterList.add(bookChapter)
                }
            }
            DebugBridge.logFull(bookSource.bookSourceUrl, "└目录列表解析完成", log, 1)
            if (chapterList.isEmpty()) {
                DebugBridge.logFull(bookSource.bookSourceUrl, "◇章节列表为空", log, 1)
            } else {
                DebugBridge.logFull(bookSource.bookSourceUrl, "≡首章信息", log, 1)
                DebugBridge.logFull(bookSource.bookSourceUrl, "◇章节名称:${chapterList[0].title}", log, 1)
                DebugBridge.logFull(bookSource.bookSourceUrl, "◇章节链接:${chapterList[0].url}", log, 1)
                DebugBridge.logFull(bookSource.bookSourceUrl, "◇章节信息:${chapterList[0].tag}", log, 1)
                DebugBridge.logFull(bookSource.bookSourceUrl, "◇是否VIP:${chapterList[0].isVip}", log, 1)
                DebugBridge.logFull(bookSource.bookSourceUrl, "◇是否购买:${chapterList[0].isPay}", log, 1)
            }
        }
        return Pair(chapterList, nextUrlList)
    }

}
