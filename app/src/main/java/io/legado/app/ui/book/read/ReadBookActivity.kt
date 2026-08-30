package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Looper
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.core.view.get
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.size
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Status
import io.legado.app.data.appDb
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.HighlightColors
import io.legado.app.help.HighlightStyle
import io.legado.app.help.HighlightStyles
import io.legado.app.help.IntentData
import io.legado.app.help.TTS
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.book.isMobi
import io.legado.app.help.book.removeType
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.motion.MotionTokens
import io.legado.app.constant.AppPattern
import io.legado.app.help.source.getSourceType
import io.legado.app.help.storage.Backup
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.SourceCallBack
import io.legado.app.model.analyzeRule.AnalyzeByJSonPath
import io.legado.app.model.jsSource.JsSourceReview
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setChapter
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.localBook.EpubFile
import io.legado.app.model.localBook.MobiFile
import io.legado.app.receiver.NetworkChangedListener
import io.legado.app.receiver.TimeBatteryReceiver
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.book.changesource.ChangeBookSourceDialog
import io.legado.app.ui.book.changesource.ChangeChapterSourceDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.read.config.AutoReadDialog
import io.legado.app.ui.book.read.config.MoreConfigDialog
import io.legado.app.ui.book.read.config.ReadAloudDialog
import io.legado.app.ui.book.read.config.ReadStyleDialog
import io.legado.app.ui.book.read.config.TextSelectMenuConfigDialog
import io.legado.app.ui.font.FontSelectDialog
import io.legado.app.ui.book.read.HighlightActionMenu.Companion.HL_BOX
import io.legado.app.ui.book.read.HighlightActionMenu.Companion.HL_EMPHASIS
import io.legado.app.ui.book.read.HighlightActionMenu.Companion.HL_FILL
import io.legado.app.ui.book.read.HighlightActionMenu.Companion.HL_STRIKE
import io.legado.app.ui.book.read.HighlightActionMenu.Companion.HL_TEXT
import io.legado.app.ui.book.read.HighlightActionMenu.Companion.HL_UNDERLINE
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.entities.PageDirection
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.read.page.provider.LayoutProgressListener
import io.legado.app.ui.book.searchContent.SearchContentActivity
import io.legado.app.ui.book.searchContent.SearchResult
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.book.toc.rule.TxtTocRuleDialog
import io.legado.app.ui.browser.WebViewActivity
import io.legado.app.ui.dict.DictDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.highlight.HighlightRuleActivity
import io.legado.app.ui.highlight.edit.HighlightRuleEditDialog
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.replace.ReplaceRuleActivity
import io.legado.app.ui.replace.edit.ReplaceEditActivity
import io.legado.app.ui.widget.PopupAction
import io.legado.app.ui.widget.popupActionMenu
import io.legado.app.ui.widget.dialog.M3ColorPickerDialog
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.utils.ACache
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.Debounce
import io.legado.app.utils.dpToPx
import io.legado.app.utils.LogUtils
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.buildMainHandler
import io.legado.app.utils.dismissDialogFragment
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.iconItemOnLongClick
import io.legado.app.utils.invisible
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJson
import io.legado.app.utils.isTrue
import io.legado.app.utils.launch
import io.legado.app.utils.navigationBarGravity
import kotlin.coroutines.CoroutineContext
import io.legado.app.utils.observeEvent
import io.legado.app.utils.observeEventSticky
import io.legado.app.utils.postEvent
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.sysScreenOffTime
import io.legado.app.utils.throttle
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.visible
import io.legado.app.data.entities.rule.ReviewRule
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.htmlunit.corejs.javascript.NativeArray
import org.htmlunit.corejs.javascript.Scriptable

/**
 * 阅读界面
 */
