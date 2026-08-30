package io.legado.app.ui.book.info

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.transition.platform.MaterialContainerTransform
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.databinding.ActivityBookInfoBinding
import io.legado.app.databinding.DialogBookAutoTaskBinding
import io.legado.app.databinding.ItemBookInfoHeaderBinding
import io.legado.app.databinding.ItemBookInfoTocHeaderBinding
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.addType
import io.legado.app.help.book.getRemoteUrl
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.book.isWebFile
import io.legado.app.help.book.readProgress
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.motion.MotionTokens
import io.legado.app.help.motion.PressSpringEffect
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.AppColorScheme
import io.legado.app.model.AutoTask
import io.legado.app.model.AutoTaskRule
import io.legado.app.model.SourceCallBack
import io.legado.app.model.remote.RemoteBookWebDav
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.audio.AudioPlayActivity
import io.legado.app.ui.book.changecover.ChangeCoverDialog
import io.legado.app.ui.book.changesource.ChangeBookSourceDialog
import io.legado.app.ui.book.group.GroupSelectDialog
import io.legado.app.ui.book.info.edit.BookInfoEditActivity
import io.legado.app.ui.book.manga.ReadMangaActivity
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.ReadBookActivity.Companion.RESULT_DELETED
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.toc.ChapterListAdapter
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.book.toc.TocListItem
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.ui.widget.dialog.VariableDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.applyAmbientBackground
import io.legado.app.utils.GSON
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.dpToPx
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.gone
import io.legado.app.utils.longToastOnUi
import io.legado.app.utils.observeEvent
import io.legado.app.utils.openFileUri
import io.legado.app.utils.sendToClip
import io.legado.app.utils.shareWithQr
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil

private const val INTRO_COLLAPSED_LINES = 4

