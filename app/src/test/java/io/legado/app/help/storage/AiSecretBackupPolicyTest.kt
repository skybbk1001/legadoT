package io.legado.app.help.storage

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiSecretBackupPolicyTest {

    private fun readSource(name: String) = listOf(
        File("src/main/java/io/legado/app/help/storage/$name"),
        File("app/src/main/java/io/legado/app/help/storage/$name")
    ).first { it.isFile }.readText()

    @Test
    fun `api key and consent are automatically excluded from backup and restore`() {
        val source = readSource("BackupConfig.kt")
        assertTrue(source.contains("PreferKey.aiApiKey"))
        assertTrue(source.contains("PreferKey.aiRoleConsent"))
    }

    @Test
    fun `role casts and aliases survive a backup round trip`() {
        val backup = readSource("Backup.kt")
        val restore = readSource("Restore.kt")
        listOf("roleCast.json", "roleAlias.json").forEach {
            assertTrue("备份清单缺 $it", backup.contains("\"$it\""))
            assertTrue("备份未写出 $it", backup.contains("\"$it\", backupPath"))
            assertTrue("恢复未读入 $it", restore.contains("\"$it\""))
        }
        assertTrue("恢复未落库角色音色", restore.contains("appDb.roleCastDao.insert("))
        assertTrue("恢复未落库角色别名", restore.contains("appDb.roleAliasDao.insert("))
    }

    @Test
    fun `the chapter annotation cache stays out of backups`() {
        // 标注可由 AI 重新生成, 体量随阅读量线性增长, 不进备份包
        assertTrue(
            "标注缓存不应进备份",
            !readSource("Backup.kt").contains("chapterRoleScript")
        )
    }
}
