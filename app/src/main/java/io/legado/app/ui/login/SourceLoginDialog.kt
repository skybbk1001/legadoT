package io.legado.app.ui.login

import android.content.DialogInterface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import androidx.core.view.setPadding
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.script.rhino.runScriptWithContext
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.rule.RowUi
import io.legado.app.databinding.DialogLoginBinding
import io.legado.app.databinding.ItemFilletTextBinding
import io.legado.app.databinding.ItemLoginFieldBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.applyTint
import io.legado.app.utils.dpToPx
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.openUrl
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import splitties.views.onClick


class SourceLoginDialog : BaseDialogFragment(R.layout.dialog_login, true) {

    private val binding by viewBinding(DialogLoginBinding::bind)
    private val viewModel by activityViewModels<SourceLoginViewModel>()
    private var currentLoginUi: List<RowUi>? = null
    private var renderJob: Job? = null
    private var v2Delegate: SourceLoginV2Delegate? = null

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val source = viewModel.source ?: return
        binding.toolBar.title = getString(R.string.login_source, source.getTag())
        if (source.isLoginUiV2()) {
            v2Delegate = SourceLoginV2Delegate(this, binding, source, viewModel.book, viewModel.chapter)
        } else {
            renderLoginUi(source, null)
        }
        binding.toolBar.inflateMenu(R.menu.source_login)
        binding.toolBar.menu.applyTint(requireContext())
        if (v2Delegate != null) {
            // v2 提交是普通 action,无「确定」语义
            binding.toolBar.menu.findItem(R.id.menu_ok)?.isVisible = false
        }
        binding.toolBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_ok -> {
                    // 渲染完成前 currentLoginUi 为 null,「确定」会把空表单当成清除登录信息,误删已存凭据
                    if (renderJob?.isActive != true) {
                        val loginData = getLoginData(currentLoginUi)
                        login(source, loginData)
                    }
                }

                R.id.menu_show_login_header -> alert {
                    setTitle(R.string.login_header)
                    source.getLoginHeader()?.let { loginHeader ->
                        setMessage(loginHeader)
                        positiveButton(R.string.copy_text) {
                            appCtx.sendToClip(loginHeader)
                        }
                    }
                }

                R.id.menu_del_login_header -> source.removeLoginHeader()
                R.id.menu_clear_login_info -> lifecycleScope.launch(IO) {
                    source.removeLoginInfo()
                    source.removeLoginHeader()
                    context?.toastOnUi(R.string.success)
                }

