package io.legado.app.ui.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.annotation.StyleRes
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.alpha
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import com.google.android.material.appbar.AppBarLayout
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.GlobalFont
import io.legado.app.lib.theme.appBarBackgroundIsLight
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.elevation
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.activity
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import splitties.views.bottomPadding
import splitties.views.topPadding

@Suppress("unused", "MemberVisibilityCanBePrivate")
class TitleBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppBarLayout(context, attrs) {

    val toolbar: Toolbar
    val menu: Menu
        get() = toolbar.menu

    var title: CharSequence?
        get() = toolbar.title
        set(title) {
            if (toolbar.title != title) {
                toolbar.title = title
            }
        }

    var subtitle: CharSequence?
        get() = toolbar.subtitle
        set(subtitle) {
            if (toolbar.subtitle != subtitle) {
                toolbar.subtitle = subtitle
            }
        }

    private val displayHomeAsUp: Boolean
    private val navigationIconTint: ColorStateList?
    private val navigationIconTintMode: Int
    private val fitStatusBar: Boolean
    private val fitNavigationBar: Boolean
    private val attachToActivity: Boolean
    private val foregroundColor: Int?
    private val titleTextColorFromAttrs: Boolean
    private val subtitleTextColorFromAttrs: Boolean
    private var autoForegroundColorEnabled = true

