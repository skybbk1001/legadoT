package io.legado.app.ui.book.toc

import io.legado.app.data.entities.BookChapter

sealed class TocListItem {
    abstract val key: String
    abstract val chapter: BookChapter
    abstract val depth: Int

    data class Volume(
        override val chapter: BookChapter,
        override val depth: Int,
        val collapsed: Boolean,
        val chapterCount: Int,
        val matchedCount: Int? = null,
        val matchedSelf: Boolean = false,
        val containsDurChapter: Boolean = false,
        // 折叠箭头是否可交互:目录页正常列表为 true;搜索结果/详情页内嵌目录为 false(无折叠语义)
        val toggleable: Boolean = true
    ) : TocListItem() {
        override val key: String = "volume:${chapter.index}"
    }

    data class Chapter(
        override val chapter: BookChapter,
        override val depth: Int,
        val parentVolumeIndex: Int? = null
    ) : TocListItem() {
        override val key: String = "chapter:${chapter.index}"
    }
}
