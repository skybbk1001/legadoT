package io.legado.app.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DB 88 接线锚点。Room 迁移在 JVM 单测里跑不起来, 这里守住实体/DAO 的注册与版本号。
 */
class DatabaseV88WiringTest {

    private fun readSource(relative: String): String {
        val candidates = listOf(
            File("src/main/java/io/legado/app/$relative"),
            File("app/src/main/java/io/legado/app/$relative")
        )
        return candidates.first { it.isFile }.readText()
    }

    @Test
    fun `database is at version 88 with an auto migration from 87`() {
        val src = readSource("data/AppDatabase.kt")
        assertTrue("版本号未升到 92", src.contains("version = 92"))
        assertTrue("缺 87→88 自动迁移", src.contains("AutoMigration(from = 87, to = 88)"))
        assertTrue("缺 91→92 自动迁移", src.contains("AutoMigration(from = 91, to = 92)"))
    }

    @Test
    fun `new entities and daos are registered`() {
        val src = readSource("data/AppDatabase.kt")
        assertTrue("RoleCast 未注册进 entities", src.contains("RoleCast::class"))
        assertTrue("ChapterRoleScript 未注册进 entities", src.contains("ChapterRoleScript::class"))
        assertTrue("RoleAlias 未注册进 entities", src.contains("RoleAlias::class"))
        assertTrue("缺 roleCastDao", src.contains("abstract val roleCastDao: RoleCastDao"))
        assertTrue(
            "缺 chapterRoleScriptDao",
            src.contains("abstract val chapterRoleScriptDao: ChapterRoleScriptDao")
        )
        assertTrue("缺 roleAliasDao", src.contains("abstract val roleAliasDao: RoleAliasDao"))
    }

    @Test
    fun `httpTts carries a voices column that survives import`() {
        val src = readSource("data/entities/HttpTTS.kt")
        assertTrue("HttpTTS 缺 voices 字段", src.contains("var voices: String?"))
        // 读取形态由 HttpTtsVoicesImportTest 以真实导入覆盖, 这里只守住导入路径仍取 $.voices
        assertTrue("导入未读取 voices", src.contains("""("${'$'}.voices")"""))
    }
}
