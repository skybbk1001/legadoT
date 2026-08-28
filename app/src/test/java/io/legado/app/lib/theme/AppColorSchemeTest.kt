package io.legado.app.lib.theme

import com.google.android.material.color.utilities.Hct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

@Suppress("RestrictedApi")
class AppColorSchemeTest {

    private val daySeed = 0xFF3D7EFF.toInt()      // md_theme_day_accent
    private val dayBg = 0xFFFAFAFA.toInt()        // md_theme_day_background
    private val nightSeed = 0xFF5E9BFF.toInt()    // md_theme_night_accent
    private val nightBg = 0xFF121212.toInt()      // md_theme_night_background

    private fun tone(color: Int) = Hct.fromInt(color).tone

    @Test
    fun `light scheme keeps seed hue and anchors surface to user background`() {
        val s = AppColorScheme.buildScheme(daySeed, isDark = false, surfaceAnchor = dayBg, isEInk = false)
        val hueDiff = abs(Hct.fromInt(daySeed).hue - Hct.fromInt(s.primary).hue)
        assertTrue("primary 应保持种子色相", hueDiff < 15.0 || hueDiff > 345.0)
        assertEquals(dayBg, s.surface)
        assertEquals(dayBg, s.background)
    }

    @Test
    fun `light surface containers step darker except lowest`() {
        val s = AppColorScheme.buildScheme(daySeed, false, dayBg, false)
        assertTrue(tone(s.surfaceContainerLowest) > tone(s.surface))
        assertTrue(tone(s.surfaceContainerLow) < tone(s.surface))
        assertTrue(tone(s.surfaceContainer) < tone(s.surfaceContainerLow))
        assertTrue(tone(s.surfaceContainerHigh) < tone(s.surfaceContainer))
        assertTrue(tone(s.surfaceContainerHighest) < tone(s.surfaceContainerHigh))
    }

    @Test
    fun `dark surface containers step lighter except lowest`() {
        val s = AppColorScheme.buildScheme(nightSeed, true, nightBg, false)
        assertEquals(nightBg, s.surface)
        assertTrue(tone(s.surfaceContainerLowest) < tone(s.surface))
        assertTrue(tone(s.surfaceContainerLow) > tone(s.surface))
        assertTrue(tone(s.surfaceContainer) > tone(s.surfaceContainerLow))
        assertTrue(tone(s.surfaceContainerHigh) > tone(s.surfaceContainer))
        assertTrue(tone(s.surfaceContainerHighest) > tone(s.surfaceContainerHigh))
    }

    @Test
    fun `surface container tone deltas match spec`() {
        // 逐档断言 tone 位移量(浅 +2/-2/-4/-6/-8,深 -2/+4/+6/+11/+16;surfaceVariant 浅-8/深+24),
        // 期望值按 buildScheme 同款 0..100 clamp 计算,容差 1.0 覆盖 Hct 往返精度
        fun assertDelta(name: String, base: Int, actual: Int, delta: Double) {
            val expected = (tone(base) + delta).coerceIn(0.0, 100.0)
            assertTrue(
                "$name tone 应为 $expected±1,实际 ${tone(actual)}",
                abs(tone(actual) - expected) <= 1.0
            )
        }

        val l = AppColorScheme.buildScheme(daySeed, false, dayBg, false)
        assertDelta("light lowest", dayBg, l.surfaceContainerLowest, 2.0)
        assertDelta("light low", dayBg, l.surfaceContainerLow, -2.0)
        assertDelta("light container", dayBg, l.surfaceContainer, -4.0)
        assertDelta("light high", dayBg, l.surfaceContainerHigh, -6.0)
        assertDelta("light highest", dayBg, l.surfaceContainerHighest, -8.0)
        assertDelta("light surfaceVariant", dayBg, l.surfaceVariant, -8.0)

        val d = AppColorScheme.buildScheme(nightSeed, true, nightBg, false)
        assertDelta("dark lowest", nightBg, d.surfaceContainerLowest, -2.0)
        assertDelta("dark low", nightBg, d.surfaceContainerLow, 4.0)
        assertDelta("dark container", nightBg, d.surfaceContainer, 6.0)
        assertDelta("dark high", nightBg, d.surfaceContainerHigh, 11.0)
        assertDelta("dark highest", nightBg, d.surfaceContainerHighest, 16.0)
        assertDelta("dark surfaceVariant", nightBg, d.surfaceVariant, 24.0)
    }

