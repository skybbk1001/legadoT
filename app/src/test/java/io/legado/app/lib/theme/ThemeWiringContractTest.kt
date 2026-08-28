package io.legado.app.lib.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 路线 B 的主题接线契约(源码级):
 * 1. Base.AppTheme 的每个 @color 引用必须能经运行时覆盖表解析(直接 token、或经 res/color selector
 *    递归引用 token、或显式静态白名单)——保证 30+ 上 attr 全部跟随用户色板,无回落库内静态值;
 * 2. 覆盖表的每个 token 必须被 res 或代码实际引用——防死条目,表与消费方同步演进。
 */
class ThemeWiringContractTest {

    /** 与 AppThemeInstaller.colorResourceOverrides 的键集合一一对应(刻意硬编码以双向锁定契约) */
    private val paletteTokens: Set<String> = setOf(
        "m3_primary", "m3_on_primary", "m3_primary_container", "m3_on_primary_container",
        "m3_secondary", "m3_on_secondary", "m3_secondary_container", "m3_on_secondary_container",
        "m3_tertiary", "m3_on_tertiary", "m3_tertiary_container", "m3_on_tertiary_container",
        "m3_error", "m3_on_error", "m3_error_container", "m3_on_error_container",
        "m3_outline", "m3_outline_variant",
        "m3_inverse_primary", "m3_inverse_surface", "m3_inverse_on_surface",
        "m3_background", "m3_on_background",
        "m3_surface", "m3_on_surface", "m3_surface_variant", "m3_on_surface_variant",
        "m3_surface_container_lowest", "m3_surface_container_low", "m3_surface_container",
        "m3_surface_container_high", "m3_surface_container_highest",
        "m3_surface_bright", "m3_surface_dim",
        "accent", "background", "background_card", "error",
    )

    /** 不参与运行时覆盖、按日夜模式静态提供的历史 token */
    private val staticTokens: Set<String> = setOf("primaryDark", "transparent")

    @Test
    fun `base theme color items all resolve through the runtime palette`() {
        val styles = readProjectFile("src/main/res/values/styles.xml")
        val block = Regex("""<style name="Base\.AppTheme".*?</style>""", RegexOption.DOT_MATCHES_ALL)
            .find(styles)?.value ?: error("未找到 Base.AppTheme 样式块")
        val refs = Regex("""@color/(\w+)""").findAll(block).map { it.groupValues[1] }.toSet()
        for (ref in refs) {
            assertTrue(
                "Base.AppTheme 引用 @color/$ref 无法解析到运行时色板",
                resolvesToPalette(ref, mutableSetOf()),
            )
        }
    }

    @Test
    fun `every runtime palette token is referenced by res or code`() {
        val used = mutableSetOf<String>()
        projectFile("src/main/res").walkTopDown()
            .filter { it.isFile && it.extension == "xml" }
            .forEach { file ->
                Regex("""@color/(\w+)""").findAll(file.readText()).forEach { used.add(it.groupValues[1]) }
            }
        projectFile("src/main/java").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .forEach { file ->
                Regex("""R\.color\.(\w+)""").findAll(file.readText()).forEach { used.add(it.groupValues[1]) }
            }
        val unused = paletteTokens - used
        assertTrue("未被任何 res/代码引用的运行时 token: $unused", unused.isEmpty())
    }

    private fun resolvesToPalette(name: String, visited: MutableSet<String>): Boolean {
        if (name in paletteTokens) return true
        if (name in staticTokens) return true
        if (!visited.add(name)) return false // 循环引用
        val selector = projectFile("src/main/res/color/$name.xml")
        if (selector.exists()) {
            val inner = Regex("""@color/(\w+)""").findAll(selector.readText())
                .map { it.groupValues[1] }.toList()
            if (inner.isNotEmpty()) {
                return inner.all { resolvesToPalette(it, visited) }
            }
        }
        return false
    }

    private fun readProjectFile(pathInApp: String): String = projectFile(pathInApp).readText()

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp")).first { it.exists() }
    }
}
