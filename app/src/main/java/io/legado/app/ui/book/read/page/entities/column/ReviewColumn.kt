package io.legado.app.ui.book.read.page.entities.column

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.annotation.Keep
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextLine.Companion.emptyTextLine
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import kotlin.math.roundToInt

/**
 * 评论按钮列
 */
@Keep
data class ReviewColumn(
    override var start: Float,
    override var end: Float,
    var count: Int = 0,
    /** 0 表示未显式指定，点击时回退用 textLine 计算；非 0 为显式 reviewId（如章末未定位）。 */
    var reviewId: Int = 0
) : BaseColumn {

    override var textLine: TextLine = emptyTextLine

    override fun isTouch(x: Float): Boolean {
        if (count == 0) return false
        // 图标较小，适当放大点击热区，便于点中
        val extraTouchWidth = ((end - start) * 0.35f).coerceAtLeast(textLine.height * 0.15f)
        return x > start - extraTouchWidth && x < end + extraTouchWidth
    }

    override fun draw(view: ContentTextView, canvas: Canvas) {
        val baseLine = textLine.lineBase - textLine.lineTop
        drawToCanvas(canvas, baseLine, ChapterProvider.getReviewHeight(textLine.isTitle))
    }
    val countText: String
        get() = ChapterProvider.getReviewCountText(count)

    val path by lazy { Path() }
    private val iconRect by lazy { RectF() }

    fun drawToCanvas(canvas: Canvas, baseLine: Float, height: Float) {
        if (count == 0) return
        val iconHeight = height * 0.9f
        val widthPx = (end - start).coerceAtLeast(1f).roundToInt()
        val heightPx = iconHeight.coerceAtLeast(1f).roundToInt()
        ChapterProvider.getReviewIconBitmap(count, widthPx, heightPx)?.let { bitmap ->
            val iconTop = (textLine.height - iconHeight) / 2f
            iconRect.set(start, iconTop, end, iconTop + iconHeight)
            canvas.drawBitmap(bitmap, null, iconRect, null)
            return
        }
        path.reset()
        path.moveTo(start + 1, baseLine - height * 2 / 5)
        path.lineTo(start + height / 6, baseLine - height * 0.55f)
        path.lineTo(start + height / 6, baseLine - height * 0.8f)
        path.lineTo(end - 1, baseLine - height * 0.8f)
        path.lineTo(end - 1, baseLine)
        path.lineTo(start + height / 6, baseLine)
        path.lineTo(start + height / 6, baseLine - height / 4)
        path.close()
        val reviewPaint = ChapterProvider.reviewPaint
        reviewPaint.style = Paint.Style.STROKE
        canvas.drawPath(path, reviewPaint)
        reviewPaint.style = Paint.Style.FILL
        canvas.drawText(
            countText,
            (start + height / 9 + end) / 2,
            baseLine - height * 0.23f,
            reviewPaint
        )
    }


}