    init {
        val a = context.obtainStyledAttributes(
            attrs, R.styleable.TitleBar,
            R.attr.titleBarStyle, 0
        )
        navigationIconTint = a.getColorStateList(R.styleable.TitleBar_navigationIconTint)
        navigationIconTintMode = a.getInt(R.styleable.TitleBar_navigationIconTintMode, 9)
        attachToActivity = a.getBoolean(R.styleable.TitleBar_attachToActivity, true)
        displayHomeAsUp = a.getBoolean(R.styleable.TitleBar_displayHomeAsUp, true)
        fitStatusBar = a.getBoolean(R.styleable.TitleBar_fitStatusBar, true)
        fitNavigationBar = a.getBoolean(R.styleable.TitleBar_fitNavigationBar, false)
        val themeMode = a.getInt(R.styleable.TitleBar_themeMode, 0)
        // 仅在“自动主题 + 沉浸式操作栏”时接管前景色。
        // 不透明时栏背景=主色，系统 actionBarStyle 叠加层已正确处理，无需干预；
        // themeMode 被强制为 dark/light（如详情页、播放页）时同样交给叠加层。
        foregroundColor = if (themeMode == 0 && AppConfig.isTransparentActionBar && !AppConfig.isEInkMode) {
            context.getPrimaryTextColor(
                appBarBackgroundIsLight(
                    transparentActionBar = true,
                    barBackgroundColor = context.primaryColor,
                    contentBackgroundColor = context.backgroundColor
                )
            )
        } else {
            null
        }

        val navigationIcon = a.getDrawable(R.styleable.TitleBar_navigationIcon)
        val navigationContentDescription =
            a.getText(R.styleable.TitleBar_navigationContentDescription)
        val titleText = a.getString(R.styleable.TitleBar_title)
        val subtitleText = a.getString(R.styleable.TitleBar_subtitle)
        titleTextColorFromAttrs = a.hasValue(R.styleable.TitleBar_titleTextColor)
        subtitleTextColorFromAttrs = a.hasValue(R.styleable.TitleBar_subtitleTextColor)

        when (themeMode) {
            1 -> inflate(context, R.layout.view_title_bar_dark, this)
            else -> inflate(context, R.layout.view_title_bar, this)
        }
        toolbar = findViewById(R.id.toolbar)
        applyGlobalFontToToolbar()

        toolbar.apply {
            navigationIcon?.let {
                this.navigationIcon = it
                this.navigationContentDescription = navigationContentDescription
            }

            if (a.hasValue(R.styleable.TitleBar_titleTextAppearance)) {
                this.setTitleTextAppearance(
                    context,
                    a.getResourceId(R.styleable.TitleBar_titleTextAppearance, 0)
                )
            }

            if (titleTextColorFromAttrs) {
                this.setTitleTextColor(a.getColor(R.styleable.TitleBar_titleTextColor, -0x1))
            } else {
                foregroundColor?.let(this::setTitleTextColor)
            }

            if (a.hasValue(R.styleable.TitleBar_subtitleTextAppearance)) {
                this.setSubtitleTextAppearance(
                    context,
                    a.getResourceId(R.styleable.TitleBar_subtitleTextAppearance, 0)
                )
            }

            if (subtitleTextColorFromAttrs) {
                this.setSubtitleTextColor(a.getColor(R.styleable.TitleBar_subtitleTextColor, -0x1))
            } else {
                foregroundColor?.let(this::setSubtitleTextColor)
            }


            if (a.hasValue(R.styleable.TitleBar_contentInsetLeft)
                || a.hasValue(R.styleable.TitleBar_contentInsetRight)
            ) {
                this.setContentInsetsAbsolute(
                    a.getDimensionPixelSize(R.styleable.TitleBar_contentInsetLeft, 0),
                    a.getDimensionPixelSize(R.styleable.TitleBar_contentInsetRight, 0)
                )
            }

            if (a.hasValue(R.styleable.TitleBar_contentInsetStart)
                || a.hasValue(R.styleable.TitleBar_contentInsetEnd)
            ) {
                this.setContentInsetsRelative(
                    a.getDimensionPixelSize(R.styleable.TitleBar_contentInsetStart, 0),
                    a.getDimensionPixelSize(R.styleable.TitleBar_contentInsetEnd, 0)
                )
            }

            if (a.hasValue(R.styleable.TitleBar_contentInsetStartWithNavigation)) {
                this.contentInsetStartWithNavigation = a.getDimensionPixelOffset(
                    R.styleable.TitleBar_contentInsetStartWithNavigation, 0
                )
            }

            if (a.hasValue(R.styleable.TitleBar_contentInsetEndWithActions)) {
                this.contentInsetEndWithActions = a.getDimensionPixelOffset(
                    R.styleable.TitleBar_contentInsetEndWithActions, 0
                )
            }

            if (!titleText.isNullOrBlank()) {
                this.title = titleText
            }

            if (!subtitleText.isNullOrBlank()) {
                this.subtitle = subtitleText
            }

            if (a.hasValue(R.styleable.TitleBar_contentLayout)) {
                inflate(context, a.getResourceId(R.styleable.TitleBar_contentLayout, 0), this)
            }
        }

        if (!isInEditMode) {
//            if (fitStatusBar) {
//                setPadding(paddingLeft, context.statusBarHeight, paddingRight, paddingBottom)
//            }
//
//            if (fitNavigationBar) {
//                setPadding(paddingLeft, paddingTop, paddingRight, context.navigationBarHeight)
//            }

            if (fitStatusBar || fitNavigationBar) {
                setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
                    val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                    if (fitStatusBar) {
                        topPadding = insets.top
                    }
                    if (fitNavigationBar) {
                        bottomPadding = insets.bottom
                    }
                    windowInsets
                }
            }

            if (AppConfig.isEInkMode) {
                setBackgroundResource(R.drawable.bg_eink_border_bottom)
            } else if (AppConfig.isTransparentActionBar) {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            } else {
                setBackgroundColor(context.primaryColor)
            }

            stateListAnimator = null
            elevation = context.elevation
        }
        a.recycle()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachToActivity()
        if (foregroundColor != null && autoForegroundColorEnabled) {
            // post 到下一帧：等返回箭头(setDisplayHomeAsUpEnabled)与溢出菜单图标创建完成后再着色
            post { applyForegroundColor() }
        }
    }

    fun setNavigationOnClickListener(clickListener: ((View) -> Unit)) {
        toolbar.setNavigationOnClickListener(clickListener)
    }

    fun setTitle(titleId: Int) {
        toolbar.setTitle(titleId)
    }

    fun setSubTitle(subtitleId: Int) {
        toolbar.setSubtitle(subtitleId)
    }

    fun setTitleTextColor(@ColorInt color: Int) {
        toolbar.setTitleTextColor(color)
    }

    fun setTitleTextAppearance(@StyleRes resId: Int) {
        toolbar.setTitleTextAppearance(context, resId)
    }

    fun setSubTitleTextColor(@ColorInt color: Int) {
        toolbar.setSubtitleTextColor(color)
    }

    fun setSubTitleTextAppearance(@StyleRes resId: Int) {
        toolbar.setSubtitleTextAppearance(context, resId)
    }

    fun setTextColor(@ColorInt color: Int) {
        setTitleTextColor(color)
        setSubTitleTextColor(color)
    }

    fun setColorFilter(@ColorInt color: Int) {
        val colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP)
        toolbar.children.firstOrNull { it is ImageView }?.background?.colorFilter = colorFilter
        toolbar.navigationIcon?.colorFilter = colorFilter
        toolbar.overflowIcon?.colorFilter = colorFilter
        toolbar.menu.children.forEach {
            it.icon?.colorFilter = colorFilter
        }
    }

    /**
     * 沉浸式操作栏接管前景色：标题/副标题文字 + 返回箭头 + 溢出(三点)图标。
     * 菜单项图标由 [io.legado.app.utils.applyTint]/getMenuColor 单独着色（会区分溢出弹窗项），此处不碰，避免把弹窗里的项也染成栏前景色。
     */
    private fun applyForegroundColor() {
        if (!autoForegroundColorEnabled) return
        val color = foregroundColor ?: return
        if (!titleTextColorFromAttrs) {
            setTitleTextColor(color)
        }
        if (!subtitleTextColorFromAttrs) {
            setSubTitleTextColor(color)
        }
        val colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP)
        toolbar.navigationIcon?.colorFilter = colorFilter
        toolbar.overflowIcon?.colorFilter = colorFilter
    }

    /**
     * 关闭沉浸式前景色自动接管。
     * 供复用本控件却自行刷不透明背景、自管前景色的宿主（如阅读菜单）调用：
     * 否则本控件会因全局沉浸式设置误判自己透明，按页面背景给标题着色，覆盖宿主设定。
     */
    fun disableAutoForegroundColor() {
        autoForegroundColorEnabled = false
    }

    override fun setBackgroundColor(color: Int) {
        if (color.alpha < 255) {
            //这里不能改为0f,改为0f在横屏模式下文字和图标颜色会变
            elevation = 0.1f
        }
        super.setBackgroundColor(color)
    }

    override fun setBackground(background: Drawable?) {
        if (background is ColorDrawable) {
            if (background.alpha < 255) {
                //这里不能改为0f,改为0f在横屏模式下文字和图标颜色会变
                elevation = 0.1f
            }
        }
        super.setBackground(background)
    }

    fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, fullScreen: Boolean) {
//        if (fitStatusBar) {
//            val topPadding = if (!isInMultiWindowMode && fullScreen) context.statusBarHeight else 0
//            setPadding(paddingLeft, topPadding, paddingRight, paddingBottom)
//        }
    }

    private fun attachToActivity() {
        if (attachToActivity) {
            activity?.let {
                it.setSupportActionBar(toolbar)
                it.supportActionBar?.setDisplayHomeAsUpEnabled(displayHomeAsUp)
            }
        }
    }

    /**
     * 全局字体兜底:Toolbar 的标题/副标题 TextView 由框架在 setTitle 时内部 new TextView 创建,
     * 不经过 inflater 工厂。挂 OnHierarchyChangeListener,标题 TextView 一加入视图树即补刷;
     * 同时补刷一次当前已有子视图(覆盖标题先于本监听器就位的路径)。
     */
    private fun applyGlobalFontToToolbar() {
        toolbar.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View, child: View) {
                GlobalFont.applyToTree(child)
            }

            override fun onChildViewRemoved(parent: View, child: View) = Unit
        })
        GlobalFont.applyToTree(toolbar)
    }

}