package io.legado.app.model

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.Status
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReadRecord
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.readSimulating
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.globalExecutor
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.AudioPlayService
import io.legado.app.utils.postEvent
import io.legado.app.utils.startService
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancelChildren
import splitties.init.appCtx
import io.legado.app.data.entities.getDisplayTitle
import io.legado.app.data.entities.getUseReplaceRule

@SuppressLint("StaticFieldLeak")
@Suppress("unused")
object AudioPlay : CoroutineScope by MainScope() {
    private data class PreloadedPlayUrl(
        val key: String,
        val url: String
    )

    /**
     * 播放模式枚举
     */
    enum class PlayMode(val iconRes: Int) {
        LIST_END_STOP(R.drawable.ic_play_mode_list_end_stop),
        SINGLE_LOOP(R.drawable.ic_play_mode_single_loop),
        RANDOM(R.drawable.ic_play_mode_random),
        LIST_LOOP(R.drawable.ic_play_mode_list_loop);

        fun next(): PlayMode {
            return when (this) {
                LIST_END_STOP -> SINGLE_LOOP
                SINGLE_LOOP -> RANDOM
                RANDOM -> LIST_LOOP
                LIST_LOOP -> LIST_END_STOP
            }
        }
    }

    var playMode = PlayMode.LIST_END_STOP
    var status = Status.STOP
    private var activityContext: Context? = null
    private var serviceContext: Context? = null
    private val context: Context get() = activityContext ?: serviceContext ?: appCtx
    var callback: CallBack? = null
    var book: Book? = null
    var chapterSize = 0
    var simulatedChapterSize = 0
    var durChapterIndex = 0
    var durChapterPos = 0
    var durChapter: BookChapter? = null
    var durPlayUrl = ""
    var durAudioSize = 0
    var inBookshelf = false
    var bookSource: BookSource? = null
        private set
    val loadingChapters = arrayListOf<Int>()
    private val skipCacheOnceKeys = hashSetOf<String>()
    private var preloadedPlayUrl: PreloadedPlayUrl? = null
    private val preloadingPlayUrlKeys = hashSetOf<String>()
    private val invalidatedPreloadKeys = hashSetOf<String>()
    private var preloadSessionId = 0L
    private var readRecord = ReadRecord()
    /** 听书走时起点;null=未在走时 */
    private var readStartTime: Long? = null

    fun changePlayMode() {
        playMode = playMode.next()
        book?.let { b ->
            b.setAudioPlayMode(playMode.ordinal)
            Coroutine.async { b.update() }
        }
        postEvent(EventBus.PLAY_MODE_CHANGED, playMode)
    }

    fun savePlaySpeed(speed: Float) {
        val book = book ?: return
        book.setAudioPlaySpeed(speed)
        Coroutine.async { book.update() }
    }

    /** 开始走时;重复调用无副作用 */
    fun markReadTimeStart() {
        if (readStartTime == null) {
            readStartTime = System.currentTimeMillis()
        }
    }

    /** 结算听书时长入阅读记录;未在走时调用无副作用 */
    fun upReadTime() {
        val start = readStartTime ?: return
        readStartTime = null
        if (!AppConfig.enableReadRecord) {
            return
        }
        val record = readRecord
        val elapsed = System.currentTimeMillis() - start
        globalExecutor.execute {
            record.readTime += elapsed
            record.lastRead = System.currentTimeMillis()
            appDb.readRecordDao.insert(record)
        }
    }

    fun upData(book: Book) {
        val oldBookUrl = AudioPlay.book?.bookUrl
        AudioPlay.book = book
        if (oldBookUrl != book.bookUrl) {
            clearPlayUrlPreloadCache()
        }
        chapterSize = appDb.bookChapterDao.getChapterCount(book.bookUrl)
        simulatedChapterSize = if (book.readSimulating()) {
            book.simulatedTotalChapterNum()
        } else {
            chapterSize
        }
        if (durChapterIndex != book.durChapterIndex) {
            stopPlay()
            durChapterIndex = book.durChapterIndex
            durChapterPos = book.durChapterPos
            durPlayUrl = ""
            durAudioSize = 0
        }
        upDurChapter()
    }

