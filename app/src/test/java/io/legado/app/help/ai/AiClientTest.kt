package io.legado.app.help.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import io.legado.app.utils.GSON

class AiClientTest {

    @Test
    fun `chat protocol works against an OpenAI compatible server`() = runBlocking {
        val requestPath = AtomicReference<String>()
        val authorization = AtomicReference<String>()
        val requestBody = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/chat/completions") { exchange ->
                requestPath.set(exchange.requestURI.path)
                authorization.set(exchange.requestHeaders.getFirst("Authorization"))
                requestBody.set(exchange.requestBody.bufferedReader().use { it.readText() })
                val response = """{"choices":[{"message":{"content":"{\"roles\":[]}"}}]}"""
                    .toByteArray()
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }
        try {
            val content = AiClient.chatJson(
                baseUrl = "http://127.0.0.1:${server.address.port}/v1",
                apiKey = "secret-key",
                model = "test-model",
                systemPrompt = "system",
                userPrompt = "user",
                client = OkHttpClient()
            )

            assertEquals("""{"roles":[]}""", content)
            assertEquals("/v1/chat/completions", requestPath.get())
            assertEquals("Bearer secret-key", authorization.get())
            val body = requestBody.get()
            val json = GSON.fromJson(body, Map::class.java)
            assertEquals("test-model", json["model"])
            assertTrue(json.containsKey("response_format"))
            assertTrue(!json.containsKey("thinking"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a 4xx rejection retries once without response_format`() = runBlocking {
        val bodies = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/chat/completions") { exchange ->
                bodies.add(exchange.requestBody.bufferedReader().use { it.readText() })
                val ok = """{"choices":[{"message":{"content":"{\"roles\":[]}"}}]}"""
                val payload = if (bodies.size == 1) {
                    """{"error":{"message":"response_format is not supported"}}"""
                } else {
                    ok
                }.toByteArray()
                exchange.sendResponseHeaders(if (bodies.size == 1) 400 else 200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
            start()
        }
        try {
            val content = AiClient.chatJson(
                baseUrl = "http://127.0.0.1:${server.address.port}/v1",
                apiKey = "",
                model = "test-model",
                systemPrompt = "system",
                userPrompt = "user",
                client = OkHttpClient()
            )

            assertEquals("""{"roles":[]}""", content)
            assertEquals(2, bodies.size)
            val retried = GSON.fromJson(bodies[1], Map::class.java)
            assertTrue("重试仍带 response_format", !retried.containsKey("response_format"))
            assertEquals("test-model", retried["model"])
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a server error surfaces its status rather than a parse failure`() = runBlocking {
        var calls = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/chat/completions") { exchange ->
                calls++
                val payload = """{"error":{"message":"upstream down"}}""".toByteArray()
                exchange.sendResponseHeaders(503, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
            start()
        }
        try {
            val error = kotlin.runCatching {
                AiClient.chatJson(
                    baseUrl = "http://127.0.0.1:${server.address.port}/v1",
                    apiKey = "",
                    model = "test-model",
                    systemPrompt = "system",
                    userPrompt = "user",
                    client = OkHttpClient()
                )
            }.exceptionOrNull()

            assertTrue("未带出状态码", error?.localizedMessage?.contains("503") == true)
            assertEquals("5xx 不该重试", 1, calls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `endpoint tolerates trailing slash and an already complete path`() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            AiClient.endpointOf("https://api.deepseek.com/v1")
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            AiClient.endpointOf("https://api.deepseek.com/v1/")
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            AiClient.endpointOf("https://api.deepseek.com/v1/chat/completions")
        )
    }

    @Test
    fun `content is pulled out of the first choice`() {
        val json = """
            {"choices":[{"message":{"role":"assistant","content":"{\"roles\":[]}"}}]}
        """.trimIndent()
        assertEquals("""{"roles":[]}""", AiClient.extractContent(json))
    }

    @Test
    fun `missing or malformed choices yield null rather than throwing`() {
        assertNull(AiClient.extractContent("""{"error":{"message":"bad key"}}"""))
        assertNull(AiClient.extractContent("""{"choices":[]}"""))
        assertNull(AiClient.extractContent("not json at all"))
    }

    @Test
    fun `null content yields null`() {
        assertNull(AiClient.extractContent("""{"choices":[{"message":{"role":"assistant","content":null}}]}"""))
    }

    @Test
    fun `choices as non-array object yields null`() {
        assertNull(AiClient.extractContent("""{"choices":{"not":"an array"}}"""))
    }

    @Test
    fun `content as array of parts yields null`() {
        assertNull(AiClient.extractContent("""{"choices":[{"message":{"content":[{"type":"text","text":"hi"}]}}]}"""))
    }
}
