package io.legado.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.script.ScriptException
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.RoleCast
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.exoplayer.InputStreamDataSource
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.readaloud.RoleAnnotator
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Response
import org.htmlunit.corejs.javascript.WrappedException
import splitties.init.appCtx
import java.io.File
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * 在线朗读
 */
@SuppressLint("UnsafeOptInUsageError")
class HttpReadAloudService : BaseReadAloudService(),
    Player.Listener {
    companion object {
        private const val MAX_PLAYER_QUEUE_SIZE = 12
    }

    private val exoPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(this).build()
    }
    private val ttsFolderPath: String by lazy {
        cacheDir.absolutePath + File.separator + "httpTTS" + File.separator
    }
    private val cache by lazy {
        SimpleCache(
            File(cacheDir, "httpTTS_cache"),
            LeastRecentlyUsedCacheEvictor(128 * 1024 * 1024),
            StandaloneDatabaseProvider(appCtx)
        )
    }
    private val cacheDataSinkFactory by lazy {
        CacheDataSink.Factory()
            .setCache(cache)
    }
    private val loadErrorHandlingPolicy by lazy {
        CustomLoadErrorHandlingPolicy()
    }
    private var speechRate: Int = AppConfig.speechRatePlay + 5
    private var downloadTask: Coroutine<*>? = null
    private var playIndexJob: Job? = null
    private var downloadErrorNo: Int = 0
    private var playErrorNo = 0
    private val downloadTaskActiveLock = Mutex()
    /** 引擎 id 到引擎的进程内缓存, 避免每个片段查一次库 */
    private val ttsCache = HashMap<Long, HttpTTS?>()
    @Volatile
    private var playbackSessionId: Long = 0L
    private var rebuildAfterCurrentSegment = false

    override fun onCreate() {
        super.onCreate()
        exoPlayer.addListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadTask?.cancel()
        exoPlayer.release()
        cache.release()
        Coroutine.async {
            removeCacheFile()
        }
    }

    override fun play() {
        pageChanged = false
        downloadTask?.cancel()
        invalidatePlaybackSession()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        if (!requestFocus()) return
        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            ReadBook.readAloud()
        } else {
            super.play()
            if (AppConfig.streamReadAloudAudio) {
                downloadAndPlayAudiosStream()
            } else {
                downloadAndPlayAudios()
            }
        }
    }

    override fun playStop() {
        downloadTask?.cancel()
        invalidatePlaybackSession()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        playIndexJob?.cancel()
    }

    override fun onRoleCastChanged() {
        if (exoPlayer.currentMediaItem == null) {
            resetSpeechScript()
            ttsCache.clear()
            if (pause) pageChanged = true else play()
        } else {
            rebuildAfterCurrentSegment = true
        }
    }

    /** @return false 表示已跨出本章, 后续播放交给换章路径 */
    private fun updateNextPos(): Boolean {
        val segs = currentScript().segmentsOf(nowSpeak)
        if (nowSegment < segs.lastIndex) {
            nowSegment++
            return true
        }
        nowSegment = 0
        readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
        paragraphStartPos = 0
        if (nowSpeak < contentList.lastIndex) {
            nowSpeak++
            return true
        }
        nextChapter(auto = true)
        return false
    }

    /**
     * 配音变更后重排: 先按旧脚本推进游标, 再作废缓存, 从新游标处重建队列。
     * 顺序固定 —— 作废后 [currentScript] 退化为每段一个片段, 先推进才不会吞掉本段剩余片段。
     * 已入队的音频按旧配音生成, 一并清掉; 暂停中只清队, 待 resume 时重建。
     *
     * @return true 表示本次回调已被重建接管, 调用方不再走常规推进
     */
    private fun applyPendingRebuild(): Boolean {
        if (!rebuildAfterCurrentSegment) return false
        rebuildAfterCurrentSegment = false
        val inChapter = updateNextPos()
        resetSpeechScript()
        ttsCache.clear()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        if (inChapter) {
            if (pause) pageChanged = true else play()
        }
        return true
    }

    private fun downloadAndPlayAudios() {
        startDownloadAndQueue(
            onComplete = { preDownloadAudios() }
        ) { sessionId, slice ->
            val httpTts = ttsOf(slice.cast.ttsEngineId)
            val fileName = md5SpeakFileName(slice.text, slice.cast)
            val speakText = slice.text.replace(AppPattern.notReadAloudRegex, "")
            if (speakText.isEmpty()) {
                AppLog.put("阅读段落内容为空，使用无声音频代替。\n朗读文本：${slice.text}")
                createSilentSound(fileName)
            } else if (!hasSpeakFile(fileName)) {
                val inputStream = getSpeakStream(httpTts, speakText, slice.cast.voice)
                if (inputStream != null) {
                    createSpeakFile(fileName, inputStream)
                } else {
                    createSilentSound(fileName)
                }
            }
            val file = getSpeakFileAsMd5(fileName)
            enqueueMediaItem(
                sessionId,
                createQueueMediaItem(Uri.fromFile(file), slice, sessionId)
            )
            val pauseMs = httpTts.pauseDuration
            if (pauseMs > 0 && !slice.isLast) {
                val pauseName = "pause_$pauseMs"
                if (!hasSpeakFile(pauseName)) {
                    createPauseFile(pauseName, pauseMs)
                }
                val pauseFile = getSpeakFileAsMd5(pauseName)
                enqueueMediaItem(sessionId, createPauseMediaItem(Uri.fromFile(pauseFile), sessionId))
            }
        }
    }

    /** 下一章预取的输入: 章节与其整章朗读段落表 */
    private data class NextChapterPrefetch(
        val chapter: TextChapter,
        val paragraphs: List<String>
    )

    /**
     * 两条预取路径共用的段落表取法, 与起播时 [contentList] 的赋值逐字相同:
     * 标注的内容 md5 与音频缓存键都按这份段落表算, 差一个字符即全部落空。
     * 排版未完成时 [TextChapter.pages] 仍在增长, 页表不全会截断段落表, 此时不预取。
     */
    private fun nextChapterPrefetch(): NextChapterPrefetch? {
        val nextChapter = ReadBook.nextTextChapter ?: return null
        if (!nextChapter.isCompleted) return null
        val paragraphs = nextChapter.getNeedReadAloud(0, readAloudByPage, 0)
            .split("\n")
            .filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) return null
        return NextChapterPrefetch(nextChapter, paragraphs)
    }

    /**
     * 流式不落音频文件, 预取只做标注: 下一章起播时 [RoleAnnotator] 命中缓存, 起播前不再等 LLM 往返。
     */
    private suspend fun preAnnotateNextChapter() {
        val book = ReadBook.book ?: return
        val (nextChapter, paragraphs) = nextChapterPrefetch() ?: return
        RoleAnnotator.prefetch(book.bookUrl, nextChapter.chapter.index, paragraphs)
    }

    /**
     * 本章音频已全部入队后才走到这里, 下一章的标注与音频都在同一个下载协程上预取。
     * 换章时 [play] 先取消下载协程, 未完成的预取随之作废。
     */
    private suspend fun preDownloadAudios() {
        val (nextChapter, paragraphs) = nextChapterPrefetch() ?: return
        // 先标注整章, 音频按下一章的真 casting 预下载, 缓存键与起播时的播放路径一致
        val fallback = currentScript().fallbackCast()
        val script = buildScriptFor(nextChapter.chapter.index, paragraphs, fallback)
        for (para in paragraphs.indices.take(10)) {
            for (seg in script.segmentsOf(para)) {
                currentCoroutineContext().ensureActive()
                val cast = script.castOf(seg)
                val content = script.textOf(seg)
                val fileName = md5SpeakFileName(content, cast, nextChapter)
                val speakText = content.replace(AppPattern.notReadAloudRegex, "")
                if (speakText.isEmpty()) {
                    createSilentSound(fileName)
                } else if (!hasSpeakFile(fileName)) {
                    runCatching {
                        val inputStream =
                            getSpeakStream(ttsOf(cast.ttsEngineId), speakText, cast.voice)
                        if (inputStream != null) {
                            createSpeakFile(fileName, inputStream)
                        } else {
                            createSilentSound(fileName)
                        }
                    }
                }
            }
        }
    }

    private fun downloadAndPlayAudiosStream() {
        startDownloadAndQueue(
            onComplete = { preAnnotateNextChapter() }
        ) { sessionId, slice ->
            val httpTts = ttsOf(slice.cast.ttsEngineId)
            val speakText = slice.text.replace(AppPattern.notReadAloudRegex, "")
            if (speakText.isEmpty()) {
                AppLog.put("阅读段落内容为空，使用无声音频代替。\n朗读文本：$speakText")
            }
            val fileName = md5SpeakFileName(slice.text, slice.cast)
            val dataSourceFactory = createDataSourceFactory(httpTts, speakText, slice.cast.voice)
            val mediaSource = DefaultMediaSourceFactory(this)
                .setDataSourceFactory(dataSourceFactory)
                .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
                .createMediaSource(createQueueMediaItem(fileName.toUri(), slice, sessionId))
            enqueueMediaSource(sessionId, mediaSource)
            val pauseMs = httpTts.pauseDuration
            if (pauseMs > 0 && !slice.isLast) {
                val pauseDataSourceFactory = DataSource.Factory {
                    InputStreamDataSource {
                        java.io.ByteArrayInputStream(generateSilentWavBytes(pauseMs))
                    }
                }
                val pauseMediaSource = DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(pauseDataSourceFactory)
                    .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
                    .createMediaSource(createPauseMediaItem("pause:$pauseMs".toUri(), sessionId))
                enqueueMediaSource(sessionId, pauseMediaSource)
            }
        }
    }

    /**
     * 一个待入队片段的全部载荷。
     *
     * @param para 片段所在段落在 [contentList] 中的下标
     * @param segIndex 片段在该段落片段表中的下标
     * @param isLast 本章最后一个片段, 其后不再接停顿项
     * @param text 已按起播偏移截断的待朗读文本
     */
    private data class SpeechSlice(
        val cast: RoleCast,
        val para: Int,
        val segIndex: Int,
        val isLast: Boolean,
        val text: String
    )

    private fun startDownloadAndQueue(
        onComplete: suspend () -> Unit = {},
        enqueueBlock: suspend (sessionId: Long, slice: SpeechSlice) -> Unit
    ) {
        downloadTask?.cancel()
        exoPlayer.clearMediaItems()
        val sessionId = startPlaybackSession()
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                ensureSessionActive(sessionId)
                prepareSpeechScript()
                val script = currentScript()
                val startPara = nowSpeak.coerceAtLeast(0)
                for (para in startPara..contentList.lastIndex) {
                    val segs = script.segmentsOf(para)
                    val startSeg = if (para == startPara) {
                        nowSegment.coerceIn(0, maxOf(segs.lastIndex, 0))
                    } else {
                        0
                    }
                    for (segIndex in startSeg..segs.lastIndex) {
                        ensureActive()
                        ensureSessionActive(sessionId)
                        val seg = segs[segIndex]
                        val offset = if (para == startPara && segIndex == startSeg) {
                            paragraphStartPos
                        } else {
                            0
                        }
                        val slice = SpeechSlice(
                            cast = script.castOf(seg),
                            para = para,
                            segIndex = segIndex,
                            isLast = para == contentList.lastIndex && segIndex == segs.lastIndex,
                            text = script.textOf(seg, offset)
                        )
                        try {
                            enqueueBlock(sessionId, slice)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLog.put("朗读下载处理失败\n${e.localizedMessage}", e)
                            pauseReadAloud()
                            return@execute
                        }
                    }
                }
                ensureSessionActive(sessionId)
                onComplete()
            }
        }.onError {
            AppLog.put("朗读下载出错\n${it.localizedMessage}", it, true)
        }
    }

    private fun createDataSourceFactory(
        httpTts: HttpTTS,
        speakText: String,
        voice: String?
    ): CacheDataSource.Factory {
        val upstreamFactory = DataSource.Factory {
            InputStreamDataSource {
                if (speakText.isEmpty()) {
                    null
                } else {
                    kotlin.runCatching {
                        runBlocking(lifecycleScope.coroutineContext[Job]!!) {
                            getSpeakStream(httpTts, speakText, voice)
                        }
                    }.onFailure {
                        when (it) {
                            is InterruptedException,
                            is CancellationException -> Unit

                            else -> pauseReadAloud()
                        }
                    }.getOrThrow()
                } ?: resources.openRawResource(R.raw.silent_sound)
            }
        }
        val factory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheWriteDataSinkFactory(cacheDataSinkFactory)
        return factory
    }

    private suspend fun getSpeakStream(
        httpTts: HttpTTS,
        speakText: String,
        voice: String?
    ): InputStream? {
        while (true) {
            try {
                val analyzeUrl = AnalyzeUrl(
                    httpTts.url,
                    speakText = speakText,
                    speakSpeed = speechRate,
                    speakVoice = voice,
                    source = httpTts,
                    readTimeout = 300 * 1000L,
                    coroutineContext = currentCoroutineContext()
                )
                var response = analyzeUrl.getResponseAwait()
                currentCoroutineContext().ensureActive()
                val checkJs = httpTts.loginCheckJs
                if (checkJs?.isNotBlank() == true) {
                    response = analyzeUrl.evalJS(checkJs, response) as Response
                }
                response.headers["Content-Type"]?.let { contentType ->
                    val contentType = contentType.substringBefore(";")
                    val ct = httpTts.contentType
                    if (contentType == "application/json" || contentType.startsWith("text/")) {
                        throw NoStackTraceException(response.body.string())
                    } else if (ct?.isNotBlank() == true) {
                        if (!contentType.matches(ct.toRegex())) {
                            throw NoStackTraceException(
                                "TTS服务器返回错误：" + response.body.string()
                            )
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                response.body.byteStream().let { stream ->
                    downloadErrorNo = 0
                    return stream
                }
            } catch (e: Exception) {
                when (e) {
                    is CancellationException -> throw e
                    is ScriptException, is WrappedException -> {
                        AppLog.put("js错误\n${e.localizedMessage}", e, true)
                        e.printOnDebug()
                        throw e
                    }

                    is SocketTimeoutException, is ConnectException -> {
                        downloadErrorNo++
                        if (downloadErrorNo > 5) {
                            val msg = "tts超时或连接错误超过5次\n${e.localizedMessage}"
                            AppLog.put(msg, e, true)
                            throw e
                        }
                    }

                    else -> {
                        downloadErrorNo++
                        val msg = "tts下载错误\n${e.localizedMessage}"
                        AppLog.put(msg, e)
                        e.printOnDebug()
                        if (downloadErrorNo > 5) {
                            val msg1 = "TTS服务器连续5次错误，已暂停阅读。"
                            AppLog.put(msg1, e, true)
                            throw e
                        } else {
                            AppLog.put("TTS下载音频出错，使用无声音频代替。\n朗读文本：$speakText")
                            break
                        }
                    }
                }
            }
        }
        return null
    }

    private fun md5SpeakFileName(
        content: String,
        cast: RoleCast,
        textChapter: TextChapter? = this.textChapter
    ): String {
        val tts = ttsOf(cast.ttsEngineId)
        val sourceVariable = tts.getVariable().orEmpty()
        val loginHeader = tts.getLoginHeader().orEmpty()
        return MD5Utils.md5Encode16(textChapter?.title ?: "") + "_" +
                MD5Utils.md5Encode16(
                    "${tts.url}-|-$speechRate-|-${cast.ttsEngineId}-|-${cast.voice}" +
                            "-|-$sourceVariable-|-$loginHeader-|-$content"
                )
    }

    /**
     * engineId 为 0(未指定)或库中查无此条时用当前朗读引擎。
     * 同步查库并写 [ttsCache], 全部调用路径都在 [downloadTaskActiveLock] 内的 IO 上下文上, 串行独占。
     */
    private fun ttsOf(engineId: Long): HttpTTS {
        val cached = when {
            engineId <= 0L -> null
            ttsCache.containsKey(engineId) -> ttsCache[engineId]
            else -> appDb.httpTTSDao.get(engineId).also { ttsCache[engineId] = it }
        }
        return cached ?: ReadAloud.httpTTS ?: throw NoStackTraceException("tts is null")
    }

    private fun createSilentSound(fileName: String) {
        val file = createSpeakFile(fileName)
        file.writeBytes(resources.openRawResource(R.raw.silent_sound).readBytes())
    }

    private fun hasSpeakFile(name: String): Boolean {
        return FileUtils.exist("${ttsFolderPath}$name.mp3")
    }

    private fun getSpeakFileAsMd5(name: String): File {
        return File("${ttsFolderPath}$name.mp3")
    }

    private fun createSpeakFile(name: String): File {
        return FileUtils.createFileIfNotExist("${ttsFolderPath}$name.mp3")
    }

    private fun createSpeakFile(name: String, inputStream: InputStream) {
        FileUtils.createFileIfNotExist("${ttsFolderPath}$name.mp3").outputStream().use { out ->
            inputStream.use {
                it.copyTo(out)
            }
        }
    }

    private fun generateSilentWavBytes(durationMs: Int): ByteArray {
        val sampleRate = 24000
        val numChannels = 1
        val bitsPerSample = 16
        val bytesPerSample = bitsPerSample / 8
        val dataSize = (sampleRate.toLong() * bytesPerSample * numChannels * durationMs / 1000).toInt()
        val alignedDataSize = ((dataSize + 1) / 2) * 2
        val totalSize = 44 + alignedDataSize
        val buf = java.nio.ByteBuffer.allocate(totalSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray())
        buf.putInt(36 + alignedDataSize)
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16)
        buf.putShort(1) // PCM
        buf.putShort(numChannels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * numChannels * bytesPerSample)
        buf.putShort((numChannels * bytesPerSample).toShort())
        buf.putShort(bitsPerSample.toShort())
        buf.put("data".toByteArray())
        buf.putInt(alignedDataSize)
        return buf.array()
    }

    private fun createPauseFile(name: String, durationMs: Int) {
        val file = createSpeakFile(name)
        file.writeBytes(generateSilentWavBytes(durationMs))
    }

    /**
     * 移除缓存文件
     */
    private fun removeCacheFile() {
        val titleMd5 = MD5Utils.md5Encode16(textChapter?.title ?: "")
        FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach {
            val isSilentSound = it.length() == 2160L
            if ((!it.name.startsWith(titleMd5)
                        && System.currentTimeMillis() - it.lastModified() > 600000)
                || isSilentSound
            ) {
                FileUtils.delete(it.absolutePath)
            }
        }
    }


    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        kotlin.runCatching {
            playIndexJob?.cancel()
            exoPlayer.pause()
        }
    }

    override fun resumeReadAloud() {
        super.resumeReadAloud()
        kotlin.runCatching {
            if (pageChanged) {
                play()
            } else {
                exoPlayer.play()
                upPlayPos()
            }
        }
    }

    private fun upPlayPos() {
        playIndexJob?.cancel()
        val textChapter = textChapter ?: return
        val seg = currentScript().segmentsOf(nowSpeak).getOrNull(nowSegment) ?: return
        // readAloudNumber 已含 paragraphStartPos, 片段起点扣掉落在它之前的那段偏移, 读数才落在真实起点上。
        // 段首起播(paragraphStartPos 为 0)取值为 seg.s; 纯旁白脚本 seg.s 恒为 0, 钳位后取值恒为 0。
        val segStart = seg.s - paragraphStartPos.coerceIn(0, seg.s)
        val speakTextLength = seg.e - seg.s
        playIndexJob = lifecycleScope.launch {
            upTtsProgress(readAloudNumber + segStart + 1)
            if (exoPlayer.duration <= 0 || speakTextLength <= 0) {
                return@launch
            }
            val sleep = exoPlayer.duration / speakTextLength
            val start = speakTextLength * exoPlayer.currentPosition / exoPlayer.duration
            for (i in start..speakTextLength.toLong()) {
                if (pageIndex + 1 < textChapter.pageSize
                    && readAloudNumber + segStart + i > textChapter.getReadLength(pageIndex + 1)
                ) {
                    pageIndex++
                    ReadBook.moveToNextPage(syncReadAloudFollow = true)
                    upTtsProgress(readAloudNumber + segStart + i.toInt())
                }
                delay(sleep)
            }
        }
    }

    /**
     * 更新朗读速度
     */
    override fun upSpeechRate(reset: Boolean) {
        downloadTask?.cancel()
        invalidatePlaybackSession()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        speechRate = AppConfig.speechRatePlay + 5
        if (AppConfig.streamReadAloudAudio) {
            downloadAndPlayAudiosStream()
        } else {
            downloadAndPlayAudios()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        super.onPlaybackStateChanged(playbackState)
        when (playbackState) {
            Player.STATE_IDLE -> {
                // 空闲
            }

            Player.STATE_BUFFERING -> {
                // 缓冲中
            }

            Player.STATE_READY -> {
                // 准备好
                if (pause) return
                exoPlayer.play()
                upPlayPos()
            }

            Player.STATE_ENDED -> {
                // 结束
                if (exoPlayer.mediaItemCount <= 0) {
                    return
                }
                val currentMedia = exoPlayer.currentMediaItem
                if (currentMedia != null && !isCurrentSessionMediaItem(currentMedia)) {
                    return
                }
                playErrorNo = 0
                if (applyPendingRebuild()) return
                updateNextPos()
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
            }
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        when (reason) {
            Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED -> {
                if (!timeline.isEmpty && exoPlayer.playbackState == Player.STATE_IDLE) {
                    exoPlayer.prepare()
                }
            }

            else -> {}
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
        if (!isCurrentSessionMediaItem(mediaItem)) return
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            playErrorNo = 0
        }
        trimPlayedMediaItems()
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && applyPendingRebuild()) {
            return
        }
        if (mediaItem?.mediaId?.endsWith(":-1") == true) {
            return
        }
        updateNextPos()
        upPlayPos()
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        val mediaItem = exoPlayer.currentMediaItem
        if (mediaItem != null && !isCurrentSessionMediaItem(mediaItem)) {
            return
        }
        val currentContent = contentList.getOrNull(nowSpeak)
        if (currentContent.isNullOrEmpty()) {
            AppLog.put("朗读错误", error)
        } else {
            AppLog.put("朗读错误\n$currentContent", error)
        }
        if (mediaItem != null) {
            deleteCurrentSpeakFile()
            trimPlayedMediaItems()
        }
        playErrorNo++
        if (playErrorNo >= 5) {
            toastOnUi("朗读连续5次错误, 最后一次错误代码(${error.localizedMessage})")
            AppLog.put("朗读连续5次错误, 最后一次错误代码(${error.localizedMessage})", error)
            pauseReadAloud()
        } else {
            if (exoPlayer.hasNextMediaItem()) {
                exoPlayer.seekToNextMediaItem()
                exoPlayer.prepare()
            } else if (mediaItem == null) {
                // 发生错误时没有当前媒体项，重建当前播放队列，避免直接卡死。
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                if (!pause) {
                    if (AppConfig.streamReadAloudAudio) {
                        downloadAndPlayAudiosStream()
                    } else {
                        downloadAndPlayAudios()
                    }
                }
            } else {
                exoPlayer.clearMediaItems()
                updateNextPos()
            }
        }
    }

    private fun deleteCurrentSpeakFile() {
        if (AppConfig.streamReadAloudAudio) {
            return
        }
        val mediaItem = exoPlayer.currentMediaItem ?: return
        val filePath = mediaItem.localConfiguration!!.uri.path!!
        File(filePath).delete()
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<HttpReadAloudService>(actionStr)
    }

    private fun createQueueMediaItem(uri: Uri, slice: SpeechSlice, sessionId: Long): MediaItem {
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId("$sessionId:${slice.para}:${slice.segIndex}")
            .build()
    }

    /** 停顿项保持两段式 id, 会话判定与 :-1 判定均沿用 */
    private fun createPauseMediaItem(uri: Uri, sessionId: Long): MediaItem {
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId("$sessionId:-1")
            .build()
    }

    private fun trimPlayedMediaItems() {
        val currentIndex = exoPlayer.currentMediaItemIndex
        if (currentIndex > 0) {
            exoPlayer.removeMediaItems(0, currentIndex)
        }
    }

    private suspend fun enqueueMediaItem(sessionId: Long, mediaItem: MediaItem) {
        awaitQueueSlot(sessionId)
        ensureSessionActive(sessionId)
        withContext(Main) {
            if (!isSessionActive(sessionId)) return@withContext
            exoPlayer.addMediaItem(mediaItem)
        }
        ensureSessionActive(sessionId)
    }

    private suspend fun enqueueMediaSource(sessionId: Long, mediaSource: MediaSource) {
        awaitQueueSlot(sessionId)
        ensureSessionActive(sessionId)
        withContext(Main) {
            if (!isSessionActive(sessionId)) return@withContext
            exoPlayer.addMediaSource(mediaSource)
        }
        ensureSessionActive(sessionId)
    }

    private suspend fun awaitQueueSlot(sessionId: Long) {
        while (true) {
            currentCoroutineContext().ensureActive()
            ensureSessionActive(sessionId)
            val hasSlot = withContext(Main) {
                if (!isSessionActive(sessionId)) {
                    return@withContext false
                }
                trimPlayedMediaItems()
                exoPlayer.mediaItemCount < MAX_PLAYER_QUEUE_SIZE
            }
            if (hasSlot) {
                return
            }
            delay(80L)
        }
    }

    private fun startPlaybackSession(): Long {
        synchronized(this) {
            playbackSessionId += 1
            return playbackSessionId
        }
    }

    private fun invalidatePlaybackSession() {
        synchronized(this) {
            playbackSessionId += 1
        }
    }

    private fun isSessionActive(sessionId: Long): Boolean {
        return playbackSessionId == sessionId
    }

    private fun ensureSessionActive(sessionId: Long) {
        if (!isSessionActive(sessionId)) {
            throw CancellationException("playback session changed")
        }
    }

    private fun isCurrentSessionMediaItem(mediaItem: MediaItem?): Boolean {
        mediaItem ?: return false
        val session = mediaItem.mediaId.substringBefore(':').toLongOrNull() ?: return false
        return isSessionActive(session)
    }

    class CustomLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(0) {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            return C.TIME_UNSET
        }
    }

}
