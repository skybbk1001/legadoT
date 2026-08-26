package io.legado.app.model.login

import com.google.gson.JsonObject
import io.legado.app.data.entities.rule.RowUi
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject

/**
 * 登录UI v2(显式状态协议)纯解析层。
 * spec: docs/superpowers/specs/2026-07-24-login-ui-v2-design.md
 * 状态/表单/命令跨 JS 边界一律 JSON 字符串,子对象以原文子串持有,不做二次反射。
 */
object LoginUiV2 {

    /** 声明式源 loginUi 字段的 v2 标记;JS 源由 JsSourceConfig 提取时写入 */
    const val MARKER = """{"version": 2}"""

    private val knownCommands = setOf("state", "error", "login", "close")

    /** loginUi 字段是 JSON 对象且 version==2 → v2;数组/JS/其他 → v1 */
    fun isV2(loginUi: String?): Boolean {
        val text = loginUi?.trim() ?: return false
        if (!text.startsWith("{")) return false
        val obj = GSON.fromJsonObject<JsonObject>(text).getOrNull() ?: return false
        return runCatching { obj.get("version")?.asInt == 2 }.getOrDefault(false)
    }

    /** loginUi(state) 返回值 {rows:[...]} 解析;形状不符返回 null 由调用方呈现错误 */
    fun parseRender(json: String?): List<RowUi>? {
        if (json.isNullOrBlank()) return null
        val obj = GSON.fromJsonObject<JsonObject>(json).getOrNull() ?: return null
        val rows = obj.get("rows")?.takeIf { it.isJsonArray } ?: return null
        return GSON.fromJsonArray<RowUi>(rows.toString()).getOrNull()
    }

    /** loginAction 命令对象;子 JSON 以字符串原文持有 */
    data class ActionResult(
        val stateJson: String? = null,
        val error: Map<String, String>? = null,
        val loginJson: String? = null,
        val close: Boolean = false,
        val unknownKeys: List<String> = emptyList(),
    )

    /** 空/非对象返回值 → 中性结果(纯副作用动作合法) */
    fun parseActionResult(json: String?): ActionResult {
        if (json.isNullOrBlank()) return ActionResult()
        val obj = GSON.fromJsonObject<JsonObject>(json).getOrNull() ?: return ActionResult()
        return ActionResult(
            stateJson = obj.get("state")?.takeIf { it.isJsonObject }?.toString(),
            error = obj.get("error")?.takeIf { it.isJsonObject }?.let {
                GSON.fromJsonObject<Map<String, String>>(it.toString()).getOrNull()
            },
            loginJson = obj.get("login")?.takeIf { it.isJsonObject }?.toString(),
            close = runCatching { obj.get("close")?.asBoolean }.getOrNull() ?: false,
            unknownKeys = obj.keySet().filterNot { it in knownCommands },
        )
    }

    /** 回填优先级:render value > 会话输入 > 已存凭据;首个非 null 生效,"" 是合法强制清空 */
    fun resolveFieldValue(renderValue: String?, sessionInput: String?, stored: String?): String? {
        return renderValue ?: sessionInput ?: stored
    }

    /** 开关仍以字符串跨表单边界传递,缺省及非 true 值均关闭 */
    fun resolveToggleValue(renderValue: String?, sessionInput: String?, stored: String?): String {
        return (resolveFieldValue(renderValue, sessionInput, stored) == "true").toString()
    }
}
