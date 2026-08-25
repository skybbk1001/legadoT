package io.legado.app.web.mcp

import com.google.gson.JsonParser
import io.legado.app.model.AutoTask
import io.legado.app.model.AutoTaskProtocol
import io.legado.app.model.AutoTaskRule
import io.legado.app.utils.GSON
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splitties.init.appCtx
import java.time.Instant
import java.util.UUID

/**
 * 定时任务 5 工具:规则 CRUD + 手动运行(与 cron 调度同管线:书源 JS 环境跑 script,
 * 返回动作摘要并把结果写回 lastRunAt/lastResult/lastError/lastLog)。
 */
internal fun Server.registerTaskTools() {
    serverAddListAutoTasks()
    serverAddGetAutoTask()
    serverAddSaveAutoTask()
    serverAddDeleteAutoTasks()
    serverAddRunAutoTask()
}

private fun Server.serverAddListAutoTasks() {
    addTool(
        name = "list_auto_tasks",
        description = "列出阅读 App 内的定时任务(摘要:id/名称/启用/cron/上次运行时间/结果/错误)。可按名称子串过滤。",
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
            val rules = AutoTask.getRules()
            if (rules.isEmpty()) {
                ok("(App 内无定时任务)")
            } else {
                val summaries = rules.map {
                    mapOf(
                        "id" to it.id,
                        "name" to it.name,
                        "enable" to it.enable,
                        "cron" to (it.cron ?: ""),
                        "lastRunAt" to
                            if (it.lastRunAt > 0) Instant.ofEpochMilli(it.lastRunAt).toString() else "",
                        "lastResult" to (it.lastResult ?: ""),
                        "lastError" to (it.lastError ?: ""),
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

private fun Server.serverAddGetAutoTask() {
    addTool(
        name = "get_auto_task",
        description = "按 id 取完整定时任务 JSON(含 script/jsLib/header/loginUrl 等全部字段)。",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("id", stringProp("任务 id(list_auto_tasks 返回)"))
            },
            required = listOf("id"),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true),
    ) { request ->
        try {
            val id = request.arguments.str("id")
                ?: return@addTool err("参数id不能为空")
            val rule = AutoTask.getRules().firstOrNull { it.id == id }
                ?: return@addTool err("未找到任务:$id")
            ok(McpFormat.truncate(McpFormat.prettyJson(GSON.toJson(rule)), 200_000))
        } catch (e: Exception) {
            err(e.localizedMessage ?: e.toString())
        }
    }
}

private fun Server.serverAddSaveAutoTask() {
    addTool(
        name = "save_auto_task",
        description = "保存/覆盖定时任务。传完整任务 JSON(get_auto_task 取回改后再传,缺省字段按默认值);" +
            "同 id 覆盖,id 缺失自动生成;保留该任务的历史运行记录(lastRunAt/lastError 等)。" +
            "保存后立即刷新调度。cron 语法同 App 内帮助文档。",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put(
                    "task",
                    stringProp(
                        "定时任务 JSON 对象。字段:id/name/enable/cron/script/header/jsLib/" +
                            "concurrentRate/enabledCookieJar/loginUrl/loginUi/loginCheckJs/comment"
                    )
                )
            },
            required = listOf("task"),
        ),
        toolAnnotations = ToolAnnotations(
            readOnlyHint = false,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false,
        ),
    ) { request ->
        try {
            val taskJson = request.arguments.str("task")
                ?: return@addTool err("参数task不能为空")
            val obj = JsonParser.parseString(taskJson).asJsonObject
            val rule = AutoTaskRule(
                id = obj.get("id")?.asString?.takeIf { it.isNotBlank() }
                    ?: UUID.randomUUID().toString(),
                name = obj.get("name")?.asString ?: "",
                enable = obj.get("enable")?.asBoolean ?: true,
                cron = obj.get("cron")?.asString?.takeIf { it.isNotBlank() },
                loginUrl = obj.get("loginUrl")?.asString?.takeIf { it.isNotBlank() },
                loginUi = obj.get("loginUi")?.asString?.takeIf { it.isNotBlank() },
                loginCheckJs = obj.get("loginCheckJs")?.asString?.takeIf { it.isNotBlank() },
                comment = obj.get("comment")?.asString?.takeIf { it.isNotBlank() },
                script = obj.get("script")?.asString ?: "",
                header = obj.get("header")?.asString?.takeIf { it.isNotBlank() },
                jsLib = obj.get("jsLib")?.asString?.takeIf { it.isNotBlank() },
                concurrentRate = obj.get("concurrentRate")?.asString?.takeIf { it.isNotBlank() },
                enabledCookieJar = obj.get("enabledCookieJar")?.asBoolean ?: true,
            )
            if (rule.script.isBlank()) {
                return@addTool err("任务脚本(script)不能为空")
            }
            val existing = AutoTask.getRules().firstOrNull { it.id == rule.id }
            val finalRule = if (existing != null) {
                rule.copy(
                    lastRunAt = existing.lastRunAt,
                    lastResult = existing.lastResult,
                    lastError = existing.lastError,
                    lastLog = existing.lastLog,
                )
            } else {
                rule
            }
            AutoTask.upsert(finalRule)
            ok("已保存:${finalRule.name}(id: ${finalRule.id})")
        } catch (e: Exception) {
            err(e.localizedMessage ?: e.toString())
        }
    }
}

private fun Server.serverAddDeleteAutoTasks() {
    addTool(
        name = "delete_auto_tasks",
        description = "按 id 删除定时任务(可多个)。",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("ids") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "任务 id 列表")
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
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            if (ids.isEmpty()) {
                return@addTool err("参数ids不能为空")
            }
            AutoTask.delete(*ids.toTypedArray())
            ok("已删除 ${ids.size} 个定时任务")
        } catch (e: Exception) {
            err(e.localizedMessage ?: e.toString())
        }
    }
}

