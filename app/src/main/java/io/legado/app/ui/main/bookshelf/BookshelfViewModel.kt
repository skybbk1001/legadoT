package io.legado.app.ui.main.bookshelf

import android.app.Application
import androidx.lifecycle.MutableLiveData
import com.google.gson.stream.JsonWriter
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.model.webBook.WebBook
import io.legado.app.model.webBook.preciseSearch
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import io.legado.app.data.entities.migrateTo
import io.legado.app.data.entities.save

class BookshelfViewModel(application: Application) : BaseViewModel(application) {
    val addBookProgressLiveData = MutableLiveData(-1)
    var addBookJob: Coroutine<*>? = null

    private fun getTargetGroupId(groupId: Long): Long? {
        return if (groupId > 0) {
            groupId
        } else {
            null
        }
    }

    fun addBookByUrl(bookUrls: String, groupId: Long = 0) {
        var successCount = 0
        addBookJob = execute {
            val targetGroupId = getTargetGroupId(groupId)
            val hasBookUrlPattern: List<BookSourcePart> by lazy {
                appDb.bookSourceDao.hasBookUrlPattern
            }
            val urls = bookUrls.split("\n")
            for (url in urls) {
                val bookUrl = url.trim()
                if (bookUrl.isEmpty()) continue
                val existedBook = appDb.bookDao.getBook(bookUrl)
                if (existedBook != null) {
                    targetGroupId?.let { currentGroupId ->
                        if (existedBook.group and currentGroupId != currentGroupId) {
                            existedBook.group = existedBook.group or currentGroupId
                            existedBook.save()
                        }
                    }
                    successCount++
                    continue
                }
                val baseUrl = NetworkUtils.getBaseUrl(bookUrl) ?: continue
                var source = appDb.bookSourceDao.getBookSourceAddBook(baseUrl)
                if (source == null) {
                    for (bookSource in hasBookUrlPattern) {
                        try {
                            val bs = bookSource.getBookSource()!!
                            if (bookUrl.matches(bs.bookUrlPattern!!.toRegex())) {
                                source = bs
                                break
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
                val bookSource = source ?: continue
                val book = Book(
                    bookUrl = bookUrl,
                    origin = bookSource.bookSourceUrl,
                    originName = bookSource.bookSourceName
                )
                targetGroupId?.let { currentGroupId ->
                    book.group = currentGroupId
                }
                kotlin.runCatching {
                    WebBook.getBookInfoAwait(bookSource, book)
                }.onSuccess { fetchedBook ->
                    val dbBook = appDb.bookDao.getBook(fetchedBook.name, fetchedBook.author)
                    if (dbBook != null) {
                        val toc = WebBook.getChapterListAwait(bookSource, fetchedBook).getOrThrow()
                        dbBook.migrateTo(fetchedBook, toc)
                        targetGroupId?.let { id ->
                            fetchedBook.group = fetchedBook.group or id
                        }
                        appDb.bookDao.insert(fetchedBook)
                        appDb.bookChapterDao.insert(*toc.toTypedArray())
                    } else {
                        fetchedBook.order = appDb.bookDao.minOrder - 1
                        fetchedBook.save()
                    }
                    successCount++
                    addBookProgressLiveData.postValue(successCount)
                }
            }
        }.onSuccess {
            if (successCount > 0) {
                context.toastOnUi(R.string.success)
            } else {
                context.toastOnUi("添加网址失败")
            }
        }.onError { error ->
            AppLog.put("添加网址出错\n${error.localizedMessage}", error, true)
        }.onFinally {
            addBookProgressLiveData.postValue(-1)
        }
    }

    fun exportBookshelf(books: List<Book>?, success: (file: File) -> Unit) {
        execute {
            books?.let { bookList ->
                val path = "${context.filesDir}/books.json"
                FileUtils.delete(path)
                val file = FileUtils.createFileWithReplace(path)
                FileOutputStream(file).use { out ->
                    val writer = JsonWriter(OutputStreamWriter(out, "UTF-8"))
                    writer.setIndent("  ")
                    writer.beginArray()
                    bookList.forEach { book ->
                        val bookMap = hashMapOf<String, String?>()
                        bookMap["name"] = book.name
                        bookMap["author"] = book.author
                        bookMap["intro"] = book.getDisplayIntro()
                        GSON.toJson(bookMap, bookMap::class.java, writer)
                    }
                    writer.endArray()
                    writer.close()
                }
                file
            } ?: throw NoStackTraceException("书籍不能为空")
        }.onSuccess { file ->
            success(file)
        }.onError { error ->
            context.toastOnUi("导出书籍出错\n${error.localizedMessage}")
        }
    }

    fun importBookshelf(str: String, groupId: Long) {
        execute {
            val text = str.trim()
            when {
                text.isAbsUrl() -> {
                    okHttpClient.newCallResponseBody {
                        url(text)
                    }.decompressed().text().let { responseText ->
                        importBookshelf(responseText, groupId)
                    }
                }

                text.isJsonArray() -> {
                    importBookshelfByJson(text, groupId)
                }

                else -> {
                    throw NoStackTraceException("格式不对")
                }
            }
        }.onError { error ->
            context.toastOnUi(error.localizedMessage ?: "ERROR")
        }
    }

    private fun importBookshelfByJson(json: String, groupId: Long) {
        execute {
            val bookSourceParts = appDb.bookSourceDao.allEnabledPart
            val semaphore = Semaphore(AppConfig.threadCount)
            GSON.fromJsonArray<Map<String, String?>>(json).getOrThrow().forEach { bookInfo ->
                val name = bookInfo["name"] ?: ""
                val author = bookInfo["author"] ?: ""
                if (name.isEmpty() || appDb.bookDao.has(name, author)) {
                    return@forEach
                }
                semaphore.withPermit {
                    preciseSearch(
                        this, bookSourceParts, name, author,
                        semaphore = semaphore
                    ).onSuccess { searchResult ->
                        val book = searchResult.first
                        if (groupId > 0) {
                            book.group = groupId
                        }
                        book.save()
                    }.onError { e ->
                        context.toastOnUi(e.localizedMessage)
                    }
                }
            }
        }.onError { error ->
            error.printOnDebug()
        }.onFinally {
            context.toastOnUi(R.string.success)
        }
    }

}
