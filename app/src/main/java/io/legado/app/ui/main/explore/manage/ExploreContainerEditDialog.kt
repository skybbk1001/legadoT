package io.legado.app.ui.main.explore.manage

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.ExploreContainer
import io.legado.app.databinding.DialogExploreContainerEditBinding
import io.legado.app.help.source.ExploreContainerHelp
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.utils.GSON
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 容器编辑对话框(管理页点行 / 发现页长按菜单共用)。
 * 可换书源/换分类:换书源后必须重选分类,改动在确定时一并落库
 */
class ExploreContainerEditDialog : BaseDialogFragment(R.layout.dialog_explore_container_edit, true) {

    companion object {
        private const val PICK_SOURCE_KEY = "exploreEditPickSource"
        private const val PICK_KIND_KEY = "exploreEditPickKind"

        fun edit(id: Long) = ExploreContainerEditDialog().apply {
            arguments = Bundle().apply { putLong("id", id) }
        }
    }

    private val binding by viewBinding(DialogExploreContainerEditBinding::bind)
    private var container: ExploreContainer? = null

    /** 进入编辑时的指向快照,保存时据此判断是否清旧缓存 */
    private var originTarget: Triple<String, String, String>? = null

    /** 换书源后待确认的新书源 URL;多选分类确定时一并落库 */
    private var pendingSourceUrl: String? = null

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.tvCancel.setOnClickListener { dismiss() }
        binding.tvOk.setOnClickListener { save() }
        binding.tvDelete.setOnClickListener { delete() }
        binding.tvChangeSource.setOnClickListener {
            showDialogFragment(ExploreSourcePickerDialog.pick(PICK_SOURCE_KEY))
        }
        binding.tvChangeKind.setOnClickListener { pickKind() }
        binding.tvPickGroup.setOnClickListener { pickGroup() }
        childFragmentManager.setFragmentResultListener(
            PICK_SOURCE_KEY, viewLifecycleOwner
        ) { _, bundle ->
            bundle.getString("sourceUrl")?.let { onSourcePicked(it) }
        }
        childFragmentManager.setFragmentResultListener(
            PICK_KIND_KEY, viewLifecycleOwner
        ) { _, bundle ->
            onKindsPicked(bundle)
        }
        val restored = savedInstanceState?.getString("container")?.let { json ->
            runCatching { GSON.fromJson(json, ExploreContainer::class.java) }.getOrNull()
        }
        if (restored != null) {
            container = restored
            originTarget = savedInstanceState.getStringArray("originTarget")
                ?.takeIf { it.size == 3 }
                ?.let { Triple(it[0], it[1], it[2]) }
                ?: Triple(restored.sourceUrl, restored.kindUrl, restored.kindTitle)
            pendingSourceUrl = savedInstanceState.getString("pendingSourceUrl")
            // 输入控件走系统自动恢复,只刷指向信息,不重查 DB、不 upView 覆盖
            upSourceInfo(restored)
            return
        }
        val id = arguments?.getLong("id", -1) ?: -1
        viewLifecycleOwner.lifecycleScope.launch {
            val c = withContext(IO) { appDb.exploreContainerDao.getById(id) }
            if (c == null) {
                toastOnUi(R.string.explore_source_not_found)
                dismiss()
                return@launch
            }
            container = c
            originTarget = Triple(c.sourceUrl, c.kindUrl, c.kindTitle)
            upView(c)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 旋转保持未落库的编辑态(换书源/换分类结果);输入控件由系统 view state 自动恢复
        container?.let { outState.putString("container", GSON.toJson(it)) }
        originTarget?.let {
            outState.putStringArray("originTarget", arrayOf(it.first, it.second, it.third))
        }
        pendingSourceUrl?.let { outState.putString("pendingSourceUrl", it) }
    }

    private fun upView(c: ExploreContainer) = binding.run {
        upSourceInfo(c)
        etTitle.setText(c.customTitle)
        etGroup.setText(c.groupName)
        if (c.style == ExploreContainer.STYLE_LIST) {
            rbList.isChecked = true
        } else {
            rbFlow.isChecked = true
        }
        etCount.setText(c.listCount.toString())
    }