class BookInfoActivity :
    VMBaseActivity<ActivityBookInfoBinding, BookInfoViewModel>(),
    GroupSelectDialog.CallBack,
    ChangeBookSourceDialog.CallBack,
    ChangeCoverDialog.CallBack,
    VariableDialog.Callback {

    private val tocActivityResult = registerForActivityResult(TocActivityResult()) {
        it?.let {
            readFromChapter(it.index, it.chapterPos, it.chapterChanged)
        } ?: let {
            if (!viewModel.inBookshelf) {
                viewModel.delBook()
            }
        }
    }
    private val localBookTreeSelect = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { treeUri ->
            AppConfig.defaultBookTreeUri = treeUri.toString()
        }
    }
    private val readBookResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.upBook(intent)
        when (it.resultCode) {
            RESULT_OK -> {
                viewModel.inBookshelf = true
                upTvBookshelf()
            }

            RESULT_DELETED -> {
                setResult(RESULT_OK)
                finish()
            }
        }
    }
    private val infoEditResult = registerForActivityResult(
        StartActivityContract(BookInfoEditActivity::class.java)
    ) {
        if (it.resultCode == RESULT_OK) {
            viewModel.upEditBook()
        }
    }
    private val editSourceResult = registerForActivityResult(
        StartActivityContract(BookSourceEditActivity::class.java)
    ) {
        if (it.resultCode == RESULT_CANCELED) {
            return@registerForActivityResult
        }
        book?.let { book ->
            viewModel.bookSource = appDb.bookSourceDao.getBookSource(book.origin)
            viewModel.refreshBook(book)
        }
    }
    private var chapterChanged = false
    private val waitDialog by lazy { WaitDialog(this) }
    private var editMenuItem: MenuItem? = null
    private var menuCustomBtn: MenuItem? = null
    private val book get() = viewModel.getBook(false)

    // 氛围背景竞写守卫:换封面可能连续触发(load 回调异步),旧协程取消,只有最新一次落地
    private var ambientJob: Job? = null

    // N3a toc-listify(后扩展至 land):内容区 RecyclerView 承载完整目录,复用目录页既有的
    // ChapterListAdapter,不新建章节行布局/适配器。
    // headerBinding/tocHeaderBinding 可能晚于 viewModel 数据就绪才 inflate(addHeaderView 异步),
    // 故 bindInfoHeader/upTocHeader 内都做"回填当前已有数据"处理,showBook/upChapterList 侧也留 null-safe 更新。
    private var headerBinding: ItemBookInfoHeaderBinding? = null
    private var introExpanded = false
    private var tocHeaderBinding: ItemBookInfoTocHeaderBinding? = null
    private var tocReversed = true   // 默认倒序(最新章在前)
    private var fullChapters: List<BookChapter> = emptyList()
    private val chapterAdapter by lazy { ChapterListAdapter(this, chapterCallback) }

    private val chapterCallback = object : ChapterListAdapter.Callback {
        override val scope get() = lifecycleScope
        override val book get() = viewModel.getBook(false)
        override val isLocalBook get() = viewModel.getBook(false)?.isLocal == true
        override val isAudioBook get() = viewModel.getBook(false)?.isAudio == true
        override val isAudioCacheStateReady get() = true
        override fun openChapter(bookChapter: BookChapter) {
            readFromChapter(bookChapter.index)
        }

        override fun durChapterIndex() = viewModel.getBook(false)?.durChapterIndex ?: 0
        override fun onListChanged() {}
        override fun onVolumeToggled(volumeIndex: Int) {}
        override fun onItemsUpdated() {}
    }

    override val binding by viewBinding(ActivityBookInfoBinding::inflate)
    override val viewModel by viewModels<BookInfoViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        if (MotionTokens.enabled) {
            window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
            val transform = MaterialContainerTransform(this, true).apply {
                addTarget(R.id.iv_cover)
                duration = 320L
                fadeMode = MaterialContainerTransform.FADE_MODE_THROUGH
            }
            window.sharedElementEnterTransition = transform
            window.sharedElementReturnTransition = MaterialContainerTransform(this, false).apply {
                addTarget(R.id.iv_cover)
                duration = 280L
            }
        }
        super.onCreate(savedInstanceState)
    }

    @SuppressLint("PrivateResource")
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        // hero 卡长按跳转会显式传 coverTransitionName(唯一名,绕开与同书列表 item 的撞名);
        // 其余全部既有入口(书架列表项/搜索结果等)不传此 extra,走默认拼名,行为不变
        binding.ivCover.transitionName = intent.getStringExtra("coverTransitionName")
            ?: ("book_cover_" + intent.getStringExtra("name").orEmpty() +
                intent.getStringExtra("author").orEmpty())
        binding.refreshLayout.setColorSchemeColors(accentColor)
        binding.refreshProgressBar.fontColor = accentColor
        binding.flAction.applyNavigationBarPadding()
        setSupportActionBar(binding.toolBar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        // CollapsingToolbarLayout 构造器无条件挂 inset 监听且 onWindowInsetChanged 恒
        // consumeSystemWindowInsets()(1.13.0 字节码实证)——其子级(tool_bar/ll_header)的
        // applyStatusBarPadding 永远收到被吞掉的 insets。让位必须在消费点之前:root 监听取值直接下发。
        binding.root.setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
            val statusBarTop = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            binding.toolBar.updatePadding(top = statusBarTop)
            binding.llHeader?.updatePadding(top = statusBarTop)
            // land 右栏(recyclerView)顶到屏幕上缘,内容让位状态栏;portrait 列表在 appBar 之下不需要。
            // clipToPadding=false,滚动中内容可进入垫区(edge-to-edge 常规行为)
            if (binding.appBar == null) {
                binding.recyclerView.updatePadding(top = statusBarTop)
            }
            windowInsets
        }
        binding.appBar?.addOnOffsetChangedListener { appBar, verticalOffset ->
            val range = appBar.totalScrollRange.takeIf { it > 0 } ?: return@addOnOffsetChangedListener
            val ratio = -verticalOffset.toFloat() / range
            binding.llHeader?.alpha = (1f - ratio * 1.4f).coerceIn(0f, 1f)
            binding.tvToolbarTitle.alpha = ((ratio - 0.6f) / 0.4f).coerceIn(0f, 1f)
            binding.refreshLayout.isEnabled = verticalOffset == 0
        }
        // 内容区双向同形态:RecyclerView(信息头+目录头 addHeaderView+完整章节列表)。
        binding.recyclerView.let { rv ->
            // 连续内容面板:信息头/目录头/章节行共坐一张 surfaceContainerLow 面板,色值经
            // scheme 取,换肤 recreate 随视图重建。portrait 顶部两角圆(面板从氛围头图上
            // 升起,随 appBar 折叠上移);形状背景不能走 skin_background(SkinInflaterFactory
            // 平涂 setBackgroundColor 会抹掉圆角)。land 双栏纵切无"升起"方向,平顶纯色。
            // 形状与裁剪施加在 content_panel(SwipeRefresh+列表+顶缘加载条的共同容器),
            // 加载条同受圆角轮廓裁剪,两端随弧线收束。
            if (binding.appBar != null) {
                binding.contentPanel.background = MaterialShapeDrawable(
                    ShapeAppearanceModel.builder()
                        .setTopLeftCornerSize(resources.getDimension(R.dimen.radius_xl))
                        .setTopRightCornerSize(resources.getDimension(R.dimen.radius_xl))
                        .build()
                ).apply {
                    fillColor = ColorStateList.valueOf(AppColorScheme.current.surfaceContainerLow)
                }
                binding.contentPanel.clipToOutline = true
            } else {
                binding.contentPanel.setBackgroundColor(AppColorScheme.current.surfaceContainerLow)
            }
            rv.layoutManager = LinearLayoutManager(this)
            rv.adapter = chapterAdapter
            chapterAdapter.addHeaderView { parent ->
                ItemBookInfoHeaderBinding.inflate(layoutInflater, parent, false).also {
                    headerBinding = it
                    bindInfoHeader(it)
                }
            }
            chapterAdapter.addHeaderView { parent ->
                ItemBookInfoTocHeaderBinding.inflate(layoutInflater, parent, false).also {
                    tocHeaderBinding = it
                    it.ivTocSort.rotationX = if (tocReversed) 0f else 180f
                    it.ivTocSort.setOnClickListener { toggleTocOrder() }
                    // 点整行 / 搜索图标 → 完整目录页(搜索/分卷),接管原信息卡 ll_toc 的入口
                    it.root.setOnClickListener { openFullToc() }
                    it.ivTocOpen.setOnClickListener { openFullToc() }
                    upTocHeader()
                }
            }
        }
        viewModel.bookData.observe(this) { showBook(it) }
        viewModel.chapterListData.observe(this) { upLoading(false, it) }
        viewModel.loadingData.observe(this) { isLoading ->
            if (isLoading) {
                upLoading(true)
            } else {
                binding.refreshProgressBar.isAutoLoading = false
                upTocHeader()
            }
        }
        viewModel.waitDialogData.observe(this) { upWaitDialogStatus(it) }
        viewModel.initData(intent)
        initViewEvent()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_info, menu)
        editMenuItem = menu.findItem(R.id.menu_edit)
        menuCustomBtn = menu.findItem(R.id.menu_custom_btn).also {
            it.isVisible = viewModel.bookSource?.customButton == true
        }
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_can_update)?.isChecked =
            viewModel.bookData.value?.canUpdate ?: true
        menu.findItem(R.id.menu_split_long_chapter)?.isChecked =
            viewModel.bookData.value?.getSplitLongChapter() ?: true
        menu.findItem(R.id.menu_login)?.isVisible =
            !viewModel.bookSource?.loginUrl.isNullOrBlank()
        menu.findItem(R.id.menu_set_source_variable)?.isVisible =
            viewModel.bookSource != null
        menu.findItem(R.id.menu_set_book_variable)?.isVisible =
            viewModel.bookSource != null
        menu.findItem(R.id.menu_can_update)?.isVisible =
            viewModel.bookSource != null
        menu.findItem(R.id.menu_split_long_chapter)?.isVisible =
            viewModel.bookData.value?.isLocalTxt ?: false
        menu.findItem(R.id.menu_upload)?.isVisible =
            viewModel.bookData.value?.isLocal ?: false
        menu.findItem(R.id.menu_auto_task_book_update)?.isVisible =
            viewModel.inBookshelf
        menu.findItem(R.id.menu_delete_alert)?.isChecked =
            LocalConfig.bookInfoDeleteAlert
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_edit -> {
                viewModel.getBook()?.let {
                    infoEditResult.launch {
                        putExtra("bookUrl", it.bookUrl)
                    }
                }
            }

            R.id.menu_custom_btn -> {
                viewModel.getBook()?.let { book ->
                    SourceCallBack.callBackBtn(
                        this, SourceCallBack.CLICK_CUSTOM_BUTTON,
                        viewModel.bookSource, book, null
                    )
                }
            }

            R.id.menu_share_it -> {
                viewModel.getBook()?.let { book ->
                    val bookJson = GSON.toJson(book)
                    val shareStr = "${book.bookUrl}#$bookJson"
                    SourceCallBack.callBackBtn(
                        this, SourceCallBack.CLICK_SHARE_BOOK,
                        viewModel.bookSource, book, null, shareStr
                    ) {
                        shareWithQr(shareStr, book.name)
                    }
                }
            }

            R.id.menu_refresh -> {
                refreshBook()
            }

            R.id.menu_auto_task_book_update -> {
                showAutoTaskDialog()
            }

            R.id.menu_login -> viewModel.bookSource?.let {
                startActivity<SourceLoginActivity> {
                    putExtra("type", "bookSource")
                    putExtra("key", it.bookSourceUrl)
                    putExtra("bookUrl", viewModel.getBook(false)?.bookUrl)
                }
            }

            R.id.menu_top -> viewModel.topBook()
            R.id.menu_set_source_variable -> setSourceVariable()
            R.id.menu_set_book_variable -> setBookVariable()
            R.id.menu_copy_book_url -> viewModel.getBook()?.let { book ->
                SourceCallBack.callBackBtn(
                    this, SourceCallBack.CLICK_COPY_BOOK_URL,
                    viewModel.bookSource, book, null, book.bookUrl
                ) {
                    sendToClip(book.bookUrl)
                }
            }

            R.id.menu_copy_toc_url -> viewModel.getBook()?.let { book ->
                val tocUrl = book.tocUrl
                SourceCallBack.callBackBtn(
                    this, SourceCallBack.CLICK_COPY_TOC_URL,
                    viewModel.bookSource, book, null, tocUrl
                ) {
                    sendToClip(tocUrl)
                }
            }

            R.id.menu_can_update -> {
                viewModel.getBook()?.let {
                    it.canUpdate = !it.canUpdate
                    if (viewModel.inBookshelf) {
                        if (!it.canUpdate) {
                            it.removeType(BookType.updateError)
                        }
                        viewModel.saveBook(it)
                    }
                }
            }

            R.id.menu_clear_cache -> {
                viewModel.getBook()?.let { book ->
                    SourceCallBack.callBackBtn(
                        this, SourceCallBack.CLICK_CLEAR_CACHE,
                        viewModel.bookSource, book, null
                    ) {
                        viewModel.clearCache()
                    }
                } ?: viewModel.clearCache()
            }
            R.id.menu_log -> showDialogFragment<AppLogDialog>()
            R.id.menu_split_long_chapter -> {
                upLoading(true)
                viewModel.getBook()?.let {
                    it.setSplitLongChapter(!item.isChecked)
                    viewModel.loadBookInfo(it, false)
                }
                item.isChecked = !item.isChecked
                if (!item.isChecked) longToastOnUi(R.string.need_more_time_load_content)
            }

            R.id.menu_delete_alert -> LocalConfig.bookInfoDeleteAlert = !item.isChecked
            R.id.menu_upload -> {
                viewModel.getBook()?.let { book ->
                    book.getRemoteUrl()?.let {
                        alert(R.string.draw, R.string.sure_upload) {
                            okButton {
                                upLoadBook(book)
                            }
                            cancelButton()
                        }
                    } ?: upLoadBook(book)
                }
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun observeLiveBus() {
        viewModel.actionLive.observe(this) {
            when (it) {
                "selectBooksDir" -> localBookTreeSelect.launch {
                    title = getString(R.string.select_book_folder)
                }
            }
        }
        observeEvent<Boolean>(EventBus.REFRESH_BOOK_INFO) { //书源js函数触发刷新
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                refreshBook()
            }
        }
        observeEvent<Boolean>(EventBus.REFRESH_BOOK_TOC) { //书源js函数触发刷新
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                refreshToc()
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            val tvIntro = headerBinding?.tvIntro
            currentFocus?.let {
                if (tvIntro != null && it === tvIntro && tvIntro.hasSelection()) {
                    it.clearFocus()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun refreshBook() {
        upLoading(true)
        viewModel.getBook()?.let {
            viewModel.refreshBook(it)
        }
    }

    private fun refreshToc() {
        upLoading(true)
        viewModel.getBook()?.let {
            viewModel.loadChapter(it)
        }
    }

    private fun upLoadBook(
        book: Book,
        bookWebDav: RemoteBookWebDav? = AppWebDav.defaultBookWebDav,
    ) {
        lifecycleScope.launch {
            waitDialog.setText("上传中.....")
            waitDialog.show()
            try {
                bookWebDav
                    ?.upload(book)
                    ?: throw NoStackTraceException("未配置webDav")
                //更新书籍最后更新时间,使之比远程书籍的时间新
                book.lastCheckTime = System.currentTimeMillis()
                viewModel.saveBook(book)
            } catch (e: Exception) {
                toastOnUi(e.localizedMessage)
            } finally {
                waitDialog.dismiss()
            }
        }
    }

    private fun showBook(book: Book) = binding.run {
        showCover(book)
        tvName.text = book.name
        tvToolbarTitle.text = book.name
        tvAuthor.text = getString(R.string.author_show, book.getRealAuthor())
        // 信息区字段都在 header 内(RecyclerView addHeaderView 异步 inflate,可能晚于本次数据到达,
        // 故 headerBinding 用 null-safe 更新,header 自己 inflate 时也会在 bindInfoHeader 内主动拉一次)。
        // tvOrigin/tvLasted 由 N5 C5a 抽出的 manageRows(view_book_info_manage_rows include)承载。
        headerBinding?.let { h ->
            h.manageRows.tvOrigin.text = getString(R.string.origin_show, book.originName)
            h.manageRows.tvLasted.text = getString(R.string.lasted_show, book.latestChapterTitle)
            upIntro(h.tvIntro, h.tvIntroExpand, book, h.vIntroDivider)
            upReadStatus(h, book)
        }
        menuCustomBtn?.isVisible = viewModel.bookSource?.customButton == true
        upTvBookshelf()
        upKinds(book)
        upGroup(book.group)
    }

    /**
     * 简介可见性:书源常不返回简介 → getDisplayIntro() 为空时 tv_intro(连同其上段间线)整段 gone,
     * 不留 minHeight 占位空白。divider 只随数据变化(空/非空),展开收起路径不传。
     */
    private fun upIntro(
        tvIntro: TextView,
        tvIntroExpand: TextView,
        book: Book,
        divider: View? = null,
    ) {
        val intro = book.getDisplayIntro()
        val hasIntro = !intro.isNullOrBlank()
        tvIntro.text = intro
        tvIntro.isVisible = hasIntro
        divider?.isVisible = hasIntro
        if (!hasIntro) {
            tvIntroExpand.isVisible = false
            return
        }

        tvIntro.maxLines = if (introExpanded) Int.MAX_VALUE else INTRO_COLLAPSED_LINES
        tvIntro.ellipsize = if (introExpanded) null else TextUtils.TruncateAt.END
        // 布局已就绪且无待定重排时立即评估一次;其余时机交给持久布局监听(bindIntroToggle 安装),
        // 布局未定时不评估,避免在陈旧 layout 上误判
        if (tvIntro.isLaidOut && !tvIntro.isLayoutRequested) {
            upIntroExpandVisibility(tvIntro, tvIntroExpand)
        }
    }

    /**
     * 展开钮可见性 = 当前文本与布局的纯函数,每次 tv_intro 布局后由持久监听重算。
     * 全程幂等(可见性与文案都先比对再写),布局回调内重入不会引发再布局循环。
     */
    private fun upIntroExpandVisibility(tvIntro: TextView, tvIntroExpand: TextView) {
        val canToggle = if (!tvIntro.isVisible) {
            false
        } else if (introExpanded) {
            tvIntro.lineCount > INTRO_COLLAPSED_LINES
        } else {
            val textLayout = tvIntro.layout
            if (textLayout == null || textLayout.lineCount == 0) {
                false
            } else if (textLayout.lineCount > INTRO_COLLAPSED_LINES) {
                true
            } else {
                val lastLine = textLayout.lineCount - 1
                textLayout.getEllipsisCount(lastLine) > 0 ||
                    textLayout.getLineEnd(lastLine) < tvIntro.text.length
            }
        }
        if (tvIntroExpand.isVisible != canToggle) {
            tvIntroExpand.isVisible = canToggle
        }
        if (canToggle) {
            val label = getString(
                if (introExpanded) R.string.book_intro_collapse else R.string.book_intro_expand
            )
            if (tvIntroExpand.text.toString() != label) {
                tvIntroExpand.text = label
            }
        }
        // 折叠溢出态铺渐隐底(bindIntroToggle 存于 tag);展开态清底,并给 tv_intro 垫
        // 一行行高的底部内边距使"收起"落于文末之下不遮字。写入均先比对,布局后重入不成环。
        val fade = if (canToggle && !introExpanded) tvIntroExpand.tag as? Drawable else null
        if (tvIntroExpand.background !== fade) {
            tvIntroExpand.background = fade
        }
        // 按钮高度运行时贴合单行行高(XML 的 book_intro_expand_height 仅初值):
        // 定高会随字体缩放偏离行高,偏高时渐隐上缘洗到倒数第二行尾部
        val lineH = tvIntro.lineHeight
        if (canToggle && tvIntroExpand.layoutParams.height != lineH) {
            tvIntroExpand.layoutParams = tvIntroExpand.layoutParams.also { it.height = lineH }
        }
        val padBottom = if (canToggle && introExpanded) lineH else 0
        if (tvIntro.paddingBottom != padBottom) {
            tvIntro.updatePadding(bottom = padBottom)
        }
    }

    private fun bindIntroToggle(
        tvIntro: TextView,
        tvIntroExpand: TextView,
    ) {
        tvIntroExpand.setOnClickListener {
            introExpanded = !introExpanded
            viewModel.getBook(false)?.let { book ->
                upIntro(tvIntro, tvIntroExpand, book)
            }
        }
        // 折叠态渐隐底:透明→按钮承载面底色(两个方向都是内容面板 surfaceContainerLow),
        // 铺在第 4 行行尾之下让文字淡入按钮;色值经 scheme 取,主题/eink 自适应,换肤 recreate
        // 随视图重建。存 tag 供 upIntroExpandVisibility 按状态取用(仅折叠溢出态铺设)。
        val fadeBase = AppColorScheme.current.surfaceContainerLow
        tvIntroExpand.tag = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(fadeBase and 0x00FFFFFF, fadeBase, fadeBase)
        )
        // 持久监听而非 upIntro 内挂一次性 doOnLayout:一次性回调与 header 异步装配/
        // 多次数据发射存在时序竞争,一旦错过(被守卫消耗或过早求值)展开钮即无再评估机会,
        // 首次进入不显示、待滚出视口再回来触发新布局才出现
        tvIntro.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            // 布局回调内不得同步翻转兄弟可见性:GONE→VISIBLE 的 requestLayout 沿父链标记的
            // FORCE_LAYOUT 会在父容器本次 layout() 收尾被清除,重排请求被吞,按钮保持
            // 0 尺寸幽灵态(实测)。post 到布局收束后再评估+写可见性,重排得以正常传播。
            tvIntro.post { upIntroExpandVisibility(tvIntro, tvIntroExpand) }
        }
    }

    /**
     * 阅读进度行(portrait header 主角):仅本地可靠数据——已读百分比 + 当前章节标题。
     * 未读(readProgress()==null,从未打开)时整行 gone;书源不返回的字数/连载状态一律不臆造。
     */
    private fun upReadStatus(h: ItemBookInfoHeaderBinding, book: Book) {
        val segs = ArrayList<String>(2)
        if (book.totalChapterNum > 1) {
            book.readProgress()?.let {
                segs.add(getString(R.string.book_info_read_percent, "${(it * 100).toInt()}%"))
            }
        }
        book.durChapterTitle?.takeIf { it.isNotBlank() }?.let { segs.add(it) }
        val show = (book.durChapterIndex > 0 || book.durChapterPos > 0) && segs.isNotEmpty()
        if (show) h.tvReadStatus.text = segs.joinToString("  ·  ")
        // isVisible(false→GONE):未读时整行收起不占高,与 upIntro 口径一致
        h.llReadStatus.isVisible = show
    }

    /**
     * header(信息头+简介)绑定:监听迁移(原 initViewEvent 里对这些 view 的
     * setOnClickListener/setOnLongClickListener 整体搬入,SourceCallBack 钩子体一字不改)+
     * 数据回填(header 可能晚于 showBook 到达,主动拉一次当前数据)。
     * 面板背景由 recyclerView 整体承载(见 onActivityCreated),header 自身透明。
     */
    private fun bindInfoHeader(h: ItemBookInfoHeaderBinding) {
        h.tvIntro.revealOnFocusHint = false
        bindIntroToggle(h.tvIntro, h.tvIntroExpand)
        h.manageRows.tvOrigin.setOnClickListener {
            viewModel.getBook()?.let { book ->
                if (book.isLocal) return@let
                if (!appDb.bookSourceDao.has(book.origin)) {
                    toastOnUi(R.string.error_no_source)
                    return@let
                }
                editSourceResult.launch {
                    putExtra("sourceUrl", book.origin)
                }
            }
        }
        h.manageRows.tvChangeSource.setOnClickListener {
            viewModel.getBook()?.let { book ->
                showDialogFragment(ChangeBookSourceDialog(book.name, book.author))
            }
        }
        // 打开完整目录页的入口已从此卡的 ll_toc 迁至内嵌目录头(见 addHeaderView + openFullToc)
        h.manageRows.tvChangeGroup.setOnClickListener {
            viewModel.getBook()?.let {
                showDialogFragment(
                    GroupSelectDialog(it.group)
                )
            }
        }
        PressSpringEffect.attach(h.manageRows.tvChangeSource)
        PressSpringEffect.attach(h.manageRows.tvChangeGroup)
        // header 可能晚于 viewModel 数据就绪才 inflate,主动拉一次当前数据回填
        viewModel.bookData.value?.let { book ->
            h.manageRows.tvOrigin.text = getString(R.string.origin_show, book.originName)
            h.manageRows.tvLasted.text = getString(R.string.lasted_show, book.latestChapterTitle)
            upIntro(h.tvIntro, h.tvIntroExpand, book, h.vIntroDivider)
            upReadStatus(h, book)
        }
    }

    private fun upKinds(book: Book) = binding.run {
        lifecycleScope.launch {
            var kinds = book.getKindList()
            if (book.isLocal) {
                withContext(IO) {
                    val size = FileDoc.fromFile(book.bookUrl).size
                    if (size > 0) {
                        kinds = kinds.toMutableList()
                        kinds.add(ConvertUtils.formatFileSize(size))
                    }
                }
            }
            if (kinds.isEmpty()) {
                lbKind.gone()
            } else {
                lbKind.visible()
                val source = viewModel.bookSource
                lbKind.setLabels(
                    kinds,
                    { kind ->
                        source?.let {
                            SourceCallBack.callBackBtn(
                                this@BookInfoActivity,
                                SourceCallBack.CLICK_BOOK_LABEL,
                                source, book, null, result = kind
                            ) {
                                SearchActivity.start(this@BookInfoActivity, kind)
                            }
                        }
                    },
                    { kind ->
                        source?.let {
                            SourceCallBack.callBackBtn(
                                this@BookInfoActivity,
                                SourceCallBack.LONG_CLICK_BOOK_LABEL,
                                source, book, null, result = kind
                            )
                        }
                        true
                    }
                )
            }
        }
    }

    private fun showCover(book: Book) {
        val coverOrigin = book.getCoverSourceOrigin()
        binding.ivCover.load(book.getDisplayCover(), book.name, book.author, false, coverOrigin) {
            binding.ivCover.post { applyAmbientHeader() }
        }
    }

    private fun applyAmbientHeader() {
        // N3a 详情头图与 N3b 音频页共用氛围背景实现(utils/AmbientBackground.kt)
        ambientJob?.cancel()
        ambientJob = (binding.appBar ?: binding.llHeaderPanel)
            ?.applyAmbientBackground(binding.ivCover.drawable, lifecycleScope) { isDestroyed }
    }

    /** 目录加载状态写入内嵌目录头 tvTocCount;成功态的 count 由 upChapterList → upTocHeader 回填 */
    private fun upLoading(isLoading: Boolean, chapterList: List<BookChapter>? = null) {
        binding.refreshProgressBar.isAutoLoading = isLoading
        when {
            isLoading -> tocHeaderBinding?.tvTocCount?.text =
                getString(R.string.toc_s, getString(R.string.loading))

            chapterList.isNullOrEmpty() -> tocHeaderBinding?.tvTocCount?.text =
                getString(R.string.toc_s, getString(R.string.error_load_toc))

            else -> book?.let {
                headerBinding?.manageRows?.tvLasted?.text =
                    getString(R.string.lasted_show, it.latestChapterTitle)
            }
        }
        chapterList?.let { upChapterList(it) }
    }

    /**
     * 详情页内嵌完整目录(RecyclerView + ChapterListAdapter),FLAT 喂入
     * (无 TocListState 分卷分组,保持"倒序即最新在前"的干净反转——N3a 计划最大偏差点)。
     */
    private fun upChapterList(chapters: List<BookChapter>) {
        fullChapters = chapters
        submitTocItems()
    }

    private fun submitTocItems(reordered: Boolean = false) {
        val ordered = if (tocReversed) fullChapters.asReversed() else fullChapters
        val items = ordered.map { TocListItem.Chapter(chapter = it, depth = 0) }
        // 倒序切换=整表重排,走无 diff 快路径(千章级 Myers diff 要数秒);
        // 目录刷新(upChapterList)仍走 diff,保滚动位置与最小重绑
        if (reordered) {
            chapterAdapter.setItemsNoDiff(items)
        } else {
            chapterAdapter.setItems(items)
        }
        upTocHeader()
    }

    private fun toggleTocOrder() {
        tocReversed = !tocReversed
        tocHeaderBinding?.ivTocSort?.rotationX = if (tocReversed) 0f else 180f
        submitTocItems(reordered = true)
    }

    private fun upTocHeader() {
        tocHeaderBinding?.tvTocCount?.text = getString(R.string.toc_s, fullChapters.size.toString())
    }

    private fun upTvBookshelf() {
        if (viewModel.inBookshelf) {
            binding.tvShelf.text = getString(R.string.remove_from_bookshelf)
        } else {
            binding.tvShelf.text = getString(R.string.add_to_bookshelf)
        }
        editMenuItem?.isVisible = viewModel.inBookshelf
    }

    private fun upGroup(groupId: Long) {
        viewModel.loadGroup(groupId) {
            val text = if (it.isNullOrEmpty()) {
                if (book?.isLocal == true) {
                    getString(R.string.group_s, getString(R.string.local_no_group))
                } else {
                    getString(R.string.group_s, getString(R.string.no_group))
                }
            } else {
                getString(R.string.group_s, it)
            }
            headerBinding?.manageRows?.tvGroup?.text = text
        }
    }

    private fun initViewEvent() = binding.run {
        ivCover.setOnClickListener {
            viewModel.getBook()?.let {
                showDialogFragment(
                    ChangeCoverDialog(it.name, it.author)
                )
            }
        }
        ivCover.setOnLongClickListener {
            viewModel.getBook()?.getDisplayCover()?.let { path ->
                showDialogFragment(PhotoDialog(path))
            }
            true
        }
        tvRead.setOnClickListener {
            viewModel.getBook()?.let { book ->
                if (book.isWebFile) {
                    showWebFileDownloadAlert {
                        readBook(it)
                    }
                } else {
                    readBook(book)
                }
            }
        }
        tvShelf.setOnClickListener {
            viewModel.getBook()?.let { book ->
                if (viewModel.inBookshelf) {
                    deleteBook()
                } else {
                    if (book.isWebFile) {
                        showWebFileDownloadAlert()
                    } else {
                        viewModel.addToBookshelf {
                            upTvBookshelf()
                        }
                    }
                }
            }
        }
        tvAuthor.setOnClickListener {
            viewModel.getBook(false)?.let { book ->
                SourceCallBack.callBackBtn(
                    this@BookInfoActivity, SourceCallBack.CLICK_AUTHOR,
                    viewModel.bookSource, book, null, book.getRealAuthor()
                ) {
                    startActivity<SearchActivity> {
                        putExtra("key", book.author)
                    }
                }
            }
        }
        tvAuthor.setOnLongClickListener {
            viewModel.getBook(false)?.let { book ->
                SourceCallBack.callBackBtn(
                    this@BookInfoActivity,
                    SourceCallBack.LONG_CLICK_AUTHOR,
                    viewModel.bookSource, book, null, result = book.author
                ) {
                    SearchActivity.start(this@BookInfoActivity, book.author)
                }
            }
            true
        }
        tvName.setOnClickListener {
            viewModel.getBook(false)?.let { book ->
                SourceCallBack.callBackBtn(
                    this@BookInfoActivity, SourceCallBack.CLICK_BOOK_NAME,
                    viewModel.bookSource, book, null, book.name
                ) {
                    startActivity<SearchActivity> {
                        putExtra("key", book.name)
                    }
                }
            }
        }
        tvName.setOnLongClickListener {
            viewModel.getBook(false)?.let { book ->
                SourceCallBack.callBackBtn(
                    this@BookInfoActivity,
                    SourceCallBack.LONG_CLICK_BOOK_NAME,
                    viewModel.bookSource, book, null, result = book.name
                ) {
                    SearchActivity.start(this@BookInfoActivity, book.name)
                }
            }
            true
        }
        refreshLayout.setOnRefreshListener {
            refreshLayout.isRefreshing = false
            refreshBook()
        }
        PressSpringEffect.attach(tvShelf)
        PressSpringEffect.attach(tvRead)
    }

    private fun setSourceVariable() {
        lifecycleScope.launch {
            val source = viewModel.bookSource
            if (source == null) {
                toastOnUi("书源不存在")
                return@launch
            }
            val comment =
                source.getDisplayVariableComment("源变量可在js中通过source.getVariable()获取")
            val variable = withContext(IO) { source.getVariable() }
            showDialogFragment(
                VariableDialog(
                    getString(R.string.set_source_variable),
                    source.getKey(),
                    variable,
                    comment
                )
            )
        }
    }

    private fun setBookVariable() {
        lifecycleScope.launch {
            val source = viewModel.bookSource
            if (source == null) {
                toastOnUi("书源不存在")
                return@launch
            }
            val book = viewModel.getBook() ?: return@launch
            val variable = withContext(IO) { book.getCustomVariable() }
            val comment = source.getDisplayVariableComment(
                """书籍变量可在js中通过book.getVariable("custom")获取"""
            )
            showDialogFragment(
                VariableDialog(
                    getString(R.string.set_book_variable),
                    book.bookUrl,
                    variable,
                    comment
                )
            )
        }
    }

    override fun setVariable(key: String, variable: String?) {
        when (key) {
            viewModel.bookSource?.getKey() -> viewModel.bookSource?.setVariable(variable)
            viewModel.bookData.value?.bookUrl -> viewModel.bookData.value?.let {
                it.putCustomVariable(variable)
                if (viewModel.inBookshelf) {
                    viewModel.saveBook(it)
                }
            }
        }
    }

    @SuppressLint("InflateParams")
    private fun deleteBook() {
        viewModel.getBook()?.let {
            if (LocalConfig.bookInfoDeleteAlert) {
                alert(
                    titleResource = R.string.draw,
                    messageResource = R.string.sure_del
                ) {
                    var checkBox: CheckBox? = null
                    if (it.isLocal) {
                        checkBox = CheckBox(this@BookInfoActivity).apply {
                            setText(R.string.delete_book_file)
                            isChecked = LocalConfig.deleteBookOriginal
                        }
                        val view = LinearLayout(this@BookInfoActivity).apply {
                            setPadding(16.dpToPx(), 0, 16.dpToPx(), 0)
                            addView(checkBox)
                        }
                        customView { view }
                    }
                    yesButton {
                        if (checkBox != null) {
                            LocalConfig.deleteBookOriginal = checkBox.isChecked
                        }
                        viewModel.delBook(LocalConfig.deleteBookOriginal) {
                            setResult(RESULT_OK)
                            finish()
                        }
                    }
                    noButton()
                }
            } else {
                viewModel.delBook(LocalConfig.deleteBookOriginal) {
                    setResult(RESULT_OK)
                    finish()
                }
            }
        }
    }

    private fun openChapterList() {
        viewModel.getBook()?.let {
            tocActivityResult.launch(it.bookUrl)
        }
    }

    /**
     * 打开完整目录页(TocActivity,含搜索/分卷/定位)。未加入书架时先落库书与目录再打开。
     * 入口为内嵌目录头(点整行 / 搜索图标)。
     */
    private fun openFullToc() {
        if (viewModel.chapterListData.value.isNullOrEmpty()) {
            toastOnUi(R.string.chapter_list_empty)
            return
        }
        val book = viewModel.getBook(false)
        if (book == null) {
            toastOnUi(R.string.book_not_exist)
            return
        }
        if (!viewModel.inBookshelf) {
            viewModel.saveBook(book) {
                viewModel.saveChapterList {
                    openChapterList()
                }
            }
        } else {
            openChapterList()
        }
    }

    /**
     * 定位到指定章节并进入阅读界面。原为 tocActivityResult 回调内联逻辑，
     * 现同时供目录页返回与详情页目录预览行点击复用。
     */
    private fun readFromChapter(index: Int, pos: Int = 0, changed: Boolean = false) {
        viewModel.getBook(false)?.let { book ->
            book.durChapterIndex = index
            book.durChapterPos = pos
            chapterChanged = changed
            if (!viewModel.inBookshelf) {
                // 未加入书架时先保存书籍和章节到数据库再进入阅读
                book.addType(BookType.notShelf)
                viewModel.saveBook(book) {
                    viewModel.saveChapterList {
                        startReadActivity(book)
                    }
                }
            } else {
                lifecycleScope.launch {
                    withContext(IO) {
                        appDb.bookDao.update(book)
                    }
                    startReadActivity(book)
                }
            }
        }
    }

    private data class BookAutoTaskConfig(
        val enabled: Boolean,
        val notifyEnabled: Boolean,
        val cacheEnabled: Boolean,
        val intervalHours: Int
    )

    private fun showAutoTaskDialog() {
        val book = viewModel.getBook() ?: return
        val taskId = AutoTask.bookTaskId(book.bookUrl)
        val existing = AutoTask.getRules().firstOrNull { it.id == taskId }
        val config = parseBookAutoTaskConfig(existing, book.bookUrl)
        val dialogBinding = DialogBookAutoTaskBinding.inflate(layoutInflater)
        dialogBinding.switchEnable.isChecked = config.enabled
        dialogBinding.switchNotify.isChecked = config.notifyEnabled
        dialogBinding.switchCache.isChecked = config.cacheEnabled
        dialogBinding.editInterval.setText(config.intervalHours.toString())
        alert(R.string.auto_task_book_update) {
            customView { dialogBinding.root }
            okButton {
                val enabled = dialogBinding.switchEnable.isChecked
                val hours = dialogBinding.editInterval.text?.toString()?.trim()?.toIntOrNull()
                    ?.coerceAtLeast(1)
                    ?: cachedOrDefaultIntervalHours
                val notifyEnabled = dialogBinding.switchNotify.isChecked
                val cacheEnabled = dialogBinding.switchCache.isChecked
                val cron = "0 */$hours * * *"
                val script = AutoTask.buildBookUpdateScript(
                    bookUrl = book.bookUrl,
                    notifyEnabled = notifyEnabled,
                    cacheEnabled = cacheEnabled
                )
                val displayName = book.name.ifBlank { book.bookUrl }
                val updated = (existing ?: AutoTaskRule(id = taskId)).copy(
                    id = taskId,
                    name = getString(R.string.auto_task_book_update_name, displayName),
                    enable = enabled,
                    cron = cron,
                    script = script
                )
                AutoTask.upsert(updated)
                LocalConfig.bookAutoTaskIntervalHours = hours
                if (enabled) {
                    toastOnUi(R.string.auto_task_book_update_saved)
                } else {
                    toastOnUi(R.string.auto_task_book_update_deleted)
                }
            }
            cancelButton()
        }
    }

    private fun parseBookAutoTaskConfig(
        task: AutoTaskRule?,
        bookUrl: String
    ): BookAutoTaskConfig {
        if (task == null) {
            return BookAutoTaskConfig(
                enabled = true,
                notifyEnabled = true,
                cacheEnabled = false,
                intervalHours = cachedOrDefaultIntervalHours
            )
        }
        val interval = parseCronHours(task.cron)
            ?: cachedOrDefaultIntervalHours
        var notifyEnabled = true
        var cacheEnabled = false
        val json = extractTaskJson(task.script)
        if (!json.isNullOrBlank()) {
            val root = GSON.fromJsonObject<Map<String, Any?>>(json).getOrNull()
            val action = findRefreshAction(root, bookUrl)
            val notify = toStringKeyMap(action?.get("notify"))
            val cache = toStringKeyMap(action?.get("cache"))
            notifyEnabled = readBoolean(notify, "enable", true)
            cacheEnabled = readBoolean(cache, "enable", false)
        }
        return BookAutoTaskConfig(
            enabled = task.enable,
            notifyEnabled = notifyEnabled,
            cacheEnabled = cacheEnabled,
            intervalHours = interval
        )
    }

    private fun extractTaskJson(script: String?): String? {
        if (script.isNullOrBlank()) return null
        val normalized = AutoTask.normalizeScript(script)
        val trimmed = normalized.trim()
        val assignMatch = Regex("^(?:var|let|const)\\s+[\\w$]+\\s*=\\s*(.+)$", RegexOption.DOT_MATCHES_ALL)
            .find(trimmed)
        if (assignMatch != null) {
            val assigned = assignMatch.groupValues[1].trim()
            val first = assigned.substringBefore(";").trim()
            val inner = unwrapJsonExpression(first) ?: first
            if (inner.startsWith("{") || inner.startsWith("[")) {
                return inner
            }
        }
        if (trimmed.startsWith("(") && trimmed.endsWith(")")) {
            val inner = trimmed.substring(1, trimmed.length - 1).trim()
            if (inner.startsWith("{") || inner.startsWith("[")) {
                return inner
            }
        }
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed
        }
        val index = trimmed.indexOf("return")
        if (index < 0) return null
        val after = trimmed.substring(index + 6).trim()
        if (after.isBlank()) return null
        return after.substringBefore(";").trim()
    }

    private fun unwrapJsonExpression(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.startsWith("(") && trimmed.endsWith(")")) {
            return trimmed.substring(1, trimmed.length - 1).trim()
        }
        return null
    }

    // 解析 cron 获取间隔小时数，兼容旧版分钟格式 `*/N * * * *` 和新版 `0 */N * * *`
    private fun parseCronHours(cron: String?): Int? {
        if (cron.isNullOrBlank()) return null
        val trimmed = cron.trim()
        // 新版：每小时执行 `0 */N * * *`
        Regex("^\\s*0\\s+\\*/(\\d+)\\s+\\*\\s+\\*\\s+\\*\\s*$")
            .find(trimmed)
            ?.groupValues?.getOrNull(1)
            ?.toIntOrNull()
            ?.let { return it }
        // 旧版：每N分钟 `*/N * * * *`，转换为小时（向上取整，最小1小时）
        Regex("^\\s*\\*/(\\d+)\\s+\\*\\s+\\*\\s+\\*\\s+\\*\\s*$")
            .find(trimmed)
            ?.groupValues?.getOrNull(1)
            ?.toIntOrNull()
            ?.let { minutes ->
                return ceil(minutes / 60.0).toInt().coerceAtLeast(1)
            }
        return null
    }

    private val cachedOrDefaultIntervalHours: Int
        get() {
            val cached = LocalConfig.bookAutoTaskIntervalHours
            return if (cached > 0) cached else 1
        }

    private fun findRefreshAction(
        root: Map<String, Any?>?,
        bookUrl: String
    ): Map<String, Any?>? {
        if (root == null) return null
        val actions = when (val actionsValue = root["actions"]) {
            is List<*> -> actionsValue
            else -> if (root.containsKey("type")) listOf(root) else emptyList()
        }
        return actions.mapNotNull { toStringKeyMap(it) }
            .firstOrNull {
                val type = it["type"]?.toString()
                val url = it["bookUrl"]?.toString()
                type.equals("refreshToc", true) && url == bookUrl
            }
    }

    private fun toStringKeyMap(value: Any?): Map<String, Any?>? {
        return when (value) {
            is Map<*, *> -> {
                val out = LinkedHashMap<String, Any?>()
                value.forEach { (k, v) ->
                    if (k != null) out[k.toString()] = v
                }
                out
            }
            else -> null
        }
    }

    private fun readBoolean(
        map: Map<String, Any?>?,
        key: String,
        defaultValue: Boolean
    ): Boolean {
        if (map == null) return defaultValue
        return when (val value = getValueIgnoreCase(map, key)) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.equals("true", true) || value == "1"
            else -> defaultValue
        }
    }

    private fun getValueIgnoreCase(map: Map<String, Any?>, key: String): Any? {
        map[key]?.let { return it }
        return map.entries.firstOrNull { it.key.equals(key, true) }?.value
    }

    private fun showWebFileDownloadAlert(
        onClick: ((Book) -> Unit)? = null,
    ) {
        val webFiles = viewModel.webFiles
        if (webFiles.isEmpty()) {
            toastOnUi("Unexpected webFileData")
            return
        }
        selector(
            R.string.download_and_import_file,
            webFiles
        ) { _, webFile, _ ->
            if (webFile.isSupported) {
                /* import */
                viewModel.importOrDownloadWebFile<Book>(webFile) {
                    onClick?.invoke(it)
                }
            } else if (webFile.isSupportDecompress) {
                /* 解压筛选后再选择导入项 */
                viewModel.importOrDownloadWebFile<Uri>(webFile) { uri ->
                    viewModel.getArchiveFilesName(uri) { fileNames ->
                        if (fileNames.size == 1) {
                            viewModel.importArchiveBook(uri, fileNames[0]) {
                                onClick?.invoke(it)
                            }
                        } else {
                            showDecompressFileImportAlert(uri, fileNames, onClick)
                        }
                    }
                }
            } else {
                alert(
                    title = getString(R.string.draw),
                    message = getString(R.string.file_not_supported, webFile.name)
                ) {
                    neutralButton(R.string.open_fun) {
                        /* download only */
                        viewModel.importOrDownloadWebFile<Uri>(webFile) {
                            openFileUri(it, "*/*")
                        }
                    }
                    noButton()
                }
            }
        }
    }

    private fun showDecompressFileImportAlert(
        archiveFileUri: Uri,
        fileNames: List<String>,
        success: ((Book) -> Unit)? = null,
    ) {
        if (fileNames.isEmpty()) {
            toastOnUi(R.string.unsupport_archivefile_entry)
            return
        }
        selector(
            R.string.import_select_book,
            fileNames
        ) { _, name, _ ->
            viewModel.importArchiveBook(archiveFileUri, name) {
                success?.invoke(it)
            }
        }
    }

    private fun readBook(book: Book) {
        if (!viewModel.inBookshelf) {
            book.addType(BookType.notShelf)
            viewModel.saveBook(book) {
                viewModel.saveChapterList {
                    startReadActivity(book)
                }
            }
        } else {
            viewModel.saveBook(book) {
                startReadActivity(book)
            }
        }
    }

    private fun startReadActivity(book: Book) {
        val options = if (MotionTokens.enabled) {
            ActivityOptionsCompat.makeCustomAnimation(
                this, android.R.anim.fade_in, android.R.anim.fade_out
            )
        } else null
        when {
            book.isAudio -> readBookResult.launch(
                Intent(this, AudioPlayActivity::class.java)
                    .putExtra("bookUrl", book.bookUrl)
                    .putExtra("inBookshelf", viewModel.inBookshelf),
                options,
            )

            else -> readBookResult.launch(
                Intent(
                    this,
                    if (!book.isLocal && book.isImage && AppConfig.showMangaUi) ReadMangaActivity::class.java
                    else ReadBookActivity::class.java
                )
                    .putExtra("bookUrl", book.bookUrl)
                    .putExtra("inBookshelf", viewModel.inBookshelf)
                    .putExtra("chapterChanged", chapterChanged),
                options,
            )
        }
    }

    override val oldBook: Book?
        get() = viewModel.bookData.value

    override fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>) {
        viewModel.changeTo(source, book, toc)
    }

    override fun coverChangeTo(coverUrl: String) {
        viewModel.bookData.value?.let { book ->
            book.customCoverUrl = coverUrl
            showCover(book)
            if (viewModel.inBookshelf) {
                viewModel.saveBook(book)
            }
        }
    }

    override fun upGroup(requestCode: Int, groupId: Long) {
        upGroup(groupId)
        viewModel.getBook()?.let { book ->
            book.group = groupId
            if (viewModel.inBookshelf) {
                viewModel.saveBook(book)
            } else if (groupId > 0) {
                viewModel.addToBookshelf {
                    upTvBookshelf()
                }
            }
        }
    }

    private fun upWaitDialogStatus(isShow: Boolean) {
        val showText = "Loading....."
        if (isShow) {
            waitDialog.run {
                setText(showText)
                show()
            }
        } else {
            waitDialog.dismiss()
        }
    }

}
