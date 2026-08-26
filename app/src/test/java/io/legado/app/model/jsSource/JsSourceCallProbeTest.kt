package io.legado.app.model.jsSource

import com.script.ScriptBindings
import com.script.buildScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.exception.NoStackTraceException
import org.htmlunit.corejs.javascript.ScriptableObject
import org.htmlunit.corejs.javascript.Function as JsFunction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定 JS 源的调用机制与归一化契约(spec §2/§7):
 * eval 调用表达式(参数=绑定)可调顶层函数;NativeObject/NativeArray 经 GSON 归一化。
 */
class JsSourceCallProbeTest {

    private fun callViaEval(mainJs: String, callExpr: String, args: List<Pair<String, Any?>>): Any? {
        val bindings = buildScriptBindings { b ->
            args.forEach { (k, v) -> b[k] = v }
        }
        val scope = RhinoScriptEngine.getRuntimeScope(bindings)
        RhinoScriptEngine.eval(mainJs, scope)
        return RhinoScriptEngine.eval(callExpr, scope)
    }

    /**
     * callFunctionIfExists 的行为镜像(不走真实 JsSourceEngine 实例):
     * buildScope() 会无条件调用 source.getShareScope() → SharedJsScope,其 companion
     * 属性初始化(cacheFolder = File(appCtx.cacheDir, ...))在裸 JUnit(无 Robolectric)下
     * 类初始化即崩(ClassNotFoundException: android.app.ActivityThread)——与 callFunction
     * 共享同一 buildScope,并非本次重构引入。按简报 Step 1 预案回退 callViaEval 风格,
     * 镜像同一段判定逻辑(ScriptableObject.getProperty(...) is JsFunction 后条件 eval)。
     */
    private fun callFunctionIfExistsViaEval(
        mainJs: String,
        name: String,
        args: List<Pair<String, Any?>> = emptyList(),
    ): String? {
        val bindings = buildScriptBindings { b ->
            args.forEach { (k, v) -> b[k] = v }
        }
        val scope = RhinoScriptEngine.getRuntimeScope(bindings)
        RhinoScriptEngine.eval(mainJs, scope)
        if (ScriptableObject.getProperty(scope, name) !is JsFunction) {
            return null
        }
        val callExpr = "$name(${args.joinToString(", ") { it.first }})"
        val raw = RhinoScriptEngine.eval(callExpr, scope)
        return JsSourceEngine.normalizeJsResult(raw)
    }

    @Test
    fun evalCallExpressionInvokesTopLevelFunction() {
        val raw = callViaEval(
            "function search(key, page) { return [{name: key, bookUrl: 'u' + page}] }",
            "search(key, page)",
            listOf("key" to "斗罗", "page" to 2),
        )
        val json = JsSourceEngine.normalizeJsResult(raw)!!
        assertTrue(json.contains("斗罗"))
        assertTrue(json.contains("u2"))
    }

    @Test
    fun nativeObjectNormalizesViaGson() {
        val raw = callViaEval(
            "function info() { return {intro: '简介', wordCount: '12万字'} }",
            "info()",
            emptyList(),
        )
        val json = JsSourceEngine.normalizeJsResult(raw)!!
        assertTrue(json.contains("\"intro\""))
        assertTrue(json.contains("12万字"))
    }

    @Test
    fun stringPassesThroughUntouched() {
        val raw = callViaEval(
            "function getContent() { return '第一段\\n第二段' }",
            "getContent()",
            emptyList(),
        )
        assertEquals("第一段\n第二段", JsSourceEngine.normalizeJsResult(raw))
    }

    @Test
    fun nullAndUndefinedBecomeNull() {
        assertNull(JsSourceEngine.normalizeJsResult(null))
        val raw = callViaEval("function noop() { }", "noop()", emptyList())
        assertNull(JsSourceEngine.normalizeJsResult(raw))
    }

    @Test
    fun jsonStringifyEquivalentToDirectReturn() {
        val direct = callViaEval(
            "function f() { return [{a: 1}] }", "f()", emptyList(),
        ).let { JsSourceEngine.normalizeJsResult(it)!! }
        val stringified = callViaEval(
            "function f() { return JSON.stringify([{a: 1}]) }", "f()", emptyList(),
        ).let { JsSourceEngine.normalizeJsResult(it)!! }
        // 语义等价(数字表示可能 1 vs 1.0,断言结构键存在即可)
        assertTrue(direct.contains("\"a\""))
        assertTrue(stringified.contains("\"a\""))
    }

    @Test
    fun circularReferenceStringifyFailureThrowsExplicitError() {
        val raw = callViaEval(
            "function f() { var a = {}; a.self = a; return a }",
            "f()",
            emptyList(),
        )
        val error = assertThrows(NoStackTraceException::class.java) {
            JsSourceEngine.normalizeJsResult(raw)
        }
        assertTrue(error.message.orEmpty().contains("JSON.stringify 失败"))
    }

    @Test
    fun callFunctionIfExistsReturnsNullWhenMissing() {
        val result = callFunctionIfExistsViaEval(
            "function search(key, page) { return [] }",
            "getBookInfo",
        )
        assertNull(result)
    }

    @Test
    fun callFunctionIfExistsInvokesWhenPresent() {
        val json = callFunctionIfExistsViaEval(
            "function getBookInfo(book) { return {intro: 'ok'} }",
            "getBookInfo",
        )!!
        assertTrue(json.contains("\"intro\""))
    }
}
