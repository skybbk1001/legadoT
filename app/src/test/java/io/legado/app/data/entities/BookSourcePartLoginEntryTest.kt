package io.legado.app.data.entities

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookSourcePartLoginEntryTest {

    private val partSource =
        File("src/main/java/io/legado/app/data/entities/BookSourcePart.kt").readText()
    private val appDbSource =
        File("src/main/java/io/legado/app/data/AppDatabase.kt").readText()

    @Test
    fun hasLoginUrlCoversJsFormLogin() {
        assertTrue("hasLoginUrl 应含 JS 源表单登录分支(mainJs+loginUi)", partSource.contains("trim(loginUi)"))
        assertTrue("表单分支应以 mainJs 非空白为前提", partSource.contains("trim(mainJs)"))
    }

    @Test
    fun viewChangeShipsWithDb87() {
        assertTrue("DatabaseView 变更必升 version", appDbSource.contains("version = 93"))
        assertTrue("应声明 86→87 AutoMigration", appDbSource.contains("AutoMigration(from = 86, to = 87)"))
        assertTrue("应声明 87→88 AutoMigration", appDbSource.contains("AutoMigration(from = 87, to = 88)"))
        assertTrue("应声明 91→92 AutoMigration", appDbSource.contains("AutoMigration(from = 91, to = 92)"))
        assertTrue("应声明 92→93 AutoMigration", appDbSource.contains("AutoMigration(from = 92, to = 93"))
    }
}
