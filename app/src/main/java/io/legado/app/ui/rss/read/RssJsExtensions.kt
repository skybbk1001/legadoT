package io.legado.app.ui.rss.read

import androidx.lifecycle.lifecycleScope
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.help.JsExtensionsAndroid
import io.legado.app.ui.association.AddToBookshelfDialog
import io.legado.app.ui.book.explore.ExploreShowActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.rss.article.RssSortActivity
import io.legado.app.utils.openUrl
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Suppress("unused")
class RssJsExtensions(private val activity: ReadRssActivity) : JsExtensionsAndroid {

    override fun getSource(): BaseSource? {
        return activity.getSource()
    }

    fun searchBook(key: String) {
        SearchActivity.start(activity, key)
    }

    fun addBook(bookUrl: String) {
        activity.showDialogFragment(AddToBookshelfDialog(bookUrl))
    }

    /**
     * 页面跳转调度
     * @param name login=源登录页 rss=订阅阅读页 sort=订阅分类列表 search=书籍搜索 explore=发现结果页
     * @param url rss=文章链接(空则开列表/单页源) explore=发现地址
     * @param title 页面标题; search 时为搜索词
     * @param origin 指定目标源(书源url/订阅源url), 缺省当前源
     */
    @JvmOverloads
    fun open(name: String, url: String? = null, title: String? = null, origin: String? = null) {
        activity.lifecycleScope.launch(IO) {
            when (name) {
                "login" -> openLogin(origin)
                "sort" -> {
                    val toSource = resolveRssSource(origin) ?: return@launch
                    withContext(Main) {
                        activity.startActivity<RssSortActivity> {
                            putExtra("url", toSource.sourceUrl)
                        }
                    }
                }

                "rss" -> openRss(url, title, origin)
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

    private suspend fun openLogin(origin: String?) {
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

    private suspend fun openRss(url: String?, title: String?, origin: String?) {
        val toSource = resolveRssSource(origin) ?: return
        val pageTitle = title ?: toSource.sourceName
        if (url.isNullOrBlank()) {
            when {
                !toSource.singleUrl -> withContext(Main) {
                    activity.startActivity<RssSortActivity> {
                        putExtra("url", toSource.sourceUrl)
                    }
                }

                toSource.sourceUrl.startsWith("http", true) -> withContext(Main) {
                    activity.startActivity<ReadRssActivity> {
                        putExtra("title", pageTitle)
                        putExtra("origin", toSource.sourceUrl)
                    }
                }

                else -> withContext(Main) {
                    activity.openUrl(toSource.sourceUrl)
                }
            }
            return
        }
        withContext(Main) {
            activity.startActivity<ReadRssActivity> {
                putExtra("title", pageTitle)
                putExtra("origin", toSource.sourceUrl)
                putExtra("link", url)
            }
        }
    }

    private fun resolveRssSource(origin: String?): RssSource? {
        if (origin.isNullOrBlank()) {
            return getSource() as? RssSource
        }
        return appDb.rssSourceDao.getByKey(origin)
    }

}
