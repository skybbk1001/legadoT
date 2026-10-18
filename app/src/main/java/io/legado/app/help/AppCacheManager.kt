package io.legado.app.help

import androidx.collection.LruCache
import io.legado.app.model.analyzeRule.QueryTTF

private val queryTTFMap = LruCache<String, QueryTTF>(4)

object AppCacheManager {

    fun put(key: String, queryTTF: QueryTTF) {
        queryTTFMap.put(key, queryTTF)
    }

    fun getQueryTTF(key: String): QueryTTF? {
        return queryTTFMap[key]
    }

    fun clearSourceVariables() {
        CacheManager.clearSourceVariablesMemory()
    }

}
