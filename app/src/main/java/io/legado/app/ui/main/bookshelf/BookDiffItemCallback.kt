package io.legado.app.ui.main.bookshelf

import androidx.core.os.bundleOf
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.getUnreadChapterNum

/**
 * 书架 Book 项的 Diff 回调公共实现（R1 由 style1/style2 两份近似拷贝合一,采 style1 版）。
 * 含 lastUpdateTime payload 分支——style2 的绑定路径无该 case,收到即忽略,无副作用。
 */
object BookDiffItemCallback : DiffUtil.ItemCallback<Book>() {

    override fun areItemsTheSame(oldItem: Book, newItem: Book): Boolean {
        return oldItem.name == newItem.name
                && oldItem.author == newItem.author
    }

    override fun areContentsTheSame(oldItem: Book, newItem: Book): Boolean {
        return when {
            oldItem.durChapterTime != newItem.durChapterTime -> false
            oldItem.name != newItem.name -> false
            oldItem.author != newItem.author -> false
            oldItem.durChapterTitle != newItem.durChapterTitle -> false
            oldItem.latestChapterTitle != newItem.latestChapterTitle -> false
            oldItem.lastCheckCount != newItem.lastCheckCount -> false
            oldItem.getDisplayCover() != newItem.getDisplayCover() -> false
            oldItem.getUnreadChapterNum() != newItem.getUnreadChapterNum() -> false
            else -> true
        }
    }

    override fun getChangePayload(oldItem: Book, newItem: Book): Any? {
        val bundle = bundleOf()
        if (oldItem.name != newItem.name) {
            bundle.putString("name", newItem.name)
        }
        if (oldItem.author != newItem.author) {
            bundle.putString("author", newItem.author)
        }
        if (oldItem.durChapterTitle != newItem.durChapterTitle) {
            bundle.putString("dur", newItem.durChapterTitle)
        }
        if (oldItem.latestChapterTitle != newItem.latestChapterTitle) {
            bundle.putString("last", newItem.latestChapterTitle)
        }
        if (oldItem.getDisplayCover() != newItem.getDisplayCover()) {
            bundle.putString("cover", newItem.getDisplayCover())
        }
        if (oldItem.lastCheckCount != newItem.lastCheckCount
            || oldItem.durChapterTime != newItem.durChapterTime
            || oldItem.getUnreadChapterNum() != newItem.getUnreadChapterNum()
        ) {
            bundle.putBoolean("refresh", true)
        }
        if (oldItem.latestChapterTime != newItem.latestChapterTime) {
            bundle.putBoolean("lastUpdateTime", true)
        }
        if (bundle.isEmpty) return null
        return bundle
    }
}
