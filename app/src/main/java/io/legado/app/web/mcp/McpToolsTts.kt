package io.legado.app.web.mcp

import io.legado.app.data.appDb
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.config.AppConfig
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.GSON
import okhttp3.Response
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 在线朗读引擎(HttpTTS)5 工具:CRUD + 试听请求探测。
 * test_tts 与 App 朗读同管线(AnalyzeUrl 请求),不播放、不写缓存;
 * 非音频响应(JSON/文本错误)原样返回错误内容,用于调试 url 模板/请求头/loginCheckJs。
 */
internal fun Server.registerTtsTools() {
    serverAddListTts()
    serverAddGetTts()
    serverAddSaveTts()
    serverAddDeleteTts()
    serverAddTestTts()
}

private fun Server.serverAddListTts() {
    addTool(
        name = "list_tts",
        description = "列出在线朗读引擎(HttpTTS,摘要:id/名称/URL/Content-Type)。可按名称子串过滤。",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("search", stringProp("名称子串过滤,大小写不敏感"))
            },
            required = emptyList(),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true),
    ) { request ->
        try {
            val search = request.arguments.str("search")
            val all = appDb.httpTTSDao.all
            if (all.isEmpty()) {
                ok("(App 内无在线朗读引擎)")
            } else {
                val summaries = all.map {
                    mapOf(
                        "id" to it.id,
                        "name" to it.name,
                        "url" to it.url,
                        "contentType" to (it.contentType ?: ""),
                    )
                }.filter {
                    search.isNullOrEmpty() ||
                        (it["name"] as String).lowercase().contains(search.lowercase())
                }
                ok("共 ${summaries.size} 条\n" + McpFormat.truncate(McpFormat.toPrettyJson(summaries)))
            }
        } catch (e: Exception) {
            err(e.localizedMessage ?: e.toString())
        }
    }
}

private fun Server.serverAddGetTts() {
    addTool(
        name = "get_tts",
        description = "按 id 取完整朗读引擎 JSON(url 模板/header/jsLib/loginUrl 等全字段)。",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("id", stringProp("引擎 id(list_tts 返回)"))
            },
            required = listOf("id"),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true),
    ) { request ->
        try {
            val id = request.arguments.str("id")?.toLongOrNull()
                ?: return@addTool err("参数id必须为整数")
            val tts = appDb.httpTTSDao.get(id)
                ?: return@addTool err("未找到朗读引擎:$id")
            ok(McpFormat.truncate(McpFormat.prettyJson(GSON.toJson(tts)), 200_000))
        } catch (e: Exception) {
            err(e.localizedMessage ?: e.toString())
        }
    }
}

private fun Server.serverAddSaveTts() {
    addTool(
        name = "save_tts",
        description = "推送/覆盖在线朗读引擎。传完整 HttpTTS JSON(get_tts 取回改后再传);id 缺失自动生成。" +
            "url 模板变量:{{speakText}} 文本、{{speakSpeed}} 语速;" +
            "contentType 用于识别非音频错误响应。保存后用 test_tts 立即验证。",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("tts", stringProp("HttpTTS JSON 对象(name/url 必填)"))
            },
            required = listOf("tts"),
        ),
        toolAnnotations = ToolAnnotations(
            readOnlyHint = false,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false,
        ),
    ) { request ->
        try {
            val json = request.arguments.str("tts")
                ?: return@addTool err("参数tts不能为空")
            val tts = HttpTTS.fromJson(json).getOrNull()
                ?: return@addTool err("解析失败:JSON 非法或缺少 name/url")
            appDb.httpTTSDao.insert(tts)
            ok("已保存:${tts.name}(id: ${tts.id})")
        } catch (e: Exception) {
            err(e.localizedMessage ?: e.toString())
        }
    }
}

private fun Server.serverAddDeleteTts() {
    addTool(
        name = "delete_tts",
        description = "按 id 删除在线朗读引擎(可多个)。",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("ids") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "integer") }
                    put("description", "引擎 id 列表")
                }
            },
            required = listOf("ids"),
        ),
        toolAnnotations = ToolAnnotations(
            readOnlyHint = false,
            destructiveHint = true,
            idempotentHint = true,
            openWorldHint = false,
        ),
    ) { request ->
        try {
            val ids = request.arguments?.get("ids")?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.toLongOrNull() }
                ?.filter { it > 0 }
                .orEmpty()
            if (ids.isEmpty()) {
                return@addTool err("参数ids不能为空")
            }
            appDb.httpTTSDao.delete(*ids.map { HttpTTS(id = it) }.toTypedArray())
            ok("已删除 ${ids.size} 个朗读引擎")
        } catch (e: Exception) {
            err(e.localizedMessage ?: e.toString())
        }
    }
}

private fun Server.serverAddTestTts() {
    addTool(
        name = "test_tts",
        description = "用样本文本真实请求一次朗读引擎,返回音频字节数与耗时;" +
            "非音频响应(JSON/文本错误)原样返回错误内容。与 App 朗读同管线(AnalyzeUrl)," +
            "用于调试 url 模板/请求头/loginCheckJs,不播放、不写缓存。",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("id", stringProp("引擎 id"))
                put("text", stringProp("测试文本,默认「你好,这是试听。」"))
                putJsonObject("speechRate") {
                    put("type", "integer")
                    put("description", "语速,默认 App 当前播放语速+5")
                }
                putJsonObject("timeoutSec") {
                    put("type", "integer")
                    put("description", "超时秒数,默认 60")
                }
            },
            required = listOf("id"),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = true),
    ) { request ->
        try {
            val id = request.arguments.str("id")?.toLongOrNull()
                ?: return@addTool err("参数id必须为整数")
            val tts = appDb.httpTTSDao.get(id)
                ?: return@addTool err("未找到朗读引擎:$id")
            val text = request.arguments.str("text")?.takeIf { it.isNotBlank() }
                ?: "你好,这是试听。"
            val speechRate = request.arguments.int("speechRate") ?: (AppConfig.speechRatePlay + 5)
            val timeoutSec = (request.arguments.int("timeoutSec") ?: 60).coerceIn(10, 120)
            val startMs = System.currentTimeMillis()
            val deferred = debugScope.async {
                runCatching {
                    val analyzeUrl = AnalyzeUrl(
                        tts.url,
                        speakText = text,
                        speakSpeed = speechRate,
                        source = tts
                    )
                    var response = analyzeUrl.getResponseAwait()
                    val checkJs = tts.loginCheckJs
                    if (checkJs?.isNotBlank() == true) {
                        response = analyzeUrl.evalJS(checkJs, response) as Response
                    }
                    val body = response.body.bytes()
                    val contentType = response.header("Content-Type")?.substringBefore(";")
                    if (contentType == "application/json" || contentType?.startsWith("text/") == true) {
                        throw IllegalStateException("引擎返回非音频($contentType):${String(body)}")
                    }
                    body
                }
            }
            val outcome = withTimeoutOrNull(timeoutSec * 1000L) { deferred.await() }
            val elapsedMs = System.currentTimeMillis() - startMs
            if (outcome == null) {
                deferred.cancel()
                return@addTool err("测试超时 ${timeoutSec}s")
            }
            val failure = outcome.exceptionOrNull()
            if (failure != null) {
                return@addTool err("请求失败:${failure.localizedMessage ?: failure.toString()}")
            }
            val bytes = outcome.getOrNull()
                ?: return@addTool err("引擎返回为空")
            ok("✓ 音频获取成功:${bytes.size} bytes,耗时 ${elapsedMs}ms")
        } catch (e: Exception) {
            err(e.localizedMessage ?: e.toString())
        }
    }
}
