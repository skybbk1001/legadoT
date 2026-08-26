package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.media.MediaPlayer
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.RoleCast
import io.legado.app.databinding.DialogRoleCastBinding
import io.legado.app.exception.NoStackTraceException
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.model.ReadBook
import io.legado.app.model.ReadAloud
import io.legado.app.model.readaloud.RoleCastManager
import io.legado.app.model.readaloud.HttpTtsPreview
import io.legado.app.model.readaloud.VoiceRef
import io.legado.app.help.config.AppConfig
import io.legado.app.service.BaseReadAloudService
import io.legado.app.utils.setLayout
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import java.io.File
import kotlinx.coroutines.withContext

class RoleCastDialog : BaseDialogFragment(R.layout.dialog_role_cast),
    RoleCastAdapter.CallBack {

    private val binding by viewBinding(DialogRoleCastBinding::bind)
    private lateinit var adapter: RoleCastAdapter
    private val bookUrl get() = ReadBook.book?.bookUrl.orEmpty()
    private var voices: List<VoiceRef> = emptyList()
    private var casts: List<RoleCast> = emptyList()
    private var previewJob: kotlinx.coroutines.Job? = null
    private var previewPlayer: MediaPlayer? = null
    private var previewFile: File? = null

    override fun onStart() {
        super.onStart()
        setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        adapter = RoleCastAdapter(requireContext(), voices, this)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.btnReanalyze.setOnClickListener { reanalyzeCurrentChapter() }
        binding.btnReset.setOnClickListener { confirmReset() }
        refresh()
    }

    private fun reanalyzeCurrentChapter() {
        val chapterIndex = BaseReadAloudService.readAloudChapterIndex
            .takeIf { it >= 0 }
            ?: ReadBook.curTextChapter?.chapter?.index
            ?: return
        lifecycleScope.launch {
            withContext(IO) { appDb.chapterRoleScriptDao.delete(bookUrl, chapterIndex) }
            postEvent(EventBus.ROLE_CAST_CHANGED, bookUrl)
            toastOnUi(R.string.role_reanalyze_scheduled)
        }
    }

    private fun confirmReset() {
        alert(R.string.role_cast_reset) {
            setMessage(R.string.sure)
            positiveButton(R.string.yes) {
                lifecycleScope.launch {
                    withContext(IO) { appDb.roleCastDao.deleteByBook(bookUrl) }
                    postEvent(EventBus.ROLE_CAST_CHANGED, bookUrl)
                    refresh()
                }
            }
            negativeButton(R.string.no)
        }
    }

    private fun refresh() {
        lifecycleScope.launch {
            val loadedCasts = withContext(IO) {
                RoleCastManager.narratorCast(bookUrl)
                appDb.roleCastDao.getByBook(bookUrl).sortedBy { it.roleName }
            }
            val pool = withContext(IO) {
                RoleCastManager.availableVoices()
            }
            if (!isAdded || view == null) return@launch
            casts = loadedCasts
            voices = pool
            adapter.updateVoices(pool)
            adapter.setItems(loadedCasts)
            binding.btnReset.isEnabled = loadedCasts.isNotEmpty()
        }
    }

    override fun onPickVoice(cast: RoleCast) {
        if (voices.isEmpty()) {
            toastOnUi(R.string.role_cast_empty)
            return
        }
        val labels = voices.map {
            val voice = it.voice
            val voiceName = voice?.name ?: getString(R.string.role_cast_default_voice)
            val gender = voice?.gender?.let { value -> "（$value）" }.orEmpty()
            "${it.engineName} · $voiceName$gender"
        }
        requireContext().selector(getString(R.string.role_cast), labels) { _, index ->
            val picked = voices[index]
            lifecycleScope.launch {
                withContext(IO) {
                    appDb.roleCastDao.insert(
                        cast.copy(
                            ttsEngineId = picked.engineId,
                            voice = picked.voice?.id,
                            isManual = true
                        )
                    )
                }
                postEvent(EventBus.ROLE_CAST_CHANGED, bookUrl)
                refresh()
            }
        }
    }

    override fun onPreview(cast: RoleCast) {
        previewJob?.cancel()
        previewPlayer?.release()
        previewPlayer = null
        previewFile?.delete()
        previewFile = null
        previewJob = lifecycleScope.launch {
            try {
                val tts = withContext(IO) {
                    appDb.httpTTSDao.get(cast.ttsEngineId)
                        ?: ReadAloud.httpTTS
                        ?: throw NoStackTraceException(getString(R.string.role_cast_engine_missing))
                }
                val bytes = withContext(IO) {
                    HttpTtsPreview.fetch(
                        tts = tts,
                        text = getString(R.string.role_preview_text),
                        speechRate = AppConfig.speechRatePlay + 5,
                        voice = cast.voice
                    )
                }
                if (!isAdded) return@launch
                val cacheDir = requireContext().cacheDir
                val file = withContext(IO) {
                    File.createTempFile("tts-preview-", ".audio", cacheDir).apply {
                        writeBytes(bytes)
                    }
                }
                previewFile = file
                previewPlayer = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    setOnCompletionListener { it.release(); previewPlayer = null }
                    setOnErrorListener { player, _, _ ->
                        player.release()
                        previewPlayer = null
                        toastOnUi(R.string.role_preview_failed)
                        true
                    }
                    setOnPreparedListener { it.start() }
                    prepareAsync()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isAdded) toastOnUi(e.localizedMessage ?: getString(R.string.role_preview_failed))
            }
        }
    }

    override fun onMerge(cast: RoleCast) {
        val targets = casts.filter { it.roleName != cast.roleName }
        if (targets.isEmpty()) return
        requireContext().selector(getString(R.string.role_merge), targets.map { it.roleName }) { _, index ->
            lifecycleScope.launch {
                val merged = withContext(IO) {
                    kotlin.runCatching {
                        RoleCastManager.mergeRole(bookUrl, cast.roleName, targets[index].roleName)
                    }
                }
                merged.onFailure {
                    if (isAdded) toastOnUi(it.localizedMessage ?: getString(R.string.role_merge_failed))
                    return@launch
                }
                postEvent(EventBus.ROLE_CAST_CHANGED, bookUrl)
                refresh()
            }
        }
    }

    override fun onDestroyView() {
        previewJob?.cancel()
        previewPlayer?.release()
        previewPlayer = null
        previewFile?.delete()
        previewFile = null
        super.onDestroyView()
    }
}
