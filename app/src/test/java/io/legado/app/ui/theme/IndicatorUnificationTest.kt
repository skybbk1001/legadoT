package io.legado.app.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Wave 2 "三选一" 收敛哨兵：RotateLoading / RefreshProgressBar 外壳保名，内核换 M3 指示器。
 *
 * Task 6（RotateLoading→CircularProgressIndicator）与 Task 7（RefreshProgressBar→LinearProgressIndicator，
 * view_loading.xml→CircularProgressIndicator）对应的断言均在此，三个方法全绿即两个 task 收敛完成。
 */
class IndicatorUnificationTest {

    @Test
    fun `rotate loading is backed by m3 circular indicator`() {
        val src = File("src/main/java/io/legado/app/ui/widget/anima/RotateLoading.kt").readText()
        assertTrue(src.contains("com.google.android.material.progressindicator.CircularProgressIndicator"))
        assertTrue("hand-drawn arc must be gone", !src.contains("onDraw"))
    }

    @Test
    fun `refresh progress bar is backed by m3 linear indicator`() {
        val src = File("src/main/java/io/legado/app/ui/widget/anima/RefreshProgressBar.kt").readText()
        assertTrue(src.contains("LinearProgressIndicator"))
        assertTrue(!src.contains("onDraw"))
    }

    @Test
    fun `view_loading uses m3 circular indicator`() {
        val xml = File("src/main/res/layout/view_loading.xml").readText()
        assertTrue(xml.contains("com.google.android.material.progressindicator.CircularProgressIndicator"))
        assertTrue(!xml.contains("ContentLoadingProgressBar"))
    }
}
