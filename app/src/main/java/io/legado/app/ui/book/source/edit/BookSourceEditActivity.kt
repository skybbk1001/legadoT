package io.legado.app.ui.book.source.edit

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import androidx.core.view.isGone
import androidx.core.view.isVisible
import android.view.MenuItem
import android.widget.EditText
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.BookSourceType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.data.entities.rule.ReviewRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.databinding.ActivityBookSourceEditBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.tabTextColors
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.source.debug.BookSourceDebugActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.widget.dialog.WebCodeDialog
import io.legado.app.ui.widget.dialog.UrlOptionDialog
import io.legado.app.ui.widget.dialog.VariableDialog
import io.legado.app.ui.widget.keyboard.KeyboardToolPop
import io.legado.app.ui.widget.recycler.NoChildScrollLinearLayoutManager
import io.legado.app.ui.widget.text.EditEntity
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.imeHeight
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.launch
import io.legado.app.utils.navigationBarHeight
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.share
import io.legado.app.utils.shareWithQr
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.views.bottomPadding

class BookSourceEditActivity :
    VMBaseActivity<ActivityBookSourceEditBinding, BookSourceEditViewModel>(),
    KeyboardToolPop.CallBack,
    VariableDialog.Callback,
    WebCodeDialog.Callback {

    override val binding by viewBinding(ActivityBookSourceEditBinding::inflate)
    override val viewModel by viewModels<BookSourceEditViewModel>()

    private val unsafeEditRequests = linkedMapOf<String, EditEntity>()
    private val adapter by lazy {
        BookSourceEditAdapter { entity ->
            // requestId 前缀编码 tab:key——Activity 重建丢 map 后仍可按其回填重建的同名字段
            val requestId = "${binding.tabLayout.selectedTabPosition}:${entity.key}:" +
                java.util.UUID.randomUUID().toString()
            if (
                WebCodeDialog.show(
                    supportFragmentManager,
                    entity.value.orEmpty(),
                    requestId = requestId,
                    title = entity.hint
                )
            ) {
                unsafeEditRequests[requestId] = entity
            }
        }
    }
    private val sourceEntities: ArrayList<EditEntity> = ArrayList()
    private val searchEntities: ArrayList<EditEntity> = ArrayList()
    private val exploreEntities: ArrayList<EditEntity> = ArrayList()
    private val infoEntities: ArrayList<EditEntity> = ArrayList()
    private val tocEntities: ArrayList<EditEntity> = ArrayList()
    private val contentEntities: ArrayList<EditEntity> = ArrayList()

    private val reviewEntities: ArrayList<EditEntity> = ArrayList()
    private val qrCodeResult = registerForActivityResult(QrCodeResult()) {
        it ?: return@registerForActivityResult
        viewModel.importSource(it) { source ->
            upSourceView(source)
        }
    }
    private val selectDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            if (uri.isContentScheme()) {
                sendText(uri.toString())
            } else {
                sendText(uri.path.toString())
            }
        }
    }

    private val softKeyboardTool by lazy {
        KeyboardToolPop(this, lifecycleScope, binding.root, this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // JS 源重定向须在窗口上屏前完成:onCreate 内 finish 的 Activity 不渲染,
        // 转场直达 JS 编辑器;若留到 initData 异步回调再判定,本页 JSON 规则编辑 UI 会先
        // 闪现一帧再二次转场。存在性判定走 hasJsSource 主键查询(不拉 mainJs 全文),
        // 主线程同步(allowMainThreadQueries)。
        intent.getStringExtra("sourceUrl")?.let { url ->
            if (appDb.bookSourceDao.hasJsSource(url)) {
                redirectToJsEditor(url)
            }
        }
        super.onCreate(savedInstanceState)
    }

    /**
     * 转场直达 JS 编辑器。正文/听书/详情/换源弹窗以 for-result 方式发起编辑并凭
     * RESULT_OK 重载书源,FLAG_ACTIVITY_FORWARD_RESULT 把本页的回执目标转让给
     * JS 编辑器,其结果(RESULT_OK + origin)直达原调用方。super.finish 绕过覆写版
     * finish 的未保存比对(本页 UI 未初始化,比对无意义且可能误弹确认)。
     */
    private fun redirectToJsEditor(url: String) {
        startActivity(Intent(this, JsSourceEditActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT)
            putExtra("sourceUrl", url)
        })
        super.finish()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        // 重定向路径:已 finish,跳过全部装配(initData 若跑,其 JS 回退分支会二次 startActivity)
        if (isFinishing) return
        softKeyboardTool.attachToWindow(window)
        initView()
        viewModel.initData(intent) {
            val source = viewModel.bookSource
            if (source != null && source.isJsSource()) {
                // 回退网:正常流程由 onCreate 重定向拦截,此分支兜底判定口径差异的极端源
                redirectToJsEditor(source.bookSourceUrl)
                return@initData
            }
            upSourceView(viewModel.bookSource)
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        if (!LocalConfig.ruleHelpVersionIsLast) {
            showHelp("ruleHelp")
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.source_edit, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_login)?.isVisible = !getSource().loginUrl.isNullOrBlank()
        menu.findItem(R.id.menu_auto_complete)?.isChecked = viewModel.autoComplete
        return super.onMenuOpened(featureId, menu)
    }

    /** 保存并记结果:落库即回传 RESULT_OK + 最新 origin,调试/登录/搜索等静默保存后
     * 直接退出的路径,for-result 调用方同样感知库内变更 */
    private fun saveSource(onSuccess: (BookSource) -> Unit) {
        viewModel.save(getSource()) {
            setResult(RESULT_OK, Intent().putExtra("origin", it.bookSourceUrl))
            onSuccess(it)
        }
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_save -> saveSource {
                finish()
            }

            R.id.menu_debug_source -> saveSource { source ->
                startActivity<BookSourceDebugActivity> {
                    putExtra("key", source.bookSourceUrl)
                }
            }

            R.id.menu_clear_cookie -> viewModel.clearCookie(getSource().bookSourceUrl)
            R.id.menu_auto_complete -> viewModel.autoComplete = !viewModel.autoComplete
            R.id.menu_copy_source -> sendToClip(GSON.toJson(getSource()))
            R.id.menu_paste_source -> viewModel.pasteSource { upSourceView(it) }
            R.id.menu_qr_code_camera -> qrCodeResult.launch()
            R.id.menu_share_str -> share(GSON.toJson(getSource()))
            R.id.menu_share_qr -> shareWithQr(
                GSON.toJson(getSource()),
                getString(R.string.share_book_source),
                ErrorCorrectionLevel.L
            )

            R.id.menu_log -> showDialogFragment<AppLogDialog>()
            R.id.menu_help -> showHelp("ruleHelp")
            R.id.menu_login -> saveSource { source ->
                startActivity<SourceLoginActivity> {
                    putExtra("type", "bookSource")
                    putExtra("key", source.bookSourceUrl)
                }
            }

            R.id.menu_set_source_variable -> setSourceVariable()
            R.id.menu_search -> saveSource { source ->
                startActivity<SearchActivity> {
                    putExtra("searchScope", SearchScope(source).toString())
                }
            }

        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initView() {
        binding.tabLayout.addTab(binding.tabLayout.newTab().apply {
            setText(R.string.source_tab_base)
        })
        binding.tabLayout.addTab(binding.tabLayout.newTab().apply {
            setText(R.string.source_tab_search)
        })
        binding.tabLayout.addTab(binding.tabLayout.newTab().apply {
            setText(R.string.source_tab_find)
        })
        binding.tabLayout.addTab(binding.tabLayout.newTab().apply {
            setText(R.string.source_tab_info)
        })
        binding.tabLayout.addTab(binding.tabLayout.newTab().apply {
            setText(R.string.source_tab_toc)
        })
        binding.tabLayout.addTab(binding.tabLayout.newTab().apply {
            setText(R.string.source_tab_content)
        })
        binding.tabLayout.addTab(binding.tabLayout.newTab().apply {
            setText(R.string.source_tab_review)
        })
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.layoutManager = NoChildScrollLinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.llTopHeader.setOnClickListener {
            toggleTopExpand(binding.llTopExpand.isGone)
        }
        val chipSummaryListener = android.widget.CompoundButton.OnCheckedChangeListener { _, _ ->
            upTopSummary()
        }
        binding.cbIsEnable.setOnCheckedChangeListener(chipSummaryListener)
        binding.cbIsEnableExplore.setOnCheckedChangeListener(chipSummaryListener)
        binding.cbIsEnableCookie.setOnCheckedChangeListener(chipSummaryListener)
        binding.cbIsEnableReview.setOnCheckedChangeListener(chipSummaryListener)
        binding.cbIsEventListener.setOnCheckedChangeListener(chipSummaryListener)
        binding.cbIsCustomButton.setOnCheckedChangeListener(chipSummaryListener)
        binding.cgType.setOnCheckedStateChangeListener { _, _ ->
            upTopSummary()
        }
        runCatching {
            val types = resources.getStringArray(R.array.book_type)
            binding.chipTypeText.text = types.getOrNull(0) ?: "Text"
            binding.chipTypeAudio.text = types.getOrNull(1) ?: "Audio"
            binding.chipTypeImage.text = types.getOrNull(2) ?: "Image"
            binding.chipTypeFile.text = types.getOrNull(3) ?: "File"
        }
        // 沉浸式操作栏:tab 栏透明,与标题栏一致透出页面背景(含背景图);透明时去 elevation,避免不可见栏投影
        if (AppConfig.isTransparentActionBar) {
            binding.tabLayout.setBackgroundColor(Color.TRANSPARENT)
            binding.tabLayout.elevation = 0f
        } else {
            binding.tabLayout.setBackgroundColor(backgroundColor)
        }
        binding.tabLayout.setSelectedTabIndicatorColor(accentColor)
        val tabColors = tabTextColors(ColorUtils.isColorLight(backgroundColor))
        binding.tabLayout.setTabTextColors(tabColors.unselected, tabColors.selected)
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabReselected(tab: TabLayout.Tab?) {

            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {

            }

            override fun onTabSelected(tab: TabLayout.Tab?) {
                setEditEntities(tab?.position)
            }
        })
        binding.recyclerView.setOnApplyWindowInsetsListenerCompat { view, windowInsets ->
            val navigationBarHeight = windowInsets.navigationBarHeight
            val imeHeight = windowInsets.imeHeight
            view.bottomPadding = if (imeHeight == 0) navigationBarHeight else 0
            softKeyboardTool.initialPadding = imeHeight
            windowInsets
        }
        binding.fieldNav.attachToRecyclerView(binding.recyclerView)
    }

    override fun finish() {
        val source = getSource()
        if (!source.equal(viewModel.bookSource ?: BookSource())) {
            alert(R.string.exit) {
                setMessage(R.string.exit_no_save)
                positiveButton(R.string.yes)
                negativeButton(R.string.no) {
                    super.finish()
                }
            }
        } else {
            super.finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        softKeyboardTool.dismiss()
    }

    /** 展开/收起顶部类型+开关面板 */
    private fun toggleTopExpand(expand: Boolean) {
        binding.llTopExpand.isVisible = expand
        binding.ivTopArrow.setImageResource(
            if (expand) R.drawable.ic_arrow_drop_up else R.drawable.ic_arrow_down
        )
        if (!expand) upTopSummary()
    }

    /** 更新折叠头的摘要文字：类型 · 各已开启项 */
    private fun upTopSummary() {
        val parts = mutableListOf<String>()
        runCatching {
            val typeChip = binding.cgType.findViewById<com.google.android.material.chip.Chip>(
                binding.cgType.checkedChipId
            )
            typeChip?.text?.toString()?.let { parts.add(it) }
        }
        if (binding.cbIsEnable.isChecked) parts.add(getString(R.string.is_enable))
        if (binding.cbIsEnableExplore.isChecked) parts.add(getString(R.string.discovery))
        if (binding.cbIsEnableCookie.isChecked) parts.add(getString(R.string.auto_save_cookie))
        if (binding.cbIsEnableReview.isChecked) parts.add(getString(R.string.review))
        if (binding.cbIsEventListener.isChecked) parts.add(getString(R.string.is_event_listener))
        if (binding.cbIsCustomButton.isChecked) parts.add(getString(R.string.custom_button))
        binding.tvTopSummary.text = parts.joinToString(" · ")
    }

    private fun setEditEntities(tabPosition: Int?) {
        val entities = when (tabPosition) {
            1 -> searchEntities
            2 -> exploreEntities
            3 -> infoEntities
            4 -> tocEntities
            5 -> contentEntities
            6 -> reviewEntities
            else -> sourceEntities
        }
        adapter.editEntities = entities
        binding.recyclerView.scrollToPosition(0)
        binding.fieldNav.setLabels(entities.map { it.hint.replace(Regex("[（(].+?[）)]"), "").trim() })
    }

    private fun upSourceView(bookSource: BookSource?) {
        val bs = bookSource ?: BookSource()
        bs.let {
            binding.cbIsEnable.isChecked = it.enabled
            binding.cbIsEnableExplore.isChecked = it.enabledExplore
            binding.cbIsEnableCookie.isChecked = it.enabledCookieJar ?: false
            binding.cbIsEnableReview.isChecked = it.ruleReview?.enabled ?: false
            binding.cbIsEventListener.isChecked = it.eventListener
            binding.cbIsCustomButton.isChecked = it.customButton
            binding.cgType.check(
                when (it.bookSourceType) {
                    BookSourceType.file -> R.id.chip_type_file
                    BookSourceType.image -> R.id.chip_type_image
                    BookSourceType.audio -> R.id.chip_type_audio
                    else -> R.id.chip_type_text
                }
            )
        }
        binding.cgType.post { upTopSummary() }
        // 基本信息
        sourceEntities.clear()
        sourceEntities.apply {
            add(EditEntity("bookSourceUrl", bs.bookSourceUrl, R.string.source_url))
            add(EditEntity("bookSourceName", bs.bookSourceName, R.string.source_name))
            add(EditEntity("bookSourceGroup", bs.bookSourceGroup, R.string.source_group))
            add(EditEntity("bookSourceComment", bs.bookSourceComment, R.string.comment))
            add(EditEntity("loginUrl", bs.loginUrl, R.string.login_url))
            add(EditEntity("loginUi", bs.loginUi, R.string.login_ui))
            add(EditEntity("loginCheckJs", bs.loginCheckJs, R.string.login_check_js))
            add(EditEntity("coverDecodeJs", bs.coverDecodeJs, R.string.cover_decode_js))
            add(EditEntity("bookUrlPattern", bs.bookUrlPattern, R.string.book_url_pattern))
            add(EditEntity("header", bs.header, R.string.source_http_header))
            add(EditEntity("variableComment", bs.variableComment, R.string.variable_comment))
            add(EditEntity("concurrentRate", bs.concurrentRate, R.string.concurrent_rate))
            add(EditEntity("jsLib", bs.jsLib, "jsLib"))
        }
        // 搜索
        val sr = bs.getSearchRule()
        searchEntities.clear()
        searchEntities.apply {
            add(EditEntity("searchUrl", bs.searchUrl, R.string.r_search_url))
            add(EditEntity("checkKeyWord", sr.checkKeyWord, R.string.check_key_word))
            add(EditEntity("bookList", sr.bookList, R.string.r_book_list))
            add(EditEntity("name", sr.name, R.string.r_book_name))
            add(EditEntity("author", sr.author, R.string.r_author))
            add(EditEntity("kind", sr.kind, R.string.rule_book_kind))
            add(EditEntity("wordCount", sr.wordCount, R.string.rule_word_count))
            add(EditEntity("lastChapter", sr.lastChapter, R.string.rule_last_chapter))
            add(EditEntity("intro", sr.intro, R.string.rule_book_intro))
            add(EditEntity("coverUrl", sr.coverUrl, R.string.rule_cover_url))
            add(EditEntity("bookUrl", sr.bookUrl, R.string.r_book_url))
        }
        // 发现
        val er = bs.getExploreRule()
        exploreEntities.clear()
        exploreEntities.apply {
            add(EditEntity("exploreUrl", bs.exploreUrl, R.string.r_find_url))
            add(EditEntity("bookList", er.bookList, R.string.r_book_list))
            add(EditEntity("name", er.name, R.string.r_book_name))
            add(EditEntity("author", er.author, R.string.r_author))
            add(EditEntity("kind", er.kind, R.string.rule_book_kind))
            add(EditEntity("wordCount", er.wordCount, R.string.rule_word_count))
            add(EditEntity("lastChapter", er.lastChapter, R.string.rule_last_chapter))
            add(EditEntity("intro", er.intro, R.string.rule_book_intro))
            add(EditEntity("coverUrl", er.coverUrl, R.string.rule_cover_url))
            add(EditEntity("bookUrl", er.bookUrl, R.string.r_book_url))
        }
        // 详情页
        val ir = bs.getBookInfoRule()
        infoEntities.clear()
        infoEntities.apply {
            add(EditEntity("init", ir.init, R.string.rule_book_info_init))
            add(EditEntity("name", ir.name, R.string.r_book_name))
            add(EditEntity("author", ir.author, R.string.r_author))
            add(EditEntity("kind", ir.kind, R.string.rule_book_kind))
            add(EditEntity("wordCount", ir.wordCount, R.string.rule_word_count))
            add(EditEntity("lastChapter", ir.lastChapter, R.string.rule_last_chapter))
            add(EditEntity("intro", ir.intro, R.string.rule_book_intro))
            add(EditEntity("coverUrl", ir.coverUrl, R.string.rule_cover_url))
            add(EditEntity("tocUrl", ir.tocUrl, R.string.rule_toc_url))
            add(EditEntity("canReName", ir.canReName, R.string.rule_can_re_name))
            add(EditEntity("downloadUrls", ir.downloadUrls, R.string.download_url_rule))
        }
        // 目录页
        val tr = bs.getTocRule()
        tocEntities.clear()
        tocEntities.apply {
            add(EditEntity("preUpdateJs", tr.preUpdateJs, R.string.pre_update_js))
            add(EditEntity("chapterList", tr.chapterList, R.string.rule_chapter_list))
            add(EditEntity("chapterName", tr.chapterName, R.string.rule_chapter_name))
            add(EditEntity("chapterUrl", tr.chapterUrl, R.string.rule_chapter_url))
            add(EditEntity("formatJs", tr.formatJs, R.string.format_js_rule))
            add(EditEntity("isVolume", tr.isVolume, R.string.rule_is_volume))
            add(EditEntity("updateTime", tr.updateTime, R.string.rule_update_time))
            add(EditEntity("isVip", tr.isVip, R.string.rule_is_vip))
            add(EditEntity("isPay", tr.isPay, R.string.rule_is_pay))
            add(EditEntity("nextTocUrl", tr.nextTocUrl, R.string.rule_next_toc_url))
        }
        // 正文页
        val cr = bs.getContentRule()
        contentEntities.clear()
        contentEntities.apply {
            add(EditEntity("content", cr.content, R.string.rule_book_content))
            add(EditEntity("title", cr.title, R.string.rule_chapter_name))
            add(EditEntity("nextContentUrl", cr.nextContentUrl, R.string.rule_next_content))
            add(EditEntity("webJs", cr.webJs, R.string.rule_web_js))
            add(EditEntity("sourceRegex", cr.sourceRegex, R.string.rule_source_regex))
            add(EditEntity("replaceRegex", cr.replaceRegex, R.string.rule_replace_regex))
            add(EditEntity("imageStyle", cr.imageStyle, R.string.rule_image_style))
            add(EditEntity("imageDecode", cr.imageDecode, R.string.rule_image_decode))
            add(EditEntity("payAction", cr.payAction, R.string.rule_pay_action))
            add(EditEntity("callBackJs", cr.callBackJs, R.string.rule_call_back))
        }
        // 段评
        val rr = bs.ruleReview ?: ReviewRule()
        reviewEntities.clear()
        reviewEntities.apply {
            add(EditEntity("reviewSummaryUrl", rr.reviewSummaryUrl, R.string.rule_review_summary_url))
            add(EditEntity("summaryListRule", rr.summaryListRule, R.string.rule_review_summary_list))
            add(EditEntity("summaryParagraphIndexRule", rr.summaryParagraphIndexRule, R.string.rule_review_summary_id))
            add(EditEntity("summaryCountRule", rr.summaryCountRule, R.string.rule_review_summary_count))
            add(EditEntity("summaryParagraphDataRule", rr.summaryParagraphDataRule, R.string.rule_review_summary_key))

            add(EditEntity("reviewDetailUrl", rr.reviewDetailUrl, R.string.rule_review_detail_url))
            add(EditEntity("reviewDetailNextPageUrl", rr.reviewDetailNextPageUrl, R.string.rule_review_detail_next_url))
            add(EditEntity("detailListRule", rr.detailListRule, R.string.rule_review_detail_list))
            add(EditEntity("detailIdRule", rr.detailIdRule, R.string.rule_review_detail_id))
            add(EditEntity("detailAvatarRule", rr.detailAvatarRule, R.string.rule_review_detail_avatar))
            add(EditEntity("detailNameRule", rr.detailNameRule, R.string.rule_review_detail_name))
            add(EditEntity("detailBadgeRule", rr.detailBadgeRule, R.string.rule_review_detail_badge))
            add(EditEntity("detailContentRule", rr.detailContentRule, R.string.rule_review_detail_content))

            add(EditEntity("replyListRule", rr.replyListRule, R.string.rule_review_reply_list))
            add(EditEntity("replyIdRule", rr.replyIdRule, R.string.rule_review_reply_id))
            add(EditEntity("replyAvatarRule", rr.replyAvatarRule, R.string.rule_review_reply_avatar))
            add(EditEntity("replyNameRule", rr.replyNameRule, R.string.rule_review_reply_name))
            add(EditEntity("replyBadgeRule", rr.replyBadgeRule, R.string.rule_review_reply_badge))
            add(EditEntity("replyContentRule", rr.replyContentRule, R.string.rule_review_reply_content))
        }
        binding.tabLayout.selectTab(binding.tabLayout.getTabAt(0))
        setEditEntities(0)
    }

    private fun getSource(): BookSource {
        val source = viewModel.bookSource?.copy() ?: BookSource()
        source.enabled = binding.cbIsEnable.isChecked
        source.enabledExplore = binding.cbIsEnableExplore.isChecked
        source.enabledCookieJar = binding.cbIsEnableCookie.isChecked
        source.eventListener = binding.cbIsEventListener.isChecked
        source.customButton = binding.cbIsCustomButton.isChecked
        source.bookSourceType = when (binding.cgType.checkedChipId) {
            R.id.chip_type_file -> BookSourceType.file
            R.id.chip_type_image -> BookSourceType.image
            R.id.chip_type_audio -> BookSourceType.audio
            else -> BookSourceType.default
        }
        val searchRule = SearchRule()
        val exploreRule = ExploreRule()
        val bookInfoRule = BookInfoRule()
        val tocRule = TocRule()
        val contentRule = ContentRule()
        val reviewRule = source.ruleReview?.copy() ?: ReviewRule()
        reviewRule.enabled = binding.cbIsEnableReview.isChecked
        sourceEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "bookSourceUrl" -> source.bookSourceUrl = it.value ?: ""
                "bookSourceName" -> source.bookSourceName = it.value ?: ""
                "bookSourceGroup" -> source.bookSourceGroup = it.value
                "loginUrl" -> source.loginUrl = it.value
                "loginUi" -> source.loginUi = it.value
                "loginCheckJs" -> source.loginCheckJs = it.value
                "coverDecodeJs" -> source.coverDecodeJs = it.value
                "bookUrlPattern" -> source.bookUrlPattern = it.value
                "header" -> source.header = it.value
                "bookSourceComment" -> source.bookSourceComment = it.value
                "concurrentRate" -> source.concurrentRate = it.value
                "variableComment" -> source.variableComment = it.value
                "jsLib" -> source.jsLib = it.value
            }
        }
        searchEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "searchUrl" -> source.searchUrl = it.value
                "checkKeyWord" -> searchRule.checkKeyWord = it.value
                "bookList" -> searchRule.bookList = it.value
                "name" -> searchRule.name =
                    viewModel.ruleComplete(it.value, searchRule.bookList)

                "author" -> searchRule.author =
                    viewModel.ruleComplete(it.value, searchRule.bookList)

                "kind" -> searchRule.kind =
                    viewModel.ruleComplete(it.value, searchRule.bookList)

                "intro" -> searchRule.intro =
                    viewModel.ruleComplete(it.value, searchRule.bookList)

//                "updateTime" -> searchRule.updateTime =
//                    viewModel.ruleComplete(it.value, searchRule.bookList)

                "wordCount" -> searchRule.wordCount =
                    viewModel.ruleComplete(it.value, searchRule.bookList)

                "lastChapter" -> searchRule.lastChapter =
                    viewModel.ruleComplete(it.value, searchRule.bookList)

                "coverUrl" -> searchRule.coverUrl =
                    viewModel.ruleComplete(it.value, searchRule.bookList, 3)

                "bookUrl" -> searchRule.bookUrl =
                    viewModel.ruleComplete(it.value, searchRule.bookList, 2)
            }
        }
        exploreEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "exploreUrl" -> source.exploreUrl = it.value
                "bookList" -> exploreRule.bookList = it.value
                "name" -> exploreRule.name =
                    viewModel.ruleComplete(it.value, exploreRule.bookList)

                "author" -> exploreRule.author =
                    viewModel.ruleComplete(it.value, exploreRule.bookList)

                "kind" -> exploreRule.kind =
                    viewModel.ruleComplete(it.value, exploreRule.bookList)

                "intro" -> exploreRule.intro =
                    viewModel.ruleComplete(it.value, exploreRule.bookList)