    fun resetData(book: Book) {
        stop()
        upReadTime()
        AudioPlay.book = book
        readRecord = ReadRecord(
            bookName = book.name,
            readTime = appDb.readRecordDao.getReadTime(book.name) ?: 0
        )
        chapterSize = appDb.bookChapterDao.getChapterCount(book.bookUrl)
        simulatedChapterSize = if (book.readSimulating()) {
            book.simulatedTotalChapterNum()
        } else {
            chapterSize
        }
        setBookSource(book.getBookSource())
        durChapterIndex = book.durChapterIndex
        durChapterPos = book.durChapterPos
        playMode = book.getAudioPlayMode()?.let { PlayMode.entries.getOrNull(it) }
            ?: PlayMode.LIST_END_STOP
        postEvent(EventBus.PLAY_MODE_CHANGED, playMode)
        postEvent(EventBus.AUDIO_SPEED, book.getAudioPlaySpeed())
        durPlayUrl = ""
        durAudioSize = 0
        upDurChapter()
        postEvent(EventBus.AUDIO_BUFFER_PROGRESS, 0)
    }

    fun setBookSource(source: BookSource?) {
        bookSource = source
        clearPlayUrlPreloadCache()
    }

    private fun addLoading(index: Int): Boolean {
        synchronized(this) {
            if (loadingChapters.contains(index)) return false
            loadingChapters.add(index)
            return true
        }
    }

    private fun removeLoading(index: Int) {
        synchronized(this) {
            loadingChapters.remove(index)
        }
    }

    fun skipCacheOnce(bookUrl: String, chapterIndex: Int) {
        synchronized(this) {
            skipCacheOnceKeys.add(skipCacheKey(bookUrl, chapterIndex))
        }
    }

    fun clearPlayUrlPreloadCache() {
        synchronized(this) {
            preloadedPlayUrl = null
            preloadingPlayUrlKeys.clear()
            invalidatedPreloadKeys.clear()
            preloadSessionId++
        }
    }

    fun clearChapterPlayUrlPreload(bookUrl: String, chapterIndex: Int, skipOnce: Boolean = true) {
        val key = skipCacheKey(bookUrl, chapterIndex)
        synchronized(this) {
            if (preloadedPlayUrl?.key == key) {
                preloadedPlayUrl = null
            }
            preloadingPlayUrlKeys.remove(key)
            if (skipOnce) {
                invalidatedPreloadKeys.add(key)
            }
        }
    }

    private fun consumeSkipCacheOnce(bookUrl: String, chapterIndex: Int): Boolean {
        synchronized(this) {
            return skipCacheOnceKeys.remove(skipCacheKey(bookUrl, chapterIndex))
        }
    }

    private fun skipCacheKey(bookUrl: String, chapterIndex: Int): String {
        return "$bookUrl#$chapterIndex"
    }

    private fun consumePreloadedPlayUrl(bookUrl: String, chapterIndex: Int): String? {
        val key = skipCacheKey(bookUrl, chapterIndex)
        synchronized(this) {
            if (invalidatedPreloadKeys.remove(key)) {
                if (preloadedPlayUrl?.key == key) {
                    preloadedPlayUrl = null
                }
                return null
            }
            if (preloadedPlayUrl?.key == key) {
                val url = preloadedPlayUrl?.url
                preloadedPlayUrl = null
                return url
            }
            return null
        }
    }

    fun loadOrUpPlayUrl() {
        if (durPlayUrl.isEmpty()) {
            loadPlayUrl()
        } else {
            upPlayUrl()
        }
    }

    /**
     * 加载播放URL
     */
    private fun loadPlayUrl() {
        val index = durChapterIndex
        if (addLoading(index)) {
            val book = book
            if (book == null) {
                removeLoading(index)
                appCtx.toastOnUi("书籍为空")
                return
            }
            upDurChapter()
            if (!consumeSkipCacheOnce(book.bookUrl, index)) {
                AudioCache.getCachedUriString(book.bookUrl, index)?.let { cachedUri ->
                    durPlayUrl = cachedUri
                    upPlayUrl()
                    removeLoading(index)
                    preloadNextPlayUrl(index)
                    return
                }
            }
            consumePreloadedPlayUrl(book.bookUrl, index)?.let { preloadedUrl ->
                durPlayUrl = preloadedUrl
                upPlayUrl()
                removeLoading(index)
                preloadNextPlayUrl(index)
                return
            }
            val bookSource = bookSource
            if (bookSource == null) {
                removeLoading(index)
                appCtx.toastOnUi("书源为空")
                return
            }
            val chapter = durChapter
            if (chapter == null) {
                removeLoading(index)
                return
            }
            if (chapter.isVolume) {
                skipTo(index + 1)
                removeLoading(index)
                return
            }
            upLoading(true)
            WebBook.getContent(this, bookSource, book, chapter, needSave = false)
                .onSuccess { content ->
                    val playUrl = content.trim()
                    if (playUrl.isEmpty()) {
                        appCtx.toastOnUi("未获取到资源链接")
                    } else {
                        contentLoadFinish(chapter, playUrl)
                    }
                }.onError {
                    AppLog.put("获取资源链接出错\n$it", it, true)
                    upLoading(false)
                }.onCancel {
                    removeLoading(index)
                }.onFinally {
                    removeLoading(index)
                }
        }
    }

