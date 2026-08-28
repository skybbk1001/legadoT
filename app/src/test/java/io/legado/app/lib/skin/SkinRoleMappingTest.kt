package io.legado.app.lib.skin

import io.legado.app.lib.theme.AppSchemeColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R2 换肤引擎三方一致性锚点:
 * [SkinRole] 枚举(ordinal) ↔ [AppSchemeColors] 构造参数(位置) ↔ attrs_skin.xml enum(value)
 * 三者必须同名同序。任何一方增删/重排角色,本测试失败并指出错位处。
 */
class SkinRoleMappingTest {

    /**
     * 用 0..31 依位置构造色板,则前 32 个 scheme 角色的 resolve 返回值必须等于其 ordinal。
     * 同时锁死两件事:resolve 的 when 分支没有串线;枚举序与数据类字段序一致。
     * surfaceBright/surfaceDim 追加在尾部:仅作 attr token,不进入 SkinRole 枚举。
     * app 四色角色(themePrimary 起)走 ThemeStore/appCtx,JVM 单测不触碰。
     */
    @Test
    fun `resolve maps every scheme role to the same-position scheme field`() {
        val scheme = AppSchemeColors(
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
            16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31,
            32, 33,
        )
        SkinRole.entries.take(32).forEachIndexed { ordinal, role ->
            assertEquals("SkinRole.$role 与 AppSchemeColors 字段错位", ordinal, role.resolve(scheme))
        }
        assertEquals(34, SkinRole.entries.size)
        assertEquals(SkinRole.themePrimary, SkinRole.entries[32])
        assertEquals(SkinRole.themeBottomBackground, SkinRole.entries[33])
    }

    /** attrs_skin.xml 的 4 个 skin_* attr:各含全部角色,enum name=角色名,value=ordinal */
    @Test
    fun `attrs xml enums match SkinRole names and ordinals`() {
        val xml = readProjectFile("src/main/res/values/attrs_skin.xml")
        val expected = SkinRole.entries.map { it.name to it.ordinal }
        val attrNames = listOf("skin_background", "skin_textColor", "skin_tint", "skin_hintColor")
        val attrBlockRegex = Regex(
            """<attr name="(skin_\w+)" format="enum">(.*?)</attr>""",
            RegexOption.DOT_MATCHES_ALL
        )
        val enumRegex = Regex("""<enum name="(\w+)" value="(\d+)" />""")

        val blocks = attrBlockRegex.findAll(xml).associate { m ->
            m.groupValues[1] to enumRegex.findAll(m.groupValues[2])
                .map { it.groupValues[1] to it.groupValues[2].toInt() }.toList()
        }
        assertEquals("attrs_skin.xml 的 attr 清单不符", attrNames.toSet(), blocks.keys)
        attrNames.forEach { attr ->
            assertEquals("$attr 的角色枚举与 SkinRole 不一致", expected, blocks.getValue(attr))
        }
        // styleable 引用齐全(SkinInflaterFactory 经 R.styleable.SkinView 读取)
        attrNames.forEach { attr ->
            assertTrue(
                "styleable SkinView 缺 $attr",
                xml.contains("""<declare-styleable name="SkinView">""") &&
                    Regex("""<declare-styleable name="SkinView">.*?<attr name="$attr" />.*?</declare-styleable>""",
                        RegexOption.DOT_MATCHES_ALL).containsMatchIn(xml)
            )
        }
    }

    @Test
    fun `fromAttrValue guards invalid values`() {
        assertEquals(SkinRole.primary, SkinRole.fromAttrValue(0))
        assertEquals(SkinRole.themeBottomBackground, SkinRole.fromAttrValue(33))
        assertNull(SkinRole.fromAttrValue(-1))
        assertNull(SkinRole.fromAttrValue(34))
    }

    private fun readProjectFile(pathInApp: String): String {
        val candidates = listOf(File(pathInApp), File("app/$pathInApp"))
        return candidates.first { it.isFile }.readText()
    }
}