                R.id.menu_log -> showDialogFragment<AppLogDialog>()
            }
            return@setOnMenuItemClickListener true
        }
        v2Delegate?.start()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        v2Delegate?.destroy()
        v2Delegate = null
    }

    private fun handleButtonClick(source: BaseSource, rowUi: RowUi, loginUi: List<RowUi>) {
        lifecycleScope.launch(IO) {
            if (rowUi.action.isAbsUrl()) {
                context?.openUrl(rowUi.action!!)
            } else if (rowUi.action != null) {
                // JavaScript
                val buttonFunctionJS = rowUi.action!!
                val loginJS = source.getLoginJs() ?: return@launch
                kotlin.runCatching {
                    runScriptWithContext {
                        source.evalJS("$loginJS\n$buttonFunctionJS") {
                            put("result", getLoginData(loginUi))
                            put("book", viewModel.book)
                            put("chapter", viewModel.chapter)
                        }
                    }
                }.onFailure { e ->
                    ensureActive()
                    AppLog.put("LoginUI Button ${rowUi.name} JavaScript error", e)
                }
            }
            ensureActive()
            withContext(Main) {
                val preserveInput = getLoginData(currentLoginUi)
                renderLoginUi(source, preserveInput)
            }
        }
    }

    private fun renderLoginUi(source: BaseSource, prefills: Map<String, String>?) {
        // loginUi 规则可能是 <js>…</js>(动态生成表单),脚本里可以随意 java.ajax 等联网,
        // 必须离开主线程求值,否则窗口显示前主线程就被拖死——用户看到的是"点了没反应"。
        // runScriptWithContext 挂上当前 Job:进 RhinoContext(安全策略放行)且弹窗关闭时可中断脚本。
        renderJob?.cancel()
        renderJob = lifecycleScope.launch {
            binding.rotateLoading.visible()
            val result = withContext(IO) {
                kotlin.runCatching {
                    runScriptWithContext {
                        // 首次渲染 prefills 传 null:已存登录信息也在 IO 线程读取解密
                        (prefills ?: source.getLoginInfoMap()) to
                            source.loginUi(viewModel.book, viewModel.chapter)
                    }
                }.onFailure { e ->
                    ensureActive()
                    AppLog.put("登录UI加载出错", e)
                }.getOrNull()
            }
            binding.rotateLoading.gone()
            currentLoginUi = result?.second
            buildLoginViews(source, result?.second, result?.first)
        }
    }

    private fun buildLoginViews(
        source: BaseSource,
        loginUi: List<RowUi>?,
        prefills: Map<String, String>?,
    ) {
        binding.flexbox.removeAllViews()
        try {
            loginUi?.forEachIndexed { index, rowUi ->
                when (rowUi.type) {
                    RowUi.Type.text -> ItemLoginFieldBinding.inflate(
                        layoutInflater,
                        binding.root,
                        false
                    ).let {
                        binding.flexbox.addView(it.root)
                        it.root.spaceBelow()
                        it.root.id = index + 1000
                        it.textInputLayout.hint = rowUi.name
                        it.textInputLayout.isHintAnimationEnabled = false
                        it.editText.setText(prefills?.get(rowUi.name))
                        it.textInputLayout.isHintAnimationEnabled = true
                    }

                    RowUi.Type.password -> ItemLoginFieldBinding.inflate(
                        layoutInflater,
                        binding.root,
                        false
                    ).let {
                        binding.flexbox.addView(it.root)
                        it.root.spaceBelow()
                        it.root.id = index + 1000
                        it.textInputLayout.hint = rowUi.name
                        it.editText.inputType =
                            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        it.textInputLayout.endIconMode =
                            com.google.android.material.textfield.TextInputLayout.END_ICON_PASSWORD_TOGGLE
                        it.textInputLayout.isHintAnimationEnabled = false
                        it.editText.setText(prefills?.get(rowUi.name))
                        it.textInputLayout.isHintAnimationEnabled = true
                    }

                    RowUi.Type.button -> ItemFilletTextBinding.inflate(
                        layoutInflater,
                        binding.root,
                        false
                    ).let {
                        binding.flexbox.addView(it.root)
                        rowUi.style().apply(it.root)
                        it.root.id = index + 1000
                        it.textView.text = rowUi.name
                        it.textView.setPadding(16.dpToPx())
                        it.root.onClick {
                            handleButtonClick(source, rowUi, loginUi)
                        }
                    }
                }
            }
        } catch (e: NullPointerException) {
            AppLog.put("登录UI JSON 数据错误", e, true)
        }
    }

    private fun getLoginData(loginUi: List<RowUi>?): HashMap<String, String> {
        val loginData = hashMapOf<String, String>()
        loginUi?.forEachIndexed { index, rowUi ->
            when (rowUi.type) {
                "text", "password" -> {
                    val rowView = binding.root.findViewById<View>(index + 1000)
                    ItemLoginFieldBinding.bind(rowView).editText.text?.let {
                        loginData[rowUi.name] = it.toString()
                    }
                }
            }
        }
        return loginData
    }

    private fun login(source: BaseSource, loginData: HashMap<String, String>) {
        lifecycleScope.launch(IO) {
            if (loginData.isEmpty()) {
                source.removeLoginInfo()
                withContext(Main) {
                    dismiss()
                }
            } else if (source.putLoginInfo(GSON.toJson(loginData))) {
                try {
                    runScriptWithContext {
                        source.login(viewModel.book, viewModel.chapter)
                    }
                    context?.toastOnUi(R.string.success)
                    withContext(Main) {
                        dismiss()
                    }
                } catch (e: Exception) {
                    AppLog.put("登录出错\n${e.localizedMessage}", e)
                    context?.toastOnUi("登录出错\n${e.localizedMessage}")
                    e.printOnDebug()
                }
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        activity?.finish()
    }

    /** 给 flexbox 中的整行输入项加底部间距，避免相邻输入框挤在一起 */
    private fun View.spaceBelow() {
        (layoutParams as? com.google.android.flexbox.FlexboxLayout.LayoutParams)?.let {
            it.bottomMargin = 4.dpToPx()
            layoutParams = it
        }
    }

}
