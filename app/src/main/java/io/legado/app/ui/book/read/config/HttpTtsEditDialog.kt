package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.HttpTTS
import io.legado.app.databinding.DialogHttpTtsEditBinding
import io.legado.app.databinding.ViewCodeEditFieldBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.widget.code.CodeView
import io.legado.app.ui.widget.code.addJsPattern
import io.legado.app.ui.widget.code.addJsonPattern
import io.legado.app.ui.widget.code.addLegadoPattern
import io.legado.app.ui.widget.code.bindCodeEditField
import io.legado.app.ui.widget.dialog.WebCodeDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.applyTint
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

class HttpTtsEditDialog() : BaseDialogFragment(R.layout.dialog_http_tts_edit, true),
    Toolbar.OnMenuItemClickListener,
    WebCodeDialog.Callback {

    constructor(id: Long) : this() {
        arguments = Bundle().apply {
            putLong("id", id)
        }
    }

    private val binding by viewBinding(DialogHttpTtsEditBinding::bind)
    private val viewModel by viewModels<HttpTtsEditViewModel>()
    private var initialDraft: HttpTtsDraft? = null
    private var bypassDismissCheck = false
    private val webEditRequests = linkedMapOf<String, CodeView>()
    private lateinit var urlField: CodeField
    private lateinit var contentTypeField: CodeField
    private lateinit var concurrentRateField: CodeField
    private lateinit var loginUrlField: CodeField
    private lateinit var loginUiField: CodeField
    private lateinit var loginCheckJsField: CodeField
    private lateinit var headersField: CodeField
    private lateinit var jsLibField: CodeField

    private data class HttpTtsDraft(
        val name: String,
        val url: String,
        val contentType: String,
        val concurrentRate: String,
        val loginUrl: String,
        val loginUi: String,
        val loginCheckJs: String,
        val header: String,
        val jsLib: String,
        val enabledCookieJar: Boolean,
        val pauseDuration: String
    )

    private data class CodeField(
        val binding: ViewCodeEditFieldBinding
    ) {
        val textInputLayout get() = binding.textInputLayout
        val codeView get() = binding.editText
        val btnWebEdit get() = binding.btnWebEdit
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog?.setCanceledOnTouchOutside(false)
        dialog?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dismiss()
                true
            } else {
                false
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        // 覆盖 BaseDialogFragment(adaptationSoftKeyboard=true) 的默认外部点击关闭行为
        view.setOnClickListener(null)
        initCodeFields()
        urlField.textInputLayout.hint = "url"
        contentTypeField.textInputLayout.hint = "Content-Type"
        concurrentRateField.textInputLayout.hint = getString(R.string.concurrent_rate)
        loginUrlField.textInputLayout.hint = getString(R.string.login_url)
        loginUiField.textInputLayout.hint = getString(R.string.login_ui)
        loginCheckJsField.textInputLayout.hint = getString(R.string.login_check_js)
        headersField.textInputLayout.hint = getString(R.string.source_http_header)
        jsLibField.textInputLayout.hint = "jsLib"

        urlField.codeView.run {
            addLegadoPattern()
            addJsonPattern()
            addJsPattern()
        }
        loginUrlField.codeView.run {
            addLegadoPattern()
            addJsonPattern()
            addJsPattern()
        }
        loginUiField.codeView.addJsonPattern()
        loginCheckJsField.codeView.addJsPattern()
        jsLibField.codeView.addJsPattern()
        headersField.codeView.run {
            addLegadoPattern()
            addJsonPattern()
            addJsPattern()
        }
        initWebCodeEditorEntrances()
        viewModel.initData(arguments) {
            initView(httpTTS = it)
            rememberInitialDraft()
        }
        initMenu()
        if (initialDraft == null) {
            rememberInitialDraft()
        }
    }

    fun initMenu() {
        binding.toolBar.inflateMenu(R.menu.speak_engine_edit)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.setOnMenuItemClickListener(this)
    }

    fun initView(httpTTS: HttpTTS) {
        binding.tvName.setText(httpTTS.name)
        urlField.codeView.setText(httpTTS.url)
        contentTypeField.codeView.setText(httpTTS.contentType)
        concurrentRateField.codeView.setText(httpTTS.concurrentRate)
        loginUrlField.codeView.setText(httpTTS.loginUrl)
        loginUiField.codeView.setText(httpTTS.loginUi)
        loginCheckJsField.codeView.setText(httpTTS.loginCheckJs)
        headersField.codeView.setText(httpTTS.header)
        jsLibField.codeView.setText(httpTTS.jsLib)
        binding.cbIsEnableCookie.isChecked = httpTTS.enabledCookieJar == true
        binding.tvPauseDuration.setText(httpTTS.pauseDuration.takeIf { it > 0 }?.toString().orEmpty())
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_save -> viewModel.save(dataFromView()) {
                rememberInitialDraft()
                toastOnUi("保存成功")
            }
            R.id.menu_login -> dataFromView().let { httpTts ->
                if (httpTts.loginUrl.isNullOrBlank()) {
                    toastOnUi("登录url不能为空")
                } else {
                    viewModel.save(httpTts) {
                        rememberInitialDraft()
                        startActivity<SourceLoginActivity> {
                            putExtra("type", "httpTts")
                            putExtra("key", httpTts.id.toString())
                        }
                    }
                }
            }
            R.id.menu_show_login_header -> alert {
                setTitle(R.string.login_header)
                dataFromView().getLoginHeader()?.let { loginHeader ->
                    setMessage(loginHeader)
                }
            }
            R.id.menu_del_login_header -> dataFromView().removeLoginHeader()
            R.id.menu_copy_source -> dataFromView().let {
                context?.sendToClip(GSON.toJson(it))
            }
            R.id.menu_paste_source -> viewModel.importFromClip {
                initView(it)
            }
            R.id.menu_log -> showDialogFragment<AppLogDialog>()
            R.id.menu_help -> showHelp("httpTTSHelp")
        }
        return true
    }

    private fun dataFromView(): HttpTTS {
        return HttpTTS(
            id = viewModel.id ?: System.currentTimeMillis(),
            name = binding.tvName.text.toString(),
            url = urlField.codeView.text.toString(),
            contentType = contentTypeField.codeView.text?.toString(),
            concurrentRate = concurrentRateField.codeView.text?.toString(),
            loginUrl = loginUrlField.codeView.text?.toString(),
            loginUi = loginUiField.codeView.text?.toString(),
            loginCheckJs = loginCheckJsField.codeView.text?.toString(),
            header = headersField.codeView.text?.toString(),
            jsLib = jsLibField.codeView.text?.toString()?.takeIf { it.isNotBlank() },
            enabledCookieJar = binding.cbIsEnableCookie.isChecked,
            pauseDuration = binding.tvPauseDuration.text?.toString()?.toIntOrNull() ?: 0
        )
    }

    private fun currentDraft(): HttpTtsDraft {
        return HttpTtsDraft(
            name = binding.tvName.text?.toString().orEmpty(),
            url = urlField.codeView.text?.toString().orEmpty(),
            contentType = contentTypeField.codeView.text?.toString().orEmpty(),
            concurrentRate = concurrentRateField.codeView.text?.toString().orEmpty(),
            loginUrl = loginUrlField.codeView.text?.toString().orEmpty(),
            loginUi = loginUiField.codeView.text?.toString().orEmpty(),
            loginCheckJs = loginCheckJsField.codeView.text?.toString().orEmpty(),
            header = headersField.codeView.text?.toString().orEmpty(),
            jsLib = jsLibField.codeView.text?.toString().orEmpty(),
            enabledCookieJar = binding.cbIsEnableCookie.isChecked,
            pauseDuration = binding.tvPauseDuration.text?.toString().orEmpty()
        )
    }

    private fun rememberInitialDraft() {
        initialDraft = currentDraft()
    }

    private fun hasUnsavedChanges(): Boolean {
        return initialDraft != null && initialDraft != currentDraft()
    }

    private fun performDismiss(allowStateLoss: Boolean) {
        bypassDismissCheck = true
        if (allowStateLoss) {
            super.dismissAllowingStateLoss()
        } else {
            super.dismiss()
        }
    }

    override fun dismiss() {
        if (bypassDismissCheck || !hasUnsavedChanges()) {
            performDismiss(allowStateLoss = false)
            return
        }
        alert(R.string.exit) {
            setMessage(R.string.exit_no_save)
            positiveButton(R.string.yes)
            negativeButton(R.string.no) {
                performDismiss(allowStateLoss = false)
            }
        }
    }

    override fun dismissAllowingStateLoss() {
        if (bypassDismissCheck || !hasUnsavedChanges()) {
            performDismiss(allowStateLoss = true)
            return
        }
        alert(R.string.exit) {
            setMessage(R.string.exit_no_save)
            positiveButton(R.string.yes)
            negativeButton(R.string.no) {
                performDismiss(allowStateLoss = true)
            }
        }
    }

    private fun initWebCodeEditorEntrances() {
        bindWebEditor(urlField.codeView, urlField.btnWebEdit, "url")
        bindWebEditor(contentTypeField.codeView, contentTypeField.btnWebEdit, "Content-Type")
        bindWebEditor(
            concurrentRateField.codeView,
            concurrentRateField.btnWebEdit,
            getString(R.string.concurrent_rate)
        )
        bindWebEditor(
            loginUrlField.codeView,
            loginUrlField.btnWebEdit,
            getString(R.string.login_url)
        )
        bindWebEditor(
            loginUiField.codeView,
            loginUiField.btnWebEdit,
            getString(R.string.login_ui)
        )
        bindWebEditor(
            loginCheckJsField.codeView,
            loginCheckJsField.btnWebEdit,
            getString(R.string.login_check_js)
        )
        bindWebEditor(
            headersField.codeView,
            headersField.btnWebEdit,
            getString(R.string.source_http_header)
        )
        bindWebEditor(jsLibField.codeView, jsLibField.btnWebEdit, "jsLib")
    }

    private fun initCodeFields() {
        urlField = resolveCodeField(R.id.field_url)
        contentTypeField = resolveCodeField(R.id.field_content_type)
        concurrentRateField = resolveCodeField(R.id.field_concurrent_rate)
        loginUrlField = resolveCodeField(R.id.field_login_url)
        loginUiField = resolveCodeField(R.id.field_login_ui)
        loginCheckJsField = resolveCodeField(R.id.field_login_check_js)
        headersField = resolveCodeField(R.id.field_headers)
        jsLibField = resolveCodeField(R.id.field_js_lib)
    }

    private fun resolveCodeField(rootId: Int): CodeField {
        return CodeField(binding.root.bindCodeEditField(rootId))
    }

    private fun bindWebEditor(
        view: CodeView,
        button: ImageButton,
        title: String
    ) {
        button.setOnClickListener {
            openWebEditor(view, title)
        }
    }

    private fun openWebEditor(view: CodeView, title: String) {
        val requestId = java.util.UUID.randomUUID().toString()
        if (
            WebCodeDialog.show(
                childFragmentManager,
                code = view.text?.toString().orEmpty(),
                requestId = requestId,
                title = title
            )
        ) {
            webEditRequests[requestId] = view
        }
    }

    override fun onCodeSave(code: String, requestId: String?) {
        val target = requestId?.let { webEditRequests.remove(it) } ?: return
        target.setText(code)
        target.setSelection(code.length)
    }

}
