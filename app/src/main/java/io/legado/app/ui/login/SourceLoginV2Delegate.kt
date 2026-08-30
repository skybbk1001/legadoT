package io.legado.app.ui.login

import android.os.CountDownTimer
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout
import com.script.rhino.runScriptWithContext
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.rule.RowUi
import io.legado.app.databinding.DialogLoginBinding
import io.legado.app.databinding.ItemFilletTextBinding
import io.legado.app.databinding.ItemLoginFieldBinding
import io.legado.app.databinding.ItemLoginLabelBinding
import io.legado.app.databinding.ItemLoginToggleBinding
import io.legado.app.lib.dialogs.selector
import io.legado.app.model.login.LoginUiV2
import io.legado.app.utils.GSON
import io.legado.app.utils.dpToPx
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.views.onClick

/**
 * 登录UI v2 控制流:App 持有弹窗内存态 stateJson(关闭即弃),loginUi(state) 纯渲染,
 * loginAction 返回命令对象(state/error/login/close),返回什么发生什么。
 * spec: docs/superpowers/specs/2026-07-24-login-ui-v2-design.md
 */
class SourceLoginV2Delegate(
    private val fragment: SourceLoginDialog,
    private val binding: DialogLoginBinding,
    private val source: BaseSource,
    private val book: Book?,
    private val chapter: BookChapter?,
) {

    private var stateJson: String = "{}"
    private var renderJob: Job? = null
    private var actionJob: Job? = null

    /** 仅首次渲染显示底部加载动画,避免每次按钮重渲染都闪一下导致弹窗尺寸抖动 */
    private var firstRender = true

    /** key → 输入行;会话输入保留、错误定位、表单收集都按 key */
    private val fieldViews = linkedMapOf<String, ItemLoginFieldBinding>()

    /** key → 开关行;表单值固定为字符串 true/false */
    private val toggleViews = linkedMapOf<String, MaterialSwitch>()
    private val toggleActionViews = hashSetOf<MaterialSwitch>()

    /** action 名 → 按钮与其显示名(倒计时寻址与文案恢复) */
    private val buttonViews = hashMapOf<String, TextView>()
    private val buttonLabels = hashMapOf<String, String>()

    /** action 名 → 倒计时剩余秒;跨重渲染由 buildViews 末尾恢复禁用态 */
    private val countdownLeft = hashMapOf<String, Int>()
    private val countdownTimers = hashMapOf<String, CountDownTimer>()

    private val scope get() = fragment.lifecycleScope
    private val inflater: LayoutInflater get() = fragment.layoutInflater

    fun start() {
        render()
    }

    fun destroy() {
        renderJob?.cancel()
        actionJob?.cancel()
        countdownTimers.values.forEach { it.cancel() }
        countdownTimers.clear()
    }

    private fun render() {
        renderJob?.cancel()
        renderJob = scope.launch {
            val showLoading = firstRender
            firstRender = false
            if (showLoading) binding.rotateLoading.visible()
            val sessionInput = collectForm()
            val result = withContext(IO) {
                kotlin.runCatching {
                    runScriptWithContext {
                        val rows = LoginUiV2.parseRender(source.evalLoginUiV2(stateJson, book, chapter))
                        rows to source.getLoginInfoMap()
                    }
                }.onFailure { e ->
                    ensureActive()
                    AppLog.put("登录UI v2 渲染出错", e)
                }.getOrNull()
            }
            ensureActive()
            if (showLoading) binding.rotateLoading.gone()
            val rows = result?.first
            if (rows == null) {
                showRenderError()
                return@launch
            }
            buildViews(rows, sessionInput, result.second)
        }
    }

    private fun showRenderError() {
        binding.flexbox.removeAllViews()
        fieldViews.clear()
        toggleViews.clear()
        toggleActionViews.clear()
        buttonViews.clear()
        ItemLoginLabelBinding.inflate(inflater, binding.root, false).let {
            binding.flexbox.addView(it.root)
            it.root.text = "登录UI加载失败:loginUi(state) 须返回 {rows:[...]},详情见日志"
        }
    }

    private fun buildViews(
        rows: List<RowUi>,
        sessionInput: Map<String, String>,
        stored: Map<String, String>?,
    ) {
        binding.flexbox.removeAllViews()
        fieldViews.clear()
        toggleViews.clear()
        toggleActionViews.clear()
        buttonViews.clear()
        buttonLabels.clear()
        rows.forEach { row ->
            when (row.type) {
                RowUi.Type.text -> addField(row, sessionInput, stored, password = false)
                RowUi.Type.password -> addField(row, sessionInput, stored, password = true)
                RowUi.Type.label -> addLabel(row)
                RowUi.Type.select -> addSelect(row, sessionInput, stored)
                RowUi.Type.toggle -> addToggle(row, sessionInput, stored)
                RowUi.Type.button -> addButton(row)
            }
        }
        countdownLeft.forEach { (action, left) ->
            if (left > 0) applyCountdown(action, left)
        }
    }

    private fun addField(
        row: RowUi,
        sessionInput: Map<String, String>,
        stored: Map<String, String>?,
        password: Boolean,
    ) {
        ItemLoginFieldBinding.inflate(inflater, binding.root, false).let {
            binding.flexbox.addView(it.root)
            it.textInputLayout.hint = row.name
            if (password) {
                it.editText.inputType =
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                it.textInputLayout.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            }
            row.hint?.let { hint -> it.textInputLayout.placeholderText = hint }
            val key = row.key
            if (key != null) {
                it.textInputLayout.isHintAnimationEnabled = false
                it.editText.setText(
                    LoginUiV2.resolveFieldValue(row.value, sessionInput[key], stored?.get(key))
                )
                it.textInputLayout.isHintAnimationEnabled = true
                fieldViews[key] = it
            }
        }
    }

    private fun addLabel(row: RowUi) {
        ItemLoginLabelBinding.inflate(inflater, binding.root, false).let {
            binding.flexbox.addView(it.root)
            it.root.text = row.name
        }
    }

    /** 单选行:复用输入行布局,点击弹 selector 单选框,值为选项字符串 */
    private fun addSelect(
        row: RowUi,
        sessionInput: Map<String, String>,
        stored: Map<String, String>?,
    ) {
        val options = row.options.orEmpty()
        ItemLoginFieldBinding.inflate(inflater, binding.root, false).let { field ->
            binding.flexbox.addView(field.root)
            field.textInputLayout.hint = row.name
            field.editText.isFocusable = false
            field.editText.isCursorVisible = false
            field.editText.keyListener = null
            val key = row.key
            if (key != null) {
                field.textInputLayout.isHintAnimationEnabled = false
                field.editText.setText(
                    LoginUiV2.resolveFieldValue(row.value, sessionInput[key], stored?.get(key))
                )
                field.textInputLayout.isHintAnimationEnabled = true
                fieldViews[key] = field
            }
            field.editText.onClick {
                val context = fragment.context ?: return@onClick
                context.selector(row.name, options) { _, i ->
                    field.editText.setText(options[i])
                }
            }
        }
    }

    private fun addButton(row: RowUi) {
        ItemFilletTextBinding.inflate(inflater, binding.root, false).let {
            binding.flexbox.addView(it.root)
            row.style().apply(it.root)
            it.textView.text = row.name
            it.textView.setPadding(16.dpToPx())
            val action = row.action ?: return@let
            buttonViews[action] = it.textView
            buttonLabels[action] = row.name
            it.root.onClick { dispatch(action, row.countdown) }
        }
    }

    private fun addToggle(
        row: RowUi,
        sessionInput: Map<String, String>,
        stored: Map<String, String>?,
    ) {
        ItemLoginToggleBinding.inflate(inflater, binding.root, false).let {
            binding.flexbox.addView(it.root)
            it.toggle.text = row.name
            val key = row.key
            it.toggle.isChecked = LoginUiV2.resolveToggleValue(
                row.value,
                key?.let(sessionInput::get),
                key?.let { stored?.get(it) },
            ) == "true"
            if (key != null) {
                toggleViews[key] = it.toggle
            }
            row.action?.let { action ->
                toggleActionViews.add(it.toggle)
                it.toggle.setOnCheckedChangeListener { _, _ ->
                    dispatch(action, countdownSec = null, actionView = it.toggle)
                }
            }
        }
    }

    /** 表单 = 全部有 key 行的当前值;必须在主线程调用(读视图) */
    private fun collectForm(): Map<String, String> {
        val form = hashMapOf<String, String>()
        fieldViews.forEach { (key, field) ->
            form[key] = field.editText.text?.toString() ?: ""
        }
        toggleViews.forEach { (key, toggle) ->
            form[key] = toggle.isChecked.toString()
        }
        return form
    }

    private fun clearErrors() {
        fieldViews.values.forEach { it.textInputLayout.error = null }
    }

    private fun applyErrors(errors: Map<String, String>) {
        errors.forEach { (key, msg) ->
            val field = fieldViews[key]
            if (field != null) {
                field.textInputLayout.error = msg
            } else {
                fragment.context?.toastOnUi(msg)
            }
        }
    }

    private fun dispatch(action: String, countdownSec: Int?, actionView: View? = null) {
        if (actionJob?.isActive == true) return
        if (countdownLeft.getOrDefault(action, 0) > 0) return
        clearErrors()
        val formJson = GSON.toJson(collectForm())
        val control = actionView ?: buttonViews[action]
        toggleActionViews.forEach { it.isEnabled = false }
        control?.isEnabled = false
        control?.alpha = 0.5f
        actionJob = scope.launch {
            try {
                val result = withContext(IO) {
                    kotlin.runCatching {
                        runScriptWithContext {
                            source.evalLoginActionV2(action, stateJson, formJson, book, chapter)
                        }
                    }.onFailure { e ->
                        ensureActive()
                        AppLog.put("登录UI v2 动作 $action 出错", e)
                        fragment.context?.toastOnUi("动作出错\n${e.localizedMessage}")
                    }
                }
                ensureActive()
                if (result.isFailure) return@launch
                val cmd = LoginUiV2.parseActionResult(result.getOrNull())
                cmd.unknownKeys.forEach {
                    AppLog.put("登录UI v2 动作 $action 返回未知命令 $it,已忽略")
                }
                cmd.error?.let { applyErrors(it) }
                cmd.loginJson?.let { info ->
                    withContext(IO) { source.putLoginInfo(info) }
                }
                if (cmd.error == null && countdownSec != null && countdownSec > 0) {
                    startCountdown(action, countdownSec)
                }
                if (cmd.close) {
                    fragment.dismissAllowingStateLoss()
                    return@launch
                }
                cmd.stateJson?.let {
                    stateJson = it
                    render()
                }
            } finally {
                toggleActionViews.forEach { it.isEnabled = true }
                control?.isEnabled = true
                control?.alpha = 1f
            }
        }
    }

    private fun startCountdown(action: String, seconds: Int) {
        countdownTimers.remove(action)?.cancel()
        countdownLeft[action] = seconds
        applyCountdown(action, seconds)
        countdownTimers[action] = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val left = ((millisUntilFinished + 999) / 1000).toInt()
                countdownLeft[action] = left
                applyCountdown(action, left)
            }

            override fun onFinish() {
                countdownLeft.remove(action)
                countdownTimers.remove(action)
                buttonViews[action]?.let {
                    it.isEnabled = true
                    it.alpha = 1f
                    it.text = buttonLabels[action]
                }
            }
        }.apply { start() }
    }

    private fun applyCountdown(action: String, left: Int) {
        buttonViews[action]?.let {
            it.isEnabled = false
            it.alpha = 0.5f
            it.text = "${buttonLabels[action]} (${left}s)"
        }
    }
}
