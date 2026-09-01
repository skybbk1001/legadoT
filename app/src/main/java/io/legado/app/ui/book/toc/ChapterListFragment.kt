package io.legado.app.ui.book.toc

import android.annotation.SuppressLint
import android.app.Activity.RESULT_OK
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.FragmentChapterListBinding
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.appBarBackgroundIsLight
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.model.AudioCache
import io.legado.app.model.AudioCacheStateChanged
import io.legado.app.ui.widget.recycler.UpLinearLayoutManager
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.observeEvent
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChapterListFragment : VMBaseFragment<TocViewModel>(R.layout.fragment_chapter_list),
    ChapterListAdapter.Callback,
    TocViewModel.ChapterListCallBack {
    override val viewModel by activityViewModels<TocViewModel>()
    private val binding by viewBinding(FragmentChapterListBinding::bind)
    private var mLayoutManager: UpLinearLayoutManager? = null
    private val adapter by lazy { ChapterListAdapter(requireContext(), this) }
    private val tocListState = TocListState()
    private var durChapterIndex = 0
    private var audioCacheStateReady = false
    private val pendingAudioCacheChanges = linkedMapOf<Int, Boolean>()
    private var initBookJob: Job? = null
    private var upListJob: Job? = null
    private var currentSearchKey: String? = null
    private var pendingScrollItemKey: String? = null

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) = binding.run {
        viewModel.chapterListCallBack = this@ChapterListFragment
        // 沉浸式时底栏透明，露出页面背景，与顶栏/底部导航一致；前景色按可见背景判断明暗
        val immersive = AppConfig.isTransparentActionBar
        val btc = requireContext().getPrimaryTextColor(
            appBarBackgroundIsLight(
                transparentActionBar = immersive,
                barBackgroundColor = bottomBackground,
                contentBackgroundColor = requireContext().backgroundColor
            )
        )
        llChapterBaseInfo.setBackgroundColor(if (immersive) Color.TRANSPARENT else bottomBackground)
        // 透明时去掉 elevation，避免栏看不见却仍在内容上投出阴影线（与 TitleBar 一致）
        llChapterBaseInfo.elevation = if (immersive) 0f else 5f.dpToPx()
        tvCurrentChapterInfo.setTextColor(btc)
        ivChapterTop.setColorFilter(btc, PorterDuff.Mode.SRC_IN)
        ivChapterBottom.setColorFilter(btc, PorterDuff.Mode.SRC_IN)
        initRecyclerView()
        initView()
        viewModel.bookData.observe(this@ChapterListFragment) {
            initBook(it)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // FragmentStateAdapter 会真正销毁离屏页,置空回避免野指针;恒等判断防止清掉新实例的注册
        if (viewModel.chapterListCallBack === this) {
            viewModel.chapterListCallBack = null
        }
    }

    private fun initRecyclerView() {
        mLayoutManager = UpLinearLayoutManager(requireContext())
        binding.recyclerView.layoutManager = mLayoutManager
        binding.recyclerView.addItemDecoration(VerticalDivider(requireContext()))
        binding.recyclerView.adapter = adapter
    }

    private fun initView() = binding.run {
        ivChapterTop.setOnClickListener {
            mLayoutManager?.scrollToPositionWithOffset(0, 0)
        }
        ivChapterBottom.setOnClickListener {
            if (adapter.itemCount > 0) {
                mLayoutManager?.scrollToPositionWithOffset(adapter.itemCount - 1, 0)
            }
        }
        tvCurrentChapterInfo.setOnClickListener {
            scrollToChapterIndex(durChapterIndex, expandVolume = true)
        }
        binding.llChapterBaseInfo.applyNavigationBarPadding()
    }

    @SuppressLint("SetTextI18n")
    private fun initBook(book: Book) {
        initBookJob?.cancel()
        upListJob?.cancel()
        initBookJob = lifecycleScope.launch {
            durChapterIndex = book.durChapterIndex
            binding.tvCurrentChapterInfo.text =
                "${book.durChapterTitle}(${book.durChapterIndex + 1}/${book.simulatedTotalChapterNum()})"

            adapter.cacheFileNames.clear()
            adapter.cachedChapterIndexes.clear()
            audioCacheStateReady = !book.isAudio
            pendingAudioCacheChanges.clear()

            val chapters = queryChapterList(null)
            tocListState.setFullChapters(
                chapters = chapters,
                durChapterIndex = durChapterIndex,
                resetCollapse = true,
                reversed = book.getReverseToc()
            )
            // initBook 与搜索键入并发时,若搜索已接管列表则不回退到全量列表、不清搜索词
            // (缓存状态刷新在下方照常执行,不依赖此处;搜索路径晚到的 setItems 会正常落地)
            if (currentSearchKey.isNullOrBlank()) {
                currentSearchKey = null
                adapter.setItems(tocListState.showNormal(durChapterIndex))
            }

            val (cacheFileNames, cachedChapterIndexes) = queryCacheState(book)
            adapter.cacheFileNames.addAll(cacheFileNames)
            adapter.cachedChapterIndexes.addAll(cachedChapterIndexes)
            pendingAudioCacheChanges.forEach { (chapterIndex, cached) ->
                if (cached) {
                    adapter.cachedChapterIndexes.add(chapterIndex)
                } else {
                    adapter.cachedChapterIndexes.remove(chapterIndex)
                }
            }
            pendingAudioCacheChanges.clear()
            audioCacheStateReady = true
            adapter.notifyItemRangeChanged(0, adapter.itemCount, true)
        }
    }

    private suspend fun queryCacheState(book: Book): Pair<Set<String>, Set<Int>> {
        return withContext(IO) {
            if (book.isAudio) {
                Pair(emptySet(), AudioCache.listCachedChapterIndexes(book.bookUrl))
            } else {
                Pair(BookHelp.getChapterFiles(book).toSet(), emptySet())
            }
        }
    }

    override fun observeLiveBus() {
        observeEvent<Pair<Book, BookChapter>>(EventBus.SAVE_CONTENT) { (book, chapter) ->
            viewModel.bookData.value?.bookUrl?.let { bookUrl ->
                if (viewModel.bookData.value?.isAudio == true) {
                    return@observeEvent
                }
                if (book.bookUrl == bookUrl) {
                    adapter.cacheFileNames.add(chapter.getFileName())
                    notifyVisibleChapterChanged(chapter.index)
                }
            }
        }
        observeEvent<AudioCacheStateChanged>(EventBus.AUDIO_CACHE_CHANGED) { event ->
            val currentBook = viewModel.bookData.value ?: return@observeEvent
            if (!currentBook.isAudio || currentBook.bookUrl != event.bookUrl) return@observeEvent
            if (!audioCacheStateReady) {
                pendingAudioCacheChanges[event.chapterIndex] = event.cached
                return@observeEvent
            }
            updateAudioChapterCacheState(event.chapterIndex, event.cached)
        }
    }

    override fun upChapterList(searchKey: String?, resetCollapse: Boolean) {
        // 搜索键入是高频触发,取消上一个未完成的 DB 查询,避免乱序返回时旧结果覆盖新结果
        upListJob?.cancel()
        upListJob = lifecycleScope.launch {
            currentSearchKey = searchKey
            val reversed = book?.getReverseToc() == true
            if (searchKey.isNullOrBlank()) {
                val chapters = queryChapterList(null)
                tocListState.setFullChapters(
                    chapters = chapters,
                    durChapterIndex = durChapterIndex,
                    resetCollapse = resetCollapse,
                    reversed = reversed
                )
                adapter.setItems(tocListState.showNormal(durChapterIndex))
            } else {
                if (resetCollapse || !tocListState.hasFullChapters()) {
                    tocListState.setFullChapters(
                        chapters = queryChapterList(null),
                        durChapterIndex = durChapterIndex,
                        resetCollapse = resetCollapse,
                        reversed = reversed
                    )
                }
                adapter.setItems(tocListState.showSearch(queryChapterList(searchKey), durChapterIndex))
            }
        }
    }

    private suspend fun queryChapterList(searchKey: String?): List<BookChapter> {
        val end = (book?.simulatedTotalChapterNum() ?: Int.MAX_VALUE) - 1
        return viewModel.queryChapterList(searchKey, end)
    }

    private fun updateAudioChapterCacheState(chapterIndex: Int, cached: Boolean) {
        if (cached) {
            adapter.cachedChapterIndexes.add(chapterIndex)
        } else {
            adapter.cachedChapterIndexes.remove(chapterIndex)
        }
        notifyVisibleChapterChanged(chapterIndex)
    }

    private fun notifyVisibleChapterChanged(chapterIndex: Int) {
        val position = adapter.findVisiblePositionByChapterIndex(chapterIndex)
        if (position >= 0) {
            // position 是 items 下标,经 updateItems 统一补 header 偏移
            // (本页无 header 偏移为 0;BookInfoActivity 复用适配器时带 2 个 header)
            adapter.updateItems(position, position, true)
        }
    }

    private fun scrollToChapterIndex(chapterIndex: Int, expandVolume: Boolean) {
        if (expandVolume && currentSearchKey.isNullOrBlank()) {
            val changed = tocListState.expandVolumeContainingChapter(chapterIndex)
            if (changed) {
                adapter.setItems(tocListState.showNormal(durChapterIndex))
            }
        }
        binding.recyclerView.post {
            val position = tocListState.findFallbackVisiblePositionForChapterIndex(chapterIndex)
            if (position >= 0) {
                mLayoutManager?.scrollToPositionWithOffset(position, 0)
                adapter.upDisplayTitles(position)
            }
        }
    }

    override fun onListChanged() {
        if (pendingScrollItemKey != null) {
            // 分卷切换中，跳过自动滚动，由 onItemsUpdated 恢复位置
            return
        }
        lifecycleScope.launch {
            val scrollPos = if (currentSearchKey.isNullOrBlank()) {
                tocListState.findFallbackVisiblePositionForChapterIndex(durChapterIndex).coerceAtLeast(0)
            } else {
                0
            }
            mLayoutManager?.scrollToPositionWithOffset(scrollPos, 0)
            adapter.upDisplayTitles(scrollPos)
        }
    }

    override fun onVolumeToggled(volumeIndex: Int) {
        if (!currentSearchKey.isNullOrBlank()) return
        pendingScrollItemKey = mLayoutManager
            ?.findFirstVisibleItemPosition()
            ?.let { adapter.getItem(it)?.key }
        if (tocListState.toggleVolume(volumeIndex)) {
            adapter.setItems(tocListState.showNormal(durChapterIndex))
        } else {
            // 状态未变不会有 onItemsUpdated 兜底,回滚锚点防止泄漏到后续列表更新
            pendingScrollItemKey = null
        }
    }

    override fun onItemsUpdated() {
        val anchorKey = pendingScrollItemKey
        pendingScrollItemKey = null
        if (anchorKey == null) return
        binding.recyclerView.post {
            val newPos = adapter.findVisiblePositionByItemKey(anchorKey)
            if (newPos >= 0) {
                mLayoutManager?.scrollToPositionWithOffset(newPos, 0)
                adapter.upDisplayTitles(newPos)
            } else {
                adapter.upDisplayTitles(mLayoutManager?.findFirstVisibleItemPosition() ?: 0)
            }
        }
    }

    override fun clearDisplayTitle() {
        adapter.clearDisplayTitle()
        adapter.upDisplayTitles(mLayoutManager?.findFirstVisibleItemPosition() ?: 0)
    }

    override fun upAdapter() {
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
    }

    override val scope: CoroutineScope
        get() = lifecycleScope

    override val book: Book?
        get() = viewModel.bookData.value

    override val isLocalBook: Boolean
        get() = viewModel.bookData.value?.isLocal == true

    override val isAudioBook: Boolean
        get() = viewModel.bookData.value?.isAudio == true

    override val isAudioCacheStateReady: Boolean
        get() = audioCacheStateReady

    override fun durChapterIndex(): Int {
        return durChapterIndex
    }

    override fun openChapter(bookChapter: BookChapter) {
        activity?.run {
            setResult(
                RESULT_OK, Intent()
                    .putExtra("index", bookChapter.index)
                    .putExtra("chapterChanged", bookChapter.index != durChapterIndex)
            )
            finish()
        }
    }

}
