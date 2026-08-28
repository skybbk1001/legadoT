package io.legado.app.lib.theme

import io.legado.app.R
import io.legado.app.lib.theme.arsc.ArscTableReader
import org.junit.Assert.assertEquals
import org.junit.Test

class AppThemeInstallerTest {

    private val scheme = AppColorScheme.buildScheme(
        seed = 0xFF3D7EFF.toInt(),
        isDark = false,
        surfaceAnchor = 0xFFFAFAFA.toInt(),
        isEInk = false,
        fidelityPrimary = true,
    )

    @Test
    fun `runtime table covers the complete app token set`() {
        val colors = AppThemeInstaller.colorResourceOverrides(scheme)

        assertEquals(scheme.primary, colors[R.color.m3_primary])
        assertEquals(scheme.onPrimary, colors[R.color.m3_on_primary])
        assertEquals(scheme.secondaryContainer, colors[R.color.m3_secondary_container])
        assertEquals(scheme.onSecondaryContainer, colors[R.color.m3_on_secondary_container])
        assertEquals(scheme.error, colors[R.color.m3_error])
        assertEquals(scheme.outline, colors[R.color.m3_outline])
        assertEquals(scheme.surface, colors[R.color.m3_surface])
        assertEquals(scheme.onSurface, colors[R.color.m3_on_surface])
        assertEquals(scheme.surfaceContainerHighest, colors[R.color.m3_surface_container_highest])
        assertEquals(scheme.surfaceBright, colors[R.color.m3_surface_bright])
        assertEquals(scheme.surfaceDim, colors[R.color.m3_surface_dim])
        assertEquals(scheme.primary, colors[R.color.accent])
        assertEquals(scheme.background, colors[R.color.background])
        assertEquals(scheme.surfaceContainerLow, colors[R.color.background_card])
        assertEquals(scheme.error, colors[R.color.error])
        // primaryText and secondaryText are intentionally not overridden:
        // 118+ 布局直引、背景上下文多样,维持 colors.xml 静态值以保证对比度契约
        assertEquals(null, colors[R.color.primaryText])
        assertEquals(null, colors[R.color.secondaryText])
        // 34 m3 token + 4 legacy alias
        assertEquals(38, colors.size)
    }

    @Test
    fun `table bytes round trip with the real token ids`() {
        val colors = AppThemeInstaller.colorResourceOverrides(scheme)
        val bytes = AppThemeInstaller.colorResourcesTable(
            packageName = "io.legado.app",
            entryNames = { "token_${it and 0xFFFF}" },
            scheme = scheme,
        )

        val table = ArscTableReader(bytes).readTable()
        assertEquals(1, table.packages.size)
        val pkg = table.packages[0]
        assertEquals("io.legado.app", pkg.name)
        assertEquals(colors, pkg.entries)
        assertEquals(colors.size, pkg.keyNames.size)
    }
}
