package io.legado.app.help.http

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

object OkHttpExceptionInterceptor : Interceptor {

    private const val PROXY_AUTH_FAIL = "PROXY_AUTH_FAIL"

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        try {
            return chain.proceed(chain.request())
        } catch (e: IOException) {
            if (isProxyAuthFailure(e)) {
                val msg = e.message ?: "SOCKS authentication failed"
                throw IOException("$PROXY_AUTH_FAIL: $msg", e)
            }
            throw e
        } catch (e: Throwable) {
            throw IOException(e)
        }
    }

    private fun isProxyAuthFailure(e: IOException): Boolean {
        val msg = buildString {
            var cursor: Throwable? = e
            while (cursor != null) {
                append(cursor.message.orEmpty())
                append('\n')
                cursor = cursor.cause
            }
        }
        return msg.contains("SOCKS : authentication failed", ignoreCase = true) ||
            msg.contains("SOCKS5 代理要求认证", ignoreCase = true) ||
            msg.contains("SOCKS5 代理不接受认证方式", ignoreCase = true) ||
            msg.contains("SOCKS5 代理返回不支持的认证方式", ignoreCase = true) ||
            msg.contains("SOCKS5 代理未按要求使用用户名密码认证", ignoreCase = true)
    }

}
