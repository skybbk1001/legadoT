package io.legado.app.data.entities

import com.script.ScriptBindings
import kotlin.coroutines.CoroutineContext

/**
 * shared 层对书源(BaseSource, 留 app)的最小契约。
 * BaseSource 实现本接口后, 已迁入 shared 的规则编排器
 * (AnalyzeRule/AnalyzeUrl/ConcurrentRateLimiter)可脱离 app 模块引用书源能力。
 */
interface SourceContract {

    /** 书源唯一标识(通常为书源URL) */
    fun getKey(): String

    /** 书源标签(日志用) */
    fun getTag(): String

    /** 并发率 */
    val concurrentRate: String?

    /** 启用cookieJar */
    val enabledCookieJar: Boolean?

    /** js库 */
    val jsLib: String?

    /** 解析header规则 */
    fun getHeaderMap(hasLoginHeader: Boolean = false): Map<String, String>

    /** 保存数据 */
    fun put(key: String, value: String): String

    /** 获取保存的数据 */
    fun get(key: String): String

    /** 获取源共享js作用域(jsLib 预执行) */
    fun getShareScope(coroutineContext: CoroutineContext?): ScriptBindings?

}
