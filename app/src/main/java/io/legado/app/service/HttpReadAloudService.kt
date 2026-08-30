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
import io.legado.app.data.entities.HttpTTS
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.exoplayer.InputStreamDataSource
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.analyzeRule.AnalyzeUrl
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
    @Volatile
    private var playbackSessionId: Long = 0L

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

    private fun updateNextPos() {
        readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
        paragraphStartPos = 0
        if (nowSpeak < contentList.lastIndex) {
            nowSpeak++
        } else {
            nextChapter(auto = true)
        }
    }

    private fun downloadAndPlayAudios() {
        startDownloadAndQueue(
            onComplete = { httpTts -> preDownloadAudios(httpTts) }
        ) { sessionId, httpTts, index, text ->
            val fileName = md5SpeakFileName(text)
            val speakText = text.replace(AppPattern.notReadAloudRegex, "")
            if (speakText.isEmpty()) {
                AppLog.put("阅读段落内容为空，使用无声音频代替。\n朗读文本：$text")
                createSilentSound(fileName)
            } else if (!hasSpeakFile(fileName)) {
                val inputStream = getSpeakStream(httpTts, speakText)
                if (inputStream != null) {
                    createSpeakFile(fileName, inputStream)
                } else {
                    createSilentSound(fileName)
                }
            }
            val file = getSpeakFileAsMd5(fileName)
            val mediaItem = createQueueMediaItem(Uri.fromFile(file), index, sessionId)
            enqueueMediaItem(sessionId, mediaItem)
            val pauseMs = httpTts.pauseDuration
            if (pauseMs > 0 && index < contentList.lastIndex) {
                val pauseName = "pause_$pauseMs"
                if (!hasSpeakFile(pauseName)) {
                    createPauseFile(pauseName, pauseMs)
                }
                val pauseFile = getSpeakFileAsMd5(pauseName)
                enqueueMediaItem(sessionId, createQueueMediaItem(Uri.fromFile(pauseFile), -1, sessionId))
            }
        }
    }

    private suspend fun preDownloadAudios(httpTts: HttpTTS) {
        val textChapter = ReadBook.nextTextChapter ?: return
        val contentList = textChapter.getNeedReadAloud(0, readAloudByPage, 0, 1)
            .splitToSequence("\n")
            .filter { it.isNotEmpty() }
            .take(10)
            .toList()
        contentList.forEach { content ->
            currentCoroutineContext().ensureActive()
            val fileName = md5SpeakFileName(content, textChapter)
            val speakText = content.replace(AppPattern.notReadAloudRegex, "")
            if (speakText.isEmpty()) {
                createSilentSound(fileName)
            } else if (!hasSpeakFile(fileName)) {
                runCatching {
                    val inputStream = getSpeakStream(httpTts, speakText)
                    if (inputStream != null) {
                        createSpeakFile(fileName, inputStream)
                    } else {
                        createSilentSound(fileName)
                    }
                }
            }
        }
    }

    private fun downloadAndPlayAudiosStream() {
        startDownloadAndQueue { sessionId, httpTts, index, text ->
            val speakText = text.replace(AppPattern.notReadAloudRegex, "")
            if (speakText.isEmpty()) {
                AppLog.put("阅读段落内容为空，使用无声音频代替。\n朗读文本：$speakText")
            }
            val fileName = md5SpeakFileName(text)
            val dataSourceFactory = createDataSourceFactory(httpTts, speakText)
            val mediaSource = createMediaSource(dataSourceFactory, fileName, index, sessionId)
            enqueueMediaSource(sessionId, mediaSource)
            val pauseMs = httpTts.pauseDuration
            if (pauseMs > 0 && index < contentList.lastIndex) {
                val pauseDataSourceFactory = DataSource.Factory {
                    InputStreamDataSource {
                        java.io.ByteArrayInputStream(generateSilentWavBytes(pauseMs))
                    }
                }
                val pauseItem = MediaItem.Builder()
                    .setUri("pause:$pauseMs".toUri())
                    .setMediaId("$sessionId:-1")
                    .build()
                val pauseMediaSource = DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(pauseDataSourceFactory)
                    .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
                    .createMediaSource(pauseItem)
                enqueueMediaSource(sessionId, pauseMediaSource)
            }
        }
    }

    private fun startDownloadAndQueue(
        onComplete: suspend (HttpTTS) -> Unit = {},
        enqueueBlock: suspend (sessionId: Long, httpTts: HttpTTS, index: Int, text: String) -> Unit
    ) {
        downloadTask?.cancel()
        exoPlayer.clearMediaItems()
        val sessionId = startPlaybackSession()
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                ensureSessionActive(sessionId)
                val httpTts = ReadAloud.httpTTS ?: throw NoStackTraceException("tts is null")
                val startIndex = nowSpeak.coerceAtLeast(0)
                for (index in startIndex..contentList.lastIndex) {
                    ensureActive()
                    ensureSessionActive(sessionId)
                    val text = getReadTextForPlay(index, contentList[index])
                    try {
                        enqueueBlock(sessionId, httpTts, index, text)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.put("朗读下载处理失败\n${e.localizedMessage}", e)
                        pauseReadAloud()
                        return@execute
                    }
                }
                ensureSessionActive(sessionId)
                onComplete(httpTts)
            }
        }.onError {
            AppLog.put("朗读下载出错\n${it.localizedMessage}", it, true)
        }
    }

    private fun getReadTextForPlay(index: Int, content: String): String {
        return if (paragraphStartPos > 0 && index == nowSpeak) {
            content.substring(paragraphStartPos)
        } else {
            content
        }
    }

    private fun createDataSourceFactory(
        httpTts: HttpTTS,
        speakText: String
    ): CacheDataSource.Factory {
        val upstreamFactory = DataSource.Factory {
            InputStreamDataSource {
                if (speakText.isEmpty()) {
                    null
                } else {
                    kotlin.runCatching {
                        runBlocking(lifecycleScope.coroutineContext[Job]!!) {
                            getSpeakStream(httpTts, speakText)
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

    private fun createMediaSource(
        factory: DataSource.Factory,
        fileName: String,
        index: Int,
        sessionId: Long
    ): MediaSource {
        return DefaultMediaSourceFactory(this)
            .setDataSourceFactory(factory)
            .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            .createMediaSource(createQueueMediaItem(fileName.toUri(), index, sessionId))
    }

    private suspend fun getSpeakStream(
        httpTts: HttpTTS,
        speakText: String
    ): InputStream? {
        while (true) {
            try {
                val analyzeUrl = AnalyzeUrl(
                    httpTts.url,
                    speakText = speakText,
                    speakSpeed = speechRate,
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

    private fun md5SpeakFileName(content: String, textChapter: TextChapter? = this.textChapter): String {
        val tts = ReadAloud.httpTTS
        val sourceVariable = tts?.getVariable().orEmpty()
        val loginHeader = tts?.getLoginHeader().orEmpty()
        return MD5Utils.md5Encode16(textChapter?.title ?: "") + "_" +
                MD5Utils.md5Encode16(
                    "${tts?.url}-|-$speechRate-|-$sourceVariable-|-$loginHeader-|-$content"
                )
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
        playIndexJob = lifecycleScope.launch {
            upTtsProgress(readAloudNumber + 1)
            if (exoPlayer.duration <= 0) {
                return@launch
            }
            val speakTextLength = contentList[nowSpeak].length
            if (speakTextLength <= 0) {
                return@launch
            }
            val sleep = exoPlayer.duration / speakTextLength
            val start = speakTextLength * exoPlayer.currentPosition / exoPlayer.duration
            for (i in start..contentList[nowSpeak].length) {
                if (pageIndex + 1 < textChapter.pageSize
                    && readAloudNumber + i > textChapter.getReadLength(pageIndex + 1)
                ) {
                    pageIndex++
                    ReadBook.moveToNextPage(syncReadAloudFollow = true)
                    upTtsProgress(readAloudNumber + i.toInt())
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

    private fun createQueueMediaItem(uri: Uri, index: Int, sessionId: Long): MediaItem {
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId("$sessionId:$index")
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
