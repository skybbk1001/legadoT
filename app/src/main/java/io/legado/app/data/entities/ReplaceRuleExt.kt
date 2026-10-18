package io.legado.app.data.entities

import io.legado.app.exception.NoStackTraceException
import io.legado.app.R
import splitties.init.appCtx

/**
 * ReplaceRule 中依赖 app 层(字符串资源)的行为,迁移实体到 shared 后拆为同包扩展
 */

@Throws(NoStackTraceException::class)
fun ReplaceRule.checkValid() {
    if (!isValid()) {
        throw NoStackTraceException(appCtx.getString(R.string.replace_rule_invalid))
    }
}
