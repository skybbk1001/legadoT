package io.legado.app.model

import androidx.collection.LruCache
import com.google.gson.reflect.TypeToken
import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.exception.NoStackTraceException
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonObject
import java.io.FileNotFoundException
import java.lang.ref.WeakReference
import kotlin.coroutines.CoroutineContext

object SharedJsScope {

    private val scopeMap = LruCache<String, WeakReference<ScriptBindings>>(16)
    private const val CRYPTO_JS_ASSET = "scripts/cryptojs.min.js"
    @Volatile
    private var cryptoJsText: String? = null
    @Volatile
    private var cryptoScope: WeakReference<ScriptBindings>? = null
    private val cryptoLock = Any()
    private const val CRYPTO_JS_ERROR_KEY = "cryptojs_load_error"

    private fun loadCryptoJs(): String? {
        val cached = cryptoJsText
        if (cached != null) return cached
        return try {
            val text = JsScopeBridge.loadAsset(CRYPTO_JS_ASSET)
                ?: throw FileNotFoundException(CRYPTO_JS_ASSET)
            cryptoJsText = text
            text
        } catch (e: Throwable) {
            val msg = "加载CryptoJS失败: ${e.message}"
            runCatching {
                JsScopeBridge.cachePut(CRYPTO_JS_ERROR_KEY, msg)
                JsScopeBridge.logDebug(msg)
                JsScopeBridge.putAppDebug(msg)
            }
            null
        }
    }

    fun getCryptoScope(coroutineContext: CoroutineContext?): ScriptBindings? {
        val cached = cryptoScope?.get()
        if (cached != null) return cached
        synchronized(cryptoLock) {
            val second = cryptoScope?.get()
            if (second != null) return second
            val js = loadCryptoJs() ?: return null
            val scope = RhinoScriptEngine.getRuntimeScope(ScriptBindings())
            RhinoScriptEngine.eval(js, scope, coroutineContext)
            scope.sealObject()
            cryptoScope = WeakReference(scope)
            return scope
        }
    }

    fun getScope(jsLib: String?, coroutineContext: CoroutineContext?): ScriptBindings? {
        if (jsLib.isNullOrBlank()) {
            return null
        }
        val key = MD5Utils.md5Encode(jsLib)
        var scope = scopeMap[key]?.get()
        if (scope == null) {
            scope = RhinoScriptEngine.getRuntimeScope(ScriptBindings())
            loadCryptoJs()?.let { js ->
                RhinoScriptEngine.eval(js, scope, coroutineContext)
            }
            if (jsLib.isJsonObject()) {
                val jsMap: Map<String, String> = GSON.fromJson(
                    jsLib,
                    TypeToken.getParameterized(
                        Map::class.java,
                        String::class.java,
                        String::class.java
                    ).type
                )
                jsMap.values.forEach { value ->
                    if (value.isAbsUrl()) {
                        val fileName = MD5Utils.md5Encode(value)
                        var js = JsScopeBridge.cacheGet(fileName)
                        if (js == null) {
                            js = JsScopeBridge.download(value)
                            if (js != null) {
                                JsScopeBridge.cachePut(fileName, js)
                            } else {
                                throw NoStackTraceException("下载jsLib-${value}失败")
                            }
                        }
                        RhinoScriptEngine.eval(js, scope, coroutineContext)
                    }
                }
            } else {
                RhinoScriptEngine.eval(jsLib, scope, coroutineContext)
            }
            scope.sealObject()
            scopeMap.put(key, WeakReference(scope))
        }
        return scope
    }

    fun remove(jsLib: String?) {
        if (jsLib.isNullOrBlank()) {
            return
        }
        val key = MD5Utils.md5Encode(jsLib)
        scopeMap.remove(key)
    }

}