    private fun upSourceInfo(c: ExploreContainer) {
        binding.tvSourceInfo.text = "${c.sourceName} · ${c.kindTitle}"
    }

    private fun onSourcePicked(sourceUrl: String) {
        val c = container ?: return
        // 换书源后旧书源勾选集合失效:记录待确认的新书源,不预选,由用户重新多选
        pendingSourceUrl = sourceUrl
        pickKinds(sourceUrl, emptyList())
    }

    private fun pickKind() {
        val c = container ?: return
        pendingSourceUrl = null
        // 预选当前固化的分类集合;旧数据(kindTitles 空)无预选
        val selectedUrls: List<String> = if (c.kindTitles.isNotBlank()) {
            c.kindUrls.splitNotBlank(AppPattern.splitGroupRegex).toList()
        } else {
            emptyList()
        }
        pickKinds(c.sourceUrl, selectedUrls)
    }

    /** 弹出多选分类弹窗:预选 selectedUrls,确定后经 [onKindsPicked] 落库 */
    private fun pickKinds(sourceUrl: String, selectedUrls: List<String>) {
        showDialogFragment(KindPickerDialog.pick(sourceUrl, selectedUrls, PICK_KIND_KEY))
    }

    /** 多选分类结果:同步 kindTitles/kindUrls,指向对齐第一个选中分类;换书源时一并落库 */
    private fun onKindsPicked(bundle: Bundle) {
        val c = container ?: return
        val titles = bundle.getStringArrayList("titles") ?: return
        val urls = bundle.getStringArrayList("urls") ?: return
        if (titles.isEmpty()) return
        pendingSourceUrl?.let { url ->
            c.sourceUrl = url
            bundle.getString("sourceName")?.let { c.sourceName = it }
        }
        pendingSourceUrl = null
        c.kindTitle = titles.first()
        c.kindUrl = urls.getOrElse(0) { "" }
        c.kindTitles = titles.joinToString(",")
        c.kindUrls = urls.joinToString(",")
        upSourceInfo(c)
    }

    /** 已有分组选择器:点选追加(不重复);自由输入(逗号分隔)仍可新建 */
    private fun pickGroup() {
        viewLifecycleOwner.lifecycleScope.launch {
            val groups = withContext(IO) {
                ExploreContainerHelp.dealGroups(
                    appDb.exploreContainerDao.all.map { it.groupName }
                )
            }
            if (groups.isEmpty()) {
                toastOnUi(R.string.explore_no_groups)
                return@launch
            }
            requireContext().selector(getString(R.string.explore_pick_group), groups) { _, i ->
                val set = linkedSetOf<String>()
                set.addAll(
                    binding.etGroup.text?.toString().orEmpty()
                        .splitNotBlank(AppPattern.splitGroupRegex)
                )
                set.add(groups[i])
                binding.etGroup.setText(set.joinToString(","))
            }
        }
    }

    private fun save() {
        val c = container ?: return
        c.customTitle = binding.etTitle.text?.toString()?.takeUnless { it.isBlank() }
        c.groupName = binding.etGroup.text?.toString()?.trim().orEmpty()
        c.style = if (binding.rbList.isChecked) {
            ExploreContainer.STYLE_LIST
        } else {
            ExploreContainer.STYLE_FLOW
        }
        c.listCount = binding.etCount.text?.toString()?.toIntOrNull()?.coerceIn(1, 20) ?: 3
        val targetChanged = originTarget != Triple(c.sourceUrl, c.kindUrl, c.kindTitle)
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(IO) {
                appDb.exploreContainerDao.update(c)
                // 指向变了:旧分类的缓存作废,防止下次冷启动水合出旧书
                if (targetChanged) ExploreContainerHelp.removeCache(c.id)
            }
            dismiss()
        }
    }

    private fun delete() {
        val c = container ?: return
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + c.getDisplayTitle())
            noButton()
            yesButton {
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(IO) {
                        appDb.exploreContainerDao.delete(c)
                        ExploreContainerHelp.removeCache(c.id)
                    }
                    dismiss()
                }
            }
        }
    }
}
