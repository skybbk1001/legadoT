package io.legado.app.ui.config

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogCoverLibraryBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.BookCover
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.SelectImagesContract
import io.legado.app.utils.externalFiles
import io.legado.app.utils.inputStream
import io.legado.app.utils.readUri
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import splitties.init.appCtx
import splitties.views.onClick
import java.io.FileOutputStream

/**
 * 默认封面图库管理：多选添加 / 点击移除，按书名确定性分配。
 */
class CoverLibraryDialog() : BaseDialogFragment(R.layout.dialog_cover_library),
    CoverLibraryAdapter.CallBack {

    override val dialogForm = DialogForm.FULL_SCREEN

    constructor(isNight: Boolean) : this() {
        arguments = Bundle().apply { putBoolean("isNight", isNight) }
    }

    private val binding by viewBinding(DialogCoverLibraryBinding::bind)
    private val adapter by lazy { CoverLibraryAdapter(requireContext(), this) }

    private var isNight = false

    private val selectImages = registerForActivityResult(SelectImagesContract()) { result ->
        result.uris.forEach { uri -> addCover(uri) }
    }

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        isNight = arguments?.getBoolean("isNight", false) ?: false
        binding.toolBar.setTitle(R.string.cover_library)
        binding.recyclerView.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.recyclerView.adapter = adapter
        binding.tvAdd.onClick {
            selectImages.launch(null)
        }
        refresh()
    }

    private fun refresh() {
        val paths = BookCover.getDefaultCoverPaths(isNight)
        adapter.setItems(paths)
        binding.tvHint.text = if (paths.isEmpty()) {
            getString(R.string.cover_library_empty)
        } else {
            getString(R.string.cover_library_hint)
        }
    }

    private fun addCover(uri: Uri) {
        readUri(uri) { fileDoc, inputStream ->
            kotlin.runCatching {
                val suffix = fileDoc.name.substringAfterLast('.', "").ifBlank { "jpg" }
                val fileName = uri.inputStream(requireContext()).getOrThrow().use {
                    MD5Utils.md5Encode(it) + ".$suffix"
                }
                val file = FileUtils.createFileIfNotExist(
                    requireContext().externalFiles, "covers", fileName
                )
                FileOutputStream(file).use {
                    inputStream.copyTo(it)
                }
                val paths = BookCover.getDefaultCoverPaths(isNight).toMutableList().apply {
                    add(file.absolutePath)
                }
                BookCover.saveDefaultCoverPaths(paths, isNight)
                refresh()
            }.onFailure {
                appCtx.toastOnUi(it.localizedMessage)
            }
        }
    }

    override fun remove(path: String) {
        alert(R.string.delete, R.string.sure_del) {
            okButton {
                val paths = BookCover.getDefaultCoverPaths(isNight).toMutableList().apply {
                    remove(path)
                }
                BookCover.saveDefaultCoverPaths(paths, isNight)
                refresh()
            }
            cancelButton()
        }
    }

}
