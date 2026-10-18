package com.script.rhino

import androidx.collection.LruCache
import com.script.CompiledScript
import com.script.ScriptBindings
import com.script.ScriptException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.htmlunit.corejs.javascript.Callable
import org.htmlunit.corejs.javascript.ConsString
import org.htmlunit.corejs.javascript.Context
import org.htmlunit.corejs.javascript.ContextFactory
import org.htmlunit.corejs.javascript.ContinuationPending
import org.htmlunit.corejs.javascript.JavaScriptException
import org.htmlunit.corejs.javascript.NativeJSON
import org.htmlunit.corejs.javascript.RhinoException
import org.htmlunit.corejs.javascript.Script
import org.htmlunit.corejs.javascript.Scriptable
import org.htmlunit.corejs.javascript.ScriptableObject
import org.htmlunit.corejs.javascript.TopLevel
import org.htmlunit.corejs.javascript.Undefined
import org.htmlunit.corejs.javascript.VarScope
import org.htmlunit.corejs.javascript.Wrapper
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/**
 * Rhino 求值引擎单例:eval/compile 唯一入口。首次触达装配全局 ContextFactory
 * (ES6/解释模式/ClassShutter/WrapFactory/指令观察);doTopCall 两个重载统一落
 * allowScriptRun 闸门与协程取消检查——脚本只能经本引擎入口运行。
 */
object RhinoScriptEngine {

    private val sourceId = AtomicLong()
    private val sourceCache = LruCache<String, String>(256)

    fun eval(js: String, bindingsConfig: ScriptBindings.() -> Unit = {}): Any? {
        val bindings = ScriptBindings()
        Context.enter()
        try {
            bindings.apply(bindingsConfig)
        } finally {
            Context.exit()
        }
        return eval(js, bindings)
    }

    @Throws(ScriptException::class)
    fun eval(js: String, scope: VarScope): Any? {
        return eval(StringReader(js), scope, null)
    }

    @Throws(ScriptException::class)
    fun eval(js: String, scope: VarScope, coroutineContext: CoroutineContext?): Any? {
        return evalWithSource(js, scope, coroutineContext)
    }

    /**
     * 带源码缓存的求值，捕获异常时能显示出错行的源码和上下文
     */
    @Throws(ScriptException::class)
    private fun evalWithSource(js: String, scope: VarScope, coroutineContext: CoroutineContext?): Any? {
        val sourceName = registerSource(js)
        val cx = Context.enter() as RhinoContext
        val previousCoroutineContext = cx.coroutineContext
        if (coroutineContext != null && coroutineContext[Job] != null) {
            cx.coroutineContext = coroutineContext
        }
        cx.allowScriptRun = true
        cx.recursiveCount++
        val ret: Any?
        try {
            cx.checkRecursive()
            ret = cx.evaluateString(scope, js, sourceName, 1, null)
        } catch (re: RhinoException) {
            throw createScriptException(re, js, sourceName)
        } catch (var14: IOException) {
            throw ScriptException(var14)
        } finally {
            cx.coroutineContext = previousCoroutineContext
            cx.allowScriptRun = false
            cx.recursiveCount--
            Context.exit()
        }
        return unwrapReturnValue(ret)
    }

    @Throws(ScriptException::class)
    fun eval(
        reader: Reader,
        scope: VarScope,
        coroutineContext: CoroutineContext? = null
    ): Any? {
        // 先读取全部内容以便在异常时显示源码上下文
        val source = reader.readText()
        return evalWithSource(source, scope, coroutineContext)
    }

    @Throws(ScriptException::class)
    suspend fun evalSuspend(js: String, scope: VarScope): Any? {
        return evalSuspend(StringReader(js), scope)
    }

    @Throws(ContinuationPending::class)
    suspend fun evalSuspend(reader: Reader, scope: VarScope): Any? {
        // 先读取全部内容以便在异常时显示源码上下文
        val source = reader.readText()
        val sourceName = registerSource(source)
        val cx = Context.enter() as RhinoContext
        Context.exit()
        var ret: Any?
        withContext(RhinoContextElement(cx)) {
            cx.allowScriptRun = true
            cx.recursiveCount++
            try {
                cx.checkRecursive()
                val script = cx.compileString(source, sourceName, 1, null)
                try {
                    ret = cx.executeScriptWithContinuations(script, scope)
                } catch (e: ContinuationPending) {
                    var pending = e
                    while (true) {
                        try {
                            @Suppress("UNCHECKED_CAST")
                            val suspendFunction = pending.applicationState as suspend () -> Any?
                            val functionResult = suspendFunction()
                            val continuation = pending.continuation
                            ret = cx.resumeContinuation(continuation, scope, functionResult)
                            break
                        } catch (e: ContinuationPending) {
                            pending = e
                        }
                    }
                }
            } catch (re: RhinoException) {
                throw createScriptException(re, source, sourceName)
            } catch (var14: IOException) {
                throw ScriptException(var14)
            } finally {
                cx.allowScriptRun = false
                cx.recursiveCount--
            }
        }
        return unwrapReturnValue(ret)
    }

