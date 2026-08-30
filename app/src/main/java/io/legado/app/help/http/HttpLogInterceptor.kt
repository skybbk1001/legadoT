package io.legado.app.help.http

import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.model.HttpLogger
import io.legado.app.model.HttpRecord
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.io.IOException

object HttpLogInterceptor : Interceptor {

    private const val MAX_BODY_SIZE = 4096L

    override fun intercept(chain: Interceptor.Chain): Response {
        if (!AppConfig.recordHttpLog) {
            return chain.proceed(chain.request())
        }

        val request = chain.request()
        val startTime = System.currentTimeMillis()
        val recordId = HttpLogger.nextId()

        // 读取请求体
        val requestBody = try {
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            val body = buffer.readUtf8()
            if (body.length > MAX_BODY_SIZE) body.take(MAX_BODY_SIZE.toInt()) + "…" else body
        } catch (_: Exception) {
            ""
        }

        // 格式化请求头
        val requestHeaders = request.headers.joinToString("\n") { "${it.first}: ${it.second}" }

        var error: String? = null
        val response: Response
        try {
            response = chain.proceed(request)
        } catch (e: IOException) {
            error = e.localizedMessage ?: e.toString()
            val record = HttpRecord(
                id = recordId,
                time = startTime,
                method = request.method,
                url = request.url.toString(),
                statusCode = -1,
                duration = System.currentTimeMillis() - startTime,
                requestHeaders = requestHeaders,
                requestBody = requestBody,
                responseHeaders = "",
                responseBody = "",
                error = error
            )
            HttpLogger.add(record)
            AppLog.put(record.summary, httpId = recordId, error = true)
            throw e
        }

        val duration = System.currentTimeMillis() - startTime

        // 读取响应体（peek 不消费）
        val responseBody = try {
            response.peekBody(MAX_BODY_SIZE).string().let {
                if (it.length >= MAX_BODY_SIZE) it + "…" else it
            }
        } catch (_: Exception) {
            ""
        }

        val responseHeaders = response.headers.joinToString("\n") { "${it.first}: ${it.second}" }

        val record = HttpRecord(
            id = recordId,
            time = startTime,
            method = request.method,
            url = request.url.toString(),
            statusCode = response.code,
            duration = duration,
            requestHeaders = requestHeaders,
            requestBody = requestBody,
            responseHeaders = responseHeaders,
            responseBody = responseBody,
            error = null
        )
        HttpLogger.add(record)
        AppLog.put(record.summary, httpId = recordId, error = response.code >= 400)

        return response
    }
}
