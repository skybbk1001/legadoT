package io.legado.app.data.entities

import org.json.JSONObject

/**
 * Server 中依赖 app 层(org.json)的行为,迁移实体到 shared 后拆为同包扩展
 */

fun Server.getConfigJsonObject(): JSONObject? {
    val json = config
    json ?: return null
    return JSONObject(json)
}
