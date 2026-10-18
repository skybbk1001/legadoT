package io.legado.app

import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.JsExtensionsAndroid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 书源 JS 经 Rhino 传 null 给 java.hexXxx/base64Xxx 系列的探针。
 *
 * 实测结论(hutool 时代 vs 当前):
 * - hutool 时代这四个函数对 JS null 全部抛 NPE: hex 三个是 Kotlin 非空参数的
 *   边界检查先于 hutool 触发; base64Decode 则因 hutool 5.8.22
 *   Base64Decoder.decodeStr 无 null 守卫同样 NPE。即"移除前/后"行为一致。
 * - 当前版本加了 null 守卫, 统一改为返回 null(行为增强, 非回归修复)。
 *
 * 对比验证(可选): 在 hutool 版本上跑同一测试:
 *   git worktree add ../legado-hutool b84b8eaa23^
 * 同样代码下两个 null 测试会以 NPE 失败, 佐证旧版行为。
 */
class JsHexNullProbeTest {

    // JsExtensions/JsEncodeUtils 大部分方法有默认实现, 仅 getSource 为抽象成员
    private class TestJs : JsExtensionsAndroid {
        override fun getSource(): BaseSource? = null
    }

    private fun evalJs(js: String): Any? {
        val bindings = ScriptBindings()
        bindings["java"] = TestJs()
        return RhinoScriptEngine.eval(js, bindings)
    }

    @Test
    fun hexNullInputsReturnNull() {
        assertNull(evalJs("java.hexDecodeToByteArray(null)"))
        assertNull(evalJs("java.hexDecodeToString(null)"))
        assertNull(evalJs("java.hexEncodeToString(null)"))
    }

    @Test
    fun base64NullInputReturnsNull() {
        assertNull(evalJs("java.base64Decode(null)"))
    }

    @Test
    fun normalInputsUnaffected() {
        assertEquals("6162", evalJs("java.hexEncodeToString('ab')"))
        assertEquals("ab", evalJs("java.hexDecodeToString('6162')"))
        assertEquals("a", evalJs("java.base64Decode('YQ==')"))
    }
}