    /** 全新一套标准对象顶层作用域,可作 chainTo 的父层复用 */
    fun newStandardTopLevel(): TopLevel {
        val cx = Context.enter()
        try {
            return cx.initStandardObjects()
        } finally {
            Context.exit()
        }
    }

    /** 对齐旧语义:每次调用挂接一套全新标准对象,脚本对内建原型的改动不跨调用泄漏 */
    fun getRuntimeScope(bindings: ScriptBindings): ScriptBindings {
        bindings.chainTo(newStandardTopLevel())
        return bindings
    }

    @Throws(ScriptException::class)
    fun compile(script: String): CompiledScript {
        return this.compile(StringReader(script) as Reader)
    }

    @Throws(ScriptException::class)
    fun compile(script: Reader): CompiledScript {
        val source = try {
            script.readText()
        } catch (e: IOException) {
            throw ScriptException(e)
        }
        val sourceName = registerSource(source)
        val cx = Context.enter()
        val ret: RhinoCompiledScript
        try {
            val scr = cx.compileString(source, sourceName, 1, null)
            ret = RhinoCompiledScript(scr, source, sourceName)
        } catch (re: RhinoException) {
            throw createScriptException(re, source, sourceName)
        } catch (var9: Exception) {
            throw ScriptException(var9)
        } finally {
            Context.exit()
        }
        return ret
    }

    private fun registerSource(source: String): String {
        val sourceName = "<script-${sourceId.incrementAndGet()}>"
        sourceCache.put(sourceName, source)
        return sourceName
    }

    internal fun createScriptException(
        exception: RhinoException,
        fallbackSource: String,
        fallbackSourceName: String,
    ): ScriptException {
        val line = exception.lineNumber().takeIf { it > 0 } ?: -1
        val column = exception.columnNumber().takeIf { it > 0 } ?: -1
        val sourceName = exception.sourceName()
        val source = sourceCache[sourceName]
            ?: fallbackSource.takeIf { sourceName == fallbackSourceName }
        val baseMessage = if (exception is JavaScriptException) {
            exception.value.toString()
        } else {
            exception.toString()
        }
        val message = source?.let {
            buildEnhancedErrorMessage(baseMessage, it, line, column)
        } ?: baseMessage
        return ScriptException(message, sourceName, line, column).also {
            it.initCause(exception)
        }
    }

    fun unwrapReturnValue(result: Any?): Any? {
        var result1 = result
        if (result1 is Wrapper) {
            result1 = result1.unwrap()
        }
        if (result1 is ConsString) {
            result1 = result1.toString()
        }
        return if (result1 is Undefined) null else result1
    }

    /**
     * 用引擎自身 JSON.stringify 序列化 Rhino 原生对象(NativeObject/NativeArray/ConsString 等)。
     * GSON 反射不认识这些内部惰性类型('u'+page 这类拼接产生 ConsString,嵌套在
     * NativeArray/NativeObject 属性里会被反射成 {left,right,length,isFlat} 内部字段),
     * 必须走引擎自身序列化才能拿到值本身而非内部实现细节。
     * 取不到顶层作用域返回 null 交调用方自行回退。执行失败时按调用方要求返回 null
     * 或抛出原始异常。
     */
    fun stringifyScriptable(
        value: Scriptable,
        coroutineContext: CoroutineContext? = null,
        throwOnFailure: Boolean = false,
    ): String? {
        val topScope = value.parentScope?.let { ScriptableObject.getTopLevelScope(it) }
            ?: return null
        val cx = Context.enter() as RhinoContext
        val previousCoroutineContext = cx.coroutineContext
        if (coroutineContext != null && coroutineContext[Job] != null) {
            cx.coroutineContext = coroutineContext
        }
        try {
            val raw = try {
                NativeJSON.stringify(cx, topScope, value, null, null)
            } catch (e: Exception) {
                if (throwOnFailure) throw e
                return null
            }
            return unwrapReturnValue(raw) as? String
        } finally {
            cx.coroutineContext = previousCoroutineContext
            Context.exit()
        }
    }

