package io.legado.app.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 哨兵:卷占位章(isVolume 且 url 以 title 起头)的正文恒为空串,声明式与 JS 源同款。
 * 正文为空时排版层(TextChapterLayout.setTypeText emptyContent 分支)才会把卷名垂直居中,
 * 返回 tag(updateTime 规则结果)会让卷页顶置卷名并把更新时间文本当正文渲染。
 * 单测工作目录 = app 模块根。
 */
class VolumePlaceholderContentSentinelTest {

    private val webBook =
        File("../shared/src/commonMain/kotlin/io/legado/app/model/webBook/WebBook.kt").readText()
    private val jsSourceBook =
        File("src/main/java/io/legado/app/model/jsSource/JsSourceBook.kt").readText()

    @Test
    fun declarativeVolumePlaceholderReturnsEmpty() {
        assertTrue(
            "WebBook.getContentAwait 必须保留卷占位章短路分支",
            webBook.contains("⇒一级目录正文不解析规则")
        )
        assertFalse(
            "卷占位章正文不得返回 tag(更新时间规则结果)",
            webBook.contains("return bookChapter.tag")
        )
    }

    @Test
    fun jsSourceVolumePlaceholderReturnsEmpty() {
        assertTrue(
            "JsSourceBook.getContentAwait 必须保留卷占位章短路分支",
            jsSourceBook.contains("⇒一级目录正文不解析")
        )
        assertFalse(
            "卷占位章正文不得返回 tag",
            jsSourceBook.contains("return bookChapter.tag")
        )
    }
}
