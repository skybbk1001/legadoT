package io.legado.app.lib.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppTabLayoutWiringTest {

    @Test
    fun `surface dialog tabs use an app semantic style`() {
        val layout = readProjectFile("src/main/res/layout/dialog_dict.xml")
        assertTrue(layout.contains("style=\"@style/Widget.App.TabLayout.Surface\""))
    }

    @Test
    fun `surface tab tint is owned by the skin factory`() {
        val factory = readProjectFile("src/main/java/io/legado/app/lib/skin/SkinInflaterFactory.kt")
        assertTrue(factory.contains("applyTabStyleTint(view, attrs)"))
        assertTrue(factory.contains("Widget_App_TabLayout_Surface"))
    }

    @Test
    fun `ambient tabs keep page-owned foreground decisions`() {
        listOf(
            "src/main/java/io/legado/app/ui/book/toc/TocActivity.kt",
            "src/main/java/io/legado/app/ui/main/bookshelf/style1/BookshelfFragment1.kt",
            "src/main/java/io/legado/app/ui/book/source/edit/BookSourceEditActivity.kt",
            "src/main/java/io/legado/app/ui/rss/article/RssSortActivity.kt",
            "src/main/java/io/legado/app/ui/rss/favorites/RssFavoritesActivity.kt",
            "src/main/java/io/legado/app/ui/rss/source/edit/RssSourceEditActivity.kt",
        ).forEach { path ->
            val source = readProjectFile(path)
            assertTrue("$path 必须保留页面背景判断", source.contains("tabTextColors"))
        }
        assertFalse(
            "DictDialog 不应继续重复施色",
            readProjectFile("src/main/java/io/legado/app/ui/dict/DictDialog.kt")
                .contains("setTabTextColors")
        )
    }

    private fun readProjectFile(pathInApp: String): String {
        return listOf(File(pathInApp), File("app/$pathInApp")).first { it.isFile }.readText()
    }
}
