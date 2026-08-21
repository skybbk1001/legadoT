package io.legado.app.lib.theme

import io.legado.app.R
import org.junit.Assert.assertEquals
import org.junit.Test

class AppThemeInstallerTest {

    @Test
    fun `runtime resources contain the complete app color scheme`() {
        val scheme = AppColorScheme.buildScheme(
            seed = 0xFF3D7EFF.toInt(),
            isDark = false,
            surfaceAnchor = 0xFFFAFAFA.toInt(),
            isEInk = false,
            fidelityPrimary = true,
        )

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
        assertEquals(scheme.primary, colors[R.color.accent])
        assertEquals(scheme.background, colors[R.color.background])
    }
}
