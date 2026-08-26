package io.legado.app.ui.book.source.edit

import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.view.Menu
import android.view.MenuItem
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.databinding.ActivityJsSourceEditBinding
import io.legado.app.help.source.SourceHelp
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.jsSource.JsSourceConfig
import io.legado.app.ui.book.source.debug.BookSourceDebugActivity
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.widget.code.addJsPattern
import io.legado.app.ui.widget.dialog.CodeEditorWebViewPool
import io.legado.app.utils.getClipText
import io.legado.app.utils.gone
import io.legado.app.utils.imeHeight
import io.legado.app.utils.navigationBarHeight
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.share
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import java.io.File
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.views.bottomPadding

/**
 * JS 源编辑器(spec §5):整页 CodeMirror(保活 WebView 池,与字段弹窗共用),
 * 行号/折叠/搜索/格式化/语法检查全套;池被占用或 boot 失败回退原生 CodeView。
 * 脚本是元数据唯一真理源,保存=JsSourceConfig.extract 重提 config 覆盖元字段
 * (enabled/customOrder 等用户态保留)。
 */
class JsSourceEditActivity : BaseActivity<ActivityJsSourceEditBinding>(),
    CodeEditorWebViewPool.Client {

    override val binding by viewBinding(ActivityJsSourceEditBinding::inflate)

    // 打开时的 bookSourceUrl,用于保存时识别脚本内改名(反查旧记录用,而非保存后的新 URL)
    private var openedSourceUrl: String? = null

    // 未保存退出确认的干净基线:纯文本快照(与 extract 后 equal() 比较严格等价——extract 把
    // 全文赋给 mainJs、equal 逐字比 mainJs,纯文本比较更简单且无 Rhino eval 开销/非法脚本误弹)
    private var baselineText: String = ""

    // 内容会话键:存 instance state 跨重建稳定;重建回来编辑器文档仍是本会话时
    // 跳过初始注入,保住未保存编辑
    private var editorSessionKey: String = ""

    private var webEditorAttached = false
    private var editorReady = false
    private var initialCodeApplied = false
    private var fallbackMode = false
    private var pendingInitialText: String? = null
    private var exitCheckPending = false

    // 当前应注入编辑器页面的底部安全区高度(键盘弹出时为 0,见 insets 监听)
    private var editorBottomInsetPx = 0

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        editorSessionKey = savedInstanceState?.getString("editorSessionKey")
            ?: "js:" + java.util.UUID.randomUUID().toString()
        // 本页无 KeyboardToolPop 工具条兜底,IME 高度直接作为 padding,输入区始终不被键盘遮挡
        binding.flEditor.setOnApplyWindowInsetsListenerCompat { view, windowInsets ->
            val imeHeight = windowInsets.imeHeight
            if (fallbackMode) {
                // 原生 CodeView 无页内避让通道,底部让出导航栏
                view.bottomPadding =
                    if (imeHeight == 0) windowInsets.navigationBarHeight else imeHeight
            } else {
                // WebView 全出血到屏幕底,导航栏高度注入页面在滚动内容里避让
                // (editor.html setBottomInset),编辑器表面铺满屏幕
                view.bottomPadding = if (imeHeight == 0) 0 else imeHeight
                editorBottomInsetPx =
                    if (imeHeight == 0) windowInsets.navigationBarHeight else 0
                CodeEditorWebViewPool.applyBottomInset(editorBottomInsetPx)
            }
            windowInsets
        }
        val sourceUrl = intent.getStringExtra("sourceUrl")
        if (!sourceUrl.isNullOrBlank()) openedSourceUrl = sourceUrl
        webEditorAttached = CodeEditorWebViewPool.attach(binding.webViewContainer, this)
        if (!webEditorAttached) {
            enterFallbackMode()
        }
        lifecycleScope.launch {
            val text = withContext(IO) {
                if (sourceUrl.isNullOrBlank()) {
                    assets.open("js_source_template.js").use { String(it.readBytes()) }
                } else {
                    appDb.bookSourceDao.getBookSource(sourceUrl)?.mainJs.orEmpty()
                }
            }
            baselineText = text
            pendingInitialText = text
            applyInitialTextIfReady()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("editorSessionKey", editorSessionKey)
    }

    override fun onDestroy() {
        if (webEditorAttached) {
            CodeEditorWebViewPool.detach(this)
        }
        super.onDestroy()
    }

    // ------ 编辑器承载:池化 CodeMirror / 回退 CodeView ------

    override fun onEditorReady() {
        if (isDestroyed) return
        editorReady = true
        CodeEditorWebViewPool.applyAppTheme()
        // 池化页面可能残留其他会话的注入值,就绪即按本页当前 insets 重发
        CodeEditorWebViewPool.applyBottomInset(editorBottomInsetPx)
        applyInitialTextIfReady()
    }

    override fun onEditorBootError(message: String?) {
        if (isDestroyed || editorReady) return
        enterFallbackMode()
    }

    // 保存不走 __save 桥(菜单动作各自经 withEditorText 取文本),此回调无消费
    override fun onEditorSave(text: String) = Unit

    private fun enterFallbackMode() {
        if (fallbackMode) return
        fallbackMode = true
        if (webEditorAttached) {
            CodeEditorWebViewPool.detach(this)
            webEditorAttached = false
        }
        binding.webViewContainer.gone()
        binding.codeView.addJsPattern()
        binding.codeView.visible()
        // 让 insets 监听按回退分支重算底部 padding(CodeView 需让出导航栏)
        ViewCompat.requestApplyInsets(binding.flEditor)
        applyInitialTextIfReady()
    }

    /** 初始文本与编辑器就绪双异步汇合点:两者齐备才注入,重复调用幂等 */
    private fun applyInitialTextIfReady() {
        val text = pendingInitialText ?: return
        if (fallbackMode) {
            pendingInitialText = null
            binding.codeView.setText(text)
            onInitialContentShown()
            return
        }
        if (!editorReady) return
        if (CodeEditorWebViewPool.isContentSession(editorSessionKey)) {
            // 重建回来:编辑器文档就是本会话内容(可能含未保存编辑),重发会覆盖
            pendingInitialText = null
            initialCodeApplied = true
            onInitialContentShown()
            return
        }
        val encoded = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        CodeEditorWebViewPool.evaluateJavascript(
            "window.setCodeFromAndroid && window.setCodeFromAndroid('$encoded');"
        ) {
            if (isDestroyed) return@evaluateJavascript
            pendingInitialText = null
            initialCodeApplied = true
            CodeEditorWebViewPool.markContentSession(editorSessionKey)
            onInitialContentShown()
        }
    }

    private fun onInitialContentShown() {
        binding.loadingProgress.gone()
        binding.webViewContainer.alpha = 1f
    }

    /** 统一取文:web 编辑器异步经 __getCode,回退模式同步;初始注入未完成时以待注入文本为准 */
    private fun withEditorText(action: (String) -> Unit) {
        if (fallbackMode) {
            action(binding.codeView.text.toString())
            return
        }
        if (!initialCodeApplied) {
            action(pendingInitialText ?: baselineText)
            return
        }
        CodeEditorWebViewPool.evaluateJavascript("window.__getCode && window.__getCode();") { value ->
            if (isDestroyed) return@evaluateJavascript
            action(CodeEditorWebViewPool.decodeJsResult(value) ?: "")
        }
    }

    private fun setEditorText(text: String) {
        if (fallbackMode) {
            binding.codeView.setText(text)
            return
        }
        val encoded = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        CodeEditorWebViewPool.evaluateJavascript(
            "window.setCodeFromAndroid && window.setCodeFromAndroid('$encoded');"
        )
    }

    // ------ 菜单与保存链路 ------

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.js_source_edit, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_save -> saveSource {
                // 基线已随保存刷新,finish 不触发未保存确认
                finish()
            }
            // 调试跑库内版本,先静默落库再进调试页
            R.id.menu_debug_source -> saveSource(showSuccessToast = false) { source ->
                startActivity<BookSourceDebugActivity> {
                    putExtra("key", source.bookSourceUrl)
                }
            }
            // 登录页读库内数据,先静默落库;两登录字段全空时提示而非空转
            R.id.menu_login -> saveSource(showSuccessToast = false) { source ->
                if (source.loginUrl.isNullOrBlank() && source.loginUi.isNullOrBlank()) {
                    toastOnUi(R.string.js_source_no_login)
                } else {
                    startActivity<SourceLoginActivity> {
                        putExtra("type", "bookSource")
                        putExtra("key", source.bookSourceUrl)
                    }
                }
            }
            R.id.menu_copy_source -> withEditorText { sendToClip(it) }
            R.id.menu_paste_source -> getClipText()?.let { setEditorText(it) }
            R.id.menu_share_js -> shareJsFile()
            R.id.menu_help -> showHelp("jsHelp")
        }
        return super.onCompatOptionsItemSelected(item)
    }

    /** 未保存退出确认:当前脚本文本与打开时基线做纯文本比对,不等则弹确认;
     * 与 extract 后 equal() 比对严格等价(见 baselineText 字段注释),但零 Rhino eval 开销,
     * 且非法脚本(未通过 extract)在未改动时也不会误弹 */
    override fun finish() {
        if (exitCheckPending) return
        exitCheckPending = true
        withEditorText { current ->
            exitCheckPending = false
            if (current == baselineText) {
                super@JsSourceEditActivity.finish()
            } else {
                alert(R.string.exit) {
                    setMessage(R.string.exit_no_save)
                    positiveButton(R.string.yes)
                    negativeButton(R.string.no) {
                        super@JsSourceEditActivity.finish()
                    }
                }
            }
        }
    }

    /** 分享脚本为 .js 文件:写临时文件到 cacheDir 再经 FileProvider 分享,
     * 文件名取脚本内 bookSourceName(去非法字符),提取失败则 toast 明确原因 */
    private fun shareJsFile() {
        withEditorText { text ->
            runCatching {
                // extract() 已保证 bookSourceName 非空白,这里只做文件系统非法字符净化
                val name = JsSourceConfig.extract(text).bookSourceName
                val fileName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trim('.')
                    .ifBlank { "jsSource" }
                val file = File(cacheDir, "$fileName.js")
                file.writeText(text)
                share(file, "text/javascript")
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
        }
    }

    private fun saveSource(
        showSuccessToast: Boolean = true,
        onSuccess: ((BookSource) -> Unit)? = null
    ) {
        withEditorText { text ->
            lifecycleScope.launch {
                runCatching {
                    withContext(IO) {
                        val source = JsSourceConfig.extract(text, coroutineContext)
                        val openedUrl = openedSourceUrl
                        // 用户态字段来源:改名场景(openedSourceUrl 非空且与新 URL 不同)旧记录只能按
                        // openedSourceUrl 查到;直接导入后首次编辑等场景没有改名,退回按新 URL 查
                        val old = openedUrl?.let { appDb.bookSourceDao.getBookSource(it) }
                            ?: appDb.bookSourceDao.getBookSource(source.bookSourceUrl)
                        // 用户态字段不归脚本管:从旧记录保留(spec §5"脚本是元数据唯一真理源"的边界);
                        // 分组两属:脚本声明时脚本值生效,未声明时保留应用内所设
                        old?.let {
                            source.enabled = it.enabled
                            source.enabledExplore = it.enabledExplore
                            source.customOrder = it.customOrder
                            source.weight = it.weight
                            source.respondTime = it.respondTime
                            if (source.bookSourceGroup.isNullOrBlank()) {
                                source.bookSourceGroup = it.bookSourceGroup
                            }
                            if (!it.equal(source)) {
                                stampSource(source)
                            } else {
                                source.lastUpdateTime = it.lastUpdateTime
                            }
                        } ?: run {
                            stampSource(source)
                        }
                        // 脚本内把 bookSourceUrl 改了名:旧行不会被新 URL 的 insert 覆盖,须显式删除,
                        // 否则旧记录成僵尸(仍 enabled 参与搜索),对齐 BookSourceEditViewModel.save() 的先例
                        if (openedUrl != null && openedUrl != source.bookSourceUrl) {
                            SourceHelp.deleteBookSource(openedUrl)
                        }
                        appDb.bookSourceDao.insert(source)
                        openedSourceUrl = source.bookSourceUrl
                        source
                    }
                }.onSuccess {
                    if (showSuccessToast) {
                        toastOnUi(R.string.success)
                    }
                    // 保存后以入库文本(可能被写回新版本号)刷新编辑区与退出确认基线
                    val savedText = it.mainJs ?: text
                    if (savedText != text) {
                        setEditorText(savedText)
                    }
                    baselineText = savedText
                    // 落库即记结果:调试/登录等静默保存后直接退出的路径,
                    // for-result 调用方同样凭 RESULT_OK + 最新 origin 感知库内变更
                    setResult(RESULT_OK, Intent().putExtra("origin", it.bookSourceUrl))
                    onSuccess?.invoke(it)
                }.onFailure {
                    toastOnUi(it.localizedMessage)
                }
            }
        }
    }

    /** 实质改动打新版本号,并写回脚本文本内的声明,脚本与库内保持单一时钟 */
    private fun stampSource(source: BookSource) {
        val stamp = System.currentTimeMillis()
        source.lastUpdateTime = stamp
        JsSourceConfig.stampLastUpdateTime(source.mainJs!!, stamp)?.let {
            source.mainJs = it
        }
    }
}
