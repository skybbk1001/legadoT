package io.legado.app.lib.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppThemeWiringTest {

    @Test
    fun `activities install app theme before AppCompat creation`() {
        listOf(
            "src/main/java/io/legado/app/base/BaseActivity.kt",
            "src/main/java/io/legado/app/lib/permission/PermissionActivity.kt",
        ).forEach { path ->
            val source = readProjectFile(path)
            val install = source.indexOf("AppThemeInstaller.install(this)")
            val superCreate = source.indexOf("super.onCreate(savedInstanceState)")
            assertTrue("$path 未安装 AppThemeInstaller", install >= 0)
            assertTrue("$path 必须在 super.onCreate 前安装主题", install < superCreate)
        }
    }

    @Test
    fun `permission activity inherits the app resource palette`() {
        val styles = readProjectFile("src/main/res/values/styles.xml")
        assertTrue(styles.contains("""<style name="Activity.Permission" parent="Base.AppTheme">"""))
    }

    @Test
    fun `bare Material buttons are limited to explicit local color exceptions`() {
        val layoutRoot = projectFile("src/main/res/layout")
        val offenders = layoutRoot.walkTopDown()
            .filter { it.extension == "xml" }
            .filter { it.readText().contains("Widget.Material3.Button.") }
            .map { it.name }
            .toList()

        assertEquals(listOf("item_book_manga_page.xml"), offenders)
    }

    private fun readProjectFile(pathInApp: String): String = projectFile(pathInApp).readText()

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp")).first { it.exists() }
    }
}
