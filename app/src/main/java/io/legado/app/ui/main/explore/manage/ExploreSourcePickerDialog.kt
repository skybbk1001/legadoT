package io.legado.app.ui.main.explore.manage

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.databinding.DialogExploreSourcePickerBinding
import io.legado.app.databinding.ItemSourcePickerBinding
import io.legado.app.ui.widget.popupActionMenu
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * 添加容器流程①:选择书源。
 * 带 resultKey 参数时为编辑流程的单选模式,选中后经 Fragment Result API 回传 sourceUrl
 */
class ExploreSourcePickerDialog : BaseDialogFragment(R.layout.dialog_explore_source_picker, true) {

    companion object {
        fun pick(resultKey: String) = ExploreSourcePickerDialog().apply {
            arguments = Bundle().apply { putString("resultKey", resultKey) }
        }
    }

    private val binding by viewBinding(DialogExploreSourcePickerBinding::bind)
    private val adapter by lazy { SourceAdapter(requireContext()) }
    private var sourceFlowJob: Job? = null

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.9f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.etSearch.doAfterTextChanged {
            upData(it?.toString())
        }
        upData(null)
    }

    private fun upData(key: String?) {
        sourceFlowJob?.cancel()
        sourceFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            val flow = if (key.isNullOrBlank()) {
                appDb.bookSourceDao.flowExplore()
            } else {
                appDb.bookSourceDao.flowExplore(key)
            }
            flow.flowOn(IO).catch {
                AppLog.put("发现容器选择书源出错", it)
            }.conflate().collect {
                adapter.setItems(it)
            }
        }
    }

    private fun onSourceClick(source: BookSourcePart) {
        val resultKey = arguments?.getString("resultKey")
        if (resultKey == null) {
            (requireActivity() as AppCompatActivity)
                .showDialogFragment(KindPickerDialog.create(source.bookSourceUrl))
        } else {
            parentFragmentManager.setFragmentResult(
                resultKey, bundleOf("sourceUrl" to source.bookSourceUrl)
            )
        }
        dismiss()
    }

    /** 长按书源:禁用其发现,禁用后不再出现在本选择器(flowExplore 仅查 enabledExplore = 1) */
    private fun showSourceMenu(source: BookSourcePart, anchor: View) {
        popupActionMenu(requireContext()) {
            item(getString(R.string.disable_explore), "disableExplore")
            danger("disableExplore")
        }.show(anchor) { action ->
            when (action) {
                "disableExplore" -> {
                    viewLifecycleOwner.lifecycleScope.launch(IO) {
                        appDb.bookSourceDao.enableExplore(source.bookSourceUrl, false)
                    }
                    toastOnUi(R.string.disabled_explore)
                }
            }
        }
    }

    private inner class SourceAdapter(context: Context) :
        RecyclerAdapter<BookSourcePart, ItemSourcePickerBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemSourcePickerBinding {
            return ItemSourcePickerBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemSourcePickerBinding,
            item: BookSourcePart,
            payloads: MutableList<Any>
        ) {
            binding.tvName.text = item.bookSourceName
            binding.tvUrl.text = item.bookSourceUrl
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemSourcePickerBinding) {
            binding.root.setOnClickListener {
                getItem(holder.layoutPosition)?.let { onSourceClick(it) }
            }
            binding.root.setOnLongClickListener {
                getItem(holder.layoutPosition)?.let { showSourceMenu(it, binding.root) }
                true
            }
        }
    }
}