//                "updateTime" -> exploreRule.updateTime =
//                    viewModel.ruleComplete(it.value, exploreRule.bookList)

                "wordCount" -> exploreRule.wordCount =
                    viewModel.ruleComplete(it.value, exploreRule.bookList)

                "lastChapter" -> exploreRule.lastChapter =
                    viewModel.ruleComplete(it.value, exploreRule.bookList)

                "coverUrl" -> exploreRule.coverUrl =
                    viewModel.ruleComplete(it.value, exploreRule.bookList, 3)

                "bookUrl" -> exploreRule.bookUrl =
                    viewModel.ruleComplete(it.value, exploreRule.bookList, 2)
            }
        }
        infoEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "init" -> bookInfoRule.init = it.value
                "name" -> bookInfoRule.name = viewModel.ruleComplete(it.value, bookInfoRule.init)
                "author" -> bookInfoRule.author =
                    viewModel.ruleComplete(it.value, bookInfoRule.init)

                "kind" -> bookInfoRule.kind =
                    viewModel.ruleComplete(it.value, bookInfoRule.init)

                "intro" -> bookInfoRule.intro =
                    viewModel.ruleComplete(it.value, bookInfoRule.init)

//                "updateTime" -> bookInfoRule.updateTime =
//                    viewModel.ruleComplete(it.value, bookInfoRule.init)

                "wordCount" -> bookInfoRule.wordCount =
                    viewModel.ruleComplete(it.value, bookInfoRule.init)

                "lastChapter" -> bookInfoRule.lastChapter =
                    viewModel.ruleComplete(it.value, bookInfoRule.init)

                "coverUrl" -> bookInfoRule.coverUrl =
                    viewModel.ruleComplete(it.value, bookInfoRule.init, 3)

                "tocUrl" -> bookInfoRule.tocUrl =
                    viewModel.ruleComplete(it.value, bookInfoRule.init, 2)

                "canReName" -> bookInfoRule.canReName = it.value
                "downloadUrls" -> bookInfoRule.downloadUrls =
                    viewModel.ruleComplete(it.value, bookInfoRule.init)
            }
        }
        tocEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "preUpdateJs" -> tocRule.preUpdateJs = it.value
                "chapterList" -> tocRule.chapterList = it.value
                "chapterName" -> tocRule.chapterName =
                    viewModel.ruleComplete(it.value, tocRule.chapterList)

                "chapterUrl" -> tocRule.chapterUrl =
                    viewModel.ruleComplete(it.value, tocRule.chapterList, 2)

                "formatJs" -> tocRule.formatJs = it.value
                "isVolume" -> tocRule.isVolume = it.value
                "updateTime" -> tocRule.updateTime = it.value
                "isVip" -> tocRule.isVip = it.value
                "isPay" -> tocRule.isPay = it.value
                "nextTocUrl" -> tocRule.nextTocUrl =
                    viewModel.ruleComplete(it.value, tocRule.chapterList, 2)
            }
        }
        contentEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "content" -> contentRule.content = viewModel.ruleComplete(it.value)
                "title" -> contentRule.title = viewModel.ruleComplete(it.value)
                "nextContentUrl" -> contentRule.nextContentUrl =
                    viewModel.ruleComplete(it.value, type = 2)

                "webJs" -> contentRule.webJs = it.value
                "sourceRegex" -> contentRule.sourceRegex = it.value
                "replaceRegex" -> contentRule.replaceRegex = it.value
                "imageStyle" -> contentRule.imageStyle = it.value
                "imageDecode" -> contentRule.imageDecode = it.value
                "payAction" -> contentRule.payAction = it.value
                "callBackJs" -> contentRule.callBackJs = it.value
            }
        }
        reviewEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "reviewSummaryUrl" -> reviewRule.reviewSummaryUrl = it.value
                "summaryListRule" -> reviewRule.summaryListRule = it.value
                "summaryParagraphIndexRule" -> reviewRule.summaryParagraphIndexRule = it.value
                "summaryParagraphDataRule" -> reviewRule.summaryParagraphDataRule = it.value
                "summaryCountRule" -> reviewRule.summaryCountRule = it.value
                "reviewDetailUrl" -> reviewRule.reviewDetailUrl = it.value
                "reviewDetailNextPageUrl" -> reviewRule.reviewDetailNextPageUrl = it.value
                "detailListRule" -> reviewRule.detailListRule = it.value
                "detailIdRule" -> reviewRule.detailIdRule = it.value
                "detailAvatarRule" -> reviewRule.detailAvatarRule = it.value
                "detailNameRule" -> reviewRule.detailNameRule = it.value
                "detailBadgeRule" -> reviewRule.detailBadgeRule = it.value
                "detailContentRule" -> reviewRule.detailContentRule = it.value
                "replyListRule" -> reviewRule.replyListRule = it.value
                "replyIdRule" -> reviewRule.replyIdRule = it.value
                "replyAvatarRule" -> reviewRule.replyAvatarRule = it.value
                "replyNameRule" -> reviewRule.replyNameRule = it.value
                "replyBadgeRule" -> reviewRule.replyBadgeRule = it.value
                "replyContentRule" -> reviewRule.replyContentRule = it.value
            }
        }
        source.ruleSearch = searchRule
        source.ruleExplore = exploreRule
        source.ruleBookInfo = bookInfoRule
        source.ruleToc = tocRule
        source.ruleContent = contentRule
        source.ruleReview = reviewRule
        return source
    }

    private fun alertGroups() {
        lifecycleScope.launch {
            val groups = withContext(IO) {
                appDb.bookSourceDao.allGroups()
            }
            selector(groups) { _, s, _ ->
                sendText(s)
            }
        }
    }

    override fun helpActions(): List<SelectItem<String>> {
        val helpActions = arrayListOf(
            SelectItem("插入URL参数", "urlOption"),
            SelectItem("书源教程", "ruleHelp"),
            SelectItem("js教程", "jsHelp"),
            SelectItem("正则教程", "regexHelp"),
        )
        val view = window.decorView.findFocus()
        if (view is EditText) {
            when (view.getTag(R.id.tag)) {
                "bookSourceGroup" -> {
                    helpActions.add(
                        SelectItem("插入分组", "addGroup")
                    )
                }

                else -> {
                    helpActions.add(
                        SelectItem("选择文件", "selectFile")
                    )
                }
            }
        }
        return helpActions
    }

    override fun onHelpActionSelect(action: String) {
        when (action) {
            "addGroup" -> alertGroups()
            "urlOption" -> UrlOptionDialog(this) { sendText(it) }.show()
            "ruleHelp" -> showHelp("ruleHelp")
            "jsHelp" -> showHelp("jsHelp")
            "regexHelp" -> showHelp("regexHelp")
            "selectFile" -> selectDoc.launch {
                mode = HandleFileContract.FILE
            }
        }
    }

    override fun sendText(text: String) {
        if (text.isBlank()) return
        val view = window.decorView.findFocus()
        if (view is EditText) {
            val start = view.selectionStart
            val end = view.selectionEnd
            val edit = view.editableText//获取EditText的文字
            if (start < 0 || start >= edit.length) {
                edit.append(text)
            } else if (start > end) {
                edit.replace(end, start, text)
            } else {
                edit.replace(start, end, text)//光标所在位置插入文字
            }
        }
    }

    private fun setSourceVariable() {
        viewModel.save(getSource()) { source ->
            lifecycleScope.launch {
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
    }

    override fun setVariable(key: String, variable: String?) {
        viewModel.bookSource?.setVariable(variable)
    }

    override fun onCodeSave(code: String, requestId: String?) {
        requestId ?: return
        val entity = unsafeEditRequests.remove(requestId)
            ?: findEntityByRequestId(requestId)
            ?: return
        entity.value = code
        val index = adapter.editEntities.indexOf(entity)
        if (index >= 0) {
            adapter.notifyItemChanged(index)
        }
    }

    /** Activity 重建后 requestId→entity 映射丢失，按 requestId 内编码的 tab:key 定位重建后的同名字段 */
    private fun findEntityByRequestId(requestId: String): EditEntity? {
        val parts = requestId.split(":", limit = 3)
        if (parts.size < 3) return null
        val entities = when (parts[0].toIntOrNull() ?: return null) {
            1 -> searchEntities
            2 -> exploreEntities
            3 -> infoEntities
            4 -> tocEntities
            5 -> contentEntities
            6 -> reviewEntities
            else -> sourceEntities
        }
        return entities.find { it.key == parts[1] }
    }

}