    /**
     * 构建增强的错误消息，包含出错行的源码和上下文（类似 Python traceback）。
     * 出错位置的 `^` 精确指向 [errorColumn](RhinoException.columnNumber,1-indexed 列偏移),
     * 而非笼统标出整行——书源脚本经常是拼接/压缩的长单行,整行加波浪线基本无诊断价值。
     * @param baseMsg 原始错误消息
     * @param source 完整源码
     * @param errorLine 出错行号（1-indexed，<=0 表示未知）
     * @param errorColumn 出错列号（1-indexed，Rhino 提供，0 表示未知）
     * @return 增强后的错误消息
     */
    private fun buildEnhancedErrorMessage(
        baseMsg: String,
        source: String,
        errorLine: Int,
        errorColumn: Int
    ): String {
        if (errorLine <= 0) return baseMsg

        val lines = source.lines()
        if (errorLine > lines.size) return baseMsg

        val contextBefore = 2  // 显示出错行前2行
        val contextAfter = 2   // 显示出错行后2行

        val startLine = maxOf(1, errorLine - contextBefore)
        val endLine = minOf(lines.size, errorLine + contextAfter)

        // 行号列宽对齐:按本次显示范围内最大行号的位数,而非固定4位(源码上千行时错位)
        val lineNumWidth = endLine.toString().length

        val sb = StringBuilder(baseMsg)
        sb.append("\n\n")
        sb.append("  源码位置（第 ").append(errorLine)
        if (errorColumn > 0) sb.append(" 行, 第 ").append(errorColumn).append(" 列") else sb.append(" 行")
        sb.append("）:\n")
        sb.append("  ").append("─".repeat(60)).append("\n")

        for (i in startLine..endLine) {
            val lineContent = lines[i - 1]  // lines 是 0-indexed
            val prefix = if (i == errorLine) "→ " else "  "
            val lineNumStr = i.toString().padStart(lineNumWidth)
            sb.append(prefix).append(lineNumStr).append(" │ ").append(lineContent).append("\n")

            if (i == errorLine) {
                // 缩进 = "  " + 行号列宽 + " │ " 的长度,与上一行的 " │ " 分隔符对齐
                val gutterWidth = 2 + lineNumWidth + 3
                sb.append(" ".repeat(gutterWidth))
                if (errorColumn > 0) {
                    // errorColumn 是 1-indexed 列偏移,前面填 errorColumn-1 个空格再放置指示符
                    sb.append(" ".repeat((errorColumn - 1).coerceAtMost(400)))
                    sb.append("^")
                } else {
                    // 列信息缺失时回退:整行加波浪线(优于完全不给指示)
                    sb.append("^".repeat(lineContent.length.coerceIn(1, 60)))
                }
                sb.append("\n")
            }
        }

        sb.append("  ").append("─".repeat(60))

        return sb.toString()
    }

    init {
        ContextFactory.initGlobal(object : ContextFactory() {

            override fun makeContext(): Context {
                val cx = RhinoContext(this)
                cx.languageVersion = Context.VERSION_ES6
                cx.setInterpretedMode(true)
                cx.setClassShutter(RhinoClassShutter)
                cx.wrapFactory = RhinoWrapFactory
                cx.instructionObserverThreshold = 10000
                cx.maximumInterpreterStackDepth = 1000
                return cx
            }

            override fun hasFeature(cx: Context, featureIndex: Int): Boolean {
                @Suppress("UNUSED_EXPRESSION")
                return when (featureIndex) {
                    Context.FEATURE_ENABLE_JAVA_MAP_ACCESS -> true
                    // 非严格裸调用的 this 取当次顶层调用作用域的 globalThis:
                    // jsLib 函数经 this.java/this.cache 访问书源执行环境的惯用法依赖此语义
                    Context.FEATURE_LEGADO_DYNAMIC_DEFAULT_THIS -> true
                    // 间接 eval((0,eval)/别名调用)与 Function 构造器在当次顶层调用作用域
                    // 求值:被 eval 的书源代码经此可见 java/cookie 等运行时绑定
                    Context.FEATURE_LEGADO_DYNAMIC_EVAL_REALM -> true
                    else -> super.hasFeature(cx, featureIndex)
                }
            }

            override fun observeInstructionCount(cx: Context, instructionCount: Int) {
                if (cx is RhinoContext) {
                    cx.ensureActive()
                }
            }

            override fun doTopCall(
                callable: Callable,
                cx: Context,
                scope: VarScope,
                thisObj: Scriptable?,
                args: Array<Any>
            ): Any? {
                try {
                    ensureScriptRunAllowed(cx)
                    return super.doTopCall(callable, cx, scope, thisObj, args)
                } catch (e: RhinoInterruptError) {
                    throw e.cause
                }
            }

            override fun doTopCall(
                script: Script,
                cx: Context,
                scope: VarScope,
                thisObj: Scriptable?
            ): Any? {
                try {
                    ensureScriptRunAllowed(cx)
                    return super.doTopCall(script, cx, scope, thisObj)
                } catch (e: RhinoInterruptError) {
                    throw e.cause
                }
            }

            private fun ensureScriptRunAllowed(cx: Context) {
                if (cx is RhinoContext) {
                    if (!cx.allowScriptRun) {
                        error("Not allow run script in unauthorized way.")
                    }
                    cx.ensureActive()
                }
            }
        })
    }

}