    private fun preloadNextPlayUrl(currentIndex: Int) {
        val book = book ?: return
        val bookSource = bookSource ?: return
        val sourceUrl = bookSource.bookSourceUrl
        val nextChapter = findNextPlayableChapter(book.bookUrl, currentIndex + 1) ?: return
        val nextIndex = nextChapter.index
        if (AudioCache.getCachedUriString(book.bookUrl, nextIndex) != null) {
            clearChapterPlayUrlPreload(book.bookUrl, nextIndex, skipOnce = false)
            return
        }
        val key = skipCacheKey(book.bookUrl, nextIndex)
        val sessionId: Long
        synchronized(this) {
            if (invalidatedPreloadKeys.contains(key)) return
            if (preloadedPlayUrl?.key == key) return
            if (!preloadingPlayUrlKeys.add(key)) return
            sessionId = preloadSessionId
        }
        WebBook.getContent(this, bookSource, book, nextChapter, needSave = false)
            .onSuccess { content ->
                if (AudioPlay.book?.bookUrl != book.bookUrl) return@onSuccess
                if (AudioPlay.bookSource?.bookSourceUrl != sourceUrl) return@onSuccess
                val nextPlayUrl = content.trim()
                if (nextPlayUrl.isNotEmpty()) {
                    synchronized(AudioPlay) {
                        if (preloadSessionId != sessionId) return@synchronized
                        if (!invalidatedPreloadKeys.contains(key)) {
                            preloadedPlayUrl = PreloadedPlayUrl(key, nextPlayUrl)
                        }
                    }
                }
            }.onError {
                AppLog.put("预加载播放链接失败 ${book.name}#$nextIndex", it)
            }.onFinally {
                synchronized(AudioPlay) {
                    preloadingPlayUrlKeys.remove(key)
                }
            }
    }

    private fun findNextPlayableChapter(bookUrl: String, startIndex: Int): BookChapter? {
        var index = startIndex
        while (index in 0..<simulatedChapterSize) {
            val chapter = appDb.bookChapterDao.getChapter(bookUrl, index) ?: return null
            if (!chapter.isVolume) {
                return chapter
            }
            index++
        }
        return null
    }

    /**
     * 加载完成
     */
    private fun contentLoadFinish(chapter: BookChapter, content: String) {
        if (chapter.index == book?.durChapterIndex) {
            durPlayUrl = content
            upPlayUrl()
            preloadNextPlayUrl(chapter.index)
        }
    }

    private fun upPlayUrl() {
        if (isPlayToEnd()) {
            playNew()
        } else {
            play()
        }
    }

    /**
     * 播放当前章节
     */
    fun play() {
        context.startService<AudioPlayService> {
            action = IntentAction.play
        }
    }

    /**
     * 从头播放新章节
     */
    private fun playNew() {
        context.startService<AudioPlayService> {
            action = IntentAction.playNew
        }
    }

    /**
     * 更新当前章节
     */
    fun upDurChapter() {
        val book = book ?: return
        durChapter = appDb.bookChapterDao.getChapter(book.bookUrl, durChapterIndex)
        durAudioSize = durChapter?.end?.toInt() ?: 0
        val title = durChapter?.title ?: appCtx.getString(R.string.data_loading)
        postEvent(EventBus.AUDIO_SUB_TITLE, title)
        postEvent(EventBus.AUDIO_SIZE, durAudioSize)
        postEvent(EventBus.AUDIO_PROGRESS, durChapterPos)
        SourceCallBack.callBackBook(
            SourceCallBack.START_READ, bookSource, book, durChapter
        )
    }

    fun pause(context: Context) {
        if (AudioPlayService.isRun) {
            context.startService<AudioPlayService> {
                action = IntentAction.pause
            }
        }
    }

    fun resume(context: Context) {
        if (AudioPlayService.isRun) {
            context.startService<AudioPlayService> {
                action = IntentAction.resume
            }
        }
    }

    fun stop() {
        if (AudioPlayService.isRun) {
            context.startService<AudioPlayService> {
                action = IntentAction.stop
            }
        }
    }

    fun adjustSpeed(adjust: Float) {
        if (AudioPlayService.isRun) {
            context.startService<AudioPlayService> {
                action = IntentAction.adjustSpeed
                putExtra("adjust", adjust)
            }
        }
    }

    fun adjustProgress(position: Int) {
        durChapterPos = position
        saveRead()
        if (AudioPlayService.isRun) {
            context.startService<AudioPlayService> {
                action = IntentAction.adjustProgress
                putExtra("position", position)
            }
        }
    }