class ReadBookActivity : BaseReadBookActivity(),
    View.OnTouchListener,
    ReadView.CallBack,
    TextActionMenu.CallBack,
    ContentTextView.CallBack,
    ReadMenu.CallBack,
    SearchMenu.CallBack,
    ReadAloudDialog.CallBack,
    ChangeBookSourceDialog.CallBack,
    ChangeChapterSourceDialog.CallBack,
    ReadBook.CallBack,
    AutoReadDialog.CallBack,
    TxtTocRuleDialog.CallBack,
    HighlightActionMenu.CallBack,
    HighlightRulePopup.CallBack,
    HighlightStyleDialog.StyleHost,
    FontSelectDialog.CallBack,
    LayoutProgressListener {

    private val tocActivity =
        registerForActivityResult(TocActivityResult()) { result ->
            result?.let {
                viewModel.openChapter(it.index, it.chapterPos) {
                    it.anchorText?.let { anchor ->
                        ReadBook.correctDurPosByAnchor(it.chapterPos, anchor)
                    }
                }
            }
        }
    private val sourceEditActivity =
        registerForActivityResult(StartActivityContract(BookSourceEditActivity::class.java)) {
            if (it.resultCode == RESULT_OK) {
                viewModel.upBookSource {
                    upMenuView()
                }
            }
        }
    private val replaceActivity =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                viewModel.replaceRuleChanged()
            }
        }
    private val searchContentActivity =
        registerForActivityResult(StartActivityContract(SearchContentActivity::class.java)) {
            val data = it.data ?: return@registerForActivityResult
            val key = data.getLongExtra("key", System.currentTimeMillis())
            val index = data.getIntExtra("index", 0)
            val searchResult = IntentData.get<SearchResult>("searchResult$key")
            val searchResultList = IntentData.get<List<SearchResult>>("searchResultList$key")
            if (searchResult != null && searchResultList != null) {
                viewModel.searchContentQuery = searchResult.query
                binding.searchMenu.upSearchResultList(searchResultList)
                isShowingSearchResult = true
                viewModel.searchResultIndex = index
                binding.searchMenu.updateSearchResultIndex(index)
                binding.searchMenu.selectedSearchResult?.let { currentResult ->
                    ReadBook.saveCurrentBookProgress() //退出全文搜索恢复此时进度
                    skipToSearch(currentResult)
                    showActionMenu()
                }
            }
        }
    private val bookInfoActivity =
        registerForActivityResult(StartActivityContract(BookInfoActivity::class.java)) {
            if (it.resultCode == RESULT_OK) {
                setResult(RESULT_DELETED)
                super.finish()
            } else {
                ReadBook.loadOrUpContent()
            }
        }
    private val selectImageDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ACache.get().put(AppConst.imagePathKey, uri.toString())
            viewModel.saveImage(it.value, uri)
        }
    }
    private var menu: Menu? = null
    private var backupJob: Job? = null
    private var tts: TTS? = null
    val textActionMenu: TextActionMenu by lazy {
        TextActionMenu(this, this)
    }
    private val popupAction: PopupAction by lazy {
        PopupAction(this)
    }
    override val isInitFinish: Boolean get() = viewModel.isInitFinish
    override val isScroll: Boolean get() = binding.readView.isScroll
    private val isAutoPage get() = binding.readView.isAutoPage
    override var isShowingSearchResult = false
    override var isSelectingSearchResult = false
        set(value) {
            field = value && isShowingSearchResult
        }
    private val timeBatteryReceiver = TimeBatteryReceiver()
    private var screenTimeOut: Long = 0
    private var loadStates: Boolean = false
    override val pageFactory get() = binding.readView.pageFactory
    override val pageDelegate get() = binding.readView.pageDelegate
    override val headerHeight: Int get() = binding.readView.curPage.headerHeight
    private val nextPageDebounce by lazy { Debounce { keyPage(PageDirection.NEXT) } }
    private val prevPageDebounce by lazy { Debounce { keyPage(PageDirection.PREV) } }
    private var bookChanged = false
    private var pageChanged = false
    /** 最近一次朗读进度的章内字符位置; 供"回到朗读位置"在同章内即时跳转 */
    private var lastReadAloudChapterStart = -1
    private val handler by lazy { buildMainHandler() }
    private val screenOffRunnable by lazy { Runnable { keepScreenOn(false) } }
    private val executor = ReadBook.executor
    private val upSeekBarThrottle = throttle(200) {
        runOnUiThread {
            upSeekBarProgress()
            binding.readMenu.upSeekBar()
        }
    }
    private var reviewSummaryAppliedKey: String? = null
    private var reviewSummaryLoadingKey: String? = null
    private var reviewSummaryRequestToken: Long = 0
    private val reviewSummaryCache = object : LinkedHashMap<String, ReviewSummaryResult>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ReviewSummaryResult>): Boolean {
            return size > 5
        }
    }
    private val reviewSummaryPrefetchingKeys = HashSet<String>()

    //恢复跳转前进度对话框的交互结果
    private var confirmRestoreProcess: Boolean? = null
    private val networkChangedListener by lazy {
        NetworkChangedListener(this)
    }
    private var justInitData: Boolean = false
    private var syncDialog: AlertDialog? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        binding.cursorLeft.setColorFilter(accentColor)
        binding.cursorRight.setColorFilter(accentColor)
        binding.cursorLeft.setOnTouchListener(this)
        binding.cursorRight.setOnTouchListener(this)
        binding.readAloudFloatBarContainer.llBackToSpeech.setOnClickListener {
            backToSpeakingPosition()
        }
        binding.readAloudFloatBarContainer.llReadFromHere.setOnClickListener {
            ReadBook.readAloud()
        }
        window.setBackgroundDrawable(null)
        upScreenTimeOut()
        ReadBook.register(this)
        initHighlightColorPickerListeners()
        onBackPressedDispatcher.addCallback(this) {
            if (isShowingSearchResult) {
                exitSearchMenu()
                restoreLastBookProcess()
                return@addCallback
            }
            //拦截返回供恢复阅读进度
            if (ReadBook.lastBookProgress != null && confirmRestoreProcess != false) {
                restoreLastBookProcess()
                return@addCallback
            }
            if (BaseReadAloudService.isPlay()) {
                ReadAloud.pause(this@ReadBookActivity)
                toastOnUi(R.string.read_aloud_pause)
                return@addCallback
            }
            if (isAutoPage) {
                autoPageStop()
                return@addCallback
            }
            if (getPrefBoolean("disableReturnKey") && !menuLayoutIsVisible) {
                return@addCallback
            }
            finish()
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        viewModel.initReadBookConfig(intent)
        ChapterProvider.clearReviewProviders()
        Looper.myQueue().addIdleHandler {
            viewModel.initData(intent)
            false
        }
        justInitData = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        resetReviewSummaryState()
        viewModel.initData(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        upSystemUiVisibility()
        if (hasFocus) {
            binding.readMenu.upBrightnessState()
        } else if (!menuLayoutIsVisible) {
            ReadBook.cancelPreDownloadTask()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        upSystemUiVisibility()
        binding.readView.upStatusBar()
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        if (!isTopResumedActivity) {
            ReadBook.cancelPreDownloadTask()
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onResume() {
        super.onResume()
        ReadBook.readStartTime = System.currentTimeMillis()
        if (bookChanged) {
            bookChanged = false
            ReadBook.callBack = this
            viewModel.initData(intent)
            justInitData = true
        } else {
            //web端阅读时，app处于阅读界面，本地记录会覆盖web保存的进度，在此处恢复
            ReadBook.webBookProgress?.let {
                ReadBook.setProgress(it)
                ReadBook.webBookProgress = null
            }
        }
        upSystemUiVisibility()
        registerReceiver(timeBatteryReceiver, timeBatteryReceiver.filter)
        binding.readView.upTime()
        upReadAloudFloatBar()
        screenOffTimerStart()
        // 网络监听，当从无网切换到网络环境时同步进度（注意注册的同时就会收到监听，因此界面激活时无需重复执行同步操作）
        networkChangedListener.register()
        networkChangedListener.onNetworkChanged = {
            // 当网络是可用状态且无需初始化时同步进度（初始化中已有同步进度逻辑）
            if (AppConfig.syncBookProgressPlus && NetworkUtils.isAvailable() && !justInitData && ReadBook.inBookshelf) {
                ReadBook.syncProgress({ progress -> sureNewProgress(progress) })
            }
        }
    }

    override fun onPause() {
        super.onPause()
        autoPageStop()
        backupJob?.cancel()
        ReadBook.saveRead()
        ReadBook.cancelPreDownloadTask()
        unregisterReceiver(timeBatteryReceiver)
        upSystemUiVisibility()
        if (!BuildConfig.DEBUG && ReadBook.inBookshelf) {
            if (AppConfig.syncBookProgressPlus) {
                ReadBook.syncProgress()
            } else {
                ReadBook.uploadProgress()
            }
        }
        if (!BuildConfig.DEBUG) {
            Backup.autoBack(this)
        }
        justInitData = false
        networkChangedListener.unRegister()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_read, menu)
        menu.iconItemOnLongClick(R.id.menu_change_source) {
            showChangeSourceMenu(it)
        }
        menu.iconItemOnLongClick(R.id.menu_refresh) {
            showRefreshMenu(it)
        }
        binding.readMenu.refreshMenuColorFilter()
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        this.menu = menu
        upMenu()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_same_title_removed)?.isChecked =
            ReadBook.curTextChapter?.sameTitleRemoved == true
        return super.onMenuOpened(featureId, menu)
    }

    /**
     * 更新菜单
     */
    private fun upMenu() {
        val menu = menu ?: return
        val book = ReadBook.book ?: return
        val onLine = !book.isLocal
        for (i in 0 until menu.size) {
            val item = menu[i]
            when (item.groupId) {
                R.id.menu_group_on_line -> item.isVisible = onLine
                R.id.menu_group_local -> item.isVisible = !onLine
                R.id.menu_group_text -> item.isVisible = book.isLocalTxt
                R.id.menu_group_epub -> item.isVisible = book.isEpub
                else -> when (item.itemId) {
                    R.id.menu_enable_replace -> item.isChecked = book.getUseReplaceRule()
                    R.id.menu_re_segment -> item.isChecked = book.getReSegment()
                    R.id.menu_reverse_content -> item.isVisible = onLine
                    R.id.menu_del_ruby_tag -> item.isChecked = book.getDelTag(Book.rubyTag)
                    R.id.menu_del_h_tag -> item.isChecked = book.getDelTag(Book.hTag)
                }
            }
        }
        lifecycleScope.launch {
            val show = ReadBook.inBookshelf && withContext(IO) {
                AppWebDav.isOk
            }
            menu.findItem(R.id.menu_get_progress)?.isVisible = show
            menu.findItem(R.id.menu_cover_progress)?.isVisible = show
        }
    }

    private fun showChangeSourceMenu(anchor: View) {
        popupActionMenu(this) {
            item(getString(R.string.chapter_change_source), "chapter")
            item(getString(R.string.book_change_source), "book")
        }.show(anchor) { action ->
            when (action) {
                "chapter" -> showChapterChangeSource()
                "book" -> showBookChangeSource()
            }
        }
    }

    private fun showRefreshMenu(anchor: View) {
        popupActionMenu(this) {
            item(getString(R.string.menu_refresh_dur), "dur")
            item(getString(R.string.menu_refresh_after), "after")
            item(getString(R.string.menu_refresh_all), "all")
        }.show(anchor) { action ->
            when (action) {
                "dur" -> refreshDurChapter()
                "after" -> refreshAfterChapters()
                "all" -> refreshAllChapters()
            }
        }
    }

    private fun showBookChangeSource() {
        binding.readMenu.runMenuOut()
        ReadBook.book?.let {
            showDialogFragment(ChangeBookSourceDialog(it.name, it.author))
        }
    }

    private fun showChapterChangeSource() {
        lifecycleScope.launch {
            val book = ReadBook.book ?: return@launch
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
                ?: return@launch
            binding.readMenu.runMenuOut()
            showDialogFragment(
                ChangeChapterSourceDialog(book.name, book.author, chapter.index, chapter.title)
            )
        }
    }

    private fun refreshDurChapter() {
        if (ReadBook.bookSource == null) {
            upContent()
        } else {
            ReadBook.book?.let {
                resetReviewSummaryState()
                ReadBook.curTextChapter = null
                binding.readView.upContent()
                viewModel.refreshContentDur(it)
            }
        }
    }

    private fun refreshAfterChapters() {
        if (ReadBook.bookSource == null) {
            upContent()
        } else {
            ReadBook.book?.let {
                resetReviewSummaryState()
                ReadBook.clearTextChapter()
                binding.readView.upContent()
                viewModel.refreshContentAfter(it)
            }
        }
    }

    private fun refreshAllChapters() {
        if (ReadBook.bookSource == null) {
            upContent()
        } else {
            ReadBook.book?.let {
                refreshContentAll(it)
            }
        }
    }

    /**
     * 菜单
     */
    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_change_source,
            R.id.menu_book_change_source -> showBookChangeSource()
            R.id.menu_chapter_change_source -> showChapterChangeSource()
            R.id.menu_refresh,
            R.id.menu_refresh_dur -> refreshDurChapter()
            R.id.menu_refresh_after -> refreshAfterChapters()
            R.id.menu_refresh_all -> refreshAllChapters()

            R.id.menu_download -> showDownloadDialog()
            R.id.menu_add_bookmark -> addBookmark()
            R.id.menu_highlight_rule -> startActivity<HighlightRuleActivity>()
            R.id.menu_simulated_reading -> showSimulatedReading()
            R.id.menu_edit_content -> showDialogFragment(ContentEditDialog())
            R.id.menu_update_toc -> ReadBook.book?.let {
                if (it.isEpub) {
                    BookHelp.clearCache(it)
                    EpubFile.clear()
                }
                if (it.isMobi) {
                    MobiFile.clear()
                }
                loadChapterList(it)
            }

            R.id.menu_enable_replace -> changeReplaceRuleState()
            R.id.menu_re_segment -> ReadBook.book?.let {
                it.setReSegment(!it.getReSegment())
                item.isChecked = it.getReSegment()
                ReadBook.loadContent(false)
            }

            R.id.menu_del_ruby_tag -> ReadBook.book?.let {
                item.isChecked = !item.isChecked
                if (item.isChecked) {
                    it.addDelTag(Book.rubyTag)
                } else {
                    it.removeDelTag(Book.rubyTag)
                }
                refreshContentAll(it)
            }

            R.id.menu_del_h_tag -> ReadBook.book?.let {
                item.isChecked = !item.isChecked
                if (item.isChecked) {
                    it.addDelTag(Book.hTag)
                } else {
                    it.removeDelTag(Book.hTag)
                }
                refreshContentAll(it)
            }

            R.id.menu_page_anim -> showPageAnimConfig {
                binding.readView.upPageAnim()
                ReadBook.loadContent(false)
            }

            R.id.menu_log -> showDialogFragment<AppLogDialog>()
            R.id.menu_toc_regex -> showDialogFragment(
                TxtTocRuleDialog(ReadBook.book?.tocUrl)
            )

            R.id.menu_reverse_content -> ReadBook.book?.let {
                viewModel.reverseContent(it)
            }

            R.id.menu_set_charset -> showCharsetConfig()
            R.id.menu_image_style -> {
                val imgStyles =
                    arrayListOf(
                        Book.imgStyleDefault, Book.imgStyleFull, Book.imgStyleText,
                        Book.imgStyleSingle
                    )
                selector(
                    R.string.image_style,
                    imgStyles
                ) { _, index ->
                    val imageStyle = imgStyles[index]
                    ReadBook.book?.setImageStyle(imageStyle)
                    if (imageStyle == Book.imgStyleSingle) {
                        ReadBook.book?.setPageAnim(0)  // 切换图片样式single后，自动切换为覆盖
                        binding.readView.upPageAnim()
                    }
                    ReadBook.loadContent(false)
                }
            }

            R.id.menu_get_progress -> ReadBook.book?.let {
                viewModel.syncBookProgress(it) { progress ->
                    sureSyncProgress(progress)
                }
            }

            R.id.menu_cover_progress -> ReadBook.book?.let {
                ReadBook.uploadProgress(true) { toastOnUi(R.string.upload_book_success) }
            }

            R.id.menu_same_title_removed -> {
                ReadBook.book?.let {
                    val contentProcessor = ContentProcessor.get(it)
                    val textChapter = ReadBook.curTextChapter
                    if (textChapter != null
                        && !textChapter.sameTitleRemoved
                        && !contentProcessor.removeSameTitleCache.contains(
                            textChapter.chapter.getFileName("nr")
                        )
                    ) {
                        toastOnUi("未找到可移除的重复标题")
                    }
                }
                viewModel.reverseRemoveSameTitle()
            }

            R.id.menu_effective_replaces -> showDialogFragment<EffectiveReplacesDialog>()

            R.id.menu_help -> showHelp()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun refreshContentAll(book: Book) {
        resetReviewSummaryState()
        ReadBook.clearTextChapter()
        binding.readView.upContent()
        viewModel.refreshContentAll(book)
    }

    private fun resetReviewSummaryState() {
        reviewSummaryRequestToken++
        reviewSummaryAppliedKey = null
        reviewSummaryLoadingKey = null
        synchronized(reviewSummaryCache) {
            reviewSummaryCache.clear()
        }
        synchronized(reviewSummaryPrefetchingKeys) {
            reviewSummaryPrefetchingKeys.clear()
        }
        ChapterProvider.clearReviewProviders()
    }

    /**
     * 按键拦截,显示菜单
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action
        val isDown = action == 0

        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (isDown && !binding.readMenu.canShowMenu) {
                binding.readMenu.runMenuIn()
                return true
            }
            if (!isDown && !binding.readMenu.canShowMenu) {
                binding.readMenu.canShowMenu = true
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 鼠标滚轮事件
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (0 != (event.source and InputDevice.SOURCE_CLASS_POINTER)) {
            if (event.action == MotionEvent.ACTION_SCROLL) {
                val axisValue = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                LogUtils.d("onGenericMotionEvent", "axisValue = $axisValue")
                // 获得垂直坐标上的滚动方向
                if (axisValue < 0.0f) { // 滚轮向下滚
                    mouseWheelPage(PageDirection.NEXT)
                } else { // 滚轮向上滚
                    mouseWheelPage(PageDirection.PREV)
                }
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    /**
     * 按键事件
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (menuLayoutIsVisible) {
            return super.onKeyDown(keyCode, event)
        }
        val longPress = event.repeatCount > 0
        when {
            isPrevKey(keyCode) -> {
                handleKeyPage(PageDirection.PREV, longPress)
                return true
            }

            isNextKey(keyCode) -> {
                handleKeyPage(PageDirection.NEXT, longPress)
                return true
            }
        }
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> if (volumeKeyPage(PageDirection.PREV, longPress)) {
                return true
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> if (volumeKeyPage(PageDirection.NEXT, longPress)) {
                return true
            }

            KeyEvent.KEYCODE_PAGE_UP -> {
                handleKeyPage(PageDirection.PREV, longPress)
                return true
            }

            KeyEvent.KEYCODE_PAGE_DOWN -> {
                handleKeyPage(PageDirection.NEXT, longPress)
                return true
            }

            KeyEvent.KEYCODE_SPACE -> {
                handleKeyPage(PageDirection.NEXT, longPress)
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    /**
     * 松开按键事件
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (volumeKeyPage(PageDirection.NONE, false)) {
                    return true
                }
            }

        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * view触摸,文字选择
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean = binding.run {
        if (!binding.readView.isTextSelected) {
            return false
        }
        when (event.action) {
            MotionEvent.ACTION_DOWN -> textActionMenu.dismiss()
            MotionEvent.ACTION_MOVE -> {
                when (v.id) {
                    R.id.cursor_left -> if (!readView.curPage.getReverseStartCursor()) {
                        readView.curPage.selectStartMove(
                            event.rawX + cursorLeft.width,
                            event.rawY - cursorLeft.height
                        )
                    } else {
                        readView.curPage.selectEndMove(
                            event.rawX - cursorRight.width,
                            event.rawY - cursorRight.height
                        )
                    }

                    R.id.cursor_right -> if (readView.curPage.getReverseEndCursor()) {
                        readView.curPage.selectStartMove(
                            event.rawX + cursorLeft.width,
                            event.rawY - cursorLeft.height
                        )
                    } else {
                        readView.curPage.selectEndMove(
                            event.rawX - cursorRight.width,
                            event.rawY - cursorRight.height
                        )
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                readView.curPage.resetReverseCursor()
                showTextActionMenu()
            }
        }
        return true
    }

    /**
     * 更新文字选择开始位置
     */
    override fun upSelectedStart(x: Float, y: Float, top: Float) = binding.run {
        cursorLeft.x = x - cursorLeft.width
        cursorLeft.y = y
        cursorLeft.visible(true)
        textMenuPosition.x = x
        textMenuPosition.y = top
    }

    /**
     * 更新文字选择结束位置
     */
    override fun upSelectedEnd(x: Float, y: Float) = binding.run {
        cursorRight.x = x
        cursorRight.y = y
        cursorRight.visible(true)
    }

    /**
     * 取消文字选择
     */
    override fun onCancelSelect() = binding.run {
        cursorLeft.invisible()
        cursorRight.invisible()
        textActionMenu.dismiss()
    }

    override fun onLongScreenshotTouchEvent(event: MotionEvent): Boolean {
        return binding.readView.onTouchEvent(event)
    }

    /**
     * 显示文本操作菜单
     */
    override fun showTextActionMenu() {
        val navigationBarHeight =
            if (!ReadBookConfig.hideNavigationBar && navigationBarGravity == Gravity.BOTTOM)
                binding.navigationBar.height else 0
        textActionMenu.show(
            binding.textMenuPosition,
            binding.root.height + navigationBarHeight,
            binding.textMenuPosition.x.toInt(),
            binding.textMenuPosition.y.toInt(),
            binding.cursorLeft.y.toInt() + binding.cursorLeft.height,
            binding.cursorRight.x.toInt(),
            binding.cursorRight.y.toInt() + binding.cursorRight.height
        )
    }

    var editingHighlight: BookHighlight? = null
        private set
    private var highlightActionMenu: HighlightActionMenu? = null
    private var highlightStyleDialog: HighlightStyleDialog? = null
    var editingHighlightRule: HighlightRule? = null
        private set
    private var highlightRulePopup: HighlightRulePopup? = null

    private fun showHighlightActionMenu(highlight: BookHighlight, x: Float, y: Float) {
        editingHighlight = highlight
        val menu = highlightActionMenu ?: HighlightActionMenu(this, this).also { highlightActionMenu = it }
        menu.show(binding.root, x.toInt(), y.toInt())
    }

    override fun onHighlightClick(highlight: BookHighlight, x: Float, y: Float) {
        showHighlightActionMenu(highlight, x, y)
    }

    override fun onHighlightRuleClick(rule: HighlightRule, x: Float, y: Float) {
        editingHighlightRule = rule
        val popup = highlightRulePopup ?: HighlightRulePopup(this, this).also { highlightRulePopup = it }
        popup.show(binding.root, x.toInt(), y.toInt())
    }

    override fun onRuleEdit() {
        editingHighlightRule?.let { showDialogFragment(HighlightRuleEditDialog.edit(it.id)) }
    }

    override fun onRuleDisable() {
        val rule = editingHighlightRule ?: return
        rule.isEnabled = false
        Coroutine.async { appDb.highlightRuleDao.update(rule) }
            .onFinally { ReadBook.upHighlightRules() }
    }

    override fun onHighlightStyle() {
        highlightActionMenu?.dismiss()
        if (editingHighlight == null) return
        val dialog = HighlightStyleDialog()
        highlightStyleDialog = dialog
        showDialogFragment(dialog)
    }

    override fun onHighlightBatch() {
        val h = editingHighlight ?: return
        highlightActionMenu?.dismiss()
        showDialogFragment(HighlightRuleEditDialog.create(
            pattern = h.bookText,
            scope = ReadBook.book?.name,
            style = h.style,
            sourceHighlightTime = h.time
        ))
    }

    override fun currentHighlightStyle(): HighlightStyle =
        editingHighlight?.styleObj() ?: HighlightStyle()

    override fun onHighlightStyleChanged(style: HighlightStyle) {
        editingHighlight?.let { h ->
            h.applyStyle(style)
            ReadBook.updateHighlight(h)
            ReadBook.saveLastHighlightStyle(style)
        }
    }

    /** HighlightStyleDialog.StyleHost: 打开某通道取色器 */
    override fun pickHighlightColor(dialogId: Int, initial: Int, withAlpha: Boolean) {
        val seed = if (initial != 0) initial else HighlightColors.bg.first()
        M3ColorPickerDialog.show(
            supportFragmentManager,
            highlightColorRequestKey(dialogId),
            seed,
            withAlpha,
            if (withAlpha) HighlightColors.bg else HighlightColors.text
        )
    }

    /** HL_* 六通道取色结果监听,注册于 onActivityCreated(非 onClick 内),旋转存活 */
    private fun initHighlightColorPickerListeners() {
        listOf(HL_FILL, HL_TEXT, HL_UNDERLINE, HL_STRIKE, HL_BOX, HL_EMPHASIS).forEach { dialogId ->
            supportFragmentManager.setFragmentResultListener(
                highlightColorRequestKey(dialogId), this
            ) { _, bundle ->
                val color = bundle.getInt(M3ColorPickerDialog.RESULT_COLOR)
                val ns = HighlightStyleDialog.applyChannelColor(currentHighlightStyle(), dialogId, color)
                onHighlightStyleChanged(ns)
                highlightStyleDialog?.refresh()
            }
        }
    }

    /** HighlightStyleDialog.StyleHost: 打开字体选择器(手动高亮) */
    override fun pickHighlightFont(current: String) {
        showDialogFragment(FontSelectDialog())
    }

    // --- FontSelectDialog.CallBack: 高亮自定义字体 ---
    override val curFontPath: String
        get() = currentHighlightStyle().fontPath

    override fun selectFont(path: String) {
        onHighlightStyleChanged(currentHighlightStyle().copy(fontPath = path))
        highlightStyleDialog?.refresh()
    }

    override fun onHighlightNote() {
        editingHighlight?.let { showDialogFragment(HighlightNoteDialog(it)) }
    }

    override fun onHighlightCopy() {
        editingHighlight?.let { sendToClip(it.bookText) }
    }

    override fun onHighlightDelete() {
        editingHighlight?.let { ReadBook.removeHighlight(it) }
    }

    /**
     * 当前选择的文本
     */
    override val selectedText: String get() = binding.readView.getSelectText()

    /**
     * 文本选择菜单操作
     */
    override fun onMenuItemSelected(itemId: Int): Boolean {
        when (itemId) {
            R.id.menu_aloud -> when (AppConfig.contentSelectSpeakMod) {
                1 -> lifecycleScope.launch {
                    binding.readView.aloudStartSelect()
                }

                else -> speak(binding.readView.getSelectText())
            }

            R.id.menu_bookmark -> binding.readView.curPage.let {
                val bookmark = it.createBookmark()
                if (bookmark == null) {
                    toastOnUi(R.string.create_bookmark_error)
                } else {
                    showDialogFragment(BookmarkDialog(bookmark))
                }
                return true
            }

            R.id.menu_highlight -> binding.readView.curPage.let {
                val style = GSON.fromJsonObject<HighlightStyle>(
                    getPrefString(PreferKey.highlightLastStyle)
                ).getOrNull() ?: HighlightStyles.presets.first()
                val anchorX = binding.textMenuPosition.x
                val anchorY = binding.textMenuPosition.y
                val highlight = it.createHighlight(style)
                if (highlight == null) {
                    toastOnUi(R.string.create_bookmark_error)
                } else {
                    ReadBook.addHighlight(highlight)
                    binding.root.post { showHighlightActionMenu(highlight, anchorX, anchorY) }
                }
                return true
            }

            R.id.menu_replace -> {
                val scopes = arrayListOf<String>()
                ReadBook.book?.name?.let {
                    scopes.add(it)
                }
                ReadBook.bookSource?.bookSourceUrl?.let {
                    scopes.add(it)
                }
                val text = selectedText.lineSequence().map { it.trim() }.joinToString("\n")
                replaceActivity.launch(
                    ReplaceEditActivity.startIntent(
                        this,
                        pattern = text,
                        scope = scopes.joinToString(";")
                    )
                )
                return true
            }

            R.id.menu_search_content -> {
                viewModel.searchContentQuery = selectedText
                openSearchActivity(selectedText)
                return true
            }

            R.id.menu_dict -> {
                showDialogFragment(DictDialog(selectedText))
                return true
            }
        }
        return false
    }

    /**
     * 文本选择菜单操作完成
     */
    override fun onMenuActionFinally() = binding.run {
        textActionMenu.dismiss()
        readView.cancelSelect()
    }

    /**
     * 打开自定义文字选择菜单编辑器(长按"更多"触发)
     */
    override fun onEditTextActionMenu() {
        showTextSelectMenuConfig()
    }

    /**
     * 打开自定义文字选择菜单编辑器(设置入口也用它)
     */
    fun showTextSelectMenuConfig() {
        showDialogFragment(TextSelectMenuConfigDialog())
    }

    private fun speak(text: String) {
        if (tts == null) {
            tts = TTS()
        }
        tts?.speak(text)
    }

    /**
     * 鼠标滚轮翻页
     */
    private fun mouseWheelPage(direction: PageDirection) {
        if (menuLayoutIsVisible || !AppConfig.mouseWheelPage) {
            return
        }
        keyPageDebounce(direction, mouseWheel = true, longPress = false)
    }

    /**
     * 音量键翻页
     */
    private fun volumeKeyPage(direction: PageDirection, longPress: Boolean): Boolean {
        if (!AppConfig.volumeKeyPage) {
            return false
        }
        if (!AppConfig.volumeKeyPageOnPlay && BaseReadAloudService.isPlay()) {
            return false
        }
        handleKeyPage(direction, longPress)
        return true
    }

    private fun handleKeyPage(direction: PageDirection, longPress: Boolean) {
        if (AppConfig.keyPageOnLongPress || direction == PageDirection.NONE) {
            keyPage(direction)
        } else {
            keyPageDebounce(direction, longPress = longPress)
        }
    }

    private fun keyPageDebounce(
        direction: PageDirection,
        mouseWheel: Boolean = false,
        longPress: Boolean
    ) {
        if (longPress) {
            return
        }
        nextPageDebounce.apply {
            wait = if (mouseWheel) 200L else 600L
            leading = !mouseWheel
            trailing = mouseWheel
        }
        prevPageDebounce.apply {
            wait = if (mouseWheel) 200L else 600L
            leading = !mouseWheel
            trailing = mouseWheel
        }
        when (direction) {
            PageDirection.NEXT -> nextPageDebounce.invoke()
            PageDirection.PREV -> prevPageDebounce.invoke()
            else -> {}
        }
    }

    private fun keyPage(direction: PageDirection) {
        binding.readView.cancelSelect()
        binding.readView.pageDelegate?.isCancel = false
        binding.readView.pageDelegate?.keyTurnPage(direction)
    }

    override fun upMenuView() {
        handler.post {
            upMenu()
            binding.readMenu.upBookView()
        }
    }

    override fun loadChapterList(book: Book) {
        ReadBook.upMsg(getString(R.string.toc_updateing))
        viewModel.loadChapterList(book)
    }

    /**
     * 内容加载完成
     */
    override fun contentLoadFinish() {
        if (intent.getBooleanExtra("readAloud", false)) {
            intent.removeExtra("readAloud")
            ReadBook.readAloud()
        }
        loadStates = true
        loadReviewSummaryIfNeeded()
    }

    /**
     * 更新内容
     */
    override fun upContent(
        relativePosition: Int,
        resetPageOffset: Boolean,
        success: (() -> Unit)?
    ) {
        lifecycleScope.launch {
            binding.readView.upContent(relativePosition, resetPageOffset)
            if (relativePosition == 0) {
                upSeekBarProgress()
            }
            loadStates = false
            loadReviewSummaryIfNeeded()
            success?.invoke()
        }
    }

    override suspend fun upContentAwait(
        relativePosition: Int,
        resetPageOffset: Boolean,
        success: (() -> Unit)?
    ) = withContext(Main.immediate) {
        binding.readView.upContent(relativePosition, resetPageOffset)
        if (relativePosition == 0) {
            upSeekBarProgress()
        }
        loadStates = false
        loadReviewSummaryIfNeeded()
    }

    override fun upPageAnim(upRecorder: Boolean) {
        lifecycleScope.launch {
            binding.readView.upPageAnim(upRecorder)
        }
    }

    override fun notifyBookChanged() {
        bookChanged = true
        if (!ReadBook.inBookshelf) {
            viewModel.removeFromBookshelf { super.finish() }
        }
    }

    override fun cancelSelect() {
        runOnUiThread {
            binding.readView.cancelSelect()
        }
    }

    /**
     * 页面改变
     */
    override fun pageChanged() {
        pageChanged = true
        binding.readView.onPageChange()
        highlightActionMenu?.dismiss()
        highlightRulePopup?.dismiss()
        handler.post {
            upSeekBarProgress()
        }
        executor.execute {
            startBackupJob()
        }
    }

    /**
     * 更新进度条位置
     */
    private fun upSeekBarProgress() {
        val progress = when (AppConfig.progressBarBehavior) {
            "page" -> ReadBook.durPageIndex
            else /* chapter */ -> ReadBook.durChapterIndex
        }
        binding.readMenu.setSeekPage(progress)
    }

    /**
     * 显示菜单
     */
    override fun showMenuBar() {
        binding.readMenu.runMenuIn()
    }

    /**
     * 回到朗读位置：恢复页面跟随朗读，并精确跳到当前朗读位置。
     * 同章内直接定位到已记录的朗读字符位置；跨章时打开朗读所在章节并定位到该字符位置。
     * 全程不打断当前朗读。
     */
    override fun backToSpeakingPosition() {
        if (!BaseReadAloudService.isRun) return
        val speakingChapterIndex = ReadAloud.readAloudChapterIndex
        // 优先用观察到的进度; 回退到服务里存活的朗读位置(Activity 重建后进度事件可能尚未到达)
        val chapterStart = lastReadAloudChapterStart.takeIf { it >= 0 }
            ?: ReadAloud.readAloudChapterStart
        when {
            speakingChapterIndex >= 0 && speakingChapterIndex != ReadBook.durChapterIndex -> {
                // 跨章：打开朗读所在章节并精确定位到朗读字符位置。
                // openChapter 会先脱离跟随, 故在加载完成回调里再恢复跟随。
                val durChapterPos = chapterStart.coerceAtLeast(0)
                ReadBook.openChapter(speakingChapterIndex, durChapterPos) {
                    ReadAloud.restoreReadAloudFollow()
                    upTextChapterAloudSpan(chapterStart)
                }
            }

            else -> {
                ReadAloud.restoreReadAloudFollow()
                if (chapterStart >= 0) upTextChapterAloudSpan(chapterStart)
            }
        }
    }

    /**
     * 把显示页定位到章内字符位置并绘制朗读高亮。
     */
    private fun upTextChapterAloudSpan(chapterStart: Int) {
        if (chapterStart < 0) return
        val textChapter = ReadBook.curTextChapter ?: return
        lifecycleScope.launch(IO) {
            ReadBook.durChapterPos = chapterStart
            val pageIndex = ReadBook.durPageIndex
            val aloudSpanStart = chapterStart - textChapter.getReadLength(pageIndex)
            textChapter.getPage(pageIndex)?.upPageAloudSpan(aloudSpanStart)
            upContent()
        }
    }

    override val oldBook: Book?
        get() = ReadBook.book

    override fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>) {
        if (!book.isAudio) {
            viewModel.changeTo(book, toc)
        } else {
            ReadAloud.stop(this)
            lifecycleScope.launch {
                withContext(IO) {
                    ReadBook.book?.migrateTo(book, toc)
                    book.removeType(BookType.updateError)
                    ReadBook.book?.delete()
                    appDb.bookDao.insert(book)
                }
                startActivityForBook(book)
                finish()
            }
        }
    }

    override fun replaceContent(content: String) {
        ReadBook.book?.let {
            viewModel.saveContent(it, content)
        }
    }

    override fun showActionMenu() {
        when {
            BaseReadAloudService.isRun -> showReadAloudDialog()
            isAutoPage -> showDialogFragment<AutoReadDialog>()
            isShowingSearchResult -> binding.searchMenu.runMenuIn()
            else -> binding.readMenu.runMenuIn()
        }
    }

    /**
     * 显示朗读菜单
     */
    override fun showReadAloudDialog() {
        showDialogFragment<ReadAloudDialog>()
    }

    /**
     * 自动翻页
     */
    override fun autoPage() {
        ReadAloud.stop(this)
        if (isAutoPage) {
            autoPageStop()
        } else {
            binding.readView.autoPager.start()
            binding.readMenu.setAutoPage(true)
            screenTimeOut = -1L
            screenOffTimerStart()
        }
    }

    override fun autoPageStop() {
        if (isAutoPage) {
            binding.readView.autoPager.stop()
            binding.readMenu.setAutoPage(false)
            dismissDialogFragment<AutoReadDialog>()
            upScreenTimeOut()
        }
    }

    override fun openSourceEditActivity() {
        ReadBook.bookSource?.let {
            sourceEditActivity.launch {
                putExtra("sourceUrl", it.bookSourceUrl)
            }
        }
    }

    override fun openBookInfoActivity() {
        ReadBook.book?.let {
            bookInfoActivity.launch {
                putExtra("name", it.name)
                putExtra("author", it.author)
            }
        }
    }

    /**
     * 替换
     */
    override fun openReplaceRule() {
        replaceActivity.launch(Intent(this, ReplaceRuleActivity::class.java))
    }

    /**
     * 打开目录
     */
    override fun openChapterList() {
        ReadBook.book?.let {
            tocActivity.launch(it.bookUrl)
        }
    }

    /**
     * 打开搜索界面
     */
    override fun openSearchActivity(searchWord: String?) {
        val book = ReadBook.book ?: return
        searchContentActivity.launch {
            putExtra("bookUrl", book.bookUrl)
            putExtra("searchWord", searchWord ?: viewModel.searchContentQuery)
            putExtra("searchResultIndex", viewModel.searchResultIndex)
            viewModel.searchResultList?.first()?.let {
                if (it.query == viewModel.searchContentQuery) {
                    IntentData.put("searchResultList", viewModel.searchResultList)
                }
            }
        }
    }

    /**
     * 禁用书源
     */
    override fun disableSource() {
        viewModel.disableSource()
    }

    /**
     * 显示阅读样式配置
     */
    override fun showReadStyle() {
        showDialogFragment<ReadStyleDialog>()
    }

    /**
     * 显示更多设置
     */
    override fun showMoreSetting() {
        showDialogFragment<MoreConfigDialog>()
    }

    override fun showSearchSetting() {
        showDialogFragment<MoreConfigDialog>()
    }

    /**
     * 更新状态栏,导航栏
     */
    override fun upSystemUiVisibility() {
        upSystemUiVisibility(isInMultiWindow, !menuLayoutIsVisible, bottomDialog > 0)
        upNavigationBarColor()
    }

    // 退出全文搜索
    override fun exitSearchMenu() {
        if (isShowingSearchResult) {
            isShowingSearchResult = false
            binding.searchMenu.invalidate()
            binding.searchMenu.invisible()
            ReadBook.clearSearchResult()
            binding.readView.cancelSelect(true)
        }
    }

    /* 恢复到 全文搜索/进度条跳转前的位置 */
    private fun restoreLastBookProcess() {
        if (confirmRestoreProcess == true) {
            ReadBook.restoreLastBookProgress()
        } else if (confirmRestoreProcess == null) {
            alert(R.string.draw) {
                setMessage(R.string.restore_last_book_process)
                yesButton {
                    confirmRestoreProcess = true
                    ReadBook.restoreLastBookProgress() //恢复启动全文搜索前的进度
                }
                noButton {
                    ReadBook.lastBookProgress = null
                    confirmRestoreProcess = false
                }
                onCancelled {
                    ReadBook.lastBookProgress = null
                    confirmRestoreProcess = false
                }
            }
        }
    }

    override fun showLogin() {
        ReadBook.bookSource?.let {
            startActivity<SourceLoginActivity> {
                putExtra("type", "bookSource")
                putExtra("key", it.bookSourceUrl)
                putExtra("bookUrl", ReadBook.book?.bookUrl)
                putExtra("durChapterIndex", ReadBook.durChapterIndex)
            }
        }
    }

    override fun payAction() {
        val book = ReadBook.book ?: return
        if (book.isLocal) return
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
        if (chapter == null) {
            toastOnUi("no chapter")
            return
        }
        alert(R.string.chapter_pay) {
            setMessage(chapter.title)
            yesButton {
                Coroutine.async(lifecycleScope) {
                    val source =
                        ReadBook.bookSource ?: throw NoStackTraceException("no book source")
                    val payAction = source.getContentRule().payAction
                    if (payAction.isNullOrBlank()) {
                        throw NoStackTraceException("no pay action")
                    }
                    val analyzeRule = AnalyzeRule(book, source)
                    analyzeRule.setCoroutineContext(coroutineContext)
                    analyzeRule.setBaseUrl(chapter.url)
                    analyzeRule.setChapter(chapter)
                    analyzeRule.evalJS(payAction).toString()
                }.onSuccess(IO) {
                    if (it.isAbsUrl()) {
                        startActivity<WebViewActivity> {
                            val bookSource = ReadBook.bookSource
                            putExtra("title", getString(R.string.chapter_pay))
                            putExtra("url", it)
                            putExtra("sourceOrigin", bookSource?.bookSourceUrl)
                            putExtra("sourceName", bookSource?.bookSourceName)
                            putExtra("sourceType", bookSource?.getSourceType())
                        }
                    } else if (it.isTrue()) {
                        //购买成功后刷新目录
                        ReadBook.book?.let {
                            ReadBook.curTextChapter = null
                            BookHelp.delContent(book, chapter)
                            loadChapterList(book)
                        }
                    }
                }.onError {
                    AppLog.put("执行购买操作出错\n${it.localizedMessage}", it, true)
                }
            }
            noButton()
        }
    }

    /**
     * 朗读按钮
     */
    override fun onClickReadAloud() {
        autoPageStop()
        when {
            !BaseReadAloudService.isRun -> {
                ReadAloud.upReadAloudClass()
                val scrollPageAnim = ReadBook.pageAnim() == 3
                if (scrollPageAnim) {
                    val pos = binding.readView.getReadAloudPos()
                    if (pos != null) {
                        val (index, line) = pos
                        if (ReadBook.durChapterIndex != index) {
                            ReadBook.openChapter(index, line.chapterPosition, false) {
                                ReadBook.readAloud(startPos = line.pagePosition)
                            }
                        } else {
                            ReadBook.durChapterPos = line.chapterPosition
                            ReadBook.readAloud(startPos = line.pagePosition)
                        }
                    } else {
                        ReadBook.readAloud()
                    }
                } else {
                    ReadBook.readAloud()
                }
            }

            BaseReadAloudService.pause -> {
                val scrollPageAnim = ReadBook.pageAnim() == 3
                if (scrollPageAnim && pageChanged) {
                    pageChanged = false
                    val pos = binding.readView.getReadAloudPos()
                    if (pos != null) {
                        val (index, line) = pos
                        if (ReadBook.durChapterIndex != index) {
                            ReadBook.openChapter(index, line.chapterPosition, false) {
                                ReadBook.readAloud(startPos = line.pagePosition)
                            }
                        } else {
                            ReadBook.durChapterPos = line.chapterPosition
                            ReadBook.readAloud(startPos = line.pagePosition)
                        }
                    } else {
                        ReadBook.readAloud()
                    }
                } else {
                    ReadAloud.resume(this)
                }
            }

            else -> ReadAloud.pause(this)
        }
    }

    override fun showHelp() {
        showHelp("readMenuHelp")
    }

    /**
     * 长按图片
     */
    @SuppressLint("RtlHardcoded")
    override fun onImageLongPress(x: Float, y: Float, src: String) {
        popupAction.setItems(
            listOf(
                SelectItem(getString(R.string.show), "show"),
                SelectItem(getString(R.string.refresh), "refresh"),
                SelectItem(getString(R.string.action_save), "save"),
                SelectItem(getString(R.string.menu), "menu"),
                SelectItem(getString(R.string.select_folder), "selectFolder")
            )
        )
        popupAction.onActionClick = {
            when (it) {
                "show" -> showDialogFragment(PhotoDialog(src))
                "refresh" -> viewModel.refreshImage(src)
                "save" -> {
                    val path = ACache.get().getAsString(AppConst.imagePathKey)
                    if (path.isNullOrEmpty()) {
                        selectImageDir.launch {
                            value = src
                        }
                    } else {
                        viewModel.saveImage(src, path.toUri())
                    }
                }

                "menu" -> showActionMenu()
                "selectFolder" -> selectImageDir.launch()
            }
            popupAction.dismiss()
        }
        val navigationBarHeight =
            if (!ReadBookConfig.hideNavigationBar && navigationBarGravity == Gravity.BOTTOM)
                binding.navigationBar.height else 0
        popupAction.showAtLocation(
            binding.readView, Gravity.BOTTOM or Gravity.LEFT, x.toInt(),
            binding.root.height + navigationBarHeight - y.toInt()
        )
    }

    override fun onImageClick(src: String): Boolean {
        val urlMatcher = AnalyzeUrl.paramPattern.matcher(src)
        if (!urlMatcher.find()) return false
        val urlOptionStr = src.substring(urlMatcher.end())
        val urlOptionMap = GSON.fromJsonObject<Map<String, String>>(urlOptionStr).getOrNull()
        val click = urlOptionMap?.get("click")?.takeIf { it.isNotBlank() } ?: return false
        Coroutine.async(lifecycleScope, IO) {
            val source = ReadBook.bookSource ?: return@async
            val book = ReadBook.book ?: return@async
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
                ?: throw Exception("no find chapter")
            source.evalJS(click) {
                put("book", book)
                put("chapter", chapter)
                put("src", src)
            }
        }.onError {
            AppLog.put("执行图片链接click键值出错\n${it.localizedMessage}", it, true)
        }
        return true
    }

    override fun onReviewClick(paragraphNum: Int, count: Int) {
        // -1 identifies the chapter title; 0 is the only invalid paragraph id.
        if (paragraphNum == 0) return
        if (count <= 0) {
            toastOnUi("暂无段评")
            return
        }
        val source = ReadBook.bookSource ?: return
        if (source.isJsSource()) {
            showDialogFragment(ReviewDetailDialog(paragraphNum, count))
            return
        }
        val rule = source.ruleReview ?: run {
            toastOnUi("未配置段评规则")
            return
        }
        if (rule.reviewDetailUrl.isNullOrBlank()) {
            toastOnUi("未配置段评详情URL")
            return
        }
        if (rule.detailListRule.isNullOrBlank() || rule.detailContentRule.isNullOrBlank()) {
            toastOnUi("未配置段评详情规则")
            return
        }
        showDialogFragment(ReviewDetailDialog(paragraphNum, count))
    }

    /**
     * 清理段评状态并作废在途请求，防止在正文加载失败/未配置段评等场景下，
     * 迟到的段评统计响应把图标重新渲染上去。
     */
    private fun invalidateReviewSummary() {
        reviewSummaryRequestToken++
        reviewSummaryAppliedKey = null
        reviewSummaryLoadingKey = null
        ChapterProvider.clearReviewProviders()
    }

    private fun loadReviewSummaryIfNeeded() {
        val source = ReadBook.bookSource ?: run {
            invalidateReviewSummary()
            return
        }
        if (source.isJsSource()) {
            loadJsReviewSummaryIfNeeded(source)
            return
        }
        val reviewRule = source.ruleReview ?: run {
            invalidateReviewSummary()
            return
        }
        if (!reviewRule.enabled) {
            invalidateReviewSummary()
            return
        }
        val rule = reviewRule.reviewSummaryUrl?.takeIf { it.isNotBlank() } ?: run {
            invalidateReviewSummary()
            return
        }
        if (reviewRule.summaryListRule.isNullOrBlank() ||
            reviewRule.summaryParagraphIndexRule.isNullOrBlank() ||
            reviewRule.summaryCountRule.isNullOrBlank()
        ) {
            invalidateReviewSummary()
            return
        }
        val book = ReadBook.book ?: return
        val chapterIndex = ReadBook.durChapterIndex
        val textChapter = ReadBook.curTextChapter
        if (textChapter != null &&
            textChapter.chapter.index == chapterIndex &&
            !textChapter.hasBodyContent
        ) {
            invalidateReviewSummary()
            return
        }
        val key = buildReviewSummaryKey(book, chapterIndex)
        if (reviewSummaryAppliedKey == key || reviewSummaryLoadingKey == key) return
        val cached = synchronized(reviewSummaryCache) { reviewSummaryCache[key] }
        if (cached != null) {
                applyReviewSummary(key, chapterIndex, cached)
                prefetchAdjacentReviewSummary(book, source, reviewRule, chapterIndex)
                return
            }
        reviewSummaryLoadingKey = key
        val requestToken = ++reviewSummaryRequestToken
        if (reviewSummaryAppliedKey != key) {
            ChapterProvider.clearReviewProviders()
        }

        Coroutine.async(lifecycleScope, IO) {
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex) ?: return@async null
            if (chapter.isVolume) return@async null
            val analyzeUrl = AnalyzeUrl(
                rule,
                baseUrl = chapter.url,
                source = source,
                ruleData = book,
                chapter = chapter,
                coroutineContext = coroutineContext
            )
            val body = analyzeUrl.getStrResponseAwait().body
            parseReviewSummary(
                body,
                reviewRule,
                source,
                book,
                chapter,
                analyzeUrl.url,
                coroutineContext
            )
        }.onSuccess(Main) { result ->
            releaseReviewSummaryLoadingKey(key)
            if (requestToken != reviewSummaryRequestToken) return@onSuccess
            val curBook = ReadBook.book ?: return@onSuccess
            val curKey = buildReviewSummaryKey(curBook, ReadBook.durChapterIndex)
            if (curKey != key) return@onSuccess
            if (result != null) {
                synchronized(reviewSummaryCache) {
                    reviewSummaryCache[key] = result
                }
                applyReviewSummary(key, chapterIndex, result)
                prefetchAdjacentReviewSummary(book, source, reviewRule, chapterIndex)
            } else {
                ChapterProvider.clearReviewProviders()
            }
        }.onError {
            releaseReviewSummaryLoadingKey(key)
            if (requestToken != reviewSummaryRequestToken) return@onError
            val curBook = ReadBook.book
            val curKey = curBook?.let { book ->
                buildReviewSummaryKey(book, ReadBook.durChapterIndex)
            }
            if (curKey != key) return@onError
            ChapterProvider.clearReviewProviders()
            AppLog.put("加载段评统计出错\n${it.localizedMessage}", it)
        }
    }

    /**
     * JS 源段评统计加载:复刻声明式路径的通用机制(book/正文就绪门、key 短路、cache、token、providers 清理),
     * 数据来源改由 JsSourceReview.getReviewSummaryAwait 直接 eval getReviewSummary 函数取得。
     * JS 源换章即时加载,未做相邻章预取(eval 成本与 search/toc 同量级,即时加载足够)。
     */
    private fun loadJsReviewSummaryIfNeeded(source: BookSource) {
        val book = ReadBook.book ?: return
        val chapterIndex = ReadBook.durChapterIndex
        val textChapter = ReadBook.curTextChapter
        if (textChapter != null &&
            textChapter.chapter.index == chapterIndex &&
            !textChapter.hasBodyContent
        ) {
            invalidateReviewSummary()
            return
        }
        val key = buildReviewSummaryKey(book, chapterIndex)
        if (reviewSummaryAppliedKey == key || reviewSummaryLoadingKey == key) return
        val cached = synchronized(reviewSummaryCache) { reviewSummaryCache[key] }
        if (cached != null) {
            applyReviewSummary(key, chapterIndex, cached)
            return
        }
        reviewSummaryLoadingKey = key
        val requestToken = ++reviewSummaryRequestToken
        if (reviewSummaryAppliedKey != key) {
            ChapterProvider.clearReviewProviders()
        }

        Coroutine.async(lifecycleScope, IO) {
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex) ?: return@async null
            if (chapter.isVolume) return@async null
            JsSourceReview.getReviewSummaryAwait(source, book, chapter)?.let { map ->
                ReviewSummaryResult(
                    counts = map.mapValues { it.value.first },
                    keys = map.mapValues { it.value.second }
                )
            }
        }.onSuccess(Main) { result ->
            releaseReviewSummaryLoadingKey(key)
            if (requestToken != reviewSummaryRequestToken) return@onSuccess
            val curBook = ReadBook.book ?: return@onSuccess
            val curKey = buildReviewSummaryKey(curBook, ReadBook.durChapterIndex)
            if (curKey != key) return@onSuccess
            if (result != null) {
                synchronized(reviewSummaryCache) {
                    reviewSummaryCache[key] = result
                }
                applyReviewSummary(key, chapterIndex, result)
            } else {
                ChapterProvider.clearReviewProviders()
            }
        }.onError {
            releaseReviewSummaryLoadingKey(key)
            if (requestToken != reviewSummaryRequestToken) return@onError
            val curBook = ReadBook.book
            val curKey = curBook?.let { book ->
                buildReviewSummaryKey(book, ReadBook.durChapterIndex)
            }
            if (curKey != key) return@onError
            ChapterProvider.clearReviewProviders()
            AppLog.put("加载段评统计出错\n${it.localizedMessage}", it)
        }
    }

    private fun applyReviewSummary(key: String, chapterIndex: Int, result: ReviewSummaryResult) {
        val counts = result.counts
        val keys = result.keys
        ChapterProvider.setReviewProviders(
            countProvider = { targetChapterIndex, reviewId ->
                if (targetChapterIndex != chapterIndex) 0 else counts[reviewId] ?: 0
            },
            keyProvider = { targetChapterIndex, reviewId ->
                if (targetChapterIndex != chapterIndex) null else keys[reviewId]
            }
        )
        reviewSummaryAppliedKey = key
        // 段评列刷新只需要重绘当前阅读视图，避免重新走 curPageChanged() 打断朗读位置。
        binding.readView.upContent(relativePosition = 0, resetPageOffset = false)
    }

    private fun prefetchAdjacentReviewSummary(
        book: Book,
        source: BaseSource,
        rule: ReviewRule,
        chapterIndex: Int
    ) {
        val maxIndex = if (ReadBook.simulatedChapterSize > 0) {
            ReadBook.simulatedChapterSize
        } else {
            ReadBook.chapterSize
        }
        if (maxIndex <= 0) return
        val indices = intArrayOf(chapterIndex - 1, chapterIndex + 1)
        val token = reviewSummaryRequestToken
        for (idx in indices) {
            if (idx !in 0 until maxIndex) continue
            val loadedChapter = sequenceOf(
                ReadBook.prevTextChapter,
                ReadBook.curTextChapter,
                ReadBook.nextTextChapter
            ).filterNotNull().firstOrNull { it.chapter.index == idx }
            // 仅对已完成排版且确认存在正文的相邻章节做段评预取，避免空正文章节被竞态误触发。
            if (loadedChapter == null || !loadedChapter.hasBodyContent) continue
            val key = buildReviewSummaryKey(book, idx)
            if (reviewSummaryLoadingKey == key) continue
            val hasCache = synchronized(reviewSummaryCache) { reviewSummaryCache.containsKey(key) }
            if (hasCache) continue
            val shouldFetch = synchronized(reviewSummaryPrefetchingKeys) {
                if (reviewSummaryPrefetchingKeys.contains(key)) false else {
                    reviewSummaryPrefetchingKeys.add(key)
                    true
                }
            }
            if (!shouldFetch) continue
            Coroutine.async(lifecycleScope, IO) {
                val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, idx) ?: return@async null
                if (chapter.isVolume) return@async null
                val summaryUrl = rule.reviewSummaryUrl?.takeIf { it.isNotBlank() } ?: return@async null
                val analyzeUrl = AnalyzeUrl(
                    summaryUrl,
                    baseUrl = chapter.url,
                    source = source,
                    ruleData = book,
                    chapter = chapter,
                    coroutineContext = coroutineContext
                )
                val body = analyzeUrl.getStrResponseAwait().body
                parseReviewSummary(
                    body,
                    rule,
                    source,
                    book,
                    chapter,
                    analyzeUrl.url,
                    coroutineContext
                )
            }.onSuccess(Main) { result ->
                synchronized(reviewSummaryPrefetchingKeys) {
                    reviewSummaryPrefetchingKeys.remove(key)
                }
                if (token != reviewSummaryRequestToken) return@onSuccess
                if (result != null) {
                    synchronized(reviewSummaryCache) {
                        reviewSummaryCache[key] = result
                    }
                }
            }.onError {
                synchronized(reviewSummaryPrefetchingKeys) {
                    reviewSummaryPrefetchingKeys.remove(key)
                }
            }
        }
    }

    private fun buildReviewSummaryKey(book: Book, chapterIndex: Int): String {
        val sourceKey = book.origin.takeIf { it.isNotBlank() }
            ?: ReadBook.bookSource?.getKey().orEmpty()
        return "$sourceKey|${book.bookUrl}#$chapterIndex"
    }

    private fun releaseReviewSummaryLoadingKey(key: String) {
        if (reviewSummaryLoadingKey == key) {
            reviewSummaryLoadingKey = null
        }
    }

    private data class ReviewSummaryResult(
        val counts: Map<Int, Int>,
        val keys: Map<Int, String>
    )

    private fun parseReviewSummary(
        body: String?,
        rule: ReviewRule?,
        source: BaseSource,
        book: Book,
        chapter: BookChapter,
        baseUrl: String,
        context: CoroutineContext
    ): ReviewSummaryResult? {
        if (body.isNullOrBlank()) return null
        if (rule == null) return null
        return parseReviewSummaryByRule(body, rule, source, book, chapter, baseUrl, context)
    }

    private fun parseReviewSummaryByRule(
        body: String,
        rule: ReviewRule,
        source: BaseSource,
        book: Book,
        chapter: BookChapter,
        baseUrl: String,
        context: CoroutineContext
    ): ReviewSummaryResult? {
        val listRule = rule.summaryListRule?.trim().orEmpty()
        val indexRule = rule.summaryParagraphIndexRule?.trim().orEmpty()
        if (listRule.isEmpty() || indexRule.isEmpty()) return null
        val analyzeRule = AnalyzeRule(book, source)
            .setChapter(chapter)
            .setCoroutineContext(context)
            .setContent(body, baseUrl)
        val hasJs = AppPattern.JS_PATTERN.matcher(listRule).find()
        val list = runCatching { analyzeRule.getElements(listRule) }.getOrElse {
            AppLog.put("段评统计列表规则执行出错\n${it.localizedMessage}", it)
            emptyList()
        }
        val finalList = if (list.isEmpty() && hasJs) {
            evalSummaryListByJs(analyzeRule, listRule) ?: list
        } else {
            list
        }
        if (finalList.isEmpty()) return ReviewSummaryResult(emptyMap(), emptyMap())
        val countMap = HashMap<Int, Int>()
        val keyMap = HashMap<Int, String>()
        val countRule = rule.summaryCountRule?.trim().orEmpty()
        val dataRule = rule.summaryParagraphDataRule?.trim().orEmpty()
        val itemRule = AnalyzeRule(book, source)
            .setChapter(chapter)
            .setCoroutineContext(context)
        for ((idx, item) in finalList.withIndex()) {
            itemRule.setContent(item, baseUrl)
            val idStr = itemRule.getString(indexRule).takeIf { it.isNotBlank() }
            val paragraphId = idStr?.toIntOrNull()
                ?: idStr?.toDoubleOrNull()?.toInt()
                ?: (idx + 1)
            val count = if (countRule.isNotEmpty()) {
                val countStr = itemRule.getString(countRule).takeIf { it.isNotBlank() }
                countStr?.toIntOrNull() ?: countStr?.toDoubleOrNull()?.toInt() ?: 0
            } else {
                0
            }
            if (count > 0 && paragraphId != 0) {
                countMap[paragraphId] = count
                val rawKey = if (dataRule.isNotEmpty()) {
                    itemRule.getString(dataRule).takeIf { it.isNotBlank() }
                } else {
                    idStr
                }
                val keyValue = rawKey?.takeIf { it.isNotBlank() }
                    ?: idStr?.takeIf { it.isNotBlank() }
                    ?: paragraphId.toString()
                keyMap[paragraphId] = keyValue
            }
        }
        return ReviewSummaryResult(countMap, keyMap)
    }

    private fun evalSummaryListByJs(
        analyzeRule: AnalyzeRule,
        listRule: String
    ): List<Any>? {
        val matcher = AppPattern.JS_PATTERN.matcher(listRule)
        if (!matcher.find()) return null
        val js = matcher.group(1) ?: matcher.group(2) ?: return null
        val result = analyzeRule.evalJS(js)
        return toAnyList(result)
    }

    private fun toAnyList(result: Any?): List<Any> {
        return when (result) {
            is NativeArray -> {
                val list = ArrayList<Any>()
                val size = result.length.toInt()
                for (i in 0 until size) {
                    val value = result.get(i, result)
                    if (value != null && value != Scriptable.NOT_FOUND) {
                        list.add(value)
                    }
                }
                list
            }
            is List<*> -> result.filterNotNull()
            is Array<*> -> result.filterNotNull().toList()
            is String -> {
                if (result.isJson()) {
                    AnalyzeByJSonPath(result).getList("$") ?: emptyList()
                } else {
                    emptyList()
                }
            }
            else -> emptyList()
        }
    }

    private fun highlightColorRequestKey(dialogId: Int): String = "readHighlightColor$dialogId"

    override fun onTocRegexDialogResult(tocRegex: String) {
        ReadBook.book?.let {
            it.tocUrl = tocRegex
            loadChapterList(it)
        }
    }

    private fun sureSyncProgress(progress: BookProgress) {
        alert(R.string.get_book_progress) {
            setMessage(R.string.current_progress_exceeds_cloud)
            okButton {
                ReadBook.setProgress(progress)
            }
            noButton()
        }
    }

    /* 进度条跳转到指定章节 */
    override fun skipToChapter(index: Int) {
        ReadBook.saveCurrentBookProgress() //退出章节跳转恢复此时进度
        viewModel.openChapter(index)
    }

    /* 全文搜索跳转 */
    override fun navigateToSearch(searchResult: SearchResult, index: Int) {
        viewModel.searchResultIndex = index
        skipToSearch(searchResult)
    }

    override fun onMenuShow() {
        binding.readView.autoPager.pause()
        upReadAloudFloatBar()
    }

    override fun onMenuHide() {
        binding.readView.autoPager.resume()
        // runMenuOut 在动画开始前即回调 onMenuHide,此刻 readMenu 尚未 invisible
        // (要等 menuOutListener.onAnimationEnd),故 menuLayoutIsVisible 仍含 readMenu 分量。
        // 传 menuHiding=true 把正在消失的 readMenu 从判断中剔除,让胶囊在菜单关闭当刻即浮现。
        upReadAloudFloatBar(menuHiding = true)
    }

    /**
     * 朗读悬浮胶囊显隐+施色。朗读运行中且已脱离跟随、且菜单未显示时浮现,
     * 提供"回到朗读位置/从此处朗读"双段快捷(免开朗读弹窗)。
     * 施色随阅读底色自适应(bottomBackground+派生前景),不用全局主题色。
     * @param menuHiding 由 onMenuHide 传 true:此刻 readMenu 正在播放退出动画尚未置 invisible,
     *   剔除其分量避免胶囊被"仍可见"的将逝菜单压住;bottomDialog/searchMenu 分量保留。
     */
    private fun upReadAloudFloatBar(menuHiding: Boolean = false) {
        val barBinding = binding.readAloudFloatBarContainer
        val menuVisible = if (menuHiding) {
            bottomDialog > 0 || binding.searchMenu.bottomMenuVisible
        } else {
            menuLayoutIsVisible
        }
        val show = ReadAloudBarVisibility.shouldShow(
            isRun = BaseReadAloudService.isRun,
            following = ReadAloud.followReadAloudPosition,
            menuVisible = menuVisible
        )
        if (show) {
            val bgColor = bottomBackground
            val textColor = getPrimaryTextColor(ColorUtils.isColorLight(bgColor))
            (barBinding.readAloudFloatBar.background as? GradientDrawable)?.apply {
                setColor(bgColor)
                // 描边保任意阅读底色可辨(日间近白底/e-ink 弱阴影下不靠投影分界)。
                // eink 走纯黑实线框,其余用前景色 0.25α 与底色派生同源。
                val strokeColor = if (AppConfig.isEInkMode) textColor
                else ColorUtils.withAlpha(textColor, 0.25f)
                setStroke(1.dpToPx(), strokeColor)
            }
            barBinding.ivBackToSpeech.setColorFilter(textColor)
            barBinding.tvBackToSpeech.setTextColor(textColor)
            barBinding.ivReadFromHere.setColorFilter(textColor)
            barBinding.tvReadFromHere.setTextColor(textColor)
            barBinding.vBarDivider.setBackgroundColor(ColorUtils.withAlpha(textColor, 0.3f))
        }
        val bar = barBinding.readAloudFloatBar
        // 稳定态短路(避免每次 re-eval 重启动画);"稳定"要连 alpha 一起判,
        // 否则淡出在途(isVisible 仍 true/alpha 渐 0)会被误判为已显示。
        val settledShown = bar.isVisible && bar.alpha == 1f
        val settledHidden = !bar.isVisible
        if (show && settledShown) return
        if (!show && settledHidden) return
        bar.animate().cancel() // 取消在途动画,防快速脱离/恢复切换卡在错误终态
        if (AppConfig.isEInkMode || !MotionTokens.enabled) {
            bar.alpha = 1f
            bar.isVisible = show
        } else if (show) {
            bar.alpha = 0f
            bar.isVisible = true
            bar.animate().alpha(1f).setDuration(200).start()
        } else {
            bar.animate().alpha(0f).setDuration(200).withEndAction { bar.isGone = true }.start()
        }
    }

    override fun onLayoutPageCompleted(index: Int, page: TextPage) {
        upSeekBarThrottle.invoke()
        binding.readView.onLayoutPageCompleted(index, page)
    }

    /* 全文搜索跳转 */
    private fun skipToSearch(searchResult: SearchResult) {
        if (searchResult.chapterIndex != ReadBook.durChapterIndex) {
            viewModel.openChapter(searchResult.chapterIndex) {
                jumpToPosition(searchResult)
            }
        } else {
            jumpToPosition(searchResult)
        }
    }

    private fun jumpToPosition(searchResult: SearchResult) {
        val curTextChapter = ReadBook.curTextChapter ?: return
        binding.searchMenu.updateSearchInfo()
        val (pageIndex, lineIndex, charIndex, addLine, charIndex2) =
            viewModel.searchResultPositions(curTextChapter, searchResult)
        ReadBook.skipToPage(pageIndex) {
            isSelectingSearchResult = true
            binding.readView.curPage.selectStartMoveIndex(0, lineIndex, charIndex)
            when (addLine) {
                0 -> binding.readView.curPage.selectEndMoveIndex(
                    0,
                    lineIndex,
                    charIndex + viewModel.searchContentQuery.length - 1
                )

                1 -> binding.readView.curPage.selectEndMoveIndex(
                    0, lineIndex + 1, charIndex2
                )
                //consider change page, jump to scroll position
                -1 -> binding.readView.curPage.selectEndMoveIndex(1, 0, charIndex2)
            }
            binding.readView.isTextSelected = true
            isSelectingSearchResult = false
        }
    }

    override fun addBookmark() {
        val book = ReadBook.book
        val page = ReadBook.curTextChapter?.getPage(ReadBook.durPageIndex)
        if (book != null && page != null) {
            val bookmark = book.createBookMark().apply {
                chapterIndex = ReadBook.durChapterIndex
                chapterPos = ReadBook.durChapterPos
                chapterName = page.title
                bookText = page.text.trim()
            }
            showDialogFragment(BookmarkDialog(bookmark))
        }
    }

    override fun changeReplaceRuleState() {
        ReadBook.book?.let {
            it.setUseReplaceRule(!it.getUseReplaceRule())
            ReadBook.saveRead()
            menu?.findItem(R.id.menu_enable_replace)?.isChecked = it.getUseReplaceRule()
            viewModel.replaceRuleChanged()
        }
    }

    private fun startBackupJob() {
        backupJob?.cancel()
        backupJob = lifecycleScope.launch(IO) {
            delay(300000)
            ReadBook.book?.let {
                AppWebDav.uploadBookProgress(it)
                ensureActive()
                it.update()
                Backup.autoBack(this@ReadBookActivity)
            }
        }
    }

    override fun sureNewProgress(progress: BookProgress) {
        syncDialog?.dismiss()
        syncDialog = alert(R.string.get_book_progress) {
            setMessage(R.string.cloud_progress_exceeds_current)
            okButton {
                ReadAloud.detachReadAloudFollow()
                ReadBook.setProgress(progress)
            }
            noButton()
        }
    }

    override fun finish() {
        val book = ReadBook.book ?: return super.finish()

        if (ReadBook.inBookshelf) {
            return super.finish()
        }

        if (!AppConfig.showAddToShelfAlert) {
            viewModel.removeFromBookshelf { super.finish() }
        } else {
            alert(title = getString(R.string.add_to_bookshelf)) {
                setMessage(getString(R.string.check_add_bookshelf, book.name))
                okButton {
                    ReadBook.book?.removeType(BookType.notShelf)
                    ReadBook.book?.save()
                    ReadBook.inBookshelf = true
                    setResult(RESULT_OK)
                }
                noButton { viewModel.removeFromBookshelf { super.finish() } }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.clearTts()
        textActionMenu.dismiss()
        popupAction.dismiss()
        highlightActionMenu?.dismiss()
        binding.readView.onDestroy()
        ReadBook.unregister(this)
        if (!ReadBook.inBookshelf && !isChangingConfigurations) {
            viewModel.removeFromBookshelf(null)
        }
        if (!isChangingConfigurations) {
            SourceCallBack.callBackBook(
                SourceCallBack.END_READ, ReadBook.bookSource,
                ReadBook.book, ReadBook.curTextChapter?.chapter
            )
        }
        if (!BuildConfig.DEBUG) {
            Backup.autoBack(this)
        }
    }

    override fun observeLiveBus() = binding.run {
        observeEvent<String>(EventBus.TIME_CHANGED) { readView.upTime() }
        observeEvent<Int>(EventBus.BATTERY_CHANGED) { readView.upBattery(it) }
        observeEvent<Boolean>(EventBus.MEDIA_BUTTON) {
            if (it) {
                onClickReadAloud()
            } else {
                ReadBook.readAloud(!BaseReadAloudService.pause)
            }
        }
        observeEvent<ArrayList<Int>>(EventBus.UP_CONFIG) {
            it.forEach { value ->
                when (value) {
                    0 -> upSystemUiVisibility()
                    1 -> readView.upBg()
                    2 -> readView.upStyle()
                    3 -> readView.upBgAlpha()
                    4 -> readView.upPageSlopSquare()
                    5 -> if (isInitFinish) ReadBook.loadContent(resetPageOffset = false)
                    6 -> readView.upContent(resetPageOffset = false)
                    8 -> ChapterProvider.upStyle()
                    9 -> readView.invalidateTextPage()
                    10 -> ChapterProvider.upLayout()
                    11 -> readView.submitRenderTask()
                }
            }
        }
        observeEvent<Int>(EventBus.ALOUD_STATE) {
            if (it == Status.STOP || it == Status.PAUSE) {
                ReadBook.curTextChapter?.let { textChapter ->
                    val page = textChapter.getPageByReadPos(ReadBook.durChapterPos)
                    if (page != null) {
                        page.removePageAloudSpan()
                        readView.upContent(resetPageOffset = false)
                    }
                }
            }
            upReadAloudFloatBar()
        }
        observeEvent<Boolean>(EventBus.READ_ALOUD_FOLLOW) {
            upReadAloudFloatBar()
        }
        observeEventSticky<Int>(EventBus.TTS_PROGRESS) { chapterStart ->
            lastReadAloudChapterStart = chapterStart
            lifecycleScope.launch(IO) {
                if (BaseReadAloudService.shouldApplySpeechProgressToVisibleReader(
                        isSpeechPlaying = BaseReadAloudService.isPlay()
                    )
                ) {
                    ReadBook.curTextChapter?.let { textChapter ->
                        ReadBook.durChapterPos = chapterStart
                        val pageIndex = ReadBook.durPageIndex
                        val aloudSpanStart = chapterStart - textChapter.getReadLength(pageIndex)
                        textChapter.getPage(pageIndex)
                            ?.upPageAloudSpan(aloudSpanStart)
                        upContent()
                    }
                }
            }
        }
        observeEvent<Boolean>(PreferKey.keepLight) {
            upScreenTimeOut()
        }
        observeEvent<Boolean>(PreferKey.textSelectAble) {
            readView.curPage.upSelectAble(it)
        }
        observeEvent<String>(PreferKey.showBrightnessView) {
            readMenu.upBrightnessState()
        }
        observeEvent<List<SearchResult>>(EventBus.SEARCH_RESULT) {
            viewModel.searchResultList = it
        }
        observeEvent<Boolean>(EventBus.UPDATE_READ_ACTION_BAR) {
            readMenu.reset()
        }
        observeEvent<Boolean>(EventBus.UP_SEEK_BAR) {
            readMenu.upSeekBar()
        }
        observeEvent<Boolean>(EventBus.REFRESH_BOOK_CONTENT) { //书源js函数触发刷新
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                refreshDurChapter()
            }
        }
        observeEvent<Boolean>(EventBus.REFRESH_BOOK_TOC) { //书源js函数触发刷新
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                ReadBook.book?.let {
                    loadChapterList(it)
                }
            }
        }
    }

    private fun upScreenTimeOut() {
        val keepLightPrefer = getPrefString(PreferKey.keepLight)?.toInt() ?: 0
        screenTimeOut = keepLightPrefer * 1000L
        screenOffTimerStart()
    }

    /**
     * 重置黑屏时间
     */
    override fun screenOffTimerStart() {
        handler.post {
            if (screenTimeOut < 0) {
                keepScreenOn(true)
                return@post
            }
            val t = screenTimeOut - sysScreenOffTime
            if (t > 0) {
                keepScreenOn(true)
                handler.removeCallbacks(screenOffRunnable)
                handler.postDelayed(screenOffRunnable, screenTimeOut)
            } else {
                keepScreenOn(false)
            }
        }
    }

    companion object {
        const val RESULT_DELETED = 100
    }

}
