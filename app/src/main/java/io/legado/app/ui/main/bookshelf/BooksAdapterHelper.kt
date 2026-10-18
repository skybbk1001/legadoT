package io.legado.app.ui.main.bookshelf

import android.os.Bundle
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.google.android.material.progressindicator.LinearProgressIndicator
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ItemBookshelfGridBinding
import io.legado.app.databinding.ItemBookshelfListBinding
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.readProgress
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.cardBackgroundColor
import io.legado.app.ui.widget.anima.RotateLoading
import io.legado.app.ui.widget.text.BadgeView
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.toTimeAgo
import io.legado.app.utils.visible
import io.legado.app.data.entities.getUnreadChapterNum

/**
 * 书架封面上"未读角标 + 刷新加载动画"随 Book 状态更新的公共实现，
 * 供 style1/style2 的网格/列表四个 BooksAdapter 共用。
 *
 * @param loadingGone 隐藏加载动画时用 gone()（列表款）还是 inVisible()（网格款，保留占位）
 */
fun upBookBadge(
    bvUnread: BadgeView,
    rlLoading: RotateLoading,
    item: Book,
    isUpdate: Boolean,
    loadingGone: Boolean
) {
    if (!item.isLocal && isUpdate) {
        bvUnread.invisible()
        rlLoading.visible()
    } else {
        if (loadingGone) rlLoading.gone() else rlLoading.inVisible()
        if (AppConfig.showUnread) {
            bvUnread.setBadgeCount(item.getUnreadChapterNum())
            bvUnread.setHighlight(item.lastCheckCount > 0)
        } else {
            bvUnread.invisible()
        }
    }
}

/**
 * 封面/列表项阅读进度条更新（开关关闭或未读=null 时 gone,零视觉噪声）。
 * indicatorColor/trackColor 均走 accentColor 运行时着色——LinearProgressIndicator 不在
 * SkinInflaterFactory 的规则 A 换肤表内;track 若留给 ?attr 默认,在 DynamicColors
 * 不注入的机型(华为/荣耀等厂商门槛)会回落 XML 预生成蓝色板,故随强调色 24% 直出。
 * @param percentView 列表款百分比文本,网格款不传(封面上不放文字)
 */
private fun upReadProgress(
    pbReadProgress: LinearProgressIndicator,
    item: Book,
    percentView: TextView? = null,
) {
    val progress = if (AppConfig.showBookshelfReadProgress) item.readProgress() else null
    if (progress == null) {
        pbReadProgress.gone()
        percentView?.gone()
    } else {
        val accent = pbReadProgress.context.accentColor
        pbReadProgress.setIndicatorColor(accent)
        pbReadProgress.trackColor = ColorUtils.withAlpha(accent, 0.24f)
        pbReadProgress.visible()
        pbReadProgress.progress = (progress * 100).toInt()
        percentView?.let {
            it.visible()
            it.text = "${(progress * 100).toInt()}%"
        }
    }
}

/**
 * 列表款书籍项绑定（全量+payload 增量单份实现,style1/style2 共用）。
 * @param withLastUpdateTime style1 显示"最近更新时间"列,style2 该视图空置
 * @param coverFragment/coverLifecycle 封面 Glide 生命周期宿主(style1 提供)
 */
fun ItemBookshelfListBinding.bindBook(
    item: Book,
    isUpdate: Boolean,
    withLastUpdateTime: Boolean,
    payloads: MutableList<Any>,
    coverFragment: Fragment? = null,
    coverLifecycle: Lifecycle? = null,
) {
    ivCover.transitionName = "book_cover_" + item.name + item.author

    fun loadCover() = ivCover.load(
        item.getDisplayCover(), item.name, item.author, false,
        item.getCoverSourceOrigin(), coverFragment, coverLifecycle
    )

    fun upLastUpdateTime() {
        if (!withLastUpdateTime) return
        if (AppConfig.showLastUpdateTime && !item.isLocal) {
            val time = item.latestChapterTime.toTimeAgo()
            if (tvLastUpdateTime.text != time) {
                tvLastUpdateTime.text = time
            }
        } else {
            tvLastUpdateTime.text = ""
        }
    }

    if (payloads.isEmpty()) {
        tvName.text = item.name
        tvAuthor.text = item.author
        tvRead.text = item.durChapterTitle
        tvLast.text = item.latestChapterTitle
        loadCover()
        upBookBadge(bvUnread, rlLoading, item, isUpdate, loadingGone = true)
        upLastUpdateTime()
        upReadProgress(pbReadProgress, item, tvReadPercent)
    } else {
        for (i in payloads.indices) {
            val bundle = payloads[i] as Bundle
            bundle.keySet().forEach {
                when (it) {
                    "name" -> tvName.text = item.name
                    "author" -> tvAuthor.text = item.author
                    "dur" -> tvRead.text = item.durChapterTitle
                    "last" -> tvLast.text = item.latestChapterTitle
                    "cover" -> loadCover()
                    "refresh" -> {
                        upBookBadge(bvUnread, rlLoading, item, isUpdate, loadingGone = true)
                        upReadProgress(pbReadProgress, item, tvReadPercent)
                    }
                    "lastUpdateTime" -> upLastUpdateTime()
                }
            }
        }
    }
}

/** 网格款书籍项绑定（全量+payload 增量单份实现,style1/style2 共用） */
fun ItemBookshelfGridBinding.bindBook(
    item: Book,
    isUpdate: Boolean,
    payloads: MutableList<Any>,
) {
    ivCover.transitionName = "book_cover_" + item.name + item.author
    coverCard.setCardBackgroundColor(root.context.cardBackgroundColor)

    fun loadCover() = ivCover.load(
        item.getDisplayCover(), item.name, item.author, false, item.getCoverSourceOrigin()
    )

    if (payloads.isEmpty()) {
        tvName.text = item.name
        loadCover()
        upBookBadge(bvUnread, rlLoading, item, isUpdate, loadingGone = false)
        upReadProgress(pbReadProgress, item)
    } else {
        for (i in payloads.indices) {
            val bundle = payloads[i] as Bundle
            bundle.keySet().forEach {
                when (it) {
                    "name" -> tvName.text = item.name
                    "cover" -> loadCover()
                    "refresh" -> {
                        upBookBadge(bvUnread, rlLoading, item, isUpdate, loadingGone = false)
                        upReadProgress(pbReadProgress, item)
                    }
                }
            }
        }
    }
}
