package io.legado.app.ui.widget.anima

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import com.google.android.material.progressindicator.CircularProgressIndicator
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.dpToPx

/**
 * RotateLoading
 *
 * 外壳保名：类名/XML 标签/公开 API 与旧手绘转圈完全兼容，内核换成 M3 [CircularProgressIndicator]。
 *
 * 两个必须绕开的 M3 内部机制（反编译 material-1.13.0 确认）：
 *
 * 1. 测量：[CircularProgressIndicator] 的 onMeasure 无视父容器 MeasureSpec，恒按
 *    indicatorSize + 2*indicatorInset 自报尺寸（默认 48dp）。子 View 在 26dp/36dp 的本壳内
 *    若按 48dp 摆放会被裁成半圆。故在本壳 onMeasure 里先用 EXACTLY 边长把 indicatorSize
 *    收敛为「边长 - 2*inset」，同一轮测量子 View 就报出与壳一致的尺寸，单次布局收敛、无裁剪。
 *
 * 2. 动画启停：BaseProgressIndicator 的 indeterminate 动画只在 setIndeterminate()/可见性翻转
 *    发生且当时 visibleToUser()（已 attach 且有效可见）时才 startAnimator()。本壳的子 View
 *    一直 VISIBLE，自身可见性从不翻转；构造期设置动画、对话框「先 start() 后 attach」等时序下
 *    动画永远不启动，drawable 甚至被标成隐藏态（draw() 直接 return，什么都不画）。
 *    故 start()/stop() 用 drawable.setVisible(...) 显式驱动：
 *    - 显示：animate=true 走库原生 show 路径并启动转圈（系统动画时长为 0 时库自动退化为静态）；
 *    - 隐藏：animate=false 立即隐藏并 cancelAnimatorImmediately，无渐隐、无残留重绘；
 *    - eink：animate=false，只显示静态圆环不启动动画（SDK>22 时库明确跳过 startAnimator），零重绘。
 *
 * loading_width(旧描边宽)映射到 trackThickness(环厚)；旧手绘的 2*stroke 内边距恰与 M3 默认
 * indicatorInset(4dp) 同量级，几何观感与旧版一致。
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
class RotateLoading @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val indicator = CircularProgressIndicator(context).apply {
        isIndeterminate = true
    }

    var hideMode = GONE

    var isStarted = false
        private set

    var loadingColor: Int = if (isInEditMode) 0xFF3D7EFF.toInt() else context.accentColor
        set(value) {
            field = value
            indicator.setIndicatorColor(value)
        }

    init {
        val ta = context.obtainStyledAttributes(attrs, R.styleable.RotateLoading)
        loadingColor = ta.getColor(R.styleable.RotateLoading_loading_color, loadingColor)
        indicator.trackThickness = ta.getDimensionPixelSize(
            R.styleable.RotateLoading_loading_width,
            4.dpToPx()
        )
        ta.recycle()
        addView(
            indicator,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
    }

    /**
     * 测量期收敛 indicatorSize：外壳拿到 EXACTLY 边长时，把内径适配成「边长 - 2*inset」，
     * 让子 View 的自测尺寸与外壳一致。宽/高任一非 EXACTLY（wrap_content 等）则保持默认。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val side = exactSide(widthMeasureSpec, heightMeasureSpec)
        if (side > 0) {
            val target = (side - 2 * indicator.indicatorInset).coerceAtLeast(0)
            if (indicator.indicatorSize != target) {
                indicator.indicatorSize = target
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun exactSide(widthMeasureSpec: Int, heightMeasureSpec: Int): Int {
        val wExact = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY
        val hExact = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY
        return when {
            wExact && hExact -> minOf(
                MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.getSize(heightMeasureSpec)
            )
            wExact -> MeasureSpec.getSize(widthMeasureSpec)
            hExact -> MeasureSpec.getSize(heightMeasureSpec)
            else -> 0
        }
    }

    fun start() {
        isStarted = true
        visibility = VISIBLE
        showIndicator()
    }

    fun stop() {
        isStarted = false
        indicator.indeterminateDrawable?.setVisible(false, false, false)
        visibility = if (hideMode == INVISIBLE) INVISIBLE else GONE
    }

    /**
     * 显式驱动 drawable 显示。未 attach 时（对话框先 start() 后 show() 的时序）
     * 挂到 attach 后执行；期间若已 stop() 则由 isStarted 守卫跳过。
     */
    private fun showIndicator() {
        val show = Runnable {
            if (isStarted) {
                // 正常模式 animate=true：走原生 show 路径并启动转圈；
                // e-ink 模式 animate=false：静态圆环定格、不启动动画（零重绘）
                indicator.indeterminateDrawable?.setVisible(true, false, !AppConfig.isEInkMode)
            }
        }
        if (indicator.isAttachedToWindow) show.run() else indicator.post(show)
    }

    fun visible() {
        start()
    }

    fun gone() {
        hideMode = GONE
        stop()
    }

    fun inVisible() {
        hideMode = INVISIBLE
        stop()
    }
}