    @Test
    fun `on colors contrast their containers`() {
        val light = AppColorScheme.buildScheme(daySeed, false, dayBg, false)
        assertTrue("浅色 onSurface 应为深色", tone(light.onSurface) < 20.0)
        assertTrue("onPrimary 应与 primary 拉开明度", abs(tone(light.onPrimary) - tone(light.primary)) > 25.0)
        val dark = AppColorScheme.buildScheme(nightSeed, true, nightBg, false)
        assertTrue("深色 onSurface 应为浅色", tone(dark.onSurface) > 80.0)
    }

    @Test
    fun `fidelity primary passes user accent through untouched`() {
        // 中明度种子(tone≈51):派生路会拉到 40,保真路必须原值直出;其余角色仍走派生
        val seed = 0xFFE91E63.toInt()
        val fidelity = AppColorScheme.buildScheme(seed, false, dayBg, false, fidelityPrimary = true)
        val derived = AppColorScheme.buildScheme(seed, false, dayBg, false)
        assertEquals("primary 应为用户原值", seed, fidelity.primary)
        assertTrue("派生路 primary 应仍被 tone 修正", derived.primary != seed)
        assertEquals("容器保真色不受直出影响", derived.primaryContainer, fidelity.primaryContainer)
        assertEquals("辅助色仍走派生", derived.secondary, fidelity.secondary)
    }

    @Test
    fun `fidelity onPrimary flips black or white by seed tone`() {
        val lightSeed = 0xFFFFEB3B.toInt() // 亮黄 tone≈90 → 黑字
        val darkSeed = 0xFF1A237E.toInt()  // 深靛 tone≈16 → 白字
        val onLight = AppColorScheme
            .buildScheme(lightSeed, false, dayBg, false, fidelityPrimary = true).onPrimary
        val onDark = AppColorScheme
            .buildScheme(darkSeed, false, dayBg, false, fidelityPrimary = true).onPrimary
        assertEquals(0xFF000000.toInt(), onLight)
        assertEquals(0xFFFFFFFF.toInt(), onDark)
    }

    @Test
    fun `fidelity does not leak into eink or ambient`() {
        val eink = AppColorScheme.buildScheme(daySeed, false, dayBg, true, fidelityPrimary = true)
        assertEquals("eink 黑白覆盖优先于保真", 0xFF000000.toInt(), eink.primary)
        // ambient 保持派生(氛围场景视觉按派生色终审):同种子 primary 仍是派生值而非原值直出
        val seed = 0xFFE91E63.toInt()
        val ambient = AppColorScheme.ambientScheme(seed, isDark = false, isEInk = false)
        assertTrue("ambient primary 应仍走派生而非直出", ambient.primary != seed)
    }

    @Test
    fun `eink scheme is pure black and white`() {
        val s = AppColorScheme.buildScheme(daySeed, false, dayBg, true)
        assertEquals(0xFF000000.toInt(), s.primary)
        assertEquals(0xFFFFFFFF.toInt(), s.onPrimary)
        assertEquals(0xFFFFFFFF.toInt(), s.surface)
        assertEquals(0xFF000000.toInt(), s.onSurface)
        assertEquals(0xFF000000.toInt(), s.outline)
    }

    @Test
    fun `ambient scheme derives from arbitrary seed without global anchor`() {
        val red = AppColorScheme.ambientScheme(0xFFB3261E.toInt(), isDark = false, isEInk = false)
        val blue = AppColorScheme.ambientScheme(0xFF3D7EFF.toInt(), isDark = false, isEInk = false)
        val hueGap = abs(Hct.fromInt(red.primary).hue - Hct.fromInt(blue.primary).hue)
        assertTrue("不同种子应派生不同色相", hueGap > 30.0 && hueGap < 330.0)
        val dark = AppColorScheme.ambientScheme(0xFF3D7EFF.toInt(), isDark = true, isEInk = false)
        assertTrue("深色氛围面应是深色", tone(dark.surface) < 30.0)
    }

