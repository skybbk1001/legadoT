package io.legado.app.utils

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import androidx.core.graphics.ColorUtils
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.AppColorScheme
import io.legado.app.lib.theme.ImageSeedExtractor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.model.BookCover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 氛围浓度档:装饰(N3a 详情头图窄装饰带,极淡去中性)vs 沉浸(N3b 音频整屏,有机纹理场景) */
enum class AmbientIntensity { DECOR, IMMERSIVE }

/** 微缩边长:封面缩到 16×16 再放大铺满——双线性插值即"模糊",带封面真实明暗的有机色场 */
private const val FIELD_EDGE = 16

/**
 * 封面氛围背景:从封面位图取色/取纹理,设为 receiver view 背景。
 * - DECOR(默认,N3a 详情头图):近中性 surfaceContainer 混 35% 淡出纯背景——窄装饰带,极克制。
 * - IMMERSIVE(N3b 音频整屏):微缩放大伪模糊底(封面缩 16×16 铺满,插值即模糊——有机纹理,
 *   治"几何色块单调";成本≈零、全 API 覆盖、无模糊机器)⊕ 派生色蒙层(双色调三段+分区透明:
 *   顶实护标题区、中透露纹理、底实保控件文字对比度;取代旧死黑蒙层,随日夜/换肤)。
 *   位图取纹理失败时兜底回不透明双色调渐变。
 * eink 早退;无封面/命中默认封面图不派生。N3a 与 N3b 共用,浓度靠 intensity 区分。
 *
 * 返回启动的 [Job]，供调用方在快速连续换封面时取消前一次未完成的取色/渲染——
 * 否则旧协程可能晚于新协程落地，出现"新封面被旧氛围色覆盖"的竞态。早退分支
 * （eink/无封面/默认封面）没有协程可取消，返回一个立即 complete 的空 Job，
 * 调用方对其 cancel() 是安全的空操作。
 */
fun View.applyAmbientBackground(
    coverDrawable: Drawable?,
    scope: CoroutineScope,
    intensity: AmbientIntensity = AmbientIntensity.DECOR,
    isDestroyed: () -> Boolean,
): Job {
    if (AppConfig.isEInkMode) return Job().apply { complete() }
    val bitmap = (coverDrawable as? BitmapDrawable)?.bitmap
        ?: return Job().apply { complete() }
    if (BookCover.isDefaultCover(coverDrawable)) return Job().apply { complete() }
    val baseBg = context.backgroundColor
    return scope.launch(Dispatchers.Default) {
        val palette = runCatching { ImageSeedExtractor.extractPalette(bitmap, 2) }.getOrNull()
        val primarySeed = palette?.firstOrNull() ?: return@launch
        val a1 = AppColorScheme.ambientScheme(primarySeed, AppConfig.isNightTheme, isEInk = false)
        val drawable: Drawable = when (intensity) {
            AmbientIntensity.DECOR -> GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(ColorUtils.blendARGB(baseBg, a1.surfaceContainer, 0.35f), baseBg)
            )
            AmbientIntensity.IMMERSIVE -> {
                val secondSeed = palette.getOrNull(1) ?: primarySeed
                val a2 = AppColorScheme.ambientScheme(secondSeed, AppConfig.isNightTheme, isEInk = false)
                // 有机纹理底:微缩放大伪模糊(BitmapDrawable 默认 FILL 重力拉伸铺满,
                // 色场无所谓变形;显式开 filter 保证插值平滑)
                val field = runCatching {
                    BitmapDrawable(
                        resources, Bitmap.createScaledBitmap(bitmap, FIELD_EDGE, FIELD_EDGE, true)
                    ).apply { setFilterBitmap(true) }
                }.getOrNull()
                if (field == null) {
                    // 兜底(位图池回收窗等极窄失败):不透明双色调渐变
                    GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(
                            ColorUtils.blendARGB(baseBg, a1.primaryContainer, 0.6f),
                            ColorUtils.blendARGB(baseBg, a2.primaryContainer, 0.32f),
                            ColorUtils.blendARGB(baseBg, a2.primaryContainer, 0.08f),
                        )
                    )
                } else {
                    // 派生色蒙层:双色调三段+分区透明度——顶 ~75% 实(状态栏/标题文字区)、
                    // 中 ~47% 最透(封面区,露纹理)、底 ~90% 实(传输控件/文字对比度兜底)
                    val scrim = GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(
                            ColorUtils.setAlphaComponent(
                                ColorUtils.blendARGB(baseBg, a1.primaryContainer, 0.35f), 190
                            ),
                            ColorUtils.setAlphaComponent(
                                ColorUtils.blendARGB(baseBg, a2.primaryContainer, 0.30f), 120
                            ),
                            ColorUtils.setAlphaComponent(
                                ColorUtils.blendARGB(baseBg, a2.primaryContainer, 0.10f), 230
                            ),
                        )
                    )
                    LayerDrawable(arrayOf(field, scrim))
                }
            }
        }
        withContext(Dispatchers.Main) {
            if (!isDestroyed()) background = drawable
        }
    }
}
