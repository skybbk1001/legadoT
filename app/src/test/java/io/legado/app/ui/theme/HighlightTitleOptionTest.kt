package io.legado.app.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 高亮规则「应用于标题」结构哨兵 */
class HighlightTitleOptionTest {
    @Test
    fun `highlight rule has applyToTitle column and db migrated`() {
        val entity = File("src/main/java/io/legado/app/data/entities/HighlightRule.kt").readText()
        assertTrue("HighlightRule 应有 applyToTitle 列", entity.contains("var applyToTitle"))
        val db = File("src/main/java/io/legado/app/data/AppDatabase.kt").readText()
        assertTrue("DB 版本应升到 93", db.contains("version = 93"))
        assertTrue("应有 85→86 AutoMigration", db.contains("AutoMigration(from = 85, to = 86)"))
        assertTrue("应有 86→87 AutoMigration", db.contains("AutoMigration(from = 86, to = 87)"))
        assertTrue("应有 87→88 AutoMigration", db.contains("AutoMigration(from = 87, to = 88)"))
        assertTrue("应有 91→92 AutoMigration", db.contains("AutoMigration(from = 91, to = 92)"))
        assertTrue("应有 92→93 AutoMigration", db.contains("AutoMigration(from = 92, to = 93"))
    }

    @Test
    fun `edit dialog has apply to title checkbox wired`() {
        val layout = File("src/main/res/layout/dialog_highlight_rule_edit.xml").readText()
        assertTrue("布局应有 cb_apply_to_title", layout.contains("@+id/cb_apply_to_title"))
        val dialog = File("src/main/java/io/legado/app/ui/highlight/edit/HighlightRuleEditDialog.kt").readText()
        assertTrue("upView 应回填 applyToTitle", dialog.contains("cbApplyToTitle.isChecked = r.applyToTitle"))
        assertTrue("getRule 应读 applyToTitle", dialog.contains("r.applyToTitle = cbApplyToTitle.isChecked"))
    }
}
