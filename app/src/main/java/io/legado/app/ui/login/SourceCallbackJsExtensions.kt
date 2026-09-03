package io.legado.app.ui.login

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.help.JsExtensions
import io.legado.app.ui.association.AddToBookshelfDialog
import io.legado.app.ui.book.explore.ExploreShowActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

@Suppress("unused")
class SourceCallbackJsExtensions(
    activity: AppCompatActivity?,
    source: BaseSource?
) : JsExtensions {

    val activityRef: WeakReference<AppCompatActivity> = WeakReference(activity)
    val sourceRef: WeakReference<BaseSource?> = WeakReference(source)

    override fun getSource(): BaseSource? {
        return sourceRef.get()
    }

    fun searchBook(key: String) {
        activityRef.get()?.let {
            SearchActivity.start(it, key)
        }
    }

    fun addBook(bookUrl: String) {
        activityRef.get()?.showDialogFragment(AddToBookshelfDialog(bookUrl))
    }

    /**
     * 页面跳转调度
     * @param name login=源登录页 search=书籍搜索 explore=发现结果页
     * @param url explore=发现地址
     * @param title 页面标题; search 时为搜索词
     * @param origin 指定目标源(书源url), 缺省当前源
     */
    @JvmOverloads
    fun open(name: String, url: String? = null, title: String? = null, origin: String? = null) {
        val activity = activityRef.get() ?: return
        activity.lifecycleScope.launch(IO) {
            when (name) {
                "login" -> openLogin(activity, origin)
                "search" -> title?.let { key ->
                    withContext(Main) { searchBook(key) }
                }
                "explore" -> {
                    if (url.isNullOrBlank()) return@launch
                    val toSource = if (origin.isNullOrBlank()) {
                        getSource() as? BookSource
                    } else {
                        appDb.bookSourceDao.getBookSource(origin)
                    } ?: return@launch
                    withContext(Main) {
                        activity.startActivity<ExploreShowActivity> {
                            putExtra("exploreName", title)
                            putExtra("sourceUrl", toSource.bookSourceUrl)
                            putExtra("exploreUrl", url)
                        }
                    }
                }
            }
        }
    }

    private suspend fun openLogin(activity: AppCompatActivity, origin: String?) {
        val toSource: BaseSource? = if (origin.isNullOrBlank()) {
            getSource()
        } else {
            appDb.bookSourceDao.getBookSource(origin) ?: appDb.rssSourceDao.getByKey(origin)
        }
        if (toSource == null) {
            activity.toastOnUi("未找到源")
            return
        }
        if (toSource.loginUrl.isNullOrBlank()) {
            activity.toastOnUi("源未配置登录")
            return
        }
        val (type, key) = when (toSource) {
            is BookSource -> "bookSource" to toSource.bookSourceUrl
            is RssSource -> "rssSource" to toSource.sourceUrl
            else -> return
        }
        withContext(Main) {
            activity.startActivity<SourceLoginActivity> {
                putExtra("type", type)
                putExtra("key", key)
            }
        }
    }

}