    @Test
    fun `ambient scheme falls back to eink table`() {
        val s = AppColorScheme.ambientScheme(0xFFB3261E.toInt(), isDark = false, isEInk = true)
        assertEquals(0xFF000000.toInt(), s.primary)
    }

    @Test
    fun `surface bright and dim follow m3 spec tones`() {
        val l = AppColorScheme.buildScheme(daySeed, false, dayBg, false)
        assertTrue("浅色 surfaceBright 应为 N98", abs(tone(l.surfaceBright) - 98.0) <= 1.0)
        assertTrue("浅色 surfaceDim 应为 N87", abs(tone(l.surfaceDim) - 87.0) <= 1.0)
        val d = AppColorScheme.buildScheme(nightSeed, true, nightBg, false)
        assertTrue("深色 surfaceBright 应为 N24", abs(tone(d.surfaceBright) - 24.0) <= 1.0)
        assertTrue("深色 surfaceDim 应为 N6", abs(tone(d.surfaceDim) - 6.0) <= 1.0)
    }

    @Test
    fun `generate default palette xml into build dir`() {
        fun AppSchemeColors.toXml(): String = buildString {
            appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
            appendLine("<resources>")
            appendLine("    <!-- 由 AppColorSchemeTest 生成:默认种子派生的完整 M3 色板,与运行时 AppColorScheme 同源。改默认种子后重新生成。 -->")
            fun line(name: String, color: Int) =
                appendLine("""    <color name="m3_$name">#${"%08X".format(color)}</color>""")
            line("primary", primary); line("on_primary", onPrimary)
            line("primary_container", primaryContainer); line("on_primary_container", onPrimaryContainer)
            line("secondary", secondary); line("on_secondary", onSecondary)
            line("secondary_container", secondaryContainer); line("on_secondary_container", onSecondaryContainer)
            line("tertiary", tertiary); line("on_tertiary", onTertiary)
            line("tertiary_container", tertiaryContainer); line("on_tertiary_container", onTertiaryContainer)
            line("error", error); line("on_error", onError)
            line("error_container", errorContainer); line("on_error_container", onErrorContainer)
            line("outline", outline); line("outline_variant", outlineVariant)
            line("inverse_primary", inversePrimary)
            line("inverse_surface", inverseSurface); line("inverse_on_surface", inverseOnSurface)
            line("background", background); line("on_background", onBackground)
            line("surface", surface); line("on_surface", onSurface)
            line("surface_variant", surfaceVariant); line("on_surface_variant", onSurfaceVariant)
            line("surface_container_lowest", surfaceContainerLowest)
            line("surface_container_low", surfaceContainerLow)
            line("surface_container", surfaceContainer)
            line("surface_container_high", surfaceContainerHigh)
            line("surface_container_highest", surfaceContainerHighest)
            line("surface_bright", surfaceBright)
            line("surface_dim", surfaceDim)
            appendLine("</resources>")
        }
        java.io.File("build/m3_palette_day.xml")
            .writeText(AppColorScheme.buildScheme(daySeed, false, dayBg, false).toXml())
        java.io.File("build/m3_palette_night.xml")
            .writeText(AppColorScheme.buildScheme(nightSeed, true, nightBg, false).toXml())
        val dayXml = java.io.File("build/m3_palette_day.xml").readText()
        assertTrue(dayXml.contains("<color name=\"m3_primary\">"))
        assertEquals(34, Regex("<color name=\"m3_").findAll(dayXml).count())
        val nightXml = java.io.File("build/m3_palette_night.xml").readText()
        assertTrue(nightXml.contains("<color name=\"m3_primary\">"))
        assertEquals(34, Regex("<color name=\"m3_").findAll(nightXml).count())
    }
}
