package io.legado.app.model

/**
 * SharedJsScope 平台桥接, 由 app 在启动时接线:
 * - loadAsset: 读取 assets 文本(cryptojs.min.js)
 * - cacheGet/cachePut: jsLib 文件缓存(ACache, cacheDir/shareJs)
 * - download: 下载远程 jsLib(okHttpClient)
 * - logDebug/putAppDebug: 日志(Debug/AppLog)
 * 未接线时所有钩子 no-op/返回 null, 行为等同于"无缓存无资源"。
 */
object JsScopeBridge {

    var loadAsset: (path: String) -> String? = { null }

    var cacheGet: (key: String) -> String? = { null }

    var cachePut: (key: String, value: String) -> Unit = { _, _ -> }

    var download: (url: String) -> String? = { null }

    var logDebug: (msg: String) -> Unit = {}

    var putAppDebug: (msg: String) -> Unit = {}

}
