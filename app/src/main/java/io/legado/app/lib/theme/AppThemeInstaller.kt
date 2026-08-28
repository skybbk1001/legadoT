package io.legado.app.lib.theme

import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.R as MaterialR
import com.google.android.material.color.ColorResourcesOverride
import com.google.android.material.color.utilities.Hct
import io.legado.app.R
import io.legado.app.lib.skin.SkinInflaterFactory
import io.legado.app.utils.ColorUtils

/**
 * Installs the app palette before AppCompat inflates any views.
 *
 * API 30+ 通路:ColorResourcesOverride 在覆盖本 app 的 m3_* 资源之外,成功后还会向 activity theme
 * 强制叠加 [com.google.android.material.R.style.ThemeOverlay_Material3_PersonalizedColors]
 * (applyIfPossible 内部行为),把 textColorPrimary/textColorSecondary/textColorAlertDialogListItem/
 * colorOnSurface 等全部改指 material 库内的 material_personalized_color_* 占位色——而这些占位色在库内
 * 的静态值全为 #FFFFFF。DynamicColors 官方路径正是以这批库内 id 为键下发运行时覆盖
 * (MaterialColorUtilitiesHelper 契约);只覆盖本 app 的 m3_* 而不覆盖它们,亮色模式下所有走
 * textColorPrimary/colorOnSurface 的文字(输入框正文、AlertDialog 标题与列表项等)都会渲染成白色。
 * 因此个性化色板必须与本 app 色板同表下发,取值对齐 AppColorScheme(中性面由用户背景锚定派生)。
 */
@Suppress("RestrictedApi")
object AppThemeInstaller {

