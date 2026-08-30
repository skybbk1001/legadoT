package io.legado.app.ui.book.audio

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.slider.Slider
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.Status
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.databinding.ActivityAudioPlayBinding
import io.legado.app.databinding.DialogDownloadChoiceBinding
import io.legado.app.databinding.DialogMultipleEditTextBinding
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfig
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.help.motion.MotionTokens
import io.legado.app.help.motion.PressSpringEffect
import io.legado.app.help.motion.ShapeMorph
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.AudioCache
import io.legado.app.model.AudioPlay
import io.legado.app.model.BookCover
import io.legado.app.model.SourceCallBack
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.AudioPlayService
import io.legado.app.ui.widget.dialog.SleepTimerDialog
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.changesource.ChangeBookSourceDialog
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.applyAmbientBackground
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.invisible
import io.legado.app.utils.observeEvent
import io.legado.app.utils.observeEventSticky
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toDurationTime
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.views.onLongClick
import java.io.File
import java.util.Locale

/**
 * 音频播放
 */
@SuppressLint("ObsoleteSdkInt")
class AudioPlayActivity :
    VMBaseActivity<ActivityAudioPlayBinding, AudioPlayViewModel>(),
    ChangeBookSourceDialog.CallBack,
    AudioPlay.CallBack,
    SleepTimerDialog.CallBack {

    override val binding by viewBinding(ActivityAudioPlayBinding::inflate)
    override val viewModel by viewModels<AudioPlayViewModel>()
    private var adjustProgress = false
    private var playMode = AudioPlay.PlayMode.LIST_END_STOP
    private var pendingCacheAction: (() -> Unit)? = null
    private var menuCustomBtn: MenuItem? = null

    /** 播放键可 morph 的形状背景引用:见 initView 中 setupPlayButtonShape 的获取实况 */
    private var fabPlayShape: MaterialShapeDrawable? = null
    private var playShapeCornerFull = 0f

    // 氛围背景竞写守卫:换封面可能连续触发(load 回调异步),旧协程取消,只有最新一次落地
    private var ambientJob: Job? = null

    private val tocActivityResult = registerForActivityResult(TocActivityResult()) {
        it?.let {
            if (it.index != AudioPlay.book?.durChapterIndex
                || it.chapterPos == 0
            ) {
                AudioPlay.skipTo(it.index)
            }
        }
    }
    private val sourceEditResult =
        registerForActivityResult(StartActivityContract(BookSourceEditActivity::class.java)) {
            if (it.resultCode == RESULT_OK) {
                viewModel.upSource()
            }
        }
    private val audioCacheDirSelect = registerForActivityResult(HandleFileContract()) {
        val action = pendingCacheAction
        pendingCacheAction = null
        it.uri?.let { treeUri ->
            AppConfig.audioCacheTreeUri = treeUri.toString()
            toastOnUi(R.string.audio_cache_folder_selected)
            action?.invoke()
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.setBackgroundResource(R.color.transparent)
        AudioPlay.register(this)
        viewModel.titleData.observe(this) {
            binding.titleBar.title = it
        }
        viewModel.coverData.observe(this) {
            upCover(it)
        }
        viewModel.customBtnData.observe(this) { menuCustomBtn?.isVisible = it }
        viewModel.initData(intent)
        initView()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.audio_play, menu)
        menuCustomBtn = menu.findItem(R.id.menu_custom_btn)?.also {
            it.isVisible = viewModel.customBtnData.value == true
        }
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_login)?.isVisible = !AudioPlay.bookSource?.loginUrl.isNullOrBlank()
        menu.findItem(R.id.menu_wake_lock)?.isChecked = AppConfig.audioPlayUseWakeLock
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_change_source -> AudioPlay.book?.let {
                showDialogFragment(ChangeBookSourceDialog(it.name, it.author))
            }

            R.id.menu_custom_btn -> AudioPlay.book?.let { book ->
                SourceCallBack.callBackBtn(
                    this, SourceCallBack.CLICK_CUSTOM_BUTTON,
                    AudioPlay.bookSource, book, AudioPlay.durChapter
                )
            }

            R.id.menu_login -> AudioPlay.bookSource?.let {
                startActivity<SourceLoginActivity> {
                    putExtra("type", "bookSource")
                    putExtra("key", it.bookSourceUrl)
                    putExtra("bookUrl", AudioPlay.book?.bookUrl)
                    putExtra("durChapterIndex", AudioPlay.durChapterIndex)
                }
            }

            R.id.menu_wake_lock -> AppConfig.audioPlayUseWakeLock = !AppConfig.audioPlayUseWakeLock
            R.id.menu_copy_audio_url -> AudioPlay.book?.let { book ->
                val url = AudioPlayService.url
                // 如果书源注册了事件回调则先触发回调，noCall 作为兜底执行复制
                SourceCallBack.callBackBtn(
                    this, SourceCallBack.CLICK_COPY_PLAY_URL,
                    AudioPlay.bookSource, book, AudioPlay.durChapter,
                    url
                ) {
                    sendToClip(url)
                }
            } ?: sendToClip(AudioPlayService.url) // book 为空时直接复制
            R.id.menu_clear_current_audio_cache -> clearCurrentChapterCache()
            R.id.menu_edit_source -> AudioPlay.bookSource?.let {
                sourceEditResult.launch {
                    putExtra("sourceUrl", it.bookSourceUrl)
                }
            }

            R.id.menu_log -> showDialogFragment<AppLogDialog>()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initView() {
        binding.ivPlayMode.setOnClickListener {
            AudioPlay.changePlayMode()
        }
        binding.ivAudioSkip.setOnClickListener {
            showAudioSkipConfigDialog()
        }
        binding.ivAudioCache.setOnClickListener {
            showAudioCacheRangeDialog()
        }

        observeEventSticky<AudioPlay.PlayMode>(EventBus.PLAY_MODE_CHANGED) {
            playMode = it
            updatePlayModeIcon()
        }

        binding.fabPlayStop.setOnClickListener {
            playButton()
        }
        binding.fabPlayStop.onLongClick {
            AudioPlay.stop()
        }
        binding.ivSkipNext.setOnClickListener {
            AudioPlay.next()
        }
        binding.ivSkipPrevious.setOnClickListener {
            AudioPlay.prev()
        }
        binding.playerProgress.addOnChangeListener { _, value, fromUser ->
            if (fromUser) binding.tvDurTime.text = value.toInt().toDurationTime()
        }
        binding.playerProgress.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                adjustProgress = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                adjustProgress = false
                AudioPlay.adjustProgress(slider.value.toInt())
            }
        })
        binding.ivChapter.setOnClickListener {
            AudioPlay.book?.let {
                tocActivityResult.launch(it.bookUrl)
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            binding.ivFastRewind.invisible()
            binding.ivFastForward.invisible()
        }
        binding.ivFastForward.setOnClickListener {
            AudioPlay.adjustSpeed(0.1f)
        }
        binding.ivFastRewind.setOnClickListener {
            AudioPlay.adjustSpeed(-0.1f)
        }
        binding.ivTimer.setOnClickListener {
            showDialogFragment(SleepTimerDialog())
        }
        updateAudioSkipButtonState()
        binding.llPlayMenu.applyNavigationBarPadding()
        setupPlayButtonShape()
        attachTransportSpring()
    }

    /**
     * 播放键 morph 引用获取实况:MaterialButton filled 背景在 inflate 后是 RippleDrawable
     * 包裹 MaterialShapeDrawable(其 backgroundTintList 已按 backgroundTint 施 primary)。
     * 直接改其 shapeAppearanceModel 会被 ripple 层遮蔽,故取 ripple 内层 content 的
     * MaterialShapeDrawable 作为可 morph 引用;取不到时兜底自建一只并回填为按钮背景。
     */
    private fun setupPlayButtonShape() {
        val fab = binding.fabPlayStop
        val model = ShapeAppearanceModel.builder()
            .setAllCorners(CornerFamily.ROUNDED, 999f)
            .build()
        val shape = ((fab.background as? RippleDrawable)
            ?.let { rd ->
                (0 until rd.numberOfLayers)
                    .map { rd.getDrawable(it) }
                    .firstOrNull { it is MaterialShapeDrawable } as? MaterialShapeDrawable
            } ?: (fab.background as? MaterialShapeDrawable))
            ?: MaterialShapeDrawable(model).also { sd ->
                sd.fillColor = ColorStateList.valueOf(fab.context.primaryColor)
                fab.background = sd
            }
        shape.shapeAppearanceModel = model
        fabPlayShape = shape
        // full 圆的 cornerSize=真实半边(M3 clamp 上限≈半边;首帧宽未测得时用 post 补测,
        // 否则兜底 999 会被 clamp 到半边→morph 起点(999)与终点(半边)渲染同形,尾部截断)
        fab.post {
            playShapeCornerFull = fab.width.coerceAtLeast(fab.height).let {
                if (it > 0) it / 2f else 999f
            }
            shape.setCornerSize(playShapeCornerFull)
        }
        shape.setCornerSize(999f)
    }

    private fun attachTransportSpring() {
        listOf(
            binding.ivSkipPrevious, binding.ivSkipNext,
            binding.ivFastRewind, binding.ivFastForward,
            binding.ivPlayMode, binding.ivAudioSkip, binding.ivAudioCache,
            binding.ivTimer, binding.ivChapter
        ).forEach { PressSpringEffect.attach(it) }
    }

    /** 播放态圆↔暂停态圆角方:走 MotionTokens 门,关动效直接落终值 */
    private fun morphPlayShape(toPause: Boolean) {
        val shape = fabPlayShape ?: return
        val full = playShapeCornerFull.takeIf { it > 0 } ?: (binding.fabPlayStop.width / 2f)
        val pauseCorner = binding.fabPlayStop.context.resources.getDimension(R.dimen.radius_l)
        val from = shape.topLeftCornerResolvedSize
        val to = if (toPause) pauseCorner else full
        if (!MotionTokens.enabled) {
            shape.setCornerSize(to)
            return
        }
        ShapeMorph.animateCornerSize(shape, from, to, this)
    }

    private fun updatePlayModeIcon() {
        binding.ivPlayMode.setImageResource(playMode.iconRes)
    }

    private fun upCover(path: String?) {
        BookCover.load(this, path, sourceOrigin = AudioPlay.bookSource?.bookSourceUrl,
            name = AudioPlay.book?.name) {
            binding.ivCover.post {
                ambientJob?.cancel()
                ambientJob = binding.root.applyAmbientBackground(
                    binding.ivCover.drawable, lifecycleScope,
                    io.legado.app.utils.AmbientIntensity.IMMERSIVE,
                ) { isDestroyed }
            }
        }.into(binding.ivCover)
    }

    private fun playButton() {
        when (AudioPlay.status) {
            Status.PLAY -> AudioPlay.pause(this)
            Status.PAUSE -> AudioPlay.resume(this)
            else -> AudioPlay.loadOrUpPlayUrl()
        }
    }

    override val oldBook: Book?
        get() = AudioPlay.book

    override fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>) {
        if (book.isAudio) {
            viewModel.changeTo(source, book, toc)
        } else {
            AudioPlay.stop()
            lifecycleScope.launch {
                withContext(IO) {
                    AudioPlay.book?.migrateTo(book, toc)
                    book.removeType(BookType.updateError)
                    AudioPlay.book?.delete()
                    appDb.bookDao.insert(book)
                }
                startActivityForBook(book)
                finish()
            }
        }
    }

    override fun finish() {
        val book = AudioPlay.book ?: return super.finish()

        if (AudioPlay.inBookshelf) {
            SourceCallBack.callBackBook(
                SourceCallBack.END_READ, AudioPlay.bookSource,
                book, AudioPlay.durChapter
            )
            return super.finish()
        }

        if (!AppConfig.showAddToShelfAlert) {
            SourceCallBack.callBackBook(
                SourceCallBack.END_READ, AudioPlay.bookSource,
                book, AudioPlay.durChapter
            )
            viewModel.removeFromBookshelf { super.finish() }
        } else {
            alert(title = getString(R.string.add_to_bookshelf)) {
                setMessage(getString(R.string.check_add_bookshelf, book.name))
                okButton {
                    AudioPlay.book?.removeType(BookType.notShelf)
                    AudioPlay.book?.save()
                    AudioPlay.inBookshelf = true
                    SourceCallBack.callBackBook(
                        SourceCallBack.ADD_BOOK_SHELF, AudioPlay.bookSource, book
                    )
                    SourceCallBack.callBackBook(
                        SourceCallBack.END_READ, AudioPlay.bookSource,
                        book, AudioPlay.durChapter
                    )
                    setResult(RESULT_OK)
                }
                noButton {
                    SourceCallBack.callBackBook(
                        SourceCallBack.END_READ, AudioPlay.bookSource,
                        book, AudioPlay.durChapter
                    )
                    viewModel.removeFromBookshelf { super.finish() }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (AudioPlay.status != Status.PLAY) {
            AudioPlay.stop()
        }
        AudioPlay.unregister(this)
    }

    @SuppressLint("SetTextI18n")
    override fun observeLiveBus() {
        observeEvent<Boolean>(EventBus.MEDIA_BUTTON) {
            if (it) {
                playButton()
            }
        }
        observeEventSticky<Int>(EventBus.AUDIO_STATE) {
            AudioPlay.status = it
            if (it == Status.PLAY) {
                binding.fabPlayStop.setIconResource(R.drawable.ic_pause_24dp)
                morphPlayShape(toPause = true)
            } else {
                binding.fabPlayStop.setIconResource(R.drawable.ic_play_24dp)
                morphPlayShape(toPause = false)
            }
        }
        observeEventSticky<String>(EventBus.AUDIO_SUB_TITLE) {
            binding.tvSubTitle.text = it
            val chapterSize = AudioPlay.simulatedChapterSize
            binding.tvChapterIndex.visible(chapterSize > 0)
            if (chapterSize > 0) {
                binding.tvChapterIndex.text = getString(
                    R.string.audio_chapter_progress, AudioPlay.durChapterIndex + 1, chapterSize
                )
            }
            binding.ivSkipPrevious.isEnabled = AudioPlay.durChapterIndex > 0
            binding.ivSkipNext.isEnabled =
                AudioPlay.durChapterIndex < AudioPlay.simulatedChapterSize - 1
            updateAudioSkipButtonState()
        }
        observeEventSticky<Int>(EventBus.AUDIO_SIZE) {
            binding.playerProgress.valueTo = it.coerceAtLeast(1).toFloat()
            binding.tvAllTime.text = it.toDurationTime()
        }
        observeEventSticky<Int>(EventBus.AUDIO_PROGRESS) {
            if (!adjustProgress) {
                binding.playerProgress.value =
                    it.toFloat().coerceIn(binding.playerProgress.valueFrom, binding.playerProgress.valueTo)
            }
            binding.tvDurTime.text = it.toDurationTime()
        }
        observeEventSticky<Int>(EventBus.AUDIO_BUFFER_PROGRESS) {
            // Slider 无二级进度语义,缓冲进度不再单独绘制
        }
        observeEventSticky<Float>(EventBus.AUDIO_SPEED) {
            if (it == 1f) {
                binding.tvSpeed.invisible()
            } else {
                binding.tvSpeed.text = String.format(Locale.ROOT, "%.1fX", it)
                binding.tvSpeed.visible()
            }
        }
        observeEventSticky<Int>(EventBus.AUDIO_DS) { upTimerText() }
        observeEventSticky<Int>(EventBus.AUDIO_CHAPTER) { upTimerText() }
    }

    /** 定时/集数停止状态显示:集数优先, 其次时间, 都无则隐藏 */
    private fun upTimerText() {
        val chapter = AudioPlayService.chapterToStop
        val minute = AudioPlayService.timeMinute
        when {
            chapter > 0 -> {
                binding.tvTimer.text = getString(R.string.audio_stop_chapters, chapter)
                binding.tvTimer.visible(true)
            }

            minute > 0 -> {
                binding.tvTimer.text = getString(R.string.timer_m, minute)
                binding.tvTimer.visible(true)
            }

            else -> binding.tvTimer.visible(false)
        }
    }

    override fun onSleepTimerMinute(minute: Int) {
        AudioPlay.setTimer(minute)
    }

    override fun onSleepTimerChapter(count: Int) {
        AudioPlay.setChapterStop(count)
    }

    override fun upLoading(loading: Boolean) {
        runOnUiThread {
            binding.progressLoading.visible(loading)
        }
    }

    private fun showAudioSkipConfigDialog() {
        val book = AudioPlay.book ?: return
        alert(titleResource = R.string.audio_skip_config) {
            val alertBinding = DialogMultipleEditTextBinding.inflate(layoutInflater).apply {
                layout1.hint = getString(R.string.audio_skip_intro_seconds)
                edit1.inputType = InputType.TYPE_CLASS_NUMBER
                edit1.setText((book.getAudioIntroMs() / 1000).toString())
                layout2.hint = getString(R.string.audio_skip_outro_seconds)
                layout2.visible()
                edit2.inputType = InputType.TYPE_CLASS_NUMBER
                edit2.setText((book.getAudioOutroMs() / 1000).toString())
            }
            customView { alertBinding.root }
            okButton {
                val introMs = parseSecondsToMs(
                    alertBinding.edit1.text?.toString(),
                    book.getAudioIntroMs()
                )
                val outroMs = parseSecondsToMs(
                    alertBinding.edit2.text?.toString(),
                    book.getAudioOutroMs()
                )
                saveBookAudioSkipConfig(book, introMs, outroMs)
            }
            neutralButton(R.string.general) {
                val introMs = parseSecondsToMs(
                    alertBinding.edit1.text?.toString(),
                    AppConfig.audioSkipIntroMs
                )
                val outroMs = parseSecondsToMs(
                    alertBinding.edit2.text?.toString(),
                    AppConfig.audioSkipOutroMs
                )
                saveGlobalAudioSkipConfig(introMs, outroMs)
            }
            cancelButton()
        }
    }

    private fun saveBookAudioSkipConfig(book: Book, introMs: Int, outroMs: Int) {
        lifecycleScope.launch(IO) {
            book.setAudioIntroMs(introMs)
            book.setAudioOutroMs(outroMs)
            book.setAudioSkipEnabled(introMs > 0 || outroMs > 0)
            book.save()
            withContext(Main) {
                updateAudioSkipButtonState()
                toastOnUi(R.string.audio_skip_saved_for_book)
            }
        }
    }

    private fun saveGlobalAudioSkipConfig(introMs: Int, outroMs: Int) {
        AppConfig.audioSkipIntroMs = introMs
        AppConfig.audioSkipOutroMs = outroMs
        AppConfig.audioSkipEnabled = introMs > 0 || outroMs > 0
        updateAudioSkipButtonState()
        toastOnUi(R.string.audio_skip_saved_as_global)
    }

    private fun parseSecondsToMs(value: String?, defaultMs: Int): Int {
        val sec = value?.trim()?.toLongOrNull() ?: return defaultMs
        return (sec.coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE / 1000L) * 1000L).toInt()
    }

    private fun showAudioCacheRangeDialog() {
        val book = AudioPlay.book ?: return
        val chapterSize = AudioPlay.simulatedChapterSize
        if (chapterSize <= 0) {
            toastOnUi(R.string.no_chapter)
            return
        }
        alert(titleResource = R.string.audio_cache_notification_title) {
            val alertBinding = DialogDownloadChoiceBinding.inflate(layoutInflater).apply {
                editStart.setText((AudioPlay.durChapterIndex + 1).toString())
                editEnd.setText(chapterSize.toString())
            }
            customView { alertBinding.root }
            okButton {
                val start = parseChapterOrder(
                    value = alertBinding.editStart.text?.toString(),
                    defaultValue = AudioPlay.durChapterIndex + 1,
                    maxChapter = chapterSize
                )
                val end = parseChapterOrder(
                    value = alertBinding.editEnd.text?.toString(),
                    defaultValue = chapterSize,
                    maxChapter = chapterSize
                )
                if (start > end) {
                    toastOnUi(R.string.error_scope_input)
                    return@okButton
                }
                ensureAudioCacheDir {
                    AudioCache.cacheRange(this@AudioPlayActivity, book.bookUrl, start - 1, end - 1)
                    toastOnUi(R.string.audio_cache_start_range)
                }
            }
            cancelButton()
        }
    }

    private fun parseChapterOrder(value: String?, defaultValue: Int, maxChapter: Int): Int {
        val max = maxChapter.coerceAtLeast(1)
        val parsed = value?.trim()?.toIntOrNull() ?: defaultValue
        return parsed.coerceIn(1, max)
    }

    private fun ensureAudioCacheDir(onReady: () -> Unit) {
        if (AudioCache.hasCacheDirConfigured() && AudioCache.isCacheDirAvailable()) {
            onReady()
            return
        }
        if (AudioCache.hasCacheDirConfigured()) {
            toastOnUi(R.string.audio_cache_folder_invalid)
        } else {
            toastOnUi(R.string.audio_cache_folder_not_set)
        }
        pendingCacheAction = onReady
        audioCacheDirSelect.launch {
            title = getString(R.string.audio_cache_select_folder)
            mode = HandleFileContract.DIR_SYS
        }
    }

    private fun updateAudioSkipButtonState() {
        val enabled = AudioPlay.book?.getAudioSkipEnabled() == true
        binding.ivAudioSkip.alpha = if (enabled) 1f else 0.55f
        val introSeconds = ((AudioPlay.book?.getAudioIntroMs() ?: 0) / 1000).toString()
        val outroSeconds = ((AudioPlay.book?.getAudioOutroMs() ?: 0) / 1000).toString()
        binding.ivAudioSkip.contentDescription =
            getString(R.string.audio_skip_config_summary, introSeconds, outroSeconds)
    }

    private fun clearCurrentChapterCache() {
        val book = AudioPlay.book ?: return
        val chapter = AudioPlay.durChapter
        val chapterIndex = AudioPlay.durChapterIndex
        val source = AudioPlay.bookSource
        AudioPlay.skipCacheOnce(book.bookUrl, chapterIndex)
        AudioPlay.clearChapterPlayUrlPreload(book.bookUrl, chapterIndex)
        lifecycleScope.launch(IO) {
            val fileCacheRemoved = AudioCache.removeCachedChapter(book.bookUrl, chapterIndex)
            var playerCacheRemoved = false
            val candidateUrls = linkedSetOf<String>()
            collectPlayerCacheCandidate(candidateUrls, AudioPlayService.url)
            collectPlayerCacheCandidate(candidateUrls, AudioPlay.durPlayUrl)
            if (candidateUrls.isEmpty() && source != null && chapter != null && !chapter.isVolume) {
                kotlin.runCatching {
                    WebBook.getContentAwait(source, book, chapter, needSave = false)
                }.getOrNull()?.let {
                    collectPlayerCacheCandidate(candidateUrls, it)
                }
            }
            for (rawUrl in candidateUrls) {
                playerCacheRemoved = ExoPlayerHelper.clearCacheByPlaybackUrl(rawUrl) || playerCacheRemoved
                val resolvedUrl = kotlin.runCatching {
                    AnalyzeUrl(
                        mUrl = rawUrl,
                        source = source,
                        ruleData = book,
                        chapter = chapter
                    ).url
                }.getOrNull()
                if (!resolvedUrl.isNullOrBlank()) {
                    playerCacheRemoved =
                        ExoPlayerHelper.clearCacheByPlaybackUrl(resolvedUrl) || playerCacheRemoved
                }
            }
            withContext(Main) {
                AudioPlay.durPlayUrl = ""
                if (fileCacheRemoved || playerCacheRemoved) {
                    toastOnUi(R.string.audio_cache_current_chapter_cleared)
                } else {
                    toastOnUi(R.string.audio_cache_current_chapter_not_found)
                }
            }
        }
    }

    private fun collectPlayerCacheCandidate(target: MutableSet<String>, rawUrl: String?) {
        val value = rawUrl?.trim().orEmpty()
        if (value.isEmpty()) return
        if (isLikelyLocalUrl(value)) return
        target.add(value)
    }

    private fun isLikelyLocalUrl(url: String): Boolean {
        return url.startsWith("content://", true)
                || url.startsWith("file:", true)
                || url.startsWith("/", false)
                || File(url).exists()
    }

}