    fun skipTo(index: Int) {
        Coroutine.async {
            stopPlay()
            if (index in 0..<simulatedChapterSize) {
                durChapterIndex = index
                durChapterPos = 0
                durPlayUrl = ""
                saveRead()
                loadPlayUrl()
            }
        }
    }

    fun prev() {
        Coroutine.async {
            stopPlay()
            if (durChapterIndex > 0) {
                durChapterIndex -= 1
                durChapterPos = 0
                durPlayUrl = ""
                saveRead()
                loadPlayUrl()
            }
        }
    }

    fun next() {
        stopPlay()
        when (playMode) {
            PlayMode.LIST_END_STOP -> {
                if (durChapterIndex + 1 < simulatedChapterSize) {
                    durChapterIndex += 1
                    durChapterPos = 0
                    durPlayUrl = ""
                    saveRead()
                    loadPlayUrl()
                }
            }

            PlayMode.SINGLE_LOOP -> {
                durChapterPos = 0
                durPlayUrl = ""
                saveRead()
                loadPlayUrl()
            }

            PlayMode.RANDOM -> {
                durChapterIndex = (0 until simulatedChapterSize).random()
                durChapterPos = 0
                durPlayUrl = ""
                saveRead()
                loadPlayUrl()
            }

            PlayMode.LIST_LOOP -> {
                durChapterIndex = (durChapterIndex + 1) % simulatedChapterSize
                durChapterPos = 0
                durPlayUrl = ""
                saveRead()
                loadPlayUrl()
            }
        }
    }

    fun setTimer(minute: Int) {
        if (AudioPlayService.isRun) {
            val intent = Intent(context, AudioPlayService::class.java)
            intent.action = IntentAction.setTimer
            intent.putExtra("minute", minute)
            context.startService(intent)
        } else {
            AudioPlayService.timeMinute = minute
            postEvent(EventBus.AUDIO_DS, minute)
        }
    }

    fun setChapterStop(count: Int) {
        if (AudioPlayService.isRun) {
            val intent = Intent(context, AudioPlayService::class.java)
            intent.action = IntentAction.setChapterStop
            intent.putExtra("count", count)
            context.startService(intent)
        } else {
            AudioPlayService.chapterToStop = count
            postEvent(EventBus.AUDIO_CHAPTER, count)
        }
    }

    fun addTimer() {
        val intent = Intent(context, AudioPlayService::class.java)
        intent.action = IntentAction.addTimer
        context.startService(intent)
    }

    fun stopPlay() {
        if (AudioPlayService.isRun) {
            context.startService<AudioPlayService> {
                action = IntentAction.stopPlay
            }
        }
    }

    fun saveRead() {
        val book = book ?: return
        Coroutine.async {
            book.lastCheckCount = 0
            book.durChapterTime = System.currentTimeMillis()
            val chapterChanged = book.durChapterIndex != durChapterIndex
            book.durChapterIndex = durChapterIndex
            book.durChapterPos = durChapterPos
            if (chapterChanged) {
                appDb.bookChapterDao.getChapter(book.bookUrl, book.durChapterIndex)?.let {
                    book.durChapterTitle = it.getDisplayTitle(
                        ContentProcessor.get(book.name, book.origin).getTitleReplaceRules(),
                        book.getUseReplaceRule()
                    )
                }
            }
            book.update()
            SourceCallBack.callBackBook(
                SourceCallBack.SAVE_READ, bookSource, book
            )
        }
    }

    /**
     * 保存章节长度
     */
    fun saveDurChapter(audioSize: Long) {
        val chapter = durChapter ?: return
        Coroutine.async {
            durAudioSize = audioSize.toInt()
            chapter.end = audioSize
            appDb.bookChapterDao.update(chapter)
        }
    }

    fun playPositionChanged(position: Int) {
        durChapterPos = position
        saveRead()
    }

    fun upLoading(loading: Boolean) {
        callback?.upLoading(loading)
    }

    private fun isPlayToEnd(): Boolean {
        return durChapterIndex + 1 == simulatedChapterSize
                && durChapterPos == durAudioSize
    }

    fun register(context: Context) {
        activityContext = context
        callback = context as CallBack
    }

    fun unregister(context: Context) {
        if (activityContext === context) {
            activityContext = null
            callback = null
        }
        coroutineContext.cancelChildren()
    }

    fun registerService(context: Context) {
        serviceContext = context
    }

    fun unregisterService() {
        serviceContext = null
    }

    interface CallBack {

        fun upLoading(loading: Boolean)

    }

}
