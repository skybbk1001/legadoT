package io.legado.app.lib.theme

import androidx.annotation.ColorInt
import com.google.android.material.color.utilities.Hct
import io.legado.app.help.config.AppConfig
import splitties.init.appCtx

/**
 * 全 app 单一取色源生成的完整 M3 色板。
 * 彩色角色(primary/secondary/tertiary/error/outline 族)来自种子色的 SchemeContent 派生;
 * 中性面(surface 族)按 M3 规范的 tone 差从用户背景色锚定派生,保证自定义背景仍成层次。
 */
data class AppSchemeColors(
    @param:ColorInt val primary: Int,
    @param:ColorInt val onPrimary: Int,
    @param:ColorInt val primaryContainer: Int,
    @param:ColorInt val onPrimaryContainer: Int,
    @param:ColorInt val secondary: Int,
    @param:ColorInt val onSecondary: Int,
    @param:ColorInt val secondaryContainer: Int,
    @param:ColorInt val onSecondaryContainer: Int,
    @param:ColorInt val tertiary: Int,
    @param:ColorInt val onTertiary: Int,
    @param:ColorInt val tertiaryContainer: Int,
    @param:ColorInt val onTertiaryContainer: Int,
    @param:ColorInt val error: Int,
    @param:ColorInt val onError: Int,
    @param:ColorInt val errorContainer: Int,
    @param:ColorInt val onErrorContainer: Int,
    @param:ColorInt val outline: Int,
    @param:ColorInt val outlineVariant: Int,
    @param:ColorInt val inversePrimary: Int,
    @param:ColorInt val inverseSurface: Int,
    @param:ColorInt val inverseOnSurface: Int,
    @param:ColorInt val background: Int,
    @param:ColorInt val onBackground: Int,
    @param:ColorInt val surface: Int,
    @param:ColorInt val onSurface: Int,
    @param:ColorInt val surfaceVariant: Int,
    @param:ColorInt val onSurfaceVariant: Int,
    @param:ColorInt val surfaceContainerLowest: Int,
    @param:ColorInt val surfaceContainerLow: Int,
    @param:ColorInt val surfaceContainer: Int,
    @param:ColorInt val surfaceContainerHigh: Int,
    @param:ColorInt val surfaceContainerHighest: Int,
    @param:ColorInt val surfaceBright: Int,
    @param:ColorInt val surfaceDim: Int,
)

@Suppress("RestrictedApi")
object AppColorScheme {

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    /** eink 容器灰:纯白底上仍可辨的浅灰(渲染为抖动),对应原 12% 黑 tonal 底 */
    private const val EINK_CONTAINER = 0xFFEBEBEB.toInt()

    private data class Key(
        val seed: Int, val isDark: Boolean, val anchor: Int, val isEInk: Boolean,
    )

    @Volatile
    private var cache: Pair<Key, AppSchemeColors>? = null

    /**
     * 跟随当前主题:种子=ThemeStore 强调色,锚=ThemeStore 背景色。
     * 键值变化(换肤/切日夜/切 eink)自动重建,无需手动失效。
     * 跟随系统切日夜的瞬间可能出现"新 isDark+旧种子"的过渡组合,
     * 随 applyDayNight 写入+RECREATE 自愈,勿加手动失效机制。
     *
     * primary 保真直出:强调色即 primary 原值,不经 tone 归一,可读性由选色本身决定;
     * 和谐派生仍承载于容器/辅助色。ambientScheme(封面/壁纸种子)不走保真。
     */
    val current: AppSchemeColors
        get() {
            val key = Key(
                seed = ThemeStore.accentColor(appCtx),
                isDark = AppConfig.isNightTheme,
                anchor = ThemeStore.backgroundColor(appCtx),
                isEInk = AppConfig.isEInkMode,
            )
            cache?.let { if (it.first == key) return it.second }
            return buildScheme(key.seed, key.isDark, key.anchor, key.isEInk, fidelityPrimary = true)
                .also { cache = key to it }
        }

