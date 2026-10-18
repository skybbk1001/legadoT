package io.legado.app.help

import io.legado.app.data.entities.Cache

/**
 * CacheManager 平台持久化桥接, 由 app 在启动时接线(参照 BigVariableStore/SharedLog 先例):
 * - db* 钩子接 Room caches 表(appDb.cacheDao)
 * - file* 钩子接 ACache 文件缓存
 * 未接线时所有钩子 no-op/返回 null, 行为等同于"无持久化"。
 */
object CacheBridge {

    /** Room caches 表按 key 读取(含已过期记录, 由调用方判断 deadline) */
    var dbGet: (key: String) -> Cache? = { null }

    /** Room caches 表写入(REPLACE) */
    var dbPut: (cache: Cache) -> Unit = {}

    /** Room caches 表按 key 删除 */
    var dbDelete: (key: String) -> Unit = {}

    /** ACache 写入二进制 */
    var filePutBinary: (key: String, value: ByteArray, saveTime: Int) -> Unit = { _, _, _ -> }

    /** ACache 读取二进制 */
    var fileGetBinary: (key: String) -> ByteArray? = { null }

    /** ACache 写入字符串 */
    var filePutString: (key: String, value: String, saveTime: Int) -> Unit = { _, _, _ -> }

    /** ACache 读取字符串 */
    var fileGetString: (key: String) -> String? = { null }

    /** ACache 按 key 移除 */
    var fileRemove: (key: String) -> Unit = {}

}
