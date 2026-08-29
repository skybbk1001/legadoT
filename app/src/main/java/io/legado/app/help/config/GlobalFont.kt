package io.legado.app.help.config

import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.tabs.TabLayout
import io.legado.app.utils.RealPathUtil
import io.legado.app.utils.isContentScheme
import splitties.init.appCtx

/**
 * 全局 UI 字体解析器。
 *
 * 与阅读页字体([ReadBookConfig.textFont])互不影响:本对象只服务于整个 App 的界面文字
 * (书架/菜单/弹窗/设置页等),阅读正文由 ChapterProvider 自行排版绘制。
 *
 * 解析优先级:自定义字体文件([AppConfig.globalFontPath]) > 系统字族([AppConfig.globalTypefaces]);
 * 两者都未设置(默认)时返回 null,让视图沿用其 layout/style 声明的原始字体。
 *
 * 缓存:按路径缓存已解析的 Typeface,避免每次 inflate 重复读文件;字体设置变更走 RECREATE
 * 整体重建,路径不一致即自动失效重解析。
 */
object GlobalFont {

    @Volatile
    private var cachedFontPath: String? = null

    @Volatile
    private var cachedTypeface: Typeface? = null

    /**
     * 当前全局字体的基础 Typeface;返回 null 表示"不覆盖",沿用系统默认字体。
     */
    fun current(): Typeface? {
        val path = AppConfig.globalFontPath
        if (path.isEmpty()) {
            return when (AppConfig.globalTypefaces) {
                1 -> Typeface.SERIF
                2 -> Typeface.MONOSPACE
                else -> null
            }
        }
        if (cachedFontPath == path) {
            return cachedTypeface
        }
        cachedFontPath = path
        cachedTypeface = resolve(path)
        return cachedTypeface
    }

    /**
     * 对单个 TextView 施加全局字体,保留其当前字重/斜体(textStyle)。
     * 未设置全局字体时是 no-op。
     */
    fun applyTo(textView: TextView) {
        val base = current() ?: return
        val style = textView.typeface?.style ?: Typeface.NORMAL
        textView.typeface = Typeface.create(base, style)
    }

    /**
     * 递归施加到整棵视图树,兜底覆盖不走 inflater 的动态 TextView
     * (如 Toolbar 标题、TabLayout 标签、代码 new 出的 TextView)。
     * 未设置全局字体时立即返回。
     */
    fun applyToTree(root: View?) {
        if (root == null || current() == null) return
        if (root is TextView) {
            applyTo(root)
            return
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                applyToTree(root.getChildAt(i))
            }
        }
    }

    /**
     * TabLayout 标签文字兜底。
     *
     * TabLayout 的标签 TextView 由 TabLayoutMediator/Tab.setText 时框架内部 new 出来
     * (TabView 懒建 mTextView),不经过 inflater 工厂,且创建时机在 attach 之后的异步队列里,
     * inflate 期的 `post { applyToTree }` 会扑空。改为事件驱动:给内部 SlidingTabStrip
     * (TabLayout 唯一直接子 View) 挂 OnHierarchyChangeListener——addTab 时 TabView 的
     * 文字 TextView 已在 setText 阶段创建完毕,一加入视图树即可整树补刷。
     */
    fun applyToTabLayout(tabLayout: TabLayout) {
        if (current() == null) return
        for (i in 0 until tabLayout.tabCount) {
            tabLayout.getTabAt(i)?.view?.let { applyToTree(it) }
        }
        val strip = tabLayout.getChildAt(0) as? ViewGroup ?: return
        strip.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View, child: View) {
                applyToTree(child)
                child.post { applyToTree(child) }
            }

            override fun onChildViewRemoved(parent: View, child: View) = Unit
        })
        // 兜底:TabView.update() 在 tab 创建/选中切换时 setTextAppearance,默认
        // tabTextAppearance(链到 android:TextAppearance.Material.Button)携带
        // fontFamily=sans-serif-medium,会把已刷的字体打回系统字体(w=500)。
        // layout 监听在重置后的重排时补刷;Typeface.create 同参同实例,setTypeface
        // 引用相等不再 requestLayout,布局稳定后收敛,无循环。
        tabLayout.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            for (i in 0 until tabLayout.tabCount) {
                tabLayout.getTabAt(i)?.view?.let { applyToTree(it) }
            }
        }
    }

    private fun resolve(path: String): Typeface? = kotlin.runCatching {
        when {
            path.isContentScheme() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                appCtx.contentResolver
                    .openFileDescriptor(Uri.parse(path), "r")?.use {
                        Typeface.Builder(it.fileDescriptor).build()
                    }
            }

            path.isContentScheme() -> {
                Typeface.createFromFile(RealPathUtil.getPath(appCtx, Uri.parse(path)))
            }

            else -> Typeface.createFromFile(path)
        }
    }.getOrNull()
}
