package io.legado.app.model.jsSource

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 哨兵:目录解析成功后的 book 字段回写(totalChapterNum/latestChapterTitle/durChapterTitle/
 * lastCheckCount/lastCheckTime/wordCount)由 BookChapterList.updateBookTocInfo 单点承载,
 * 声明式与 JS 源共用。
 * 目录页按 `index <= simulatedTotalChapterNum()-1` 截取章节,该字段由目录解析回写供给。
 * 单测工作目录 = app 模块根。
 */
class JsSourceTocWriteBackSentinelTest {

    private val jsSourceBook =
        File("src/main/java/io/legado/app/model/jsSource/JsSourceBook.kt").readText()
    private val bookChapterList =
        File("../shared/src/commonMain/kotlin/io/legado/app/model/webBook/BookChapterList.kt").readText()

    @Test
    fun jsTocPathSharesDeclarativeWriteBack() {
        assertTrue(
            "JsSourceBook.getChapterListAwait 必须调用 BookChapterList.updateBookTocInfo",
            jsSourceBook.contains("BookChapterList.updateBookTocInfo(")
        )
    }

    @Test
    fun writeBackIsSingleSourced() {
        assertTrue(
            "BookChapterList 必须提供共享回写 updateBookTocInfo",
            bookChapterList.contains("suspend fun updateBookTocInfo(")
        )
        assertTrue(
            "totalChapterNum 回写在共享助手内唯一出现",
            bookChapterList.indexOf("book.totalChapterNum = list.size") ==
                bookChapterList.lastIndexOf("book.totalChapterNum = list.size")
        )
    }
}
