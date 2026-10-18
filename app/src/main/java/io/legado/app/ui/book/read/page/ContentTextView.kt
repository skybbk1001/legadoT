package io.legado.app.ui.book.read.page

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import io.legado.app.R
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.HighlightRule
import io.legado.app.help.HighlightMatcher
import io.legado.app.help.HighlightStyle
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.delegate.PageDelegate
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.TextPos
import io.legado.app.ui.book.read.page.entities.column.BaseColumn
import io.legado.app.ui.book.read.page.entities.column.ButtonColumn
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.ReviewColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.read.page.provider.TextPageFactory
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.utils.activity
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * 阅读内容视图
 */
class ContentTextView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var selectAble = AppConfig.textSelectAble
    val selectedPaint by lazy {
        Paint().apply {
            color = context.getCompatColor(R.color.btn_bg_press_2)
            style = Paint.Style.FILL
        }
    }
    private var callBack: CallBack
    private val visibleRect = ChapterProvider.visibleRect
    val selectStart = TextPos(0, -1, -1)
    private val selectEnd = TextPos(0, -1, -1)
    var textPage: TextPage = TextPage()
        private set
    var isMainView = false
    var longScreenshot = false
    var reverseStartCursor = false
    var reverseEndCursor = false

    //滚动参数
    private val pageFactory get() = callBack.pageFactory
    private val pageDelegate get() = callBack.pageDelegate
    private var pageOffset = 0
    private var autoPager: AutoPager? = null
    private var isScroll = false
    private val renderRunnable by lazy { Runnable { preRenderPage() } }

    //绘制图片的paint
    val imagePaint by lazy {
        Paint().apply {
            isAntiAlias = AppConfig.useAntiAlias
        }
    }

    init {
        callBack = activity as CallBack
    }

    /**
     * 设置内容
     */
    fun setContent(textPage: TextPage) {
        this.textPage = textPage
        upHighlight()
        // 非滑动翻页动画需要同步重绘，不然翻页可能会出现闪烁
        if (isScroll) {
            postInvalidate()
        } else {
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!isMainView) return
        ChapterProvider.upViewSize(w, h)
        textPage.format()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        autoPager?.onDraw(canvas)
        if (longScreenshot) {
            canvas.translate(0f, scrollY.toFloat())
        }
        check(!visibleRect.isEmpty) { "visibleRect 为空" }
        canvas.clipRect(visibleRect)
        drawPage(canvas)
    }

    /**
     * 绘制页面
     */
    private fun drawPage(canvas: Canvas) {
        var relativeOffset = relativeOffset(0)
        textPage.draw(this, canvas, relativeOffset)
        if (!callBack.isScroll) return
        //滚动翻页
        if (!pageFactory.hasNext()) return
        val textPage1 = relativePage(1)
        relativeOffset += textPage.height
        textPage1.draw(this, canvas, relativeOffset)
        if (!pageFactory.hasNextPlus()) return
        relativeOffset += textPage1.height
        if (relativeOffset < ChapterProvider.visibleHeight) {
            val textPage2 = relativePage(2)
            textPage2.draw(this, canvas, relativeOffset)
        }
    }

    override fun computeScroll() {
        pageDelegate?.computeScroll()
        autoPager?.computeOffset()
    }

    /**
     * 滚动事件
     * pageOffset 向上滚动 减小 向下滚动 增大
     * pageOffset 范围 0 ~ -textPage.height 大于0为上一页，小于-textPage.height为下一页
     * 以内容显示区域顶端为界，pageOffset的绝对值为textPage上方的高度
     * pageOffset + textPage.height 为 textPage 下方的高度
     */
    fun scroll(mOffset: Int) {
        pageOffset += mOffset
        if (longScreenshot) {
            scrollY += -mOffset
        }
        if (!pageFactory.hasPrev() && pageOffset > 0) {
            pageOffset = 0
            pageDelegate?.abortAnim()
        } else if (!pageFactory.hasNext()
            && pageOffset < 0
            && pageOffset + textPage.height < ChapterProvider.visibleHeight
        ) {
            val offset = (ChapterProvider.visibleHeight - textPage.height).toInt()
            pageOffset = min(0, offset)
            pageDelegate?.abortAnim()
        } else if (pageOffset > 0) {
            if (pageFactory.moveToPrev(true)) {
                pageOffset -= textPage.height.toInt()
            } else {
                pageOffset = 0
                pageDelegate?.abortAnim()
            }
        } else if (pageOffset < -textPage.height) {
            val height = textPage.height
            if (pageFactory.moveToNext(upContent = true)) {
                pageOffset += height.toInt()
            } else {
                pageOffset = -height.toInt()
                pageDelegate?.abortAnim()
            }
        }
        postInvalidate()
    }

    fun submitRenderTask() {
        renderThread.submit(renderRunnable)
    }

    private fun preRenderPage() {
        val view = this
        var invalidate = false
        pageFactory.run {
            if (hasPrev() && prevPage.render(view)) {
                invalidate = true
            }
            if (curPage.render(view)) {
                invalidate = true
            }
            if (hasNext() && nextPage.render(view) && callBack.isScroll) {
                invalidate = true
            }
            if (hasNextPlus() && nextPlusPage.render(view) && callBack.isScroll
                && relativeOffset(2) < ChapterProvider.visibleHeight
            ) {
                invalidate = true
            }
            if (invalidate) {
                postInvalidate()
                pageDelegate?.postInvalidate()
            }
        }
    }

    /**
     * 重置滚动位置
     */
    fun resetPageOffset() {
        pageOffset = 0
    }

    /**
     * 仅处理「点中段评气泡」：选中态下点气泡时用，避免被取消选择的默认流程吃掉。
     */
    fun clickReviewColumn(x: Float, y: Float): Boolean {
        var handled = false
        touch(x, y) { _, _, _, textLine, column ->
            if (column is ReviewColumn) {
                callBack.onReviewClick(
                    if (column.reviewId != 0) column.reviewId else resolveReviewId(textLine),
                    column.count
                )
                handled = true
            }
        }
        return handled
    }

    /**
     * 长按
     */
    fun longPress(
        x: Float,
        y: Float,
        select: (textPos: TextPos) -> Unit,
    ) {
        touch(x, y) { relativeOffset, textPos, textPage, textLine, column ->
            when (column) {
                is ImageColumn -> callBack.onImageLongPress(x, y, column.src)
                is TextColumn -> {
                    if (AppConfig.highlightActionByLongPress &&
                        dispatchHighlightAction(relativeOffset, textPos, textPage, textLine, column)
                    ) return@touch
                    if (!selectAble) return@touch
                    column.selected = true
                    select(textPos)
                }
            }
        }
    }

    /**
     * 单击
     * @return true:已处理, false:未处理
     */
    @Suppress("UNUSED_ANONYMOUS_PARAMETER")
    fun click(x: Float, y: Float): Boolean {
        var handled = false
        touch(x, y) { relativeOffset, textPos, textPage, textLine, column ->
            when (column) {
                is ButtonColumn -> {
                    context.toastOnUi("Button Pressed!")
                    handled = true
                }

                is ReviewColumn -> {
                    val reviewId = if (column.reviewId != 0) column.reviewId else resolveReviewId(textLine)
                    callBack.onReviewClick(reviewId, column.count)
                    handled = true
                }

                is ImageColumn -> {
                    if (callBack.onImageClick(column.src)) {
                        handled = true
                    } else if (AppConfig.previewImageByClick) {
                        activity?.showDialogFragment(PhotoDialog(column.src))
                        handled = true
                    }
                }

                is TextColumn -> {
                    if (!AppConfig.highlightActionByLongPress &&
                        dispatchHighlightAction(relativeOffset, textPos, textPage, textLine, column)
                    ) {
                        handled = true
                    }
                }
            }
        }
        return handled
    }

    /**
     * 选择文字
     */
    fun selectText(
        x: Float,
        y: Float,
        select: (textPos: TextPos) -> Unit,
    ) {
        touchRough(x, y) { _, textPos, _, _, column ->
            if (column is TextColumn) {
                column.selected = true
                select(textPos)
            }
        }
    }

    /**
     * 开始选择符移动
     */
    fun selectStartMove(x: Float, y: Float) {
        touchRough(x, y) { _, textPos, _, _, _ ->
            if (selectStart.compare(textPos) == 0) {
                return@touchRough
            }
            if (textPos.compare(selectEnd) <= 0) {
                selectStartMoveIndex(textPos)
            } else {
                touchRough(x - 2 * cursorWidth, y) { _, textPos, _, _, _ ->
                    if (textPos.compare(selectEnd) > 0) {
                        reverseStartCursor = true
                        reverseEndCursor = false
                        selectEnd.columnIndex++
                        selectStartMoveIndex(selectEnd)
                        selectEndMoveIndex(textPos)
                    }
                }
            }
        }
    }

    /**
     * 结束选择符移动
     */
    fun selectEndMove(x: Float, y: Float) {
        touchRough(x, y) { _, textPos, _, _, _ ->
            if (textPos.compare(selectEnd) == 0) {
                return@touchRough
            }
            if (textPos.compare(selectStart) >= 0) {
                selectEndMoveIndex(textPos)
            } else {
                touchRough(x + 2 * cursorWidth, y) { _, textPos, _, _, _ ->
                    if (textPos.compare(selectStart) < 0) {
                        reverseEndCursor = true
                        reverseStartCursor = false
                        selectStart.columnIndex--
                        selectEndMoveIndex(selectStart)
                        selectStartMoveIndex(textPos)
                    }
                }
            }
        }
    }

    /**
     * 触碰位置信息
     * @param touched 回调
     */
    private fun touch(
        x: Float,
        y: Float,
        touched: (
            relativeOffset: Float,
            textPos: TextPos,
            textPage: TextPage,
            textLine: TextLine,
            column: BaseColumn
        ) -> Unit
    ) {
        if (!visibleRect.contains(x, y)) return
        var relativeOffset: Float
        for (relativePos in 0..2) {
            relativeOffset = relativeOffset(relativePos)
            if (relativePos > 0) {
                //滚动翻页
                if (!callBack.isScroll) return
                if (relativeOffset >= ChapterProvider.visibleHeight) return
            }
            val textPage = relativePage(relativePos)
            for ((lineIndex, textLine) in textPage.lines.withIndex()) {
                if (textLine.isTouch(x, y, relativeOffset)) {
                    val reviewIndex = textLine.columns.indexOfFirst {
                        it is ReviewColumn && it.isTouch(x)
                    }
                    if (reviewIndex >= 0) {
                        val reviewColumn = textLine.columns[reviewIndex]
                        touched.invoke(
                            relativeOffset,
                            TextPos(relativePos, lineIndex, reviewIndex),
                            textPage, textLine, reviewColumn
                        )
                        return
                    }
                    for ((charIndex, textColumn) in textLine.columns.withIndex()) {
                        if (textColumn.isTouch(x)) {
                            touched.invoke(
                                relativeOffset,
                                TextPos(relativePos, lineIndex, charIndex),
                                textPage, textLine, textColumn
                            )
                            return
                        }
                    }
                    return
                }
            }
        }
    }

    /**
     * 触碰位置信息
     * 文本选择专用
     * @param touched 回调
     */
    private fun touchRough(
        x: Float,
        y: Float,
        touched: (
            relativeOffset: Float,
            textPos: TextPos,
            textPage: TextPage,
            textLine: TextLine,
            column: BaseColumn
        ) -> Unit
    ) {
        var relativeOffset: Float
        for (relativePos in 0..2) {
            relativeOffset = relativeOffset(relativePos)
            if (relativePos > 0) {
                //滚动翻页
                if (!callBack.isScroll) return
                if (relativeOffset >= ChapterProvider.visibleHeight) return
            }
            val textPage = relativePage(relativePos)
            for (lineIndex in textPage.lines.indices) {
                val textLine = textPage.getLine(lineIndex)
                if (textLine.isTouchY(y, relativeOffset)) {
                    if (textPage.doublePage) {
                        val halfWidth = width / 2
                        if (textLine.isLeftLine && x > halfWidth) {
                            continue
                        }
                        if (!textLine.isLeftLine && x < halfWidth) {
                            continue
                        }
                    }
                    val columns = textLine.columns
                    for (charIndex in columns.indices) {
                        val textColumn = columns[charIndex]
                        if (textColumn.isTouch(x)) {
                            touched.invoke(
                                relativeOffset,
                                TextPos(relativePos, lineIndex, charIndex),
                                textPage, textLine, textColumn
                            )
                            return
                        }
                    }
                    val isLast = columns.first().start < x
                    val charIndex = if (isLast) columns.lastIndex + 1 else -1
                    val textColumn = if (isLast) columns.last() else columns.first()
                    touched.invoke(
                        relativeOffset,
                        TextPos(relativePos, lineIndex, charIndex),
                        textPage, textLine, textColumn
                    )
                    return
                }
            }
        }
    }

    fun getCurVisiblePage(): TextPage {
        val visiblePage = TextPage()
        var relativeOffset: Float
        for (relativePos in 0..2) {
            relativeOffset = relativeOffset(relativePos)
            if (relativePos > 0) {
                //滚动翻页
                if (!callBack.isScroll) break
                if (relativeOffset >= ChapterProvider.visibleHeight) break
            }
            val textPage = relativePage(relativePos)
            val lines = textPage.lines
            for (i in lines.indices) {
                val textLine = lines[i]
                if (textLine.isVisible(relativeOffset)) {
                    val visibleLine = textLine.copy().apply {
                        lineTop += relativeOffset
                        lineBottom += relativeOffset
                    }
                    visiblePage.addLine(visibleLine)
                }
            }
        }
        return visiblePage
    }

    fun getReadAloudPos(): Pair<Int, TextLine>? {
        var relativeOffset: Float
        for (relativePos in 0..2) {
            relativeOffset = relativeOffset(relativePos)
            if (relativePos > 0) {
                //滚动翻页
                if (!callBack.isScroll) break
                if (relativeOffset >= ChapterProvider.visibleHeight) break
            }
            val textPage = relativePage(relativePos)
            val lines = textPage.lines
            for (i in lines.indices) {
                val textLine = lines[i]
                if (textLine.isVisible(relativeOffset)) {
                    val visibleLine = textLine.copy().apply {
                        lineTop += relativeOffset
                        lineBottom += relativeOffset
                    }
                    return textPage.chapterIndex to visibleLine
                }
            }
        }
        return null
    }

    /**
     * 选择开始文字
     */
    fun selectStartMoveIndex(
        relativePagePos: Int,
        lineIndex: Int,
        charIndex: Int,
    ) {
        selectStart.relativePagePos = relativePagePos
        selectStart.lineIndex = lineIndex
        selectStart.columnIndex = max(0, charIndex)
        val textLine = relativePage(relativePagePos).getLine(lineIndex)
        val textColumn = textLine.getColumn(charIndex)
        upSelectedStart(
            if (charIndex < textLine.columns.size) textColumn.start else textColumn.end,
            textLine.lineBottom + relativeOffset(relativePagePos),
            textLine.lineTop + relativeOffset(relativePagePos)
        )
        upSelectChars()
    }

    fun selectStartMoveIndex(textPos: TextPos) = textPos.run {
        selectStartMoveIndex(relativePagePos, lineIndex, columnIndex)
    }

    /**
     * 选择结束文字
     */
    fun selectEndMoveIndex(
        relativePage: Int,
        lineIndex: Int,
        charIndex: Int,
    ) {
        selectEnd.relativePagePos = relativePage
        selectEnd.lineIndex = lineIndex
        val textLine = relativePage(relativePage).getLine(lineIndex)
        selectEnd.columnIndex = min(charIndex, textLine.columns.lastIndex)
        val textColumn = textLine.getColumn(charIndex)
        upSelectedEnd(
            if (charIndex > -1) textColumn.end else textColumn.start,
            textLine.lineBottom + relativeOffset(relativePage)
        )
        upSelectChars()
    }

    fun selectEndMoveIndex(textPos: TextPos) = textPos.run {
        selectEndMoveIndex(relativePagePos, lineIndex, columnIndex)
    }

    private fun upSelectChars() {
        if (!selectStart.isSelected() && !selectEnd.isSelected()) {
            return
        }
        val last = if (callBack.isScroll) 2 else 0
        val textPos = TextPos(0, 0, 0)
        for (relativePos in 0..last) {
            textPos.relativePagePos = relativePos
            val textPage = relativePage(relativePos)
            for ((lineIndex, textLine) in textPage.lines.withIndex()) {
                textPos.lineIndex = lineIndex
                for ((charIndex, column) in textLine.columns.withIndex()) {
                    textPos.columnIndex = charIndex
                    if (column is TextColumn) {
                        val compareStart = textPos.compare(selectStart)
                        val compareEnd = textPos.compare(selectEnd)
                        column.selected = compareStart >= 0 && compareEnd <= 0
                        column.isSearchResult =
                            column.selected && callBack.isSelectingSearchResult
                        if (column.isSearchResult) {
                            textPage.searchResult.add(column)
                        }
                    }
                }
            }
        }
        postInvalidate()
    }

    private fun upSelectedStart(x: Float, y: Float, top: Float) {
        callBack.run {
            upSelectedStart(x, y + headerHeight, top + headerHeight)
        }
    }

    private fun upSelectedEnd(x: Float, y: Float) {
        callBack.run {
            upSelectedEnd(x, y + headerHeight)
        }
    }

    fun resetReverseCursor() {
        reverseStartCursor = false
        reverseEndCursor = false
    }

    fun cancelSelect(clearSearchResult: Boolean = false) {
        val last = if (callBack.isScroll) 2 else 0
        for (relativePos in 0..last) {
            val textPage = relativePage(relativePos)
            textPage.lines.forEach { textLine ->
                textLine.columns.forEach {
                    if (it is TextColumn) {
                        it.selected = false
                        if (clearSearchResult) {
                            it.isSearchResult = false
                            textPage.searchResult.remove(it)
                        }
                    }
                }
            }
        }
        selectStart.reset()
        selectEnd.reset()
        postInvalidate()
        callBack.onCancelSelect()
    }

    /** 仅当当前 view 内已绘制过高亮时才需要在无高亮时再跑一次清除 */
    private var hasHighlightDrawn = false

    /**
     * 应用高亮: 仿 upSelectChars, 用 HighlightMatcher 按章节位置给可见页的列设色
     */
    fun upHighlight() {
        val hasRules = ReadBook.highlightRules.isNotEmpty()
        if (ReadBook.highlights.isEmpty() && !hasRules && !hasHighlightDrawn) return
        hasHighlightDrawn = ReadBook.highlights.isNotEmpty() || hasRules
        // 用本视图自身的 isScroll, 不可用 callBack.isScroll:
        // setContent 会在 ReadView.<init> 期间被调到这里, 此刻 Activity 的 binding 仍在惰性初始化,
        // 访问 callBack.isScroll(读 binding)会重入 binding 触发无限递归(崩溃: 二次打开有高亮的书)。
        val last = if (isScroll) 2 else 0
        for (relativePos in 0..last) {
            val page = relativePage(relativePos)
            if (page.lines.isEmpty() || page.isMsgPage) continue
            val textChapter = page.getTextChapter()
            val pageBase = textChapter.getReadLength(page.index)
            val pageLen = page.lines.sumOf { it.charSize + if (it.isParagraphEnd) 1 else 0 }
            val pageEnd = pageBase + pageLen
            // 规则命中: 整章匹配(按 textChapter 缓存) → 只取落在本页坐标窗口者(高频词也不拖慢)
            val ruleRanges = ReadBook.ruleMatchesOfChapter(textChapter)
                .asSequence()
                .filter { it.start < pageEnd && it.end > pageBase }
                .map { HighlightMatcher.Range(it.start, it.end, it.style, it.applyToTitle) }
                .toList()
            // 手动高亮排在规则之后 → resolve 按列表序逐通道 merge, 手动压过规则
            // 存量偏移经 bookText 重锚, 净化删字后仍落在原文上; 原文被删者不产出 → 隐藏
            val manualRanges = ReadBook.anchoredHighlightsOfChapter(textChapter).map { (h, a) ->
                HighlightMatcher.Range(a.start, a.end, h.styleObj())
            }
            val ranges = ruleRanges + manualRanges
            val lineSpecs = page.lines.map { line ->
                HighlightMatcher.LineSpec(
                    line.charSize,
                    line.columns.map { if (it is TextColumn) it.charData.length else 0 },
                    line.isParagraphEnd,
                    line.isTitle
                )
            }
            val colors = HighlightMatcher.resolve(pageBase, lineSpecs, ranges)
            page.lines.forEachIndexed { li, line ->
                line.columns.forEachIndexed { ci, col ->
                    if (col is TextColumn) {
                        // 标题行过滤已下沉 resolve(仅 applyToTitle 规则可画, 手动划线不画)
                        col.highlightStyle = colors[li][ci]
                    }
                }
            }
        }
        postInvalidate()
    }

    fun getSelectedText(): String {
        val textPos = TextPos(0, 0, 0)
        val builder = StringBuilder()
        for (relativePos in selectStart.relativePagePos..selectEnd.relativePagePos) {
            val textPage = relativePage(relativePos)
            textPos.relativePagePos = relativePos
            textPage.lines.forEachIndexed { lineIndex, textLine ->
                textPos.lineIndex = lineIndex
                textLine.columns.forEachIndexed { charIndex, column ->
                    textPos.columnIndex = charIndex
                    val compareStart = textPos.compare(selectStart)
                    val compareEnd = textPos.compare(selectEnd)
                    if (column is TextColumn) {
                        when {
                            compareStart == -1 -> if (
                                selectStart.columnIndex == textLine.columns.size
                                && charIndex == textLine.columns.lastIndex
                            ) {
                                builder.append("\n")
                            }

                            compareEnd == 1 -> if (selectEnd.columnIndex == -1 && charIndex == 0) {
                                builder.append("\n")
                            }

                            compareStart >= 0 && compareEnd <= 0 -> {
                                builder.append(column.charData)
                                if (
                                    textLine.isParagraphEnd
                                    && charIndex == textLine.columns.lastIndex
                                    && compareEnd != 0
                                ) {
                                    builder.append("\n")
                                }
                            }
                        }
                    }
                }
            }
        }
        return builder.toString()
    }

    fun createBookmark(): Bookmark? {
        val page = relativePage(selectStart.relativePagePos)
        page.getTextChapter().let { chapter ->
            ReadBook.book?.let { book ->
                return book.createBookMark().apply {
                    chapterIndex = page.chapterIndex
                    chapterPos = chapter.getReadLength(page.index) +
                            page.getPosByLineColumn(selectStart.lineIndex, selectStart.columnIndex)
                    chapterName = chapter.title
                    bookText = getSelectedText()
                }
            }
        }
        return null
    }

    fun createHighlight(style: HighlightStyle): BookHighlight? {
        val book = ReadBook.book ?: return null
        val startPage = relativePage(selectStart.relativePagePos)
        val chapter = startPage.getTextChapter()
        val endPage = relativePage(selectEnd.relativePagePos)
        val endColumn = endPage.getLine(selectEnd.lineIndex).getColumn(selectEnd.columnIndex)
        val endLen = (endColumn as? TextColumn)?.charData?.length ?: 0
        val startPos = chapter.getReadLength(startPage.index) +
                startPage.getPosByLineColumn(selectStart.lineIndex, selectStart.columnIndex)
        val endPos = chapter.getReadLength(endPage.index) +
                endPage.getPosByLineColumn(selectEnd.lineIndex, selectEnd.columnIndex) + endLen
        return BookHighlight(
            bookName = book.name,
            bookAuthor = book.author,
            chapterIndex = startPage.chapterIndex,
            chapterPos = startPos,
            chapterPosEnd = endPos,
            chapterName = chapter.title,
            bookText = getSelectedText()
        ).apply { applyStyle(style) }
    }

    /**
     * 解析按点处的高亮/规则并弹操作浮条,手动划线优先于规则命中
     * @return true=命中并已派发
     */
    private fun dispatchHighlightAction(
        relativeOffset: Float,
        textPos: TextPos,
        textPage: TextPage,
        textLine: TextLine,
        column: TextColumn,
    ): Boolean {
        if (column.highlightStyle == null) return false
        val top = textLine.lineTop + relativeOffset + callBack.headerHeight
        val highlight = highlightAt(textPos, textPage)
        if (highlight != null) {
            callBack.onHighlightClick(highlight, column.start, top)
            return true
        }
        val rule = ruleMatchAt(textPos, textPage)
        if (rule != null) {
            callBack.onHighlightRuleClick(rule, column.start, top)
            return true
        }
        return false
    }

    private fun highlightAt(textPos: TextPos, page: TextPage): BookHighlight? {
        val chapter = page.getTextChapter()
        val pos = chapter.getReadLength(page.index) +
                page.getPosByLineColumn(textPos.lineIndex, textPos.columnIndex)
        // 命中判定用重锚后的区间, 与绘制一致
        return ReadBook.anchoredHighlightsOfChapter(chapter)
            .firstOrNull { (_, a) -> pos >= a.start && pos < a.end }
            ?.first
    }

    private fun ruleMatchAt(textPos: TextPos, page: TextPage): HighlightRule? {
        val chapter = page.getTextChapter()
        val pos = chapter.getReadLength(page.index) +
                page.getPosByLineColumn(textPos.lineIndex, textPos.columnIndex)
        val match = ReadBook.ruleMatchesOfChapter(chapter)
            .lastOrNull { pos >= it.start && pos < it.end } ?: return null // 重叠取最后命中
        return ReadBook.highlightRuleById(match.ruleId)
    }

    private fun relativeOffset(relativePos: Int): Float {
        return when (relativePos) {
            0 -> pageOffset.toFloat()
            1 -> pageOffset + textPage.height
            else -> pageOffset + textPage.height + pageFactory.nextPage.height
        }
    }

    fun relativePage(relativePos: Int): TextPage {
        return when (relativePos) {
            0 -> textPage
            1 -> pageFactory.nextPage
            else -> pageFactory.nextPlusPage
        }
    }

    fun setAutoPager(autoPager: AutoPager?) {
        this.autoPager = autoPager
    }

    fun setIsScroll(value: Boolean) {
        isScroll = value
    }

    override fun canScrollVertically(direction: Int): Boolean {
        return callBack.isScroll && pageFactory.hasNext()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                longScreenshot = true
                scrollY = 0
            }

            MotionEvent.ACTION_UP -> {
                longScreenshot = false
                scrollY = 0
            }
        }
        return callBack.onLongScreenshotTouchEvent(event)
    }

    companion object {
        private val renderThread by lazy {
            Executors.newSingleThreadExecutor {
                Thread(it, "TextPageRender")
            }
        }
        private val cursorWidth = 24.dpToPx()
    }

    interface CallBack {
        val headerHeight: Int
        val pageFactory: TextPageFactory
        val pageDelegate: PageDelegate?
        val isScroll: Boolean
        var isSelectingSearchResult: Boolean
        fun upSelectedStart(x: Float, y: Float, top: Float)
        fun upSelectedEnd(x: Float, y: Float)
        fun onImageLongPress(x: Float, y: Float, src: String)
        fun onImageClick(src: String): Boolean
        fun onReviewClick(paragraphNum: Int, count: Int)
        fun onCancelSelect()
        fun onHighlightClick(highlight: BookHighlight, x: Float, y: Float)
        fun onHighlightRuleClick(rule: HighlightRule, x: Float, y: Float)
        fun onLongScreenshotTouchEvent(event: MotionEvent): Boolean
    }

    /** 选区起点所在段落的段评入口信息；未选择或无有效段落时返回 null。 */
    fun selectedReviewTarget(): ReviewTarget? {
        if (!selectStart.isSelected()) return null
        val page = relativePage(selectStart.relativePagePos)
        val line = page.lines.getOrNull(selectStart.lineIndex) ?: return null
        val reviewId = resolveReviewId(line)
        if (reviewId <= 0) return null
        return ReviewTarget(
            chapterIndex = page.chapterIndex,
            reviewId = reviewId,
            count = ChapterProvider.getReviewCount(
                paragraphNum = line.paragraphNum,
                isTitle = line.isTitle,
                titleOffset = line.reviewTitleOffset,
                chapterIndex = page.chapterIndex
            )
        )
    }

    data class ReviewTarget(val chapterIndex: Int, val reviewId: Int, val count: Int)

    private fun resolveReviewId(textLine: TextLine): Int {
        if (textLine.isTitle) return -1
        val reviewId = textLine.paragraphNum - textLine.reviewTitleOffset
        return if (reviewId > 0) reviewId else textLine.paragraphNum
    }
}
