package io.legado.app.lib.theme

import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.ColorResourcesOverride
import io.legado.app.R
import io.legado.app.lib.skin.SkinInflaterFactory
import io.legado.app.utils.ColorUtils

/** Installs the app palette before AppCompat inflates any views. */
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

    internal fun colorResourceOverrides(scheme: AppSchemeColors): Map<Int, Int> = mapOf(
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
        R.color.primaryText to ColorUtils.withAlpha(scheme.onBackground, 0.87f),
        R.color.secondaryText to ColorUtils.withAlpha(scheme.onBackground, 0.60f),
    )
}
