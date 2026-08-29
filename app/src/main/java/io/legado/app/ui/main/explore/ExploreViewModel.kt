package io.legado.app.ui.main.explore

import android.app.Application
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.ExploreContainer
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.source.ExploreContainerHelp
import io.legado.app.help.source.exploreKinds
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * 单个容器的界面状态。error 与 books 并存时界面优先展示 books(保留旧数据)
 */
data class ExploreContainerState(
    val container: ExploreContainer,
    val books: List<SearchBook> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** 当前展示批次的页码,仅换一批推进,刷新一律回 1 */
    val page: Int = 1,
    /** 本批数据的写入时刻,0 = 未知(隐藏时间标签,视为过期) */
    val updateTime: Long = 0,
    /** 所属书源当前可用分类(供卡片顶部分类切换标签);空/单分类时界面隐藏 */
    val kinds: List<ExploreKind> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreViewModel(application: Application) : BaseViewModel(application) {

    val statesData = MutableLiveData<List<ExploreContainerState>>()
    val upBookshelfLiveData = MutableLiveData<Boolean>()
    val bookshelf: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val states = linkedMapOf<Long, ExploreContainerState>()
    private val stateMutex = Mutex()
    private val loadingIds = ConcurrentHashMap.newKeySet<Long>()

    /** 容器加载并发上限:refreshAll 对几十个容器同时发起时,限流网络请求段避免打爆连接池 */
    private val loadLimiter = Semaphore(4)

    init {
        execute {
            appDb.bookDao.flowAll().mapLatest { books ->
                val keys = arrayListOf<String>()
                books.filterNot { it.isNotShelf }.forEach {
                    keys.add("${it.name}-${it.author}")
                    keys.add(it.name)
                    keys.add(it.bookUrl)
                }
                keys
            }.catch {
                AppLog.put("发现页获取书架数据失败\n${it.localizedMessage}", it)
            }.collect {
                bookshelf.clear()
                bookshelf.addAll(it)
                upBookshelfLiveData.postValue(true)
            }
        }
        viewModelScope.launch(IO) {
            appDb.exploreContainerDao.flowEnabled().catch {
                AppLog.put("发现页容器数据出错", it)
            }.collect { containers ->
                onContainersChanged(containers)
            }
        }
    }

    private suspend fun onContainersChanged(containers: List<ExploreContainer>) {
        val toLoad = arrayListOf<ExploreContainer>()
        val toLoadKinds = arrayListOf<ExploreContainer>()
        stateMutex.withLock {
            val old = HashMap(states)
            states.clear()
            containers.forEach { c ->
                val oldState = old[c.id]
                when {
                    oldState == null -> {
                        // 新出现的容器:缓存优先;无缓存或缓存过期则后台拉网络(旧数据保持显示)
                        val cached = ExploreContainerHelp.getCached(c.id)
                        val stale = cached == null || ExploreContainerHelp.isExpired(
                            cached.time, System.currentTimeMillis()
                        )
                        if (stale) toLoad.add(c)
                        states[c.id] = ExploreContainerState(
                            container = c,
                            books = cached?.books ?: emptyList(),
                            loading = stale,
                            page = cached?.page ?: 1,
                            updateTime = cached?.time ?: 0,
                        )
                        toLoadKinds.add(c)
                    }

                    oldState.container.sourceUrl != c.sourceUrl -> {
                        // 换书源:分类列表随之变化,旧书作废并重拉分类与书籍
                        toLoad.add(c)
                        toLoadKinds.add(c)
                        states[c.id] = ExploreContainerState(container = c, loading = true)
                    }

                    !sameTarget(oldState.container, c) -> {
                        // 仅切换/编辑分类(同书源):重新按容器选中集合算标签,旧书作废重新加载
                        toLoad.add(c)
                        toLoadKinds.add(c)
                        states[c.id] = ExploreContainerState(container = c, loading = true)
                    }

                    !sameKinds(oldState.container, c) -> {
                        // 勾选集合变了但指向不变(编辑换分类只增减勾选):重算标签,书籍无需重载
                        toLoadKinds.add(c)
                        states[c.id] = oldState.copy(container = c)
                    }

                    else -> states[c.id] = oldState.copy(container = c)
                }
            }
            statesData.postValue(states.values.toList())
        }
        toLoadKinds.forEach { loadKinds(it) }
        toLoad.forEach { loadContainer(it) }
    }

    /** groupName 空 = 全部;否则只刷该分组(下拉刷新只刷当前可见分组) */
    fun refreshAll(groupName: String = "") {
        viewModelScope.launch(IO) {
            val containers = stateMutex.withLock {
                states.values.map { it.container }
                    .filter { groupName.isEmpty() || it.hasGroup(groupName) }
            }
            containers.forEach { loadContainer(it) }
        }
    }

    fun refreshContainer(id: Long) {
        viewModelScope.launch(IO) {
            val container = stateMutex.withLock { states[id]?.container } ?: return@launch
            loadContainer(container)
        }
    }

    /** 换一批:请求下一页整批替换;仅此路径推进页码 */
    fun nextBatch(id: Long) {
        viewModelScope.launch(IO) {
            val state = stateMutex.withLock { states[id] } ?: return@launch
            loadContainer(state.container, state.page + 1)
        }
    }

    /** 加载容器分类标签:添加时勾选的分类固定展示;旧数据(未勾选)才动态取书源全部分类 */
    private fun loadKinds(container: ExploreContainer) {
        viewModelScope.launch(IO) {
            val source = appDb.bookSourceDao.getBookSource(container.sourceUrl)
            val sourceKinds = if (source == null) emptyList()
            else ExploreContainerHelp.validKinds(source.exploreKinds())
            val kinds = if (container.kindTitles.isNotBlank()) {
                ExploreContainerHelp.resolveContainerKinds(container, sourceKinds)
            } else {
                sourceKinds
            }
            stateMutex.withLock {
                val state = states[container.id] ?: return@withLock
                // 加载期间容器换了书源:本次分类作废,由新书源触发的 loadKinds 覆盖
                if (state.container.sourceUrl != container.sourceUrl) return@withLock
                states[container.id] = state.copy(kinds = kinds)
                statesData.postValue(states.values.toList())
            }
        }
    }

    /** 切换容器当前分类:更新指向并清旧缓存;flowEnabled 重发后按目标变化自动重拉 */
    fun switchKind(id: Long, kind: ExploreKind) {
        viewModelScope.launch(IO) {
            val url = kind.url ?: return@launch
            val state = stateMutex.withLock { states[id] } ?: return@launch
            val container = state.container
            if (container.kindTitle == kind.title && container.kindUrl == url) return@launch
            // 新建时固化了勾选集合:把当前展示的标签(补上新分类)一起持久化;
            // 旧数据(kindTitles 空)不冻结动态分类列表,只更新当前指向
            val updated = if (container.kindTitles.isBlank()) {
                container.copy(kindTitle = kind.title, kindUrl = url)
            } else {
                val titles = linkedSetOf<String>()
                val urls = linkedSetOf<String>()
                state.kinds.forEach { k ->
                    k.url?.let {
                        titles.add(k.title)
                        urls.add(it)
                    }
                }
                titles.add(kind.title)
                urls.add(url)
                container.copy(
                    kindTitle = kind.title,
                    kindUrl = url,
                    kindTitles = titles.joinToString(","),
                    kindUrls = urls.joinToString(","),
                )
            }
            appDb.exploreContainerDao.update(updated)
            ExploreContainerHelp.removeCache(id)
        }
    }

    /** 缓存超过有效期的容器静默重拉(回到前台时调用);in-flight 的跳过 */
    fun refreshStale() {
        viewModelScope.launch(IO) {
            val now = System.currentTimeMillis()
            val stale = stateMutex.withLock {
                states.values.filter {
                    !it.loading && ExploreContainerHelp.isExpired(it.updateTime, now)
                }.map { it.container }
            }
            stale.forEach { loadContainer(it) }
        }
    }

    /** 书源/分类指向是否一致(样式等展示属性变化不影响已加载书籍) */
    private fun sameTarget(a: ExploreContainer, b: ExploreContainer): Boolean {
        return a.sourceUrl == b.sourceUrl && a.kindUrl == b.kindUrl && a.kindTitle == b.kindTitle
    }

    /** 勾选分类集合是否一致(编辑换分类增减勾选、指向不变时也能感知) */
    private fun sameKinds(a: ExploreContainer, b: ExploreContainer): Boolean {
        return a.kindTitles == b.kindTitles && a.kindUrls == b.kindUrls
    }

    private fun loadContainer(container: ExploreContainer, page: Int = 1) {
        if (!loadingIds.add(container.id)) return
        viewModelScope.launch(IO) {
            upStateIfSameTarget(container) { it.copy(loading = true, error = null) }
            kotlin.runCatching {
                val source = appDb.bookSourceDao.getBookSource(container.sourceUrl)
                    ?: throw NoStackTraceException(
                        context.getString(R.string.explore_source_not_found)
                    )
                val kinds = source.exploreKinds()
                val url = ExploreContainerHelp.resolveKindUrl(
                    kinds, container.kindTitle, container.kindUrl
                )
                var loadedPage = page
                var books = loadLimiter.withPermit {
                    withTimeout(30_000L) {
                        WebBook.exploreBookAwait(source, url, loadedPage)
                    }
                }
                if (books.isEmpty() && loadedPage > 1) {
                    // 换一批翻到尽头:回绕一次回第 1 页
                    loadedPage = 1
                    books = loadLimiter.withPermit {
                        withTimeout(30_000L) {
                            WebBook.exploreBookAwait(source, url, loadedPage)
                        }
                    }
                }
                loadedPage to books
            }.onSuccess { (loadedPage, books) ->
                if (books.isEmpty() && page > 1) {
                    // 回绕后仍无内容:保留原数据,轻提示
                    upStateIfSameTarget(container) { it.copy(loading = false) }
                    context.toastOnUi(R.string.explore_no_more)
                } else {
                    val time = System.currentTimeMillis()
                    val accepted = upStateIfSameTarget(container) {
                        it.copy(
                            books = books, loading = false, error = null,
                            page = loadedPage, updateTime = time
                        )
                    }
                    if (accepted) {
                        ExploreContainerHelp.putCached(container.id, books, loadedPage, time)
                    }
                }
            }.onFailure { e ->
                AppLog.put("发现容器[${container.getDisplayTitle()}]加载失败", e)
                upStateIfSameTarget(container) {
                    it.copy(loading = false, error = e.localizedMessage ?: "加载失败")
                }
            }
            loadingIds.remove(container.id)
            // 加载期间容器被编辑改了指向:本次结果已丢弃,换当前指向重新加载
            val current = stateMutex.withLock { states[container.id]?.container }
            if (current != null && !sameTarget(current, container)) {
                loadContainer(current)
            }
        }
    }

    /**
     * 仅当容器当前指向与发起加载时一致才更新状态(也排除已删除的容器),
     * 避免编辑竞态下旧指向的加载结果覆盖新指向
     */
    private suspend fun upStateIfSameTarget(
        loaded: ExploreContainer,
        transform: (ExploreContainerState) -> ExploreContainerState
    ): Boolean {
        stateMutex.withLock {
            val state = states[loaded.id] ?: return false
            if (!sameTarget(state.container, loaded)) return false
            states[loaded.id] = transform(state)
            statesData.postValue(states.values.toList())
        }
        return true
    }

    fun isInBookShelf(book: SearchBook): Boolean {
        val key = if (book.author.isNotBlank()) "${book.name}-${book.author}" else book.name
        return bookshelf.contains(key) || bookshelf.contains(book.bookUrl)
    }

    fun deleteContainer(container: ExploreContainer) {
        execute {
            appDb.exploreContainerDao.delete(container)
            ExploreContainerHelp.removeCache(container.id)
        }
    }
}