    fun install(activity: AppCompatActivity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                ColorResourcesOverride.getInstance()
                    ?.applyIfPossible(activity, colorResourceOverrides(AppColorScheme.current))
            }
        }
        // API 26-29 need the inflater fallback. Newer versions keep it for explicit skin_* roles.
        SkinInflaterFactory.install(activity)
    }

    internal fun colorResourceOverrides(scheme: AppSchemeColors): Map<Int, Int> =
        appColorResourceOverrides(scheme) + personalizedColorResourceOverrides(scheme)

    internal fun appColorResourceOverrides(scheme: AppSchemeColors): Map<Int, Int> = mapOf(
        R.color.m3_primary to scheme.primary,
        R.color.m3_on_primary to scheme.onPrimary,
        R.color.m3_primary_container to scheme.primaryContainer,
        R.color.m3_on_primary_container to scheme.onPrimaryContainer,
        R.color.m3_secondary to scheme.secondary,
        R.color.m3_on_secondary to scheme.onSecondary,
        R.color.m3_secondary_container to scheme.secondaryContainer,
        R.color.m3_on_secondary_container to scheme.onSecondaryContainer,
        R.color.m3_tertiary to scheme.tertiary,
        R.color.m3_on_tertiary to scheme.onTertiary,
        R.color.m3_tertiary_container to scheme.tertiaryContainer,
        R.color.m3_on_tertiary_container to scheme.onTertiaryContainer,
        R.color.m3_error to scheme.error,
        R.color.m3_on_error to scheme.onError,
        R.color.m3_error_container to scheme.errorContainer,
        R.color.m3_on_error_container to scheme.onErrorContainer,
        R.color.m3_outline to scheme.outline,
        R.color.m3_outline_variant to scheme.outlineVariant,
        R.color.m3_inverse_primary to scheme.inversePrimary,
        R.color.m3_inverse_surface to scheme.inverseSurface,
        R.color.m3_inverse_on_surface to scheme.inverseOnSurface,
        R.color.m3_background to scheme.background,
        R.color.m3_on_background to scheme.onBackground,
        R.color.m3_surface to scheme.surface,
        R.color.m3_on_surface to scheme.onSurface,
        R.color.m3_surface_variant to scheme.surfaceVariant,
        R.color.m3_on_surface_variant to scheme.onSurfaceVariant,
        R.color.m3_surface_container_lowest to scheme.surfaceContainerLowest,
        R.color.m3_surface_container_low to scheme.surfaceContainerLow,
        R.color.m3_surface_container to scheme.surfaceContainer,
        R.color.m3_surface_container_high to scheme.surfaceContainerHigh,
        R.color.m3_surface_container_highest to scheme.surfaceContainerHighest,
        // Legacy aliases still used by the base theme and Widget.App.TabLayout.
        R.color.accent to scheme.primary,
        R.color.background to scheme.background,
        R.color.background_card to scheme.surfaceContainerLow,
        R.color.error to scheme.error,
        // Note: primaryText and secondaryText are NOT overridden here because:
        // 1. They are widely used in 118+ layout files with varying background contexts
        // 2. Static color references in XML must match their expected contrast ratio
        // 3. These legacy colors should remain as static values defined in colors.xml
    )

    /**
     * ThemeOverlay.Material3.PersonalizedColors 引用的 material 库个性化占位色(库内静态 #FFFFFF),
     * applyIfPossible 叠加该 overlay 后这些 id 若不覆盖会直接渲染成白色。
     * 键集与取值对齐 MaterialColorUtilitiesHelper(DynamicColors 的官方契约):
     * 42 个基础色 + 被 primary_text/secondary_text/hint_foreground 等 ColorStateList 引用的语义色。
     * 列表选择器本体(如 material_personalized_color_primary_text)不需入表——其 android:color 引用
     * 落在本表已覆盖的语义色上,运行时按覆盖值解析。
     */
    internal fun personalizedColorResourceOverrides(scheme: AppSchemeColors): Map<Int, Int> = mapOf(
        MaterialR.color.material_personalized_color_primary to scheme.primary,
        MaterialR.color.material_personalized_color_on_primary to scheme.onPrimary,
        MaterialR.color.material_personalized_color_primary_inverse to scheme.inversePrimary,
        MaterialR.color.material_personalized_color_primary_container to scheme.primaryContainer,
        MaterialR.color.material_personalized_color_on_primary_container to scheme.onPrimaryContainer,
        MaterialR.color.material_personalized_color_secondary to scheme.secondary,
        MaterialR.color.material_personalized_color_on_secondary to scheme.onSecondary,
        MaterialR.color.material_personalized_color_secondary_container to scheme.secondaryContainer,
        MaterialR.color.material_personalized_color_on_secondary_container to scheme.onSecondaryContainer,
        MaterialR.color.material_personalized_color_tertiary to scheme.tertiary,
        MaterialR.color.material_personalized_color_on_tertiary to scheme.onTertiary,
        MaterialR.color.material_personalized_color_tertiary_container to scheme.tertiaryContainer,
        MaterialR.color.material_personalized_color_on_tertiary_container to scheme.onTertiaryContainer,
        MaterialR.color.material_personalized_color_background to scheme.background,
        MaterialR.color.material_personalized_color_on_background to scheme.onBackground,
        MaterialR.color.material_personalized_color_surface to scheme.surface,
        MaterialR.color.material_personalized_color_on_surface to scheme.onSurface,
        MaterialR.color.material_personalized_color_surface_variant to scheme.surfaceVariant,
        MaterialR.color.material_personalized_color_on_surface_variant to scheme.onSurfaceVariant,
        MaterialR.color.material_personalized_color_surface_inverse to scheme.inverseSurface,
        MaterialR.color.material_personalized_color_on_surface_inverse to scheme.inverseOnSurface,
        MaterialR.color.material_personalized_color_surface_bright to scheme.surfaceBright,
        MaterialR.color.material_personalized_color_surface_dim to scheme.surfaceDim,
        MaterialR.color.material_personalized_color_surface_container to scheme.surfaceContainer,
        MaterialR.color.material_personalized_color_surface_container_low to scheme.surfaceContainerLow,
        MaterialR.color.material_personalized_color_surface_container_high to scheme.surfaceContainerHigh,
        MaterialR.color.material_personalized_color_surface_container_lowest to scheme.surfaceContainerLowest,
        MaterialR.color.material_personalized_color_surface_container_highest to scheme.surfaceContainerHighest,
        MaterialR.color.material_personalized_color_outline to scheme.outline,
        MaterialR.color.material_personalized_color_outline_variant to scheme.outlineVariant,
        MaterialR.color.material_personalized_color_error to scheme.error,
        MaterialR.color.material_personalized_color_on_error to scheme.onError,
        MaterialR.color.material_personalized_color_error_container to scheme.errorContainer,
        MaterialR.color.material_personalized_color_on_error_container to scheme.onErrorContainer,
        MaterialR.color.material_personalized_color_control_activated to scheme.primary,
        MaterialR.color.material_personalized_color_control_normal to scheme.onSurfaceVariant,
        MaterialR.color.material_personalized_color_control_highlight to
            scheme.surface.withTone(if (scheme.surfaceIsLight) 0.0 else 100.0),
        MaterialR.color.material_personalized_color_text_primary_inverse to scheme.inverseOnSurface,
        MaterialR.color.material_personalized_color_text_secondary_and_tertiary_inverse to
            scheme.inverseSurface.withTone(if (scheme.surfaceIsLight) 80.0 else 30.0),
        MaterialR.color.material_personalized_color_text_secondary_and_tertiary_inverse_disabled to
            ColorUtils.withAlpha(scheme.inverseOnSurface, 0.38f),
        MaterialR.color.material_personalized_color_text_primary_inverse_disable_only to
            ColorUtils.withAlpha(scheme.inverseOnSurface, 0.38f),
        MaterialR.color.material_personalized_color_text_hint_foreground_inverse to
            ColorUtils.withAlpha(scheme.inverseOnSurface, 0.38f),
    )

    /** M3 surfaceBright:浅色 N98 / 深色 N24(按 surface 明度判定模式,与 MaterialDynamicColors 对齐) */
    private val AppSchemeColors.surfaceBright: Int
        get() = surface.withTone(if (surfaceIsLight) 98.0 else 24.0)

    /** M3 surfaceDim:浅色 N87 / 深色 N6 */
    private val AppSchemeColors.surfaceDim: Int
        get() = surface.withTone(if (surfaceIsLight) 87.0 else 6.0)

    private val AppSchemeColors.surfaceIsLight: Boolean
        get() = Hct.fromInt(surface).tone >= 50

    private fun Int.withTone(tone: Double): Int {
        val hct = Hct.fromInt(this)
        return Hct.from(hct.hue, hct.chroma, tone).toInt()
    }
}
