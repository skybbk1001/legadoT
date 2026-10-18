@file:Suppress("unused")

package io.legado.app.help.book

import io.legado.app.constant.BookSourceType
import io.legado.app.constant.BookType
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.Book
import io.legado.app.constant.AppPattern
import io.legado.app.exception.NoStackTraceException
import io.legado.app.utils.MD5Utils
import java.time.LocalDate
import java.time.Period.between
import kotlin.math.max
import kotlin.math.min


val Book.isAudio: Boolean
    get() = isType(BookType.audio)

val Book.isImage: Boolean
    get() = isType(BookType.image)

val Book.isLocal: Boolean
    get() {
        if (type == 0) {
            return origin == BookType.localTag || origin.startsWith(BookType.webDavTag)
        }
        return isType(BookType.local)
    }

val Book.isLocalTxt: Boolean
    get() = isLocal && originName.endsWith(".txt", true)

val Book.isEpub: Boolean
    get() = isLocal && originName.endsWith(".epub", true)

val Book.isUmd: Boolean
    get() = isLocal && originName.endsWith(".umd", true)

val Book.isPdf: Boolean
    get() = isLocal && originName.endsWith(".pdf", true)

val Book.isMobi: Boolean
    get() = isLocal && (originName.endsWith(".mobi", true) ||
            originName.endsWith(".azw3", true) ||
            originName.endsWith(".azw", true))

val Book.isOnLineTxt: Boolean
    get() = !isLocal && isType(BookType.text)

val Book.isWebFile: Boolean
    get() = isType(BookType.webFile)

val Book.isUpError: Boolean
    get() = isType(BookType.updateError)

val Book.isArchive: Boolean
    get() = isType(BookType.archive)

val Book.isNotShelf: Boolean
    get() = isType(BookType.notShelf)

val Book.archiveName: String
    get() {
        if (!isArchive) throw NoStackTraceException("Book is not deCompressed from archive")
        // local_book::archive.rar
        // webDav::https://...../archive.rar
        return origin.substringAfter("::").substringAfterLast("/")
    }

fun Book.contains(word: String?): Boolean {
    if (word.isNullOrEmpty()) {
        return true
    }
    return name.contains(word)
            || author.contains(word)
            || originName.contains(word)
            || origin.contains(word)
            || kind?.contains(word) == true
            || intro?.contains(word) == true
}

fun Book.getRemoteUrl(): String? {
    if (origin.startsWith(BookType.webDavTag)) {
        return origin.substring(BookType.webDavTag.length)
    }
    return null
}

fun Book.setType(@BookType.Type vararg types: Int) {
    type = 0
    addType(*types)
}

fun Book.addType(@BookType.Type vararg types: Int) {
    types.forEach {
        type = type or it
    }
}

fun Book.removeType(@BookType.Type vararg types: Int) {
    types.forEach {
        type = type and it.inv()
    }
}

fun Book.removeAllBookType() {
    removeType(BookType.allBookType)
}

fun Book.clearType() {
    type = 0
}

fun Book.isType(@BookType.Type bookType: Int): Boolean = type and bookType > 0

fun Book.upType() {
    if (type < 8) {
        type = when (type) {
            BookSourceType.image -> BookType.image
            BookSourceType.audio -> BookType.audio
            BookSourceType.file -> BookType.webFile
            else -> BookType.text
        }
        if (origin == BookType.localTag || origin.startsWith(BookType.webDavTag)) {
            type = type or BookType.local
        }
    }
}

fun Book.primaryStr(): String {
    return origin + bookUrl
}

fun Book.releaseHtmlData() {
    infoHtml = null
    tocHtml = null
}

fun Book.isSameNameAuthor(other: Any?): Boolean {
    if (other is BaseBook) {
        return name == other.name && author == other.author
    }
    return false
}

fun Book.getFolderNameNoCache(): String {
    return name.replace(AppPattern.fileNameRegex, "").let {
        it.substring(0, min(9, it.length)) + MD5Utils.md5Encode16(bookUrl)
    }
}

// 根据当前日期计算章节总数
fun Book.simulatedTotalChapterNum(): Int {
    return if (readSimulating()) {
        val currentDate = LocalDate.now()
        // 起始日期可能为空(旧数据/导入备份)，缺失时按今天处理，避免 Period.between 空指针
        val startDate = config.startDate ?: currentDate
        val daysPassed = between(startDate, currentDate).days + 1
        // 计算当前应该解锁到哪一章
        val chaptersToUnlock =
            max(0, (config.startChapter ?: 0) + (daysPassed * config.dailyChapters))
        min(totalChapterNum, chaptersToUnlock)
    } else {
        totalChapterNum
    }
}

fun Book.readSimulating(): Boolean {
    return config.readSimulating
}

/** 阅读进度 0..1;null=未读(从未打开)。单章书(总章<=1)已读即 1f */
fun Book.readProgress(): Float? {
    if (durChapterIndex == 0 && durChapterPos == 0) return null
    if (totalChapterNum <= 1) return 1f
    return (durChapterIndex.toFloat() / (totalChapterNum - 1)).coerceIn(0f, 1f)
}
