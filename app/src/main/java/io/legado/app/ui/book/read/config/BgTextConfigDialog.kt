package io.legado.app.ui.book.read.config

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.TooltipCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.google.android.material.slider.Slider
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.databinding.DialogReadBgTextBinding
import io.legado.app.databinding.DialogReviewIconEditBinding
import io.legado.app.databinding.DialogReviewIconManageBinding
import io.legado.app.databinding.ItemBgImageBinding
import io.legado.app.databinding.ItemReviewIconBinding
import io.legado.app.help.DefaultData
import io.legado.app.help.book.isImage
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReviewIconStore
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.lib.theme.getSecondaryTextColor
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.dialog.M3ColorPickerDialog
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyAppTint
import io.legado.app.utils.FileDoc
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.hexString
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.SvgUtils
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.createFileReplace
import io.legado.app.utils.createFolderReplace
import io.legado.app.utils.delete
import io.legado.app.utils.dpToPx
import io.legado.app.utils.externalCache
import io.legado.app.utils.externalFiles
import io.legado.app.utils.find
import io.legado.app.utils.getFile
import io.legado.app.utils.inputStream
import io.legado.app.utils.longToast
import io.legado.app.utils.openInputStream
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.outputStream
import io.legado.app.utils.postEvent
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.readBytes
import io.legado.app.utils.readUri
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream

class BgTextConfigDialog : BaseDialogFragment(R.layout.dialog_read_bg_text) {

    /** 贴底面板自设背景(bottomBackground),豁免统一圆角模板 */
    override val dialogForm = DialogForm.SELF_MANAGED

    companion object {
        const val TEXT_COLOR = "bgTextConfigTextColor"
        const val BG_COLOR = "bgTextConfigBgColor"
        const val REVIEW_ICON_COLOR = "bgTextConfigReviewIconColor"
    }

