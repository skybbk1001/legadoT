package io.legado.app.data.entities

import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeUrl
import kotlin.coroutines.coroutineContext

/**
 * DictRule 中依赖 app 层(AnalyzeUrl/AnalyzeRule)的行为,迁移实体到 shared 后拆为同包扩展
 */

/**
 * 搜索字典
 */
suspend fun DictRule.search(word: String): String {
    val analyzeUrl = AnalyzeUrl(urlRule, key = word, coroutineContext = coroutineContext)
    val body = analyzeUrl.getStrResponseAwait().body
    if (showRule.isBlank()) {
        return body!!
    }
    val analyzeRule = AnalyzeRule().setCoroutineContext(coroutineContext)
    return analyzeRule.getString(showRule, mContent = body)
}
