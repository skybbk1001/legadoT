package io.legado.app.model.analyzeRule

import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SourceContract
import io.legado.app.help.JsExtensionsAndroid
import io.legado.app.model.webBook.WebBook
import java.net.URL

/**
 * AnalyzeRule 迁入 shared 后仅实现纯 JsExtensions; app 侧经 AnalyzeRule.javaDelegateFactory
 * 注入本委托, evalJS 的 java 绑定指向本实例, 使书源 JS 的 java.toast/webView 等
 * Android 扩展与 java.ajax/reGetBook/getElements 等 AnalyzeRule 上下文方法同时可用。
 * 行为与迁移前(java=AnalyzeRule 实例, 其实现 JsExtensionsAndroid)保持一致。
 */
class AnalyzeRuleAndroidDelegate(
    private val analyzeRule: AnalyzeRule
) : JsExtensionsAndroid {

    override fun getSource(): BaseSource? = analyzeRule.getSource() as? BaseSource

    override fun ajax(url: Any): String? = analyzeRule.ajax(url)

    fun reGetBook() = analyzeRule.reGetBook()

    fun refreshTocUrl() = analyzeRule.refreshTocUrl()

    fun setContent(content: Any?, baseUrl: String? = null): AnalyzeRule =
        analyzeRule.setContent(content, baseUrl)

    fun setBaseUrl(baseUrl: String?): AnalyzeRule = analyzeRule.setBaseUrl(baseUrl)

    fun setRedirectUrl(url: String): URL? = analyzeRule.setRedirectUrl(url)

    fun setLocal(key: String, value: String): AnalyzeRule = analyzeRule.setLocal(key, value)

    fun put(key: String, value: String): String = analyzeRule.put(key, value)

    fun get(key: String): String = analyzeRule.get(key)

    fun evalJS(jsStr: String, result: Any? = null): Any? = analyzeRule.evalJS(jsStr, result)

    fun getStringList(rule: String?, mContent: Any? = null, isUrl: Boolean = false): List<String>? =
        analyzeRule.getStringList(rule, mContent, isUrl)

    fun getStringList(
        ruleList: List<AnalyzeRule.SourceRule>,
        mContent: Any? = null,
        isUrl: Boolean = false
    ): List<String>? = analyzeRule.getStringList(ruleList, mContent, isUrl)

    fun getString(ruleStr: String?, mContent: Any? = null, isUrl: Boolean = false): String =
        analyzeRule.getString(ruleStr, mContent, isUrl)

    fun getString(ruleStr: String?, unescape: Boolean): String =
        analyzeRule.getString(ruleStr, unescape)

    fun getString(
        ruleList: List<AnalyzeRule.SourceRule>,
        mContent: Any? = null,
        isUrl: Boolean = false,
        unescape: Boolean = true
    ): String = analyzeRule.getString(ruleList, mContent, isUrl, unescape)

    fun getElement(ruleStr: String): Any? = analyzeRule.getElement(ruleStr)

    fun getElements(ruleStr: String): List<Any> = analyzeRule.getElements(ruleStr)

    fun splitSourceRule(ruleStr: String?, allInOne: Boolean = false): List<AnalyzeRule.SourceRule> =
        analyzeRule.splitSourceRule(ruleStr, allInOne)

}

/**
 * AnalyzeRule.PreUpdateHook 的 app 实现:
 * 承接迁移前 AnalyzeRule.reGetBook/refreshTocUrl 中对 WebBook 的调用。
 */
object WebBookPreUpdateHook : AnalyzeRule.PreUpdateHook {

    override suspend fun reGetBook(source: SourceContract, book: BaseBook) {
        val bookSource = source as? BookSource ?: return
        val mBook = book as? Book ?: return
        WebBook.preciseSearchAwait(bookSource, mBook.name, mBook.author)
            .getOrThrow().let {
                mBook.bookUrl = it.bookUrl
                it.variableMap.forEach { entry ->
                    mBook.putVariable(entry.key, entry.value)
                }
            }
        WebBook.getBookInfoAwait(bookSource, mBook, false)
    }

    override suspend fun refreshTocUrl(source: SourceContract, book: BaseBook) {
        val bookSource = source as? BookSource ?: return
        val mBook = book as? Book ?: return
        WebBook.getBookInfoAwait(bookSource, mBook, false)
    }

}
