package io.legado.app.web.mcp

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BookSource
import org.htmlunit.corejs.javascript.Undefined
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpFormatTest {

    @Test
    fun detectFormatBoundary() {
        assertEquals("json", McpFormat.detectFormat("  {\"a\":1}"))
        assertEquals("json", McpFormat.detectFormat("[1]"))
        assertEquals("js", McpFormat.detectFormat("// @name x"))
        assertEquals("js", McpFormat.detectFormat(""))
    }

    @Test
    fun summarizeFilterAndShape() {
        val a = BookSource(bookSourceName = "起点", bookSourceUrl = "https://a.com")
        val b = BookSource(bookSourceName = "笔趣", bookSourceUrl = "https://b.com")
        val all = McpFormat.summarizeSources(listOf(a, b), null)
        assertEquals(2, all.size)
        assertEquals("起点", all[0]["bookSourceName"])
        assertEquals(false, all[0]["isJsSource"])
        val hit = McpFormat.summarizeSources(listOf(a, b), "B.COM")
        assertEquals(1, hit.size)
        assertEquals("https://b.com", hit[0]["bookSourceUrl"])
    }

    @Test
    fun truncateBoundary() {
        assertEquals("abc", McpFormat.truncate("abc", 5))
        val cut = McpFormat.truncate("abcdef", 5)
        assertTrue(cut.startsWith("abcde"))
        assertTrue(cut.contains("已截断,原文 6 字符"))
    }

    @Test
    fun renderEvalResultScalars() {
        assertEquals("abc", McpFormat.renderEvalResult("abc"))
        assertEquals("null", McpFormat.renderEvalResult(null))
        assertEquals("undefined", McpFormat.renderEvalResult(Undefined.instance))
        assertEquals("true", McpFormat.renderEvalResult(true))
        assertEquals("42", McpFormat.renderEvalResult(42.0))
        assertEquals("1.5", McpFormat.renderEvalResult(1.5))
    }

    @Test
    fun renderEvalResultJsonShape() {
        val rendered = McpFormat.renderEvalResult(mapOf("a" to listOf(1.0, "x"), "b" to true))
        assertEquals(
            """
            {
              "a": [
                1,
                "x"
              ],
              "b": true
            }
            """.trimIndent(),
            rendered
        )
    }

    @Test
    fun renderEvalResultLeafFallback() {
        val rendered = McpFormat.renderEvalResult(Any())
        assertTrue(rendered.endsWith("(Object)"))
    }

    @Test
    fun renderEvalResultCyclicFallsBack() {
        val m = HashMap<String, Any>()
        m["self"] = m
        val rendered = McpFormat.renderEvalResult(m)
        assertTrue(rendered.endsWith("(HashMap)"))
    }

    @Test
    fun renderAppLogsShapesEachEntry() {
        val entries = listOf(
            AppLog.Entry(
                id = 7,
                time = 0L,
                message = "多行\n消息",
                tag = "某书源",
            ),
            AppLog.Entry(
                id = 6,
                time = 0L,
                message = "请求失败",
                throwable = IllegalStateException("boom"),
                httpId = 12,
            ),
        )
        val lines = McpFormat.renderAppLogs(entries).lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("#7 1970-01-01T00:00:00Z [某书源] 多行 消息"))
        assertTrue(lines[1].contains("httpLog #12"))
        assertTrue(lines[1].contains("IllegalStateException: boom"))
    }

    @Test
    fun renderAppLogsCategoryLabelFallback() {
        val entry = AppLog.Entry(id = 1, time = 0L, message = "普通信息")
        assertTrue(McpFormat.renderAppLogs(listOf(entry)).contains("[信息]"))
        val error = AppLog.Entry(id = 2, time = 0L, message = "炸了", error = true)
        assertTrue(McpFormat.renderAppLogs(listOf(error)).contains("[错误]"))
        assertEquals("", McpFormat.renderAppLogs(emptyList()))
    }

    @Test
    fun renderCheckSummarySeparatesGoodAndBad() {
        val bad = BookSource(
            bookSourceName = "坏站",
            bookSourceUrl = "https://bad.com",
            bookSourceGroup = "自定义,搜索失效",
            bookSourceComment = "// Error: 搜索超时",
        )
        val good = BookSource(
            bookSourceName = "好站",
            bookSourceUrl = "https://good.com",
            respondTime = 3000L,
        )
        val rendered = McpFormat.renderCheckSummary(
            listOf(bad, good),
            mapOf("https://bad.com" to "[00:12.000] 校验失败:搜索失效"),
            180000L,
        )
        assertTrue(rendered.contains("坏源 1/2"))
        assertTrue(rendered.contains("✗ 坏站"))
        assertTrue(rendered.contains("搜索失效"))
        assertTrue(rendered.contains("// Error: 搜索超时"))
        assertTrue(rendered.contains("✓ 好站"))
    }

    @Test
    fun renderCheckSummaryTimeoutJudgedBad() {
        val slow = BookSource(
            bookSourceName = "超时站",
            bookSourceUrl = "https://slow.com",
            respondTime = 200000L,
        )
        val rendered = McpFormat.renderCheckSummary(listOf(slow), emptyMap(), 180000L)
        assertTrue(rendered.contains("坏源 1/1"))
        assertTrue(rendered.contains("✗ 超时站"))
    }
}
