package io.legado.app.help.ai

import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.NoHttpLog
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.postJson
import io.legado.app.utils.GSON
import io.legado.app.utils.jsonPath
import io.legado.app.utils.readString
import kotlinx.coroutines.ensureActive
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import okhttp3.OkHttpClient

/**
 * OpenAI 兼容的 chat completions 调用。只负责协议, 不认识角色与朗读。
 */
object AiClient {

    private const val PATH = "/chat/completions"

    fun isConfigured(): Boolean =
        AppConfig.aiBaseUrl.isNotBlank() && AppConfig.aiModel.isNotBlank()

    fun endpointOf(baseUrl: String): String {
        val trimmed = baseUrl.trimEnd('/')
        return if (trimmed.endsWith(PATH)) trimmed else trimmed + PATH
    }

    fun extractContent(responseJson: String): String? = kotlin.runCatching {
        jsonPath.parse(responseJson).readString("$.choices[0].message.content")
    }.getOrNull()

    /**
     * @return assistant 返回的 content 文本
     * @throws NoStackTraceException 未配置 / 服务端未返回 content
     */
    suspend fun chatJson(systemPrompt: String, userPrompt: String): String {
        if (!isConfigured()) {
            throw NoStackTraceException("AI 服务未配置")
        }
        return chatJson(
            baseUrl = AppConfig.aiBaseUrl,
            apiKey = AppConfig.aiApiKey,
            model = AppConfig.aiModel,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt
        )
    }

    internal suspend fun chatJson(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        client: OkHttpClient = okHttpClient
    ): String {
        val call = client.newBuilder()
            .callTimeout(300, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build()
        val first = post(call, baseUrl, apiKey, requestBody(model, systemPrompt, userPrompt, true))
        // response_format 是 OpenAI 扩展, 本地推理与部分代理会以 4xx 拒收整个请求
        val response = if (first.isSuccessful() || first.code() !in 400..499) {
            first
        } else {
            post(call, baseUrl, apiKey, requestBody(model, systemPrompt, userPrompt, false))
        }
        val text = response.body ?: throw NoStackTraceException("AI 服务无响应体")
        if (!response.isSuccessful()) {
            throw NoStackTraceException("AI 服务返回 ${response.code()}: ${text.take(200)}")
        }
        return extractContent(text)
            ?: throw NoStackTraceException("AI 服务返回异常: ${text.take(200)}")
    }

    private fun requestBody(
        model: String,
        systemPrompt: String,
        userPrompt: String,
        jsonMode: Boolean
    ): String = GSON.toJson(
        buildMap {
            put("model", model)
            put("temperature", 0.0)
            if (jsonMode) put("response_format", mapOf("type" to "json_object"))
            put(
                "messages", listOf(
                    mapOf("role" to "system", "content" to systemPrompt),
                    mapOf("role" to "user", "content" to userPrompt)
                )
            )
        }
    )

    private suspend fun post(
        client: OkHttpClient,
        baseUrl: String,
        apiKey: String,
        body: String
    ) = client.newCallStrResponse {
        tag(NoHttpLog::class.java, NoHttpLog)
        try {
            url(endpointOf(baseUrl))
        } catch (e: IllegalArgumentException) {
            throw NoStackTraceException("AI 服务地址异常: $baseUrl")
        }
        apiKey.takeIf { it.isNotBlank() }?.let {
            addHeader("Authorization", "Bearer $it")
        }
        postJson(body)
    }

    suspend fun testConnection(): Result<String> = kotlin.runCatching {
        chatJson("You reply with JSON only.", """回复 {"ok":true}""")
    }.onFailure {
        coroutineContext.ensureActive()
    }
}