    fun buildScheme(
        @ColorInt seed: Int,
        isDark: Boolean,
        @ColorInt surfaceAnchor: Int,
        isEInk: Boolean,
        fidelityPrimary: Boolean = false,
    ): AppSchemeColors {
        if (isEInk) return einkScheme()
        val m3 = M3ColorScheme.generate(seed, isDark)
        // 中性面锚定派生:tone 差取自 M3 规范(浅色 surface=98 各容器 100/96/94/92/90;深色 6→4/10/12/17/22)
        return AppSchemeColors(
            primary = if (fidelityPrimary) seed else m3.primary,
            onPrimary = if (fidelityPrimary) fidelityOnColor(seed) else m3.onPrimary,
            primaryContainer = m3.primaryContainer,
            onPrimaryContainer = m3.onPrimaryContainer,
            secondary = m3.secondary,
            onSecondary = m3.onSecondary,
            secondaryContainer = m3.secondaryContainer,
            onSecondaryContainer = m3.onSecondaryContainer,
            tertiary = m3.tertiary,
            onTertiary = m3.onTertiary,
            tertiaryContainer = m3.tertiaryContainer,
            onTertiaryContainer = m3.onTertiaryContainer,
            error = m3.error,
            onError = m3.onError,
            errorContainer = m3.errorContainer,
            onErrorContainer = m3.onErrorContainer,
            outline = m3.outline,
            outlineVariant = m3.outlineVariant,
            inversePrimary = m3.inversePrimary,
            inverseSurface = m3.inverseSurface,
            inverseOnSurface = m3.inverseOnSurface,
            background = surfaceAnchor,
            onBackground = surfaceAnchor.withTone(if (isDark) 90.0 else 10.0),
            surface = surfaceAnchor,
            onSurface = surfaceAnchor.withTone(if (isDark) 90.0 else 10.0),
            surfaceVariant = surfaceAnchor.shiftTone(if (isDark) 24.0 else -8.0),
            onSurfaceVariant = surfaceAnchor.withTone(if (isDark) 80.0 else 30.0),
            surfaceContainerLowest = surfaceAnchor.shiftTone(if (isDark) -2.0 else 2.0),
            surfaceContainerLow = surfaceAnchor.shiftTone(if (isDark) 4.0 else -2.0),
            surfaceContainer = surfaceAnchor.shiftTone(if (isDark) 6.0 else -4.0),
            surfaceContainerHigh = surfaceAnchor.shiftTone(if (isDark) 11.0 else -6.0),
            surfaceContainerHighest = surfaceAnchor.shiftTone(if (isDark) 16.0 else -8.0),
            surfaceBright = surfaceAnchor.withTone(if (isDark) 24.0 else 98.0),
            surfaceDim = surfaceAnchor.withTone(if (isDark) 6.0 else 87.0),
        )
    }

    /**
     * 氛围色派生：从任意种子（封面/壁纸主色）生成局部 scheme。
     * 与 [current] 无关——不读不写全局缓存，锚=种子自身的 M3 中性背景。
     * 消费方：N3a 详情头图、N3b 音频氛围场景、N4 壁纸取色。
     * 纯 JVM 环境（单测）必须显式传 isEInk——默认参数会触发 AppConfig 的 Android 初始化。
     */
    fun ambientScheme(
        @ColorInt seed: Int,
        isDark: Boolean,
        isEInk: Boolean = AppConfig.isEInkMode,
    ): AppSchemeColors {
        if (isEInk) return einkScheme()
        val neutralAnchor = M3ColorScheme.generate(seed, isDark).background
        return buildScheme(seed, isDark, neutralAnchor, false)
    }

    private fun einkScheme() = AppSchemeColors(
        primary = BLACK, onPrimary = WHITE,
        primaryContainer = EINK_CONTAINER, onPrimaryContainer = BLACK,
        secondary = BLACK, onSecondary = WHITE,
        secondaryContainer = EINK_CONTAINER, onSecondaryContainer = BLACK,
        tertiary = BLACK, onTertiary = WHITE,
        tertiaryContainer = EINK_CONTAINER, onTertiaryContainer = BLACK,
        error = BLACK, onError = WHITE,
        errorContainer = EINK_CONTAINER, onErrorContainer = BLACK,
        outline = BLACK, outlineVariant = BLACK,
        inversePrimary = WHITE, inverseSurface = BLACK, inverseOnSurface = WHITE,
        background = WHITE, onBackground = BLACK,
        surface = WHITE, onSurface = BLACK,
        surfaceVariant = EINK_CONTAINER, onSurfaceVariant = BLACK,
        surfaceContainerLowest = WHITE,
        surfaceContainerLow = EINK_CONTAINER,
        surfaceContainer = EINK_CONTAINER,
        surfaceContainerHigh = EINK_CONTAINER,
        surfaceContainerHighest = EINK_CONTAINER,
        surfaceBright = WHITE,
        surfaceDim = EINK_CONTAINER,
    )

    /**
     * 保真直出时的 onPrimary:按种子 tone 取黑或白。
     * 对比度交叉点在 tone≈50,阈值 60 与 M3 惯例一致,中明度色偏向白字。
     */
    private fun fidelityOnColor(@ColorInt color: Int): Int =
        if (Hct.fromInt(color).tone >= 60) BLACK else WHITE

    private fun Int.shiftTone(delta: Double): Int {
        val hct = Hct.fromInt(this)
        return Hct.from(hct.hue, hct.chroma, (hct.tone + delta).coerceIn(0.0, 100.0)).toInt()
    }

    private fun Int.withTone(tone: Double): Int {
        val hct = Hct.fromInt(this)
        return Hct.from(hct.hue, hct.chroma, tone).toInt()
    }
}
