package io.legado.app.model.jsSource

import com.google.gson.JsonObject
import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.constant.BookSourceType
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.model.login.LoginUiV2
import io.legado.app.utils.GSON
import org.htmlunit.corejs.javascript.Scriptable
import org.htmlunit.corejs.javascript.ScriptableObject
import org.htmlunit.corejs.javascript.Function as JsFunction
import kotlin.coroutines.CoroutineContext

/**
 * JS 源的 config 提取与校验(spec §5)。
 * 无能力 scope:仅标准库原型,不注入 java/source/cookie/cache 也不挂 CryptoJS——
 * 顶层只有声明、函数体不执行,导入预览阶段执行用户脚本因此无 IO 能力。
 * 脚本是元数据唯一真理源:保存/导入都经本提取覆盖 BookSource 元字段。
 */
object JsSourceConfig {

    val requiredFunctions = listOf("search", "getChapters", "getContent")

    /** 文件源详情页直转下载导入,目录/正文不参与流程;downloadUrls 由 getBookInfo 供给 */
    val fileSourceRequiredFunctions = listOf("search", "getBookInfo")

    /** config 不接受的键:主脚本自身与六个声明式规则字段(spec §1) */
    private val strippedKeys = listOf(
        "mainJs", "ruleSearch", "ruleExplore", "ruleBookInfo",
        "ruleToc", "ruleContent", "ruleReview",
    )

    @Throws(NoStackTraceException::class)
    fun extract(text: String, coroutineContext: CoroutineContext? = null): BookSource {
        val bindings = ScriptBindings()
        val scope = RhinoScriptEngine.getRuntimeScope(bindings)
        try {
            RhinoScriptEngine.eval(text, scope, coroutineContext)
        } catch (e: Exception) {
            throw NoStackTraceException("JS源脚本执行失败: ${e.message}")
        }
        val configRaw = ScriptableObject.getProperty(scope, "config")
        if (configRaw == null || configRaw === Scriptable.NOT_FOUND) {
            throw NoStackTraceException("JS源缺少顶层 config 配置对象")
        }
        val json = JsSourceEngine.normalizeJsResult(configRaw, coroutineContext)
            ?: throw NoStackTraceException("config 配置对象无法解析")
        val jsonObj = runCatching { GSON.fromJson(json, JsonObject::class.java) }.getOrNull()
            ?: throw NoStackTraceException("config 配置对象不是合法对象")
        strippedKeys.forEach { jsonObj.remove(it) }
        normalizeExploreUrl(jsonObj)
        normalizeLoginUi(jsonObj)
        val bookSource = runCatching { GSON.fromJson(jsonObj, BookSource::class.java) }.getOrNull()
            ?: throw NoStackTraceException("config 配置对象字段类型不符")
        if (bookSource.bookSourceUrl.isNullOrBlank()) {
            throw NoStackTraceException("JS源 config.bookSourceUrl 不能为空")
        }
        if (bookSource.bookSourceName.isNullOrBlank()) {
            throw NoStackTraceException("JS源 config.bookSourceName 不能为空")
        }
        val required = if (bookSource.bookSourceType == BookSourceType.file) {
            fileSourceRequiredFunctions
        } else {
            requiredFunctions
        }
        required.forEach { name ->
            if (ScriptableObject.getProperty(scope, name) !is JsFunction) {
                throw NoStackTraceException("JS源缺少必备函数 $name")
            }
        }
        if (!bookSource.exploreUrl.isNullOrBlank() &&
            ScriptableObject.getProperty(scope, "explore") !is JsFunction
        ) {
            throw NoStackTraceException("JS源声明了 exploreUrl,缺少配对的 explore 函数")
        }
        if (!bookSource.loginUi.isNullOrBlank() &&
            ScriptableObject.getProperty(scope, "login") !is JsFunction
        ) {
            throw NoStackTraceException("JS源声明了 loginUi,缺少配对的 login 函数")
        }
        // 登录UI v2:模块声明 loginUi 函数 → 落库 v2 标记;与 config.loginUi 数据形态互斥
        if (ScriptableObject.getProperty(scope, "loginUi") is JsFunction) {
            if (!bookSource.loginUi.isNullOrBlank()) {
                throw NoStackTraceException("loginUi 函数与 config.loginUi 数据只能二选一")
            }
            if (ScriptableObject.getProperty(scope, "loginAction") !is JsFunction) {
                throw NoStackTraceException("JS源声明了 loginUi 函数,缺少配对的 loginAction 函数")
            }
            bookSource.loginUi = LoginUiV2.MARKER
        }
        if (ScriptableObject.getProperty(scope, "getReviewSummary") is JsFunction &&
            ScriptableObject.getProperty(scope, "getReviewDetail") !is JsFunction
        ) {
            throw NoStackTraceException("JS源声明了 getReviewSummary,缺少配对的 getReviewDetail 函数")
        }
        if (ScriptableObject.getProperty(scope, "getReviewDetail") is JsFunction &&
            ScriptableObject.getProperty(scope, "getReviewSummary") !is JsFunction
        ) {
            throw NoStackTraceException("JS源声明了 getReviewDetail,缺少配对的 getReviewSummary 函数")
        }
        bookSource.mainJs = text
        return bookSource
    }

