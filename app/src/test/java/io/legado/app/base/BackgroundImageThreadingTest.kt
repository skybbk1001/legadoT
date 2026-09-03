package io.legado.app.base

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 背景图加载线程哨兵:解码/模糊回到主线程同步路径会阻塞首帧,
 * 拉长启动后的"纯背景空窗期"(#31 的根因)
 */
class BackgroundImageThreadingTest {

    @Test
    fun `background image decodes off the main thread`() {
        val source = File("src/main/java/io/legado/app/base/BaseActivity.kt").readText()
        val body = source.substringAfter("open fun upBackgroundImage")
        assertTrue(
            "upBackgroundImage 须在 Dispatchers.IO 上解码背景图",
            body.contains("Dispatchers.IO")
        )
        assertTrue(
            "异步挂图前须检查 activity 存活,避免泄漏的窗口写入",
            body.contains("isFinishing")
        )
    }
}
