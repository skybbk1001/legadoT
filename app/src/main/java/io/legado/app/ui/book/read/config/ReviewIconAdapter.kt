package io.legado.app.ui.book.read.config

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.TextPaint
import android.util.LruCache
import android.view.ViewGroup
import androidx.core.view.isVisible
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.ItemReviewIconBinding
import io.legado.app.help.config.ReviewIconStore
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.SvgUtils

/**
 * 段评图标库列表:渲染 SVG 预览,单击应用,长按编辑/删除(事件由调用方注册)
 */
class ReviewIconAdapter(
    context: Context,
    private val textColor: Int,
    private val selectedColor: Int,
) : RecyclerAdapter<ReviewIconStore.ReviewIcon, ItemReviewIconBinding>(context) {

    /** 当前应用的 SVG 模板(空串=默认内置图标) */
    var selectedSvg: String = ""
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    private val previewCache = LruCache<String, Bitmap>(32)

    override fun getViewBinding(parent: ViewGroup): ItemReviewIconBinding {
        return ItemReviewIconBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemReviewIconBinding,
        item: ReviewIconStore.ReviewIcon,
        payloads: MutableList<Any>
    ) {
        val selected = item.svg.trim() == selectedSvg
        binding.run {
            ivIcon.setImageBitmap(getPreviewBitmap(item.svg))
            ivSelected.isVisible = selected
            ivSelected.setColorFilter(selectedColor)
            tvName.text = item.name
            tvName.setTextColor(if (selected) selectedColor else textColor)
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemReviewIconBinding) = Unit

    private fun getPreviewBitmap(svg: String): Bitmap? {
        if (svg.isBlank()) return null
        val key = svg.hashCode().toString()
        previewCache.get(key)?.let { if (!it.isRecycled) return it }
        val bitmap = SvgUtils.createBitmapFromSvgText(
            svg.replace(ChapterProvider.reviewIconPlaceholder, "88"),
            PREVIEW_SIZE, PREVIEW_SIZE
        ) ?: return null
        previewCache.put(key, bitmap)
        return bitmap
    }

    companion object {

        private const val PREVIEW_SIZE = 96

        /**
         * 内置默认段评图标的预览(与 ReviewColumn 绘制的气泡形状保持一致)
         */
        fun createDefaultPreviewBitmap(color: Int): Bitmap {
            val size = PREVIEW_SIZE
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val margin = 4f
            val start = margin
            val end = size - margin
            val height = size - margin * 2
            val baseline = size - margin
            val path = Path()
            path.moveTo(start + 1, baseline - height * 2 / 5)
            path.lineTo(start + height / 6, baseline - height * 0.55f)
            path.lineTo(start + height / 6, baseline - height * 0.8f)
            path.lineTo(end - 1, baseline - height * 0.8f)
            path.lineTo(end - 1, baseline)
            path.lineTo(start + height / 6, baseline)
            path.lineTo(start + height / 6, baseline - height / 4)
            path.close()
            val paint = TextPaint().apply {
                this.color = color
                textSize = height * 0.45f
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            }
            paint.style = Paint.Style.STROKE
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
            canvas.drawText("88", (start + height / 9 + end) / 2, baseline - height * 0.23f, paint)
            return bitmap
        }
    }
}
