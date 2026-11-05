package io.legado.app.help.http

import io.legado.app.model.analyzeRule.AnalyzeUrl
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class AnalyzeUrlProxyTest {
    @Test
    fun mixedCaseProxyIsUsedAndNotSentToWebsite() = runBlocking {
        val previous = HttpBridge.proxyClient
        try {
            var selected: String? = null
            HttpBridge.proxyClient = { proxy ->
                selected = proxy
                OkHttpClient.Builder().addInterceptor { chain ->
                    assertNull(chain.request().header("proxy"))
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK").body("ok".toResponseBody()).build()
                }.build()
            }
            AnalyzeUrl("https://example.test/login", headerMapF = mapOf("Proxy" to "http://127.0.0.1:8080"))
                .getStrResponseAwait(useWebView = false)
            assertEquals("http://127.0.0.1:8080", selected)
        } finally { HttpBridge.proxyClient = previous }
    }
}
