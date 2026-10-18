package io.legado.app.help.source

import io.legado.app.constant.BookSourceType
import io.legado.app.constant.BookType
import io.legado.app.data.entities.BookSourceContract

/**
 * 书源类型换算(纯逻辑, 自 app help/source 迁入 shared)。
 * app 侧同名扩展已删除, 调用点 import 包名不变。
 */
fun BookSourceContract.getBookType(): Int {
    return when (bookSourceType) {
        BookSourceType.file -> BookType.text or BookType.webFile
        BookSourceType.image -> BookType.image
        BookSourceType.audio -> BookType.audio
        else -> BookType.text
    }
}
