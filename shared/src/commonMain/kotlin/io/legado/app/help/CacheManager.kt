package io.legado.app.help

import androidx.collection.LruCache
import io.legado.app.data.entities.Cache
import io.legado.app.utils.memorySize

/**
 * 最多只缓存50M的数据,防止OOM
 */
private val memoryLruCache = object : LruCache<String, Any>(1024 * 1024 * 50) {

    override fun sizeOf(key: String, value: Any): Int {
        return value.toString().memorySize()
    }

}

@Suppress("unused")
object CacheManager {

    /**
     * saveTime 单位为秒
     */
    @JvmOverloads
    fun put(key: String, value: Any, saveTime: Int = 0) {
        val deadline =
            if (saveTime == 0) 0 else System.currentTimeMillis() + saveTime * 1000
        when (value) {
            is ByteArray -> CacheBridge.filePutBinary(key, value, saveTime)
            else -> {
                val valueStr = value.toString()
                putMemory(key, valueStr)
                val cache = Cache(key, valueStr, deadline)
                CacheBridge.dbPut(cache)
            }
        }
    }

    fun putMemory(key: String, value: Any) {
        memoryLruCache.put(key, value)
    }

    //从内存中获取数据 使用lruCache
    fun getFromMemory(key: String): Any? {
        return memoryLruCache[key]
    }

    fun deleteMemory(key: String) {
        memoryLruCache.remove(key)
    }

    fun get(key: String): String? {
        (getFromMemory(key) as? String)?.let { return it }
        return get(key, true)?.also { putMemory(key, it) }
    }

    fun get(key: String, onlyDisk: Boolean): String? {
        if (!onlyDisk) return get(key)
        val cache = CacheBridge.dbGet(key)
        if (cache != null && (cache.deadline == 0L || cache.deadline > System.currentTimeMillis())) {
            return cache.value
        }
        return null
    }

    fun getInt(key: String): Int? {
        return get(key)?.toIntOrNull()
    }

    fun getLong(key: String): Long? {
        return get(key)?.toLongOrNull()
    }

    fun getDouble(key: String): Double? {
        return get(key)?.toDoubleOrNull()
    }

    fun getFloat(key: String): Float? {
        return get(key)?.toFloatOrNull()
    }

    fun getByteArray(key: String): ByteArray? {
        return CacheBridge.fileGetBinary(key)
    }

    fun putFile(key: String, value: String, saveTime: Int = 0) {
        CacheBridge.filePutString(key, value, saveTime)
    }

    fun getFile(key: String): String? {
        return CacheBridge.fileGetString(key)
    }

    fun delete(key: String) {
        CacheBridge.dbDelete(key)
        deleteMemory(key)
        CacheBridge.fileRemove(key)
    }

    /**
     * 清除源相关变量(v_/userInfo_/loginHeader_/sourceVariable_ 前缀)的内存缓存
     */
    fun clearSourceVariablesMemory() {
        val prefixes = arrayOf("v_", "userInfo_", "loginHeader_", "sourceVariable_")
        memoryLruCache.snapshot().keys.forEach {
            if (prefixes.any { prefix -> it.startsWith(prefix) }) {
                memoryLruCache.remove(it)
            }
        }
    }

}