    /**
     * exploreUrl 数组形态归一化:JS 里写分类数组([{title,url,style?},...])更合母语,
     * 落库前序列化为 JSON 数组字符串——exploreKinds() 对该形态原生支持,消费侧零改动。
     * 字符串形态(名称::url 行文/JSON 文本)原样保留;每项须有非空 title,url 可缺省(分区头)。
     */
    private fun normalizeExploreUrl(jsonObj: JsonObject) {
        val element = jsonObj.get("exploreUrl") ?: return
        if (!element.isJsonArray) return
        val array = element.asJsonArray
        // 空数组=未声明分类(模板留空态),折叠为无发现,免触发 explore 配对校验
        if (array.isEmpty) {
            jsonObj.remove("exploreUrl")
            return
        }
        array.forEachIndexed { index, item ->
            val title = runCatching {
                item.asJsonObject.get("title")?.asString
            }.getOrNull()
            if (title.isNullOrBlank()) {
                throw NoStackTraceException("exploreUrl 第 ${index + 1} 项缺少 title")
            }
        }
        jsonObj.addProperty("exploreUrl", GSON.toJson(array))
    }

    /**
     * loginUi 数组形态归一化:JS 里写 RowUi 数组([{name,type,action?,style?},...])更合母语,
     * 落库前序列化为 JSON 数组字符串——BaseSource.loginUi() 对该形态原生支持,消费侧零改动。
     * 字符串形态原样保留;每项须有非空 name(既是输入框提示也是凭据键)。
     * 空数组=未声明(模板留空态),折叠为无表单,免触发 login 配对校验。
     */
    private fun normalizeLoginUi(jsonObj: JsonObject) {
        val element = jsonObj.get("loginUi") ?: return
        if (!element.isJsonArray) return
        val array = element.asJsonArray
        if (array.isEmpty) {
            jsonObj.remove("loginUi")
            return
        }
        array.forEachIndexed { index, item ->
            val name = runCatching {
                item.asJsonObject.get("name")?.asString
            }.getOrNull()
            if (name.isNullOrBlank()) {
                throw NoStackTraceException("loginUi 第 ${index + 1} 项缺少 name")
            }
        }
        jsonObj.addProperty("loginUi", GSON.toJson(array))
    }

    /** 数字字面量或 Date.now() 形态的 lastUpdateTime 声明(键可带引号),只认首个 */
    private val lastUpdateTimeRegex =
        Regex("""(["']?lastUpdateTime["']?\s*:\s*)(Date\.now\(\)|\d+)""")

    /**
     * 把脚本文本中声明的 lastUpdateTime 改写为 [stamp],供编辑器保存时把新版本号写回
     * 脚本——脚本文本与库内列保持单一时钟,分享出去的文件才带得上新版本号。
     * 声明缺失或是其他表达式形态时返回 null,原文不动。
     */
    fun stampLastUpdateTime(text: String, stamp: Long): String? {
        val match = lastUpdateTimeRegex.find(text) ?: return null
        return text.replaceRange(match.range, "${match.groupValues[1]}$stamp")
    }
}
