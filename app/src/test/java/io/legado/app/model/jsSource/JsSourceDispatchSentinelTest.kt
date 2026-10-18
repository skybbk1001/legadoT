package io.legado.app.model.jsSource

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 哨兵:WebBook 五入口必须在声明式骨架之前分派 JS 源(spec §5)。
 * 单测工作目录 = app 模块根。
 */
class JsSourceDispatchSentinelTest {

    private val webBook =
        File("../shared/src/commonMain/kotlin/io/legado/app/model/webBook/WebBook.kt").readText()

    @Test
    fun fourAwaitEntriesDispatchToJsSourceBook() {
        //迁移 shared 后经 WebBookBridge.jsSource 代理分派(app 侧接线 JsSourceBook)
        listOf(
            "jsSource?.searchAwait",
            "jsSource?.exploreAwait",
            "jsSource?.getBookInfoAwait",
            "jsSource?.getChapterListAwait",
            "jsSource?.getContentAwait",
        ).forEach {
            assertTrue("WebBook 缺少分派: $it", webBook.contains(it))
        }
    }

    @Test
    fun searchDispatchPrecedesDeclarativeGuard() {
        val dispatch = webBook.indexOf("jsSource?.searchAwait")
        val declarativeGuard = webBook.indexOf("搜索url不能为空")
        assertTrue("JS 分派必须在 searchUrl 空判之前", dispatch in 1 until declarativeGuard)
    }
}
