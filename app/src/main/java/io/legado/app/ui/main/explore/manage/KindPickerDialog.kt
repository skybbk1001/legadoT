package io.legado.app.ui.main.explore.manage

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ExploreContainer
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.databinding.DialogKindPickerBinding
import io.legado.app.databinding.ItemKindPickerBinding
import io.legado.app.help.source.exploreKinds
import io.legado.app.utils.gone
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 添加容器流程②:勾选分类(多选)+ 默认样式,批量插入容器。
 * 带 resultKey 参数时为编辑流程:隐藏样式/数量、按传入 URL 预选,确定后回传多选结果
 */
class KindPickerDialog : BaseDialogFragment(R.layout.dialog_kind_picker, true) {

    companion object {
        fun create(sourceUrl: String) = KindPickerDialog().apply {
            arguments = Bundle().apply { putString("sourceUrl", sourceUrl) }
        }

        fun pick(sourceUrl: String, selectedUrls: List<String>, resultKey: String) =
            KindPickerDialog().apply {
                arguments = Bundle().apply {
                    putString("sourceUrl", sourceUrl)
                    putStringArrayList("selectedUrls", ArrayList(selectedUrls))
                    putString("resultKey", resultKey)
                }
            }
    }

    private val binding by viewBinding(DialogKindPickerBinding::bind)
    private val adapter by lazy { KindAdapter(requireContext()) }
    private var source: BookSource? = null

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.9f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.tvCancel.setOnClickListener { dismiss() }
        binding.tvOk.setOnClickListener { save() }
        val sourceUrl = arguments?.getString("sourceUrl")
        val resultKey = arguments?.getString("resultKey")
        val isPickMode = resultKey != null
        if (isPickMode) {
            // 编辑流程:只选分类,隐藏默认样式/数量配置
            binding.tvStyleLabel.gone()
            binding.rgStyle.gone()
            binding.llCount.gone()
        }
        binding.rlLoading.visible()
        viewLifecycleOwner.lifecycleScope.launch {
            val s = withContext(IO) {
                sourceUrl?.let { appDb.bookSourceDao.getBookSource(it) }
            }
            if (s == null) {
                toastOnUi(R.string.explore_source_not_found)
                dismiss()
                return@launch
            }
            source = s
            binding.tvTitle.text = "${s.bookSourceName} · ${getString(
                if (isPickMode) R.string.explore_change_kind else R.string.explore_select_kinds
            )}"
            val kinds = s.exploreKinds().filter {
                !it.url.isNullOrBlank() && !it.title.startsWith("ERROR:")
            }
            binding.rlLoading.inVisible()
            if (kinds.isEmpty()) {
                toastOnUi(R.string.explore_no_kinds)
                dismiss()
                return@launch
            }
            adapter.setItems(kinds)
            if (isPickMode) {
                arguments?.getStringArrayList("selectedUrls")?.let { adapter.preselect(it) }
            }
        }
    }

    private fun save() {
        val s = source ?: return
        val selected = adapter.getSelectedKinds()
        if (selected.isEmpty()) {
            toastOnUi(R.string.explore_select_kinds_least)
            return
        }
        val resultKey = arguments?.getString("resultKey")
        if (resultKey != null) {
            // 编辑流程:回传多选分类(附新书源信息,便于换书源场景一并落库),由编辑对话框落库
            parentFragmentManager.setFragmentResult(
                resultKey,
                bundleOf(
                    "sourceUrl" to s.bookSourceUrl,
                    "sourceName" to s.bookSourceName,
                    "titles" to selected.map { it.title },
                    "urls" to selected.mapNotNull { it.url },
                )
            )
            dismiss()
            return
        }
        binding.tvOk.isEnabled = false
        val style = if (binding.rbList.isChecked) {
            ExploreContainer.STYLE_LIST
        } else {
            ExploreContainer.STYLE_FLOW
        }
        val count = binding.etCount.text?.toString()?.toIntOrNull()?.coerceIn(1, 20) ?: 3
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(IO) {
                val container = ExploreContainer(
                    sourceUrl = s.bookSourceUrl,
                    sourceName = s.bookSourceName,
                    kindTitle = selected.first().title,
                    kindUrl = selected.first().url!!,
                    kindTitles = selected.joinToString(",") { it.title },
                    kindUrls = selected.joinToString(",") { it.url!! },
                    style = style,
                    listCount = count,
                    sortOrder = appDb.exploreContainerDao.maxOrder + 1
                )
                appDb.exploreContainerDao.insert(container)
            }
            toastOnUi(getString(R.string.explore_container_added_single, selected.size))
            dismiss()
        }
    }

    private inner class KindAdapter(context: Context) :
        RecyclerAdapter<ExploreKind, ItemKindPickerBinding>(context) {

        private val selectedKinds = linkedSetOf<ExploreKind>()

        fun getSelectedKinds(): List<ExploreKind> {
            return getItems().filter { selectedKinds.contains(it) }
        }

        /** 编辑流程预选:按 URL 匹配,URL 快照漂移时不误勾选 */
        fun preselect(urls: List<String>) {
            val urlSet = urls.toSet()
            selectedKinds.clear()
            getItems().filter { it.url != null && it.url in urlSet }
                .forEach { selectedKinds.add(it) }
            notifyDataSetChanged()
        }

        override fun getViewBinding(parent: ViewGroup): ItemKindPickerBinding {
            return ItemKindPickerBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemKindPickerBinding,
            item: ExploreKind,
            payloads: MutableList<Any>
        ) {
            binding.cbKind.text = item.title
            binding.cbKind.isChecked = selectedKinds.contains(item)
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemKindPickerBinding) {
            binding.cbKind.setOnClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.let { kind ->
                    if (!selectedKinds.add(kind)) {
                        selectedKinds.remove(kind)
                    }
                }
            }
        }
    }
}
