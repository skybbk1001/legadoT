package io.legado.app.lib.theme

import com.google.android.material.R as MaterialR
import io.legado.app.R
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
    fun `runtime resources contain the complete app color scheme`() {
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
        // primaryText and secondaryText are intentionally not overridden
        assertEquals(null, colors[R.color.primaryText])
        assertEquals(null, colors[R.color.secondaryText])
    }

    /**
     * applyIfPossible 成功后会给 activity theme 叠加 ThemeOverlay.Material3.PersonalizedColors,
     * 其引用的 material_personalized_color_* 占位色库内静态值全为 #FFFFFF——必须与 app 色板同表覆盖,
     * 否则亮色模式下 textColorPrimary/colorOnSurface 系文字(输入框正文、弹窗标题与列表项)渲染为白色。
     */
    @Test
    fun `runtime resources cover the personalized overlay placeholders`() {
        val colors = AppThemeInstaller.personalizedColorResourceOverrides(scheme)

        assertEquals(scheme.primary, colors[MaterialR.color.material_personalized_color_primary])
        assertEquals(scheme.onSurface, colors[MaterialR.color.material_personalized_color_on_surface])
        assertEquals(scheme.onBackground, colors[MaterialR.color.material_personalized_color_on_background])
        assertEquals(scheme.surface, colors[MaterialR.color.material_personalized_color_surface])
        assertEquals(
            scheme.onSurfaceVariant,
            colors[MaterialR.color.material_personalized_color_on_surface_variant],
        )
        assertEquals(
            scheme.surfaceContainerHighest,
            colors[MaterialR.color.material_personalized_color_surface_container_highest],
        )
        assertEquals(scheme.primary, colors[MaterialR.color.material_personalized_color_control_activated])
        assertEquals(
            scheme.onSurfaceVariant,
            colors[MaterialR.color.material_personalized_color_control_normal],
        )
        assertEquals(
            scheme.inverseOnSurface,
            colors[MaterialR.color.material_personalized_color_text_primary_inverse],
        )
        // 全部 42 个占位色都必须有覆盖值,任一缺失即回落到库内白色静态值
        assertEquals(42, colors.size)
    }
}
