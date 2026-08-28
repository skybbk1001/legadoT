package io.legado.app.lib.theme

import android.content.res.Resources
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import io.legado.app.R
import io.legado.app.lib.skin.SkinInflaterFactory
import io.legado.app.lib.theme.arsc.ColorResourcesTableCreator
import java.io.FileOutputStream

/**
 * Installs the app palette before AppCompat inflates any views.
 *
 * 路线 B:自持 ARSC 表生成器 + 公共 API [ResourcesLoader],运行时覆盖 app 自身的 @color token,
 * 不再使用 material 的 ColorResourcesOverride——其 applyIfPossible 会强制向 activity theme 叠加
 * ThemeOverlay.Material3.PersonalizedColors,把 textColorPrimary/colorOnSurface 等改指库内静态
 * #FFFFFF 的占位色(material_personalized_color_*),必须额外维护一份库内部 id 表才能自愈。
 * 自持路径无任何隐藏副作用,也不依赖库内部资源 id。
 *
 * 主题侧契约:Base.AppTheme 的每个颜色 item 都指向本表覆盖的 app token(文字色 attr 亦显式接管,
 * 不再继承 Theme.Material3 指向库内 selector 的默认值),由 ThemeWiringContractTest 锁定;
 * 表生成器本身是纯 JVM 函数,由 ColorResourcesTableCreatorTest 做字节往返验证。
 */
object AppThemeInstaller {

    private const val TAG = "AppThemeInstaller"

    fun install(activity: AppCompatActivity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val table = colorResourcesTable(
                packageName = activity.packageName,
                entryNames = activity.resources::getResourceEntryName,
                scheme = AppColorScheme.current,
            )
            if (!installTable(activity.resources, table)) {
                Log.w(TAG, "运行时色板注入失败,attr 回落 XML 静态色板")
            }
        }
        // API 26-29 无运行时颜色资源覆盖能力:attr 回落 XML 预生成色板,skin_* 角色仍由引擎逐视图施色。
        SkinInflaterFactory.install(activity)
    }

    internal fun colorResourcesTable(
        packageName: String,
        entryNames: (Int) -> String,
        scheme: AppSchemeColors,
    ): ByteArray = ColorResourcesTableCreator.create(packageName, colorResourceOverrides(scheme), entryNames)

    /**
     * 运行时覆盖表 = app 自身 @color token 全集。
     * Base.AppTheme 的 attr、布局/样式里的直引(如卡片描边 m3_outline_variant)、
     * 代码 getColor(R.color.*) 都经此表解析到当前色板,语义与 skin_* 角色同源。
     * primaryText/secondaryText 刻意不覆盖:118+ 布局直引、背景上下文多样,
     * 维持 colors.xml 按日夜模式的静态值以保证对比度契约。
     */
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
        R.color.m3_surface_bright to scheme.surfaceBright,
        R.color.m3_surface_dim to scheme.surfaceDim,
        // Legacy aliases still used by the base theme, layouts and Widget.App.TabLayout.
        R.color.accent to scheme.primary,
        R.color.background to scheme.background,
        R.color.background_card to scheme.surfaceContainerLow,
        R.color.error to scheme.error,
    )

    /**
     * 以公共 API 把 ARSC 覆盖表挂到 [Resources]:memfd 内存文件承载表字节,
     * ResourcesProvider.loadFromTable 解析 + Resources.addLoaders 生效(仅 API 30+)。
     * 与 material 的 ColorResourcesLoaderCreator 同构,但无任何库内部依赖。
     */
    private fun installTable(resources: Resources, table: ByteArray): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            val fd = Os.memfd_create("legado_palette", 0)
            FileOutputStream(fd).use { it.write(table) }
            val pfd = ParcelFileDescriptor.dup(fd)
            Os.close(fd)
            val loader = ResourcesLoader()
            loader.addProvider(ResourcesProvider.loadFromTable(pfd, null))
            pfd.close()
            resources.addLoaders(loader)
            true
        } catch (e: Exception) {
            Log.w(TAG, "installTable 失败", e)
            false
        }
    }
}
