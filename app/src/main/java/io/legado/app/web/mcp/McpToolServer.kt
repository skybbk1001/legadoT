package io.legado.app.web.mcp

import io.legado.app.api.ReturnData
import io.legado.app.constant.AppConst
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.LoggingLevel
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotification
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotification
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import splitties.init.appCtx

/**
 * MCP 工具注册中枢,按领域拆分:
 *  - McpToolsBookSource:书源 / HTTP 日志 / 应用日志 / cookie / eval_js / 批量校验(14)
 *  - McpToolsTask:定时任务(5)
 *  - McpToolsTts:在线朗读引擎(5)
 * 本文件只保留共享基础设施:调试通道互斥、进度/日志通知、返回文本构造、帮助文档 resources。
 * 每个工具都带 ToolAnnotations 提示(读/破坏/幂等/开放世界)。
 * 另将 assets 帮助文档(web/help/md)全量注册为 resources。
 * Debug 是全局单例:debugMutex 串行化 MCP 侧调试,他端(调试页/校验)占用时直接报忙。
 */
object McpToolServer {

    fun create(): Server {
        val server = Server(
            serverInfo = Implementation(
                name = "legado",
                version = AppConst.appInfo.versionName,
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                    resources = ServerCapabilities.Resources(),
                    logging = buildJsonObject {},
                ),
            ),
        )
        server.registerBookSourceTools()
        server.registerTaskTools()
        server.registerTtsTools()
        registerResources(server)
        return server
    }

    private val helpDescriptions = mapOf(
        "appHelp" to "阅读 App 功能总览帮助",
        "autoTaskHelp" to "定时任务脚本语法与 actions(refreshToc/notify),配合 save_auto_task/run_auto_task 使用",
        "jsHelp" to "书源 JS 扩展 API(java.* 方法)文档",
        "ruleHelp" to "书源规则语法(CSS/XPath/JSONPath/正则/JS)总览",
        "xpathHelp" to "XPath 规则语法",
        "regexHelp" to "正则规则语法",
        "debugHelp" to "书源调试用法与调试入口格式",
        "dictRuleHelp" to "字典规则说明",
        "ExtensionContentType" to "扩展名与 MIME 类型对照表",
        "httpTTSHelp" to "在线朗读引擎(HttpTTS)url 模板与变量说明,配合 save_tts/test_tts 使用",
        "readMenuHelp" to "阅读界面菜单与设置帮助",
        "replaceRuleHelp" to "替换/净化规则管理帮助",
        "SourceMBookHelp" to "书源制作教程",
        "SourceMRssHelp" to "RSS 订阅源制作教程",
        "txtTocRuleHelp" to "TXT 目录正则说明",
        "webDavBookHelp" to "WebDAV 书籍同步简明教程",
        "webDavHelp" to "WebDAV 备份教程",
    )

    private fun registerResources(server: Server) {
        val names = appCtx.assets.list("web/help/md").orEmpty()
            .filter { it.endsWith(".md") }
            .map { it.removeSuffix(".md") }
        for (name in names) {
            val uri = "legado://help/$name"
            server.addResource(
                uri = uri,
                name = name,
                description = helpDescriptions[name] ?: "应用内帮助文档:$name",
                mimeType = "text/markdown",
            ) { _ ->
                val text = String(appCtx.assets.open("web/help/md/$name.md").readBytes())
                ReadResourceResult(
                    contents = listOf(
                        TextResourceContents(text = text, uri = uri, mimeType = "text/markdown")
                    )
                )
            }
        }
    }
}

// ==================== 共享基础设施(同包各工具文件使用)====================

internal val debugScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Debug 是全局单例:串行化 MCP 侧调试,他端(调试页/校验)占用时直接报忙。 */
internal val debugMutex = Mutex()

internal fun ok(text: String) = CallToolResult(content = listOf(TextContent(text)))

internal fun err(text: String) =
    CallToolResult(content = listOf(TextContent(text)), isError = true)

internal fun ReturnData.dataOrThrow(): Any? {
    if (!isSuccess) throw RuntimeException(errorMsg)
    return data
}

internal fun JsonObject?.str(key: String): String? =
    this?.get(key)?.jsonPrimitive?.contentOrNull

internal fun JsonObject?.int(key: String): Int? =
    this?.get(key)?.jsonPrimitive?.intOrNull

internal fun JsonObject?.bool(key: String): Boolean? =
    this?.get(key)?.jsonPrimitive?.booleanOrNull

internal fun stringProp(desc: String) = buildJsonObject {
    put("type", "string")
    put("description", desc)
}

internal fun progressTokenOf(request: CallToolRequest): RequestId? {
    val prim = request.meta?.json?.get("progressToken") as? JsonPrimitive ?: return null
    return if (prim.isString) {
        RequestId.StringId(prim.content)
    } else {
        prim.content.toLongOrNull()?.let { RequestId.NumberId(it) }
    }
}

// 通知是纯增强:单条 2s 上限,失败静默,不影响工具主流程
internal suspend fun ClientConnection.sendProgressLine(
    line: String,
    progress: Int,
    total: Int?,
    progressToken: RequestId?,
    logger: String,
) {
    withTimeoutOrNull(2000) {
        runCatching {
            sendLoggingMessage(
                LoggingMessageNotification(
                    LoggingMessageNotificationParams(
                        level = LoggingLevel.Info,
                        data = JsonPrimitive(line),
                        logger = logger,
                    )
                )
            )
            if (progressToken != null) {
                notification(
                    ProgressNotification(
                        ProgressNotificationParams(
                            progressToken = progressToken,
                            progress = progress.toDouble(),
                            total = total?.toDouble(),
                            message = line,
                        )
                    )
                )
            }
        }
    }
}