private fun Server.serverAddRunAutoTask() {
    addTool(
        name = "run_auto_task",
        description = "立即手动运行一个定时任务(与 cron 调度同管线:书源 JS 环境执行 script,返回动作执行摘要)," +
            "并把结果/错误写回任务的 lastRunAt/lastResult/lastError/lastLog,返回最终日志文本。" +
            "注意:与 cron 定时触发的运行互不感知,调试期间建议先把该任务 enable 关掉或改远期 cron,避免并发重跑。",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("id", stringProp("任务 id"))
                putJsonObject("timeoutSec") {
                    put("type", "integer")
                    put("description", "超时秒数,默认 120")
                }
            },
            required = listOf("id"),
        ),
        toolAnnotations = ToolAnnotations(
            readOnlyHint = false,
            destructiveHint = false,
            idempotentHint = false,
            openWorldHint = true,
        ),
    ) { request ->
        try {
            val id = request.arguments.str("id")
                ?: return@addTool err("参数id不能为空")
            val timeoutSec = (request.arguments.int("timeoutSec") ?: 120).coerceIn(10, 600)
            val rule = AutoTask.getRules().firstOrNull { it.id == id }
                ?: return@addTool err("未找到任务:$id")
            val script = AutoTask.normalizeScript(rule.script)
            if (script.isBlank()) {
                return@addTool err("任务脚本为空")
            }
            val source = AutoTask.buildSource(rule)
            val startMs = System.currentTimeMillis()
            val deferred = debugScope.async {
                val evalContext = currentCoroutineContext()
                runCatching { source.evalJS(script, evalContext) }
            }
            val outcome = withTimeoutOrNull(timeoutSec * 1000L) { deferred.await() }
            val elapsedMs = System.currentTimeMillis() - startMs
            if (outcome == null) {
                deferred.cancel()
                val msg = "手动运行超时 ${timeoutSec}s"
                val runAt = System.currentTimeMillis()
                AutoTask.update(id) {
                    it.copy(
                        lastRunAt = runAt,
                        lastResult = null,
                        lastError = msg,
                        lastLog = AutoTask.buildErrorLog(msg, null, runAt),
                    )
                }
                return@addTool err(msg)
            }
            val failure = outcome.exceptionOrNull()
            if (failure != null) {
                val msg = failure.localizedMessage ?: failure.toString()
                val runAt = System.currentTimeMillis()
                AutoTask.update(id) {
                    it.copy(
                        lastRunAt = runAt,
                        lastResult = null,
                        lastError = msg,
                        lastLog = AutoTask.buildErrorLog(msg, failure, runAt),
                    )
                }
                return@addTool err("运行失败:$msg")
            }
            val value = outcome.getOrNull()
            val logLines = mutableListOf<String>()
            val handle = AutoTaskProtocol.handle(value, appCtx, rule.name) { logLines.add(it) }
            val detail = value?.toString()?.take(200)
            val runAt = System.currentTimeMillis()
            val lastLog = AutoTask.buildLastLog(logLines, detail, elapsedMs, runAt)
            AutoTask.update(id) {
                it.copy(
                    lastRunAt = runAt,
                    lastResult = detail,
                    lastError = null,
                    lastLog = lastLog,
                )
            }
            val hint = if (handle.handled) {
                ""
            } else {
                "\n\n[脚本未返回 actions,无 refreshToc/notify 动作执行]"
            }
            ok(lastLog + hint)
        } catch (e: Exception) {
            err(e.localizedMessage ?: e.toString())
        }
    }
}
