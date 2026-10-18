package io.legado.app.data.entities

import io.legado.app.constant.PageAnim
import io.legado.app.data.appDb
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.getFolderNameNoCache
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isImage
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ReadBook
import kotlin.math.max

/**
 * Book 中依赖 app 层(help/config/appDb)的行为,迁移实体到 shared 后拆为同包扩展
 */

fun Book.getUnreadChapterNum() = max(simulatedTotalChapterNum() - durChapterIndex - 1, 0)

fun Book.getUseReplaceRule(): Boolean {
    val useReplaceRule = config.useReplaceRule
    if (useReplaceRule != null) {
        return useReplaceRule
    }
    //图片类书源 epub本地 默认关闭净化
    if (isImage || isEpub) {
        return false
    }
    return AppConfig.replaceEnableDefault
}

fun Book.getPageAnim(): Int {
    var pageAnim = config.pageAnim
        ?: if (isImage) PageAnim.scrollPageAnim else ReadBookConfig.pageAnim
    if (pageAnim < 0) {
        pageAnim = ReadBookConfig.pageAnim
    }
    return pageAnim
}

fun Book.getAudioSkipEnabled(): Boolean {
    return config.audioSkipEnabled ?: AppConfig.audioSkipEnabled
}

fun Book.getAudioIntroMs(): Int {
    return config.audioIntroMs ?: AppConfig.audioSkipIntroMs
}

fun Book.getAudioOutroMs(): Int {
    return config.audioOutroMs ?: AppConfig.audioSkipOutroMs
}

fun Book.getAudioSkipMinDurationMs(): Int {
    return config.audioSkipMinDurationMs ?: AppConfig.audioSkipMinDurationMs
}

fun Book.getFolderName(): String {
    folderName?.let {
        return it
    }
    //防止书名过长,只取9位
    folderName = getFolderNameNoCache()
    return folderName!!
}

fun Book.toSearchBook() = SearchBook(
    name = name,
    author = author,
    kind = kind,
    bookUrl = bookUrl,
    origin = origin,
    originName = originName,
    type = type,
    wordCount = wordCount,
    latestChapterTitle = latestChapterTitle,
    coverUrl = coverUrl,
    intro = intro,
    tocUrl = tocUrl,
    originOrder = originOrder,
    variable = variable
).apply {
    this.infoHtml = this@toSearchBook.infoHtml
    this.tocHtml = this@toSearchBook.tocHtml
}

/**
 * 迁移旧的书籍的一些信息到新的书籍中
 */
fun Book.migrateTo(newBook: Book, toc: List<BookChapter>): Book {
    newBook.durChapterIndex = BookHelp
        .getDurChapter(durChapterIndex, durChapterTitle, toc, totalChapterNum)
    newBook.durChapterTitle = toc[newBook.durChapterIndex].getDisplayTitle(
        ContentProcessor.get(newBook.name, newBook.origin).getTitleReplaceRules(),
        getUseReplaceRule()
    )
    newBook.durChapterPos = durChapterPos
    newBook.durChapterTime = durChapterTime
    newBook.group = group
    newBook.order = order
    newBook.customCoverUrl = customCoverUrl
    newBook.customIntro = customIntro
    newBook.customTag = customTag
    newBook.canUpdate = canUpdate
    newBook.readConfig = readConfig
    return newBook
}

fun Book.save() {
    if (appDb.bookDao.has(bookUrl)) {
        appDb.bookDao.update(this)
    } else {
        appDb.bookDao.insert(this)
    }
}

fun Book.delete() {
    if (ReadBook.book?.bookUrl == bookUrl) {
        ReadBook.book = null
    }
    appDb.bookDao.delete(this)
}
