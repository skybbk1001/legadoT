package io.legado.app.ui.main.explore

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.ExploreContainer
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.databinding.ItemExploreContainerBinding
import io.legado.app.databinding.ItemSearchBinding
import io.legado.app.help.source.ExploreContainerHelp
import io.legado.app.lib.skin.SkinRole
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.book.explore.bindSearchBook
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.visible
import splitties.views.onLongClick

/**
 * 发现页容器卡片流。单 viewType:卡片内 rv_books(横滑)/ ll_books(列表)二选一显示
 */
class ExploreContainerAdapter(context: Context, val callBack: CallBack) :
    RecyclerAdapter<ExploreContainerState, ItemExploreContainerBinding>(context) {

    private val coverPool = RecyclerView.RecycledViewPool()

    val diffItemCallBack = object : DiffUtil.ItemCallback<ExploreContainerState>() {
        override fun areItemsTheSame(
            oldItem: ExploreContainerState,
            newItem: ExploreContainerState
        ) = oldItem.container.id == newItem.container.id

        override fun areContentsTheSame(
            oldItem: ExploreContainerState,
            newItem: ExploreContainerState
        ): Boolean {
            return oldItem.container == newItem.container
                    && oldItem.books === newItem.books
                    && oldItem.loading == newItem.loading
                    && oldItem.error == newItem.error
                    && oldItem.kinds == newItem.kinds
        }
    }

    override fun getViewBinding(parent: ViewGroup): ItemExploreContainerBinding {
        val binding = ItemExploreContainerBinding.inflate(inflater, parent, false)
        binding.rvBooks.layoutManager =
            LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        binding.rvBooks.setRecycledViewPool(coverPool)
        binding.rvBooks.adapter = ExploreCoverAdapter(context, callBack)
        binding.rlLoading.loadingColor = context.accentColor
        binding.bodyOutline.background = outlineBodyDrawable()
        return binding
    }

    private fun outlineBodyDrawable(): Drawable {
        // 底部圆角跟随容器卡片圆角,顶部直角(标签栏连体)
        val radius = context.resources.getDimension(R.dimen.radius_l)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, radius, radius, radius, radius)
            setColor(SkinRole.surfaceContainerLow.color)
            setStroke(1.dpToPx(), SkinRole.outlineVariant.color)
        }
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemExploreContainerBinding,
        item: ExploreContainerState,
        payloads: MutableList<Any>
    ) {
        binding.run {
            // 卡底色(surfaceContainerLow)+1dp 阴影由布局声明,换肤引擎接管;不再走沉浸式透明
            if (payloads.isNotEmpty()) {
                if (payloads.contains("isInBookshelf")) upBookshelfBadge(binding, item)
                if (payloads.contains("time")) upTime(binding, item)
                return
            }
            upKindTabs(binding, item)
            val container = item.container
            tvSource.text = container.sourceName
            upTime(binding, item)
            if (item.loading) rlLoading.visible() else rlLoading.inVisible()
            when {
                item.books.isEmpty() && item.error != null -> {
                    hostBooks.gone()
                    llBooks.gone()
                    tvError.text = item.error
                    tvError.visible()
                    tvNextBatch.gone()
                    rvBooks.tag = null
                }

                container.style == ExploreContainer.STYLE_LIST -> {
                    upLightError(binding, item)
                    hostBooks.gone()
                    llBooks.visible()
                    tvNextBatch.visible()
                    rvBooks.tag = null
                    upListBooks(binding, item, holder)
                }

                else -> {
                    upLightError(binding, item)
                    llBooks.gone()
                    hostBooks.visible()
                    tvNextBatch.visible()
                    val coverAdapter = rvBooks.adapter as ExploreCoverAdapter
                    coverAdapter.onItemLongClick = {
                        showMenu(root, holder.layoutPosition)
                    }
                    val booksChanged = rvBooks.tag !== item.books
                    rvBooks.tag = item.books
                    coverAdapter.setBooks(item.books)
                    if (booksChanged) {
                        rvBooks.scrollToPosition(0)
                    }
                }
            }
        }
    }

    /** 分类切换标签:仅当 >1 个分类时显示;列表引用未变时只刷新选中态,不重建 */
    private fun upKindTabs(binding: ItemExploreContainerBinding, item: ExploreContainerState) {
        val kinds = item.kinds
        val showTabs = kinds.size > 1
        binding.hostKinds.isGone = !showTabs
        (binding.bodyOutline.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin =
            if (showTabs) 29.dpToPx() else 0
        if (!showTabs) return
        val ll = binding.llKinds
        if (ll.tag === kinds && ll.childCount == kinds.size) {
            var selectedTab: TextView? = null
            for (i in 0 until ll.childCount) {
                val tab = ll.getChildAt(i) as? TextView ?: continue
                val selected = isCurrentKind(item.container, kinds[i])
                tab.isSelected = selected
                upKindTabStyle(tab, selected)
                tab.setTextColor(if (selected) context.accentColor else SkinRole.onSurfaceVariant.color)
                if (selected) selectedTab = tab
            }
            selectedTab?.let { scrollKindTabIntoView(binding.hsvKinds, it) }
            return
        }
        ll.removeAllViews()
        ll.tag = kinds
        var selectedTab: TextView? = null
        kinds.forEach { kind ->
            val tab = inflater.inflate(R.layout.item_explore_kind_chip, ll, false) as TextView
            tab.text = kind.title
            tab.isSelected = isCurrentKind(item.container, kind)
            upKindTabStyle(tab, tab.isSelected)
            tab.setTextColor(if (tab.isSelected) context.accentColor else SkinRole.onSurfaceVariant.color)
            if (tab.isSelected) selectedTab = tab
            tab.setOnClickListener {
                // 手动单选 + 通知切换;选中态在状态回流后由本方法校准
                for (i in 0 until ll.childCount) {
                    val child = ll.getChildAt(i) as? TextView ?: continue
                    child.isSelected = false
                    upKindTabStyle(child, false)
                    child.setTextColor(SkinRole.onSurfaceVariant.color)
                }
                tab.isSelected = true
                upKindTabStyle(tab, true)
                tab.setTextColor(context.accentColor)
                scrollKindTabIntoView(binding.hsvKinds, tab)
                callBack.switchKind(item, kind)
            }
            ll.addView(tab)
        }
        selectedTab?.let { binding.hsvKinds.post { scrollKindTabIntoView(binding.hsvKinds, it) } }
    }

    /** 平滑滚动标签栏,让选中标签居中可见(参考横滑封面切换后落位;首次布局后执行避免宽高为 0) */
    private fun scrollKindTabIntoView(hsv: HorizontalScrollView, tab: TextView) {
        if (hsv.width <= 0 || hsv.height <= 0) return
        val target = (tab.left - (hsv.width - tab.width) / 2).coerceAtLeast(0)
        hsv.smoothScrollTo(target, 0)
    }

    /** 选中分类与内容面板连体，其他分类维持独立的紧凑圆角方框。 */
    private fun upKindTabStyle(tab: TextView, selected: Boolean) {
        val accent = context.accentColor
        val radius = 6.dpToPx()
        val strokeWidth = 1.dpToPx()
        val tabHeight = 30.dpToPx()
        if (selected) {
            // 浏览器式选中标签:顶部圆角+左右/顶描边,底边开放,与面板同色连体盖住基线
            val selectedShape = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(
                    radius.toFloat(), radius.toFloat(), radius.toFloat(), radius.toFloat(),
                    0f, 0f, 0f, 0f
                )
                setColor(SkinRole.surfaceContainerLow.color)
                setStroke(strokeWidth, ColorUtils.adjustAlpha(accent, 0.42f))
            }
            val bottomFill = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(SkinRole.surfaceContainerLow.color)
            }
            tab.background = LayerDrawable(arrayOf(selectedShape, bottomFill)).apply {
                setLayerInset(1, 0, tabHeight - strokeWidth, 0, 0)
            }
        } else {
            // 未选中标签:顶部圆角、底部直边,底部留 1dp 露出基线
            val tabShape = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(
                    radius.toFloat(), radius.toFloat(), radius.toFloat(), radius.toFloat(),
                    0f, 0f, 0f, 0f
                )
                setColor(SkinRole.surfaceContainerLow.color)
                setStroke(strokeWidth, SkinRole.outlineVariant.color)
            }
            tab.background = InsetDrawable(tabShape, 0, 0, 0, strokeWidth)
        }
    }

    /** 当前分类高亮:按分类名匹配(书源分类名通常唯一;URL 快照漂移时仍可高亮) */
    private fun isCurrentKind(container: ExploreContainer, kind: ExploreKind): Boolean =
        container.kindTitle == kind.title

    /** 有旧数据时的刷新失败轻提示(完整错误信息仅在无数据的全错误态显示) */
    private fun upLightError(binding: ItemExploreContainerBinding, item: ExploreContainerState) {
        binding.tvError.run {
            if (item.error == null) {
                gone()
            } else {
                text = context.getString(R.string.explore_refresh_error)
                visible()
            }
        }
    }

    /** 列表样式:行视图复用,只 inflate 缺口、移除多余,避免每次重绑全量 removeAllViews+inflate */
    private fun upListBooks(
        binding: ItemExploreContainerBinding,
        item: ExploreContainerState,
        holder: ItemViewHolder
    ) {
        val llBooks = binding.llBooks
        val books = item.books.take(item.container.listCount)
        while (llBooks.childCount > books.size) {
            llBooks.removeViewAt(llBooks.childCount - 1)
        }
        books.forEachIndexed { index, book ->
            val rowBinding = if (index < llBooks.childCount) {
                ItemSearchBinding.bind(llBooks.getChildAt(index))
            } else {
                ItemSearchBinding.inflate(inflater, llBooks, false).also {
                    llBooks.addView(it.root)
                }
            }
            rowBinding.bindSearchBook(context, book, callBack.isInBookshelf(book))
            rowBinding.root.setOnClickListener { callBack.showBookInfo(book) }
            rowBinding.root.onLongClick {
                showMenu(binding.root, holder.layoutPosition)
            }
        }
    }

    /** 书架变化的 payload 增量:只刷角标 */
    private fun upBookshelfBadge(
        binding: ItemExploreContainerBinding,
        item: ExploreContainerState
    ) {
        binding.run {
            if (item.container.style == ExploreContainer.STYLE_LIST) {
                val books = item.books.take(item.container.listCount)
                books.forEachIndexed { index, book ->
                    if (index < llBooks.childCount) {
                        ItemSearchBinding.bind(llBooks.getChildAt(index))
                            .ivInBookshelf.isVisible = callBack.isInBookshelf(book)
                    }
                }
            } else {
                (rvBooks.adapter as? ExploreCoverAdapter)?.let {
                    it.notifyItemRangeChanged(0, it.itemCount, "isInBookshelf")
                }
            }
        }
    }

    /** 时间标签:相对时间;time=0 隐藏 */
    private fun upTime(binding: ItemExploreContainerBinding, item: ExploreContainerState) {
        val text = ExploreContainerHelp.formatUpdateTime(
            item.updateTime, System.currentTimeMillis()
        )
        binding.tvTime.text = text
        binding.tvTime.isGone = text == null
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemExploreContainerBinding) {
        binding.apply {
            tvMore.setOnClickListener {
                getItem(holder.layoutPosition)?.let { callBack.openExplore(it) }
            }
            tvNextBatch.setOnClickListener {
                getItem(holder.layoutPosition)?.let { callBack.nextBatch(it) }
            }
            tvError.setOnClickListener {
                getItem(holder.layoutPosition)?.let { callBack.refreshContainer(it) }
            }
            root.onLongClick {
                showMenu(root, holder.layoutPosition)
            }
        }
    }

    private fun showMenu(view: View, position: Int) {
        val item = getItem(position) ?: return
        callBack.showContainerMenu(view, item)
    }

    interface CallBack : ExploreCoverAdapter.CallBack {
        fun openExplore(state: ExploreContainerState)
        fun nextBatch(state: ExploreContainerState)
        fun refreshContainer(state: ExploreContainerState)
        fun showContainerMenu(anchor: View, state: ExploreContainerState)
        fun switchKind(state: ExploreContainerState, kind: ExploreKind)
    }
}
