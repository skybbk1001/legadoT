package io.legado.app.ui.widget.dialog

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogWebCodeViewBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyTint
import io.legado.app.utils.imeHeight
import io.legado.app.utils.navigationBarHeight
import io.legado.app.utils.setLayout
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import splitties.views.bottomPadding
import splitties.views.topPadding

class WebCodeDialog() : BaseDialogFragment(R.layout.dialog_web_code_view),
    CodeEditorWebViewPool.Client {

    /** 真全屏编辑器页:贴边盖满整屏、不透明页面背景,豁免浮动卡留边模板 */
    override val dialogForm = DialogForm.SELF_MANAGED

    companion object {
        private const val DIALOG_TAG = "WebCodeDialog"

        fun show(
            manager: FragmentManager,
            code: String,
            requestId: String? = null,
            title: String? = null
        ): Boolean {
            if (manager.isStateSaved || manager.findFragmentByTag(DIALOG_TAG) != null) {
                return false
            }
            WebCodeDialog(code, requestId, title).show(manager, DIALOG_TAG)
            return true
        }
    }

    constructor(code: String, requestId: String? = null, title: String? = null) : this() {
        arguments = Bundle().apply {
            putString("code", code)
            putString("requestId", requestId)
            putString("title", title)
        }
    }

    private val binding by viewBinding(DialogWebCodeViewBinding::bind)
    private var pendingCode: String = ""
    private var encodedCode: String = ""
    private var editorReady = false
    private var bootFailed = false
    private var initialCodeApplied = false
    private var pendingClose = false
    private var confirmShown = false

    // 当前应注入编辑器页面的底部安全区高度(键盘弹出时为 0,见 insets 监听)
    private var editorBottomInsetPx = 0

    /** 内容会话键：随 requestId 稳定跨重建，旋转后据此跳过初始代码重发 */
    private val sessionKey: String?
        get() = arguments?.getString("requestId")?.let { "dlg:$it" }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog?.window?.run {
            // 对话框默认窗底是带内边距的面板皮,透明化让页面视图铺满整个窗口
            setBackgroundDrawableResource(R.color.transparent)
            // 对话框窗口独立于 Activity,沉浸须自设:铺到系统栏后+透明栏色,
            // 状态栏/导航栏避让由视图侧 insets 监听负责
            WindowCompat.setDecorFitsSystemWindows(this, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // 浮动对话框的窗框默认自带 fitInsetsTypes=systemBars(),
                // WindowManager 会把窗框缩到导航栏上方留缝;清零让窗框铺满整屏
                val attr = attributes
                attr.fitInsetsTypes = 0
                attributes = attr
            }
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            @Suppress("DEPRECATION")
            statusBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // 透明导航栏时华为等 ROM 会叠系统对比度灰罩,关闭之
                isNavigationBarContrastEnforced = false
            }
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            WindowInsetsControllerCompat(this, decorView).run {
                isAppearanceLightStatusBars = ColorUtils.isColorLight(primaryColor)
                isAppearanceLightNavigationBars = ColorUtils.isColorLight(backgroundColor)
            }
        }
        dialog?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                requestClose()
                true
            } else {
                false
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        // 窗口铺到系统栏后:工具栏自垫状态栏高度;编辑器键盘弹出用 padding 抬升,
        // 导航栏高度注入页内避让(与 JsSourceEditActivity 同一套通道)
        binding.toolBar.setOnApplyWindowInsetsListenerCompat { v, windowInsets ->
            v.topPadding = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            windowInsets
        }
        binding.flEditor.setOnApplyWindowInsetsListenerCompat { v, windowInsets ->
            val imeHeight = windowInsets.imeHeight
            v.bottomPadding = if (imeHeight == 0) 0 else imeHeight
            editorBottomInsetPx = if (imeHeight == 0) windowInsets.navigationBarHeight else 0
            CodeEditorWebViewPool.applyBottomInset(editorBottomInsetPx)
            windowInsets
        }
        arguments?.getString("title")?.let {
            binding.toolBar.title = it
        }
        binding.toolBar.inflateMenu(R.menu.code_edit)
        binding.toolBar.menu.applyTint(requireContext())
        val saveItem = binding.toolBar.menu.findItem(R.id.menu_save)
        saveItem?.isEnabled = false
        editorReady = false
        bootFailed = false
        initialCodeApplied = false
        pendingCode = arguments?.getString("code").orEmpty()
        encodedCode = Base64.encodeToString(
            pendingCode.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        binding.toolBar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_save -> {
                    if (editorReady && initialCodeApplied && !bootFailed) {
                        CodeEditorWebViewPool.evaluateJavascript(
                            "window.__save && window.__save();"
                        )
                    }
                    return@setOnMenuItemClickListener true
                }
            }
            true
        }
        updateEditorUiState()
        if (!CodeEditorWebViewPool.attach(binding.webViewContainer, this)) {
            toastOnUi(R.string.code_editor_busy)
            dismissAllowingStateLoss()
        }
    }

    private fun updateEditorUiState() {
        val contentReady = editorReady && initialCodeApplied && !bootFailed
        binding.toolBar.menu.findItem(R.id.menu_save)?.isEnabled = contentReady
        binding.loadingProgress.visibility = if (contentReady || bootFailed) {
            View.GONE
        } else {
            View.VISIBLE
        }
        binding.webViewContainer.alpha = if (contentReady || bootFailed) 1f else 0f
    }

    private fun sendInitialCodeToEditor() {
        if (!editorReady || bootFailed || initialCodeApplied) return
        // 同会话重挂载（旋转/重建）：编辑器文档就是本会话内容，重发会用初始值覆盖未保存编辑
        if (CodeEditorWebViewPool.isContentSession(sessionKey)) {
            initialCodeApplied = true
            updateEditorUiState()
            return
        }
        CodeEditorWebViewPool.evaluateJavascript(
            "window.setCodeFromAndroid && window.setCodeFromAndroid('" + encodedCode + "');",
        ) {
            if (view == null) return@evaluateJavascript
            initialCodeApplied = true
            CodeEditorWebViewPool.markContentSession(sessionKey)
            updateEditorUiState()
        }
    }

    private fun requestClose() {
        if (pendingClose || confirmShown) return
        if (!editorReady || !initialCodeApplied) {
            dismissAllowingStateLoss()
            return
        }
        pendingClose = true
        CodeEditorWebViewPool.evaluateJavascript("window.__getCode && window.__getCode();") { value ->
            pendingClose = false
            if (view == null) return@evaluateJavascript
            val current = CodeEditorWebViewPool.decodeJsResult(value)
            if (current == null || current == pendingCode) {
                dismissAllowingStateLoss()
                return@evaluateJavascript
            }
            confirmShown = true
            alert(R.string.exit, R.string.exit_no_save) {
                positiveButton(R.string.yes) {
                    confirmShown = false
                }
                negativeButton(R.string.no) {
                    confirmShown = false
                    dismissAllowingStateLoss()
                }
                onDismiss {
                    confirmShown = false
                }
            }
        }
    }

    override fun onDestroyView() {
        CodeEditorWebViewPool.detach(this)
        super.onDestroyView()
    }

    override fun onEditorReady() {
        if (view == null) return
        bootFailed = false
        editorReady = true
        CodeEditorWebViewPool.applyAppTheme()
        // 池化页面可能残留其他会话的注入值,就绪即按本窗当前 insets 重发
        CodeEditorWebViewPool.applyBottomInset(editorBottomInsetPx)
        updateEditorUiState()
        sendInitialCodeToEditor()
    }

    override fun onEditorBootError(message: String?) {
        if (view == null || editorReady) return
        bootFailed = true
        updateEditorUiState()
    }

    override fun onEditorSave(text: String) {
        if (view == null) return
        if (text == pendingCode) {
            // 未修改时静默关闭会让用户不确定是否保存成功:提示并留在编辑页
            toastOnUi(R.string.code_no_changes)
            return
        }
        pendingCode = text
        val requestId = arguments?.getString("requestId")
        (parentFragment as? Callback)?.onCodeSave(text, requestId)
            ?: (activity as? Callback)?.onCodeSave(text, requestId)
        dismissAllowingStateLoss()
    }

    interface Callback {
        fun onCodeSave(code: String, requestId: String?)
    }
}
