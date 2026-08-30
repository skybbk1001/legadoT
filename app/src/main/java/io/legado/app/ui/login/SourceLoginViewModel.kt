package io.legado.app.ui.login

import android.app.Application
import android.content.Intent
import com.script.rhino.runScriptWithContext
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.model.AutoTask
import io.legado.app.exception.NoStackTraceException
import io.legado.app.utils.toastOnUi

class SourceLoginViewModel(application: Application) : BaseViewModel(application) {

    var source: BaseSource? = null
    var headerMap: Map<String, String> = emptyMap()

    /** 触发登录的书籍上下文:仅从带 bookUrl 的入口(阅读页/详情页/听书页)进入时非空 */
    var book: Book? = null
    var chapter: BookChapter? = null

    fun initData(intent: Intent, success: (bookSource: BaseSource) -> Unit, error: () -> Unit) {
        execute {
            val sourceKey = intent.getStringExtra("key")
                ?: throw NoStackTraceException("没有参数")
            when (intent.getStringExtra("type")) {
                "bookSource" -> source = appDb.bookSourceDao.getBookSource(sourceKey)
                "rssSource" -> source = appDb.rssSourceDao.getByKey(sourceKey)
                "httpTts" -> source = appDb.httpTTSDao.get(sourceKey.toLong())
                "autoTask" -> {
                    val rule = AutoTask.getRules().firstOrNull { it.id == sourceKey }
                        ?: return@execute null
                    source = BookSource(
                        bookSourceUrl = "${AutoTask.SOURCE_KEY}:${rule.id}",
                        bookSourceName = rule.name
                    ).apply {
                        loginUrl = rule.loginUrl
                        loginUi = rule.loginUi
                        loginCheckJs = rule.loginCheckJs
                        header = rule.header
                        jsLib = rule.jsLib
                        concurrentRate = rule.concurrentRate
                        enabledCookieJar = rule.enabledCookieJar
                    }
                }
            }
            headerMap = runScriptWithContext {
                source?.getHeaderMap(true) ?: emptyMap()
            }
            loadBookContext(intent)
            source
        }.onSuccess {
            if (it != null) {
                success.invoke(it)
            } else {
                context.toastOnUi("未找到书源")
            }
        }.onError {
            error.invoke()
            AppLog.put("登录 UI 初始化失败\n$it", it, true)
        }
    }

    /**
     * 从 intent 恢复书籍/章节上下文。bookUrl 是书籍主键,durChapterIndex < 0 表示
     * 无当前章节(如详情页入口)。书籍/章节按需从库重载,避免 Parcel 传递整棵实体。
     */
    private fun loadBookContext(intent: Intent) {
        val bookUrl = intent.getStringExtra("bookUrl")?.takeIf { it.isNotBlank() } ?: return
        book = appDb.bookDao.getBook(bookUrl)
        val chapterIndex = intent.getIntExtra("durChapterIndex", -1)
        if (chapterIndex >= 0) {
            chapter = appDb.bookChapterDao.getChapter(bookUrl, chapterIndex)
        }
    }

}
