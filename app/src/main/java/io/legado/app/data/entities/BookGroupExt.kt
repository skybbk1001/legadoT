package io.legado.app.data.entities

import io.legado.app.data.entities.BookGroup.Companion.IdAll
import io.legado.app.data.entities.BookGroup.Companion.IdAudio
import io.legado.app.data.entities.BookGroup.Companion.IdError
import io.legado.app.data.entities.BookGroup.Companion.IdLocal
import io.legado.app.data.entities.BookGroup.Companion.IdLocalNone
import io.legado.app.data.entities.BookGroup.Companion.IdNetNone
import io.legado.app.help.config.AppConfig
import android.content.Context
import io.legado.app.R

/**
 * BookGroup 中依赖 app 层(资源字符串/AppConfig)的行为,迁移实体到 shared 后拆为同包扩展
 */

fun BookGroup.getManageName(context: Context): String {
    return when (groupId) {
        IdAll -> "$groupName(${context.getString(R.string.all)})"
        IdAudio -> "$groupName(${context.getString(R.string.audio)})"
        IdLocal -> "$groupName(${context.getString(R.string.local)})"
        IdNetNone -> "$groupName(${context.getString(R.string.net_no_group)})"
        IdLocalNone -> "$groupName(${context.getString(R.string.local_no_group)})"
        IdError -> "$groupName(${context.getString(R.string.update_book_fail)})"
        else -> groupName
    }
}

fun BookGroup.getRealBookSort(): Int {
    if (bookSort < 0) {
        return AppConfig.bookshelfSort
    }
    return bookSort
}