    private val binding by viewBinding(DialogReadBgTextBinding::bind)
    private val configFileName = "readConfig.zip"
    private val adapter by lazy { BgAdapter(requireContext(), secondaryTextColor) }
    private var primaryTextColor = 0
    private var secondaryTextColor = 0
    private val importFormNet = "网络导入"
    private val selectBgImage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            setBgFromUri(uri)
        }
    }
    private val selectExportDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            exportConfig(uri)
        }
    }
    private val selectImportDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            if (uri.path == "/$importFormNet") {
                importNetConfigAlert()
            } else {
                importConfig(uri)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(android.R.color.transparent)
            decorView.setPadding(0, 0, 0, 0)
            val attr = attributes
            attr.dimAmount = 0.0f
            attr.gravity = Gravity.BOTTOM
            attributes = attr
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        (activity as ReadBookActivity).bottomDialog++
        initView()
        initData()
        initEvent()
        initColorPickerListeners()
    }

    private fun initColorPickerListeners() {
        parentFragmentManager.setFragmentResultListener(REVIEW_ICON_COLOR, viewLifecycleOwner) { _, bundle ->
            val color = bundle.getInt(M3ColorPickerDialog.RESULT_COLOR)
            ReadBookConfig.reviewIconColor = color
            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 9, 11))
        }
        parentFragmentManager.setFragmentResultListener(TEXT_COLOR, viewLifecycleOwner) { _, bundle ->
            val color = bundle.getInt(M3ColorPickerDialog.RESULT_COLOR)
            ReadBookConfig.durConfig.setCurTextColor(color)
            postEvent(EventBus.UP_CONFIG, arrayListOf(2, 6, 9, 11))
            if (AppConfig.readBarStyleFollowPage) {
                postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
            }
        }
        parentFragmentManager.setFragmentResultListener(BG_COLOR, viewLifecycleOwner) { _, bundle ->
            val color = bundle.getInt(M3ColorPickerDialog.RESULT_COLOR)
            ReadBookConfig.durConfig.setCurBg(0, "#${color.hexString}")
            postEvent(EventBus.UP_CONFIG, arrayListOf(1))
            if (AppConfig.readBarStyleFollowPage) {
                postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        ReadBookConfig.save()
        (activity as ReadBookActivity).bottomDialog--
    }

    private fun initView() = binding.run {
        val bg = requireContext().bottomBackground
        val isLight = ColorUtils.isColorLight(bg)
        primaryTextColor = requireContext().getPrimaryTextColor(isLight)
        secondaryTextColor = requireContext().getSecondaryTextColor(isLight)
        val radius = requireContext().resources.getDimension(R.dimen.radius_l)
        rootView.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            setColor(bg)
        }
        tvNameTitle.setTextColor(primaryTextColor)
        tvName.setTextColor(secondaryTextColor)
        ivEdit.setColorFilter(secondaryTextColor, PorterDuff.Mode.SRC_IN)
        tvRestore.setTextColor(primaryTextColor)
        swDarkStatusIcon.setTextColor(primaryTextColor)
        swUnderline.setTextColor(primaryTextColor)
        ivImport.setColorFilter(primaryTextColor, PorterDuff.Mode.SRC_IN)
        ivExport.setColorFilter(primaryTextColor, PorterDuff.Mode.SRC_IN)
        ivDelete.setColorFilter(primaryTextColor, PorterDuff.Mode.SRC_IN)
        tvBgAlpha.setTextColor(primaryTextColor)
        tvBgImage.setTextColor(primaryTextColor)
        sbBgAlpha.applyAppTint(primaryTextColor)
        // 描边钮随 bottomBackground 亮暗染色(替代原 StrokeTextView 自绘)
        val btnColor = ColorStateList.valueOf(primaryTextColor)
        arrayOf(tvReviewIconSvg, tvReviewIconSize, tvReviewIconColor, tvTextColor, tvBgColor)
            .forEach {
                it.setTextColor(btnColor)
                it.strokeColor = btnColor
                it.rippleColor =
                    ColorStateList.valueOf(requireContext().getCompatColor(R.color.transparent30))
            }
        swUnderline.isGone = ReadBook.book?.isImage == true
        recyclerView.adapter = adapter
        adapter.addHeaderView {
            ItemBgImageBinding.inflate(layoutInflater, it, false).apply {
                tvName.setTextColor(secondaryTextColor)
                tvName.text = getString(R.string.select_image)
                ivBg.setImageResource(R.drawable.ic_image)
                ivBg.setColorFilter(primaryTextColor, PorterDuff.Mode.SRC_IN)
                root.setOnClickListener {
                    selectBgImage.launch {
                        mode = HandleFileContract.IMAGE
                    }
                }
            }
        }
        requireContext().assets.list("bg")?.let {
            adapter.setItems(it.toList())
        }
    }

    @SuppressLint("InflateParams")
    private fun initData() = with(ReadBookConfig.durConfig) {
        binding.tvName.text = name.ifBlank { "文字" }
        binding.swDarkStatusIcon.isChecked = curStatusIconDark()
        binding.swUnderline.isChecked = underline
        binding.sbBgAlpha.value = bgAlpha.toFloat()
            .coerceIn(binding.sbBgAlpha.valueFrom, binding.sbBgAlpha.valueTo)
    }

    @SuppressLint("InflateParams")
    private fun initEvent() = with(ReadBookConfig.durConfig) {
        binding.ivEdit.setOnClickListener {
            alert(R.string.style_name) {
                val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                    editView.hint = "name"
                    editView.setText(ReadBookConfig.durConfig.name)
                }
                customView { alertBinding.root }
                okButton {
                    alertBinding.editView.text?.toString()?.let {
                        binding.tvName.text = it
                        ReadBookConfig.durConfig.name = it
                    }
                }
                cancelButton()
            }
        }
        binding.tvRestore.setOnClickListener {
            val defaultConfigs = DefaultData.readConfigs
            val layoutNames = defaultConfigs.map { it.name }
            context?.selector("选择预设布局", layoutNames) { _, i ->
                if (i >= 0) {
                    ReadBookConfig.durConfig = defaultConfigs[i].copy()
                    initData()
                    postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
                }
            }
        }
        binding.swDarkStatusIcon.setOnCheckedChangeListener { _, isChecked ->
            setCurStatusIconDark(isChecked)
            (activity as? ReadBookActivity)?.upSystemUiVisibility()
        }
        binding.swUnderline.setOnCheckedChangeListener { _, isChecked ->
            underline = isChecked
            postEvent(EventBus.UP_CONFIG, arrayListOf(6, 9, 11))
        }
        binding.tvReviewIconSvg.setOnClickListener {
            showReviewIconManager()
        }
        binding.tvReviewIconSize.setOnClickListener {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = getString(R.string.review_icon_size_hint)
                editView.inputType = InputType.TYPE_CLASS_NUMBER
                editView.setSingleLine(true)
                editView.setText(ReadBookConfig.reviewIconScale.toString())
                editView.setSelection(editView.text?.length ?: 0)
            }
            val dialog = alert(R.string.review_icon_size_title) {
                customView { alertBinding.root }
                okButton()
                cancelButton()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val newScale = alertBinding.editView.text?.toString().orEmpty().trim().toIntOrNull()
                if (newScale == null || newScale !in 50..200) {
                    toastOnUi(R.string.review_icon_size_invalid)
                    return@setOnClickListener
                }
                if (newScale != ReadBookConfig.reviewIconScale) {
                    ReadBookConfig.reviewIconScale = newScale
                    notifyReviewIconStyleChanged(relayout = true)
                }
                dialog.dismiss()
            }
        }
        binding.tvReviewIconColor.setOnClickListener {
            M3ColorPickerDialog.show(
                parentFragmentManager,
                REVIEW_ICON_COLOR,
                ReadBookConfig.reviewIconColor.takeIf { it != 0 }
                    ?: ChapterProvider.reviewPaint.color,
                false,
                null
            )
        }
        binding.tvReviewIconColor.setOnLongClickListener {
            if (ReadBookConfig.reviewIconColor != 0) {
                ReadBookConfig.reviewIconColor = 0
                postEvent(EventBus.UP_CONFIG, arrayListOf(8, 9, 11))
                toastOnUi(R.string.review_icon_color_reset)
            }
            true
        }
        binding.tvTextColor.setOnClickListener {
            M3ColorPickerDialog.show(
                parentFragmentManager,
                TEXT_COLOR,
                curTextColor(),
                false,
                null
            )
        }
        binding.tvBgColor.setOnClickListener {
            val bgColor =
                if (curBgType() == 0) curBgStr().toColorInt()
                else "#015A86".toColorInt()
            M3ColorPickerDialog.show(
                parentFragmentManager,
                BG_COLOR,
                bgColor,
                false,
                null
            )
        }
        binding.tvBgColor.apply {
            TooltipCompat.setTooltipText(this, text)
        }
        binding.ivImport.setOnClickListener {
            selectImportDoc.launch {
                mode = HandleFileContract.FILE
                title = getString(R.string.import_str)
                allowExtensions = arrayOf("zip")
                otherActions = arrayListOf(SelectItem(importFormNet, -1))
            }
        }
        binding.ivExport.setOnClickListener {
            selectExportDir.launch {
                title = getString(R.string.export_str)
            }
        }
        binding.ivDelete.setOnClickListener {
            if (ReadBookConfig.deleteDur()) {
                postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
                dismissAllowingStateLoss()
            } else {
                toastOnUi("数量已是最少,不能删除.")
            }
        }
        binding.sbBgAlpha.addOnChangeListener { _, value, _ ->
            ReadBookConfig.bgAlpha = value.toInt()
            postEvent(EventBus.UP_CONFIG, arrayListOf(3))
        }
        binding.sbBgAlpha.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit

            override fun onStopTrackingTouch(slider: Slider) {
                postEvent(EventBus.UP_CONFIG, arrayListOf(3))
            }
        })
    }

    private fun exportConfig(uri: Uri) {
        val exportFileName = if (ReadBookConfig.config.name.isBlank()) {
            configFileName
        } else {
            "${ReadBookConfig.config.name}.zip"
        }
        execute {
            val exportFiles = arrayListOf<File>()
            val configDir = requireContext().externalCache.getFile("readConfig")
            configDir.createFolderReplace()
            val configFile = configDir.getFile("readConfig.json")
            configFile.createFileReplace()
            val config = ReadBookConfig.getExportConfig()
            val fontPath = ReadBookConfig.textFont
            if (fontPath.isNotEmpty()) {
                val fontDoc = FileDoc.fromFile(fontPath)
                val fontName = fontDoc.name
                val fontInputStream = fontDoc.openInputStream().getOrNull()
                fontInputStream?.use {
                    val fontExportFile = FileUtils.createFileIfNotExist(configDir, fontName)
                    fontExportFile.outputStream().use { out ->
                        it.copyTo(out)
                    }
                    config.textFont = fontName
                    exportFiles.add(fontExportFile)
                }
            }
            configFile.writeText(GSON.toJson(config))
            exportFiles.add(configFile)
            repeat(3) {
                val path = ReadBookConfig.durConfig.getBgPath(it) ?: return@repeat
                val bgExportFile = copyBgImage(path, configDir) ?: return@repeat
                exportFiles.add(bgExportFile)
            }
            val configZipPath = FileUtils.getPath(requireContext().externalCache, configFileName)
            if (ZipUtils.zipFiles(exportFiles, File(configZipPath))) {
                val exportDir = FileDoc.fromDir(uri)
                exportDir.find(exportFileName)?.delete()
                val exportFileDoc = exportDir.createFileIfNotExist(exportFileName)
                exportFileDoc.openOutputStream().getOrThrow().use { out ->
                    File(configZipPath).inputStream().use {
                        it.copyTo(out)
                    }
                }
            }
        }.onSuccess {
            toastOnUi("导出成功, 文件名为 $exportFileName")
        }.onError {
            it.printOnDebug()
            AppLog.put("导出失败:${it.localizedMessage}", it)
            longToast("导出失败:${it.localizedMessage}")
        }
    }

    private fun copyBgImage(path: String, configDir: File): File? {
        val bgName = FileUtils.getName(path)
        val bgFile = File(path)
        if (bgFile.exists()) {
            val bgExportFile = File(FileUtils.getPath(configDir, bgName))
            if (!bgExportFile.exists()) {
                bgFile.copyTo(bgExportFile)
                return bgExportFile
            }
        }
        return null
    }

    @SuppressLint("InflateParams")
    private fun importNetConfigAlert() {
        alert("输入地址") {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater)
            customView { alertBinding.root }
            okButton {
                alertBinding.editView.text?.toString()?.let { url ->
                    importNetConfig(url)
                }
            }
            cancelButton()
        }
    }

    private fun importNetConfig(url: String) {
        execute {
            okHttpClient.newCallResponseBody {
                url(url)
            }.bytes().let {
                importConfig(it)
            }
        }.onError {
            longToast(it.stackTraceStr)
        }
    }

    private fun importConfig(uri: Uri) {
        execute {
            importConfig(uri.readBytes(requireContext()))
        }.onError {
            it.printOnDebug()
            longToast("导入失败:${it.localizedMessage}")
        }
    }

    private fun importConfig(byteArray: ByteArray) {
        execute {
            ReadBookConfig.import(byteArray)
        }.onSuccess {
            ReadBookConfig.durConfig = it
            postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
            toastOnUi("导入成功")
        }.onError {
            it.printOnDebug()
            longToast("导入失败:${it.localizedMessage}")
        }
    }

    /**
     * 段评图标管理器:图标库网格中单击应用、长按编辑/删除,首个单元格恢复默认图标,末尾新增图标
     */
    @SuppressLint("InflateParams")
    private fun showReviewIconManager() {
        val context = requireContext()
        val accentColor = context.accentColor
        val managerBinding = DialogReviewIconManageBinding.inflate(layoutInflater)
        val iconAdapter = ReviewIconAdapter(context, secondaryTextColor, accentColor)
        managerBinding.recyclerView.adapter = iconAdapter
        var defaultItemBinding: ItemReviewIconBinding? = null

        fun refreshManagerSelection() {
            val current = ReadBookConfig.reviewIconSvg.trim()
            iconAdapter.selectedSvg = current
            defaultItemBinding?.run {
                tvName.setTextColor(if (current.isBlank()) accentColor else secondaryTextColor)
                ivSelected.isVisible = current.isBlank()
                ivSelected.setColorFilter(accentColor)
            }
        }

        fun refreshIconItems() {
            iconAdapter.setItems(ReviewIconStore.iconList.toList())
            refreshManagerSelection()
        }

        iconAdapter.addHeaderView {
            ItemReviewIconBinding.inflate(layoutInflater, it, false).apply {
                defaultItemBinding = this
                ivIcon.setImageBitmap(ReviewIconAdapter.createDefaultPreviewBitmap(secondaryTextColor))
                tvName.text = getString(R.string.review_icon_default)
                tvName.setTextColor(secondaryTextColor)
                root.setOnClickListener {
                    applyReviewIconSvg("")
                    refreshManagerSelection()
                }
            }
        }
        iconAdapter.addFooterView {
            ItemReviewIconBinding.inflate(layoutInflater, it, false).apply {
                ivIcon.setImageResource(R.drawable.ic_add)
                ivIcon.setColorFilter(secondaryTextColor, PorterDuff.Mode.SRC_IN)
                tvName.text = getString(R.string.review_icon_add)
                tvName.setTextColor(secondaryTextColor)
                root.setOnClickListener {
                    //当前自定义图标不在库中时,预填它方便直接入库保存
                    val current = ReadBookConfig.reviewIconSvg.trim()
                    val prefill = current.takeIf { it.isNotBlank() && !ReviewIconStore.containsSvg(it) }
                    showReviewIconEditDialog(null, prefill.orEmpty()) { refreshIconItems() }
                }
            }
        }
        iconAdapter.setOnItemClickListener { _, item ->
            applyReviewIconSvg(item.svg.trim())
            refreshManagerSelection()
        }
        iconAdapter.setOnItemLongClickListener { _, item ->
            showReviewIconItemMenu(item) { refreshIconItems() }
            true
        }
        iconAdapter.setItems(ReviewIconStore.iconList.toList())
        refreshManagerSelection()
        alert(R.string.review_icon_svg_title) {
            customView { managerBinding.root }
            okButton()
        }
        //图标较多时限制列表高度,避免弹窗撑满屏幕
        managerBinding.recyclerView.post {
            val maxHeight = 360.dpToPx()
            if (managerBinding.recyclerView.height > maxHeight) {
                managerBinding.recyclerView.layoutParams.height = maxHeight
                managerBinding.recyclerView.requestLayout()
            }
        }
    }

    /**
     * 图标项长按菜单:编辑/删除
     */
    private fun showReviewIconItemMenu(
        item: ReviewIconStore.ReviewIcon,
        onChanged: () -> Unit
    ) {
        context?.selector(
            item.name,
            listOf(getString(R.string.edit), getString(R.string.delete))
        ) { _, which ->
            when (which) {
                0 -> showReviewIconEditDialog(item, "") { onChanged() }

                1 -> alert(getString(R.string.delete), item.name) {
                    yesButton {
                        ReviewIconStore.removeIcon(item)
                        onChanged()
                    }
                    noButton()
                }
            }
        }
    }

    /**
     * 新增/编辑图标:名称 + SVG 模板,保存入库;新增或编辑当前应用图标时同步应用
     */
    @SuppressLint("InflateParams")
    private fun showReviewIconEditDialog(
        icon: ReviewIconStore.ReviewIcon?,
        prefillSvg: String,
        onChanged: () -> Unit
    ) {
        val alertBinding = DialogReviewIconEditBinding.inflate(layoutInflater).apply {
            editName.setText(icon?.name)
            editSvg.hint = getString(R.string.review_icon_svg_hint)
            editSvg.setSingleLine(false)
            editSvg.maxLines = 8
            editSvg.setText(icon?.svg ?: prefillSvg)
            editSvg.setSelection(editSvg.text?.length ?: 0)
        }
        val dialog = alert(if (icon == null) R.string.review_icon_add else R.string.edit) {
            customView { alertBinding.root }
            okButton()
            cancelButton()
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val newSvg = alertBinding.editSvg.text?.toString().orEmpty().trim()
            if (newSvg.isBlank() || !isValidReviewIconSvg(newSvg)) {
                toastOnUi(R.string.review_icon_svg_invalid)
                return@setOnClickListener
            }
            val newName = alertBinding.editName.text?.toString().orEmpty().trim()
                .ifBlank {
                    getString(R.string.review_icon_default_name, ReviewIconStore.iconList.size + 1)
                }
            if (icon == null) {
                ReviewIconStore.addIcon(newName, newSvg)
                applyReviewIconSvg(newSvg)
            } else {
                val wasApplied = icon.svg.trim() == ReadBookConfig.reviewIconSvg.trim()
                icon.name = newName
                icon.svg = newSvg
                ReviewIconStore.save()
                if (wasApplied) {
                    applyReviewIconSvg(newSvg)
                }
            }
            onChanged()
            dialog.dismiss()
        }
    }

    private fun applyReviewIconSvg(svg: String) {
        if (ReadBookConfig.reviewIconSvg != svg) {
            ReadBookConfig.reviewIconSvg = svg
            notifyReviewIconStyleChanged(relayout = true)
        }
    }

    private fun notifyReviewIconStyleChanged(relayout: Boolean) {
        ChapterProvider.clearReviewIconCache()
        if (relayout) {
            ChapterProvider.refreshReviewColumnsForStyleChange()
        }
        postEvent(EventBus.UP_CONFIG, arrayListOf(6, 9, 11))
    }

    private fun isValidReviewIconSvg(svg: String): Boolean {
        val bitmap = SvgUtils.createBitmapFromSvgText(
            svg.replace(ChapterProvider.reviewIconPlaceholder, "88"), 48, 48
        )
        return bitmap?.let {
            it.recycle()
            true
        } ?: false
    }
    private fun setBgFromUri(uri: Uri) {
        readUri(uri) { fileDoc, inputStream ->
            kotlin.runCatching {
                var file = requireContext().externalFiles
                val suffix = fileDoc.name.substringAfterLast(".")
                val fileName = uri.inputStream(requireContext()).getOrThrow().use {
                    MD5Utils.md5Encode(it) + ".$suffix"
                }
                file = FileUtils.createFileIfNotExist(file, "bg", fileName)
                FileOutputStream(file).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
                ReadBookConfig.durConfig.setCurBg(2, fileName)
                postEvent(EventBus.UP_CONFIG, arrayListOf(1))
            }.onFailure {
                appCtx.toastOnUi(it.localizedMessage)
            }
        }
    }
}
