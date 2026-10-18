package io.legado.app.help.book

import io.legado.app.constant.AppPattern

/**
 * 书名/作者的展示格式化(纯逻辑, 自 app BookHelp 迁入 shared)。
 * app BookHelp 保留同名成员委托本对象, 调用点不变。
 */
object BookFormat {

    /**
     * 格式化书名
     */
    fun formatBookName(name: String): String {
        return name
            .replace(AppPattern.nameRegex, "")
            .trim { it <= ' ' }
    }

    /**
     * 格式化作者
     */
    fun formatBookAuthor(author: String): String {
        return author
            .replace(AppPattern.authorRegex, "")
            .trim { it <= ' ' }
    }

}
