package io.legado.app.ui.book.read.page.provider

import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.PageAnim
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookContent
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getBookSource
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.ReviewColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.utils.ImageStyleParser
import io.legado.app.utils.dpToPx
import io.legado.app.utils.fastSum
import io.legado.app.utils.getTextWidthsCompat
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.LinkedList
import java.util.regex.Matcher
import kotlin.math.roundToInt
import io.legado.app.data.entities.getPageAnim

class TextChapterLayout(
    scope: CoroutineScope,
    private val textChapter: TextChapter,
    private val textPages: MutableList<TextPage>,
    private val book: Book,
    private val bookContent: BookContent,
) {

    @Volatile
    private var listener: LayoutProgressListener? = textChapter

    private val paddingLeft = ChapterProvider.paddingLeft
    private val paddingTop = ChapterProvider.paddingTop

    private val titlePaint = ChapterProvider.titlePaint
    private val titlePaintTextHeight = ChapterProvider.titlePaintTextHeight
    private val titlePaintFontMetrics = ChapterProvider.titlePaintFontMetrics

    private val contentPaint = ChapterProvider.contentPaint
    private val contentPaintTextHeight = ChapterProvider.contentPaintTextHeight
    private val contentPaintFontMetrics = ChapterProvider.contentPaintFontMetrics

    private val titleTopSpacing = ChapterProvider.titleTopSpacing
    private val titleBottomSpacing = ChapterProvider.titleBottomSpacing
    private val lineSpacingExtra = ChapterProvider.lineSpacingExtra
    private val paragraphSpacing = ChapterProvider.paragraphSpacing

    private val visibleHeight = ChapterProvider.visibleHeight
    private val visibleWidth = ChapterProvider.visibleWidth

    private val viewWidth = ChapterProvider.viewWidth
    private val doublePage = ChapterProvider.doublePage
    private val indentCharWidth = ChapterProvider.indentCharWidth
    private val stringBuilder = StringBuilder()

    private val paragraphIndent = ReadBookConfig.paragraphIndent
    private val titleMode = ReadBookConfig.titleMode
    private val useZhLayout = ReadBookConfig.useZhLayout
    private val isMiddleTitle = ReadBookConfig.isMiddleTitle
    private val textFullJustify = ReadBookConfig.textFullJustify
    private val pageAnim = book.getPageAnim()
    private val reviewTitleOffset = if (titleMode != 2 || bookChapter.isVolume || bookContent.textList.isEmpty()) {
        1
    } else {
        0
    }

    private var pendingTextPage = TextPage()

    private val bookChapter inline get() = textChapter.chapter
    private val displayTitle inline get() = textChapter.title
    private val chaptersSize inline get() = textChapter.chaptersSize

    private var durY = 0f
    private var absStartX = paddingLeft
    private var floatArray = FloatArray(128)

    private var isCompleted = false
    private val job: Coroutine<*>

    var exception: Throwable? = null

    var channel = Channel<TextPage>(Channel.UNLIMITED)


    init {
        job = Coroutine.async(
            scope,
            start = CoroutineStart.LAZY,
            executeContext = IO
        ) {
            launch {
                val bookSource = book.getBookSource() ?: return@launch
                BookHelp.saveImages(bookSource, book, bookChapter, bookContent.toString())
            }
            getTextChapter(book, bookChapter, displayTitle, bookContent)
        }.onError {
            exception = it
            onException(it)
        }.onCancel {
            channel.cancel()
        }.onFinally {
            isCompleted = true
        }
        job.start()
    }

    fun setProgressListener(l: LayoutProgressListener?) {
        try {
            if (isCompleted) {
                // no op
            } else if (exception != null) {
                l?.onLayoutException(exception!!)
            } else {
                listener = l
            }
        } catch (e: Exception) {
            e.printStackTrace()
            AppLog.put("调用布局进度监听回调出错\n${e.localizedMessage}", e)
        }
    }

    fun cancel() {
        job.cancel()
        listener = null
    }

    private fun onPageCompleted() {
        val textPage = pendingTextPage
        textPage.index = textPages.size
        textPage.chapterIndex = bookChapter.index
        textPage.chapterSize = chaptersSize
        textPage.title = displayTitle
        textPage.doublePage = doublePage
        textPage.paddingTop = paddingTop
        textPage.isCompleted = true
        textPage.textChapter = textChapter
        textPage.upLinesPosition()
        textPage.upRenderHeight()
        textPages.add(textPage)
        channel.trySend(textPage)
        try {
            listener?.onLayoutPageCompleted(textPages.lastIndex, textPage)
        } catch (e: Exception) {
            e.printStackTrace()
            AppLog.put("调用布局进度监听回调出错\n${e.localizedMessage}", e)
        }
    }

    private fun onCompleted() {
        channel.close()
        try {
            listener?.onLayoutCompleted()
        } catch (e: Exception) {
            e.printStackTrace()
            AppLog.put("调用布局进度监听回调出错\n${e.localizedMessage}", e)
        } finally {
            listener = null
        }
    }

    private fun onException(e: Throwable) {
        channel.close(e)
        if (e is CancellationException) {
            listener = null
            return
        }
        try {
            listener?.onLayoutException(e)
        } catch (e: Exception) {
            e.printStackTrace()
            AppLog.put("调用布局进度监听回调出错\n${e.localizedMessage}", e)
        } finally {
            listener = null
        }
    }

    /**
     * 获取拆分完的章节数据
     */
    private suspend fun getTextChapter(
        book: Book,
        bookChapter: BookChapter,
        displayTitle: String,
        bookContent: BookContent,
    ) {
        val contents = bookContent.textList
        val imageStyle = book.getImageStyle()
        val isSingleImageStyle = imageStyle.equals(Book.imgStyleSingle, true)
        val isTextImageStyle = imageStyle.equals(Book.imgStyleText, true)

        if (titleMode != 2 || bookChapter.isVolume || contents.isEmpty()) {
            //标题非隐藏
            displayTitle.splitNotBlank("\n").forEach { text ->
                setTypeText(
                    book,
                    text,
                    titlePaint,
                    titlePaintTextHeight,
                    titlePaintFontMetrics,
                    imageStyle,
                    isTitle = true,
                    emptyContent = contents.isEmpty(),
                    isVolumeTitle = bookChapter.isVolume
                )
                pendingTextPage.lines.last().isParagraphEnd = true
                stringBuilder.append("\n")
            }
            durY += titleBottomSpacing

            // 如果是单图模式且当前页有内容，强制分页
            if (isSingleImageStyle && pendingTextPage.lines.isNotEmpty() && contents.isNotEmpty()) {
                prepareNextPageIfNeed()
            }
        }

        val sb = StringBuffer()
        var isSetTypedImage = false
        contents.forEach { content ->
            currentCoroutineContext().ensureActive()
            //单图内联样式:行内(TEXT)图片替换为占位符,其余图片保留块级标签
            var text = content.replace(ChapterProvider.srcReplaceChar, "▣")
            val srcList = LinkedList<String>()
            sb.setLength(0)
            val styleMatcher = AppPattern.imgPattern.matcher(text)
            while (styleMatcher.find()) {
                val src = styleMatcher.group(1) ?: continue
                val inlineStyle = ImageStyleParser.ImageStyle.fromSrc(src)
                val isInline = inlineStyle == ImageStyleParser.ImageStyle.Text
                        || (isTextImageStyle && inlineStyle == null)
                if (isInline) {
                    srcList.add(src)
                    styleMatcher.appendReplacement(sb, ChapterProvider.srcReplaceChar)
                } else {
                    styleMatcher.appendReplacement(
                        sb,
                        Matcher.quoteReplacement(styleMatcher.group())
                    )
                }
            }
            styleMatcher.appendTail(sb)
            text = sb.toString()

            if (isSingleImageStyle && isSetTypedImage) {
                isSetTypedImage = false
                prepareNextPageIfNeed()
            }
            var start = 0
            if (text.contains("<img")) {
                val matcher = AppPattern.imgPattern.matcher(text)
                while (matcher.find()) {
                    currentCoroutineContext().ensureActive()
                    val textSegment = text.substring(start, matcher.start())
                    if (textSegment.isNotBlank()) {
                        setTypeText(
                            book,
                            textSegment,
                            contentPaint,
                            contentPaintTextHeight,
                            contentPaintFontMetrics,
                            imageStyle,
                            isFirstLine = start == 0,
                            srcList = srcList
                        )
                    }
                    setTypeImage(
                        book,
                        matcher.group(1)!!,
                        contentPaintTextHeight,
                        imageStyle
                    )
                    isSetTypedImage = true
                    start = matcher.end()
                }
            }
            if (start < text.length) {
                if (isSingleImageStyle && isSetTypedImage) {
                    isSetTypedImage = false
                    prepareNextPageIfNeed()
                }
                val textSegment = text.substring(start, text.length)
                if (textSegment.isNotBlank()) {
                    setTypeText(
                        book,
                        textSegment,
                        contentPaint,
                        contentPaintTextHeight,
                        contentPaintFontMetrics,
                        imageStyle,
                        isFirstLine = start == 0,
                        srcList = srcList
                    )
                }
            }
            pendingTextPage.lines.last().isParagraphEnd = true
            ChapterProvider.appendReviewColumnIfNeeded(
                pendingTextPage.lines.last(),
                reviewTitleOffset,
                chapterIndex = bookChapter.index
            )
            stringBuilder.append("\n")
        }
        val textPage = pendingTextPage
        val endPadding = 20.dpToPx()
        val durYPadding = durY + endPadding
        if (textPage.height < durYPadding) {
            textPage.height = durYPadding
        } else {
            textPage.height += endPadding
        }
        textPage.text = stringBuilder.toString()
        currentCoroutineContext().ensureActive()
        onPageCompleted()
        onCompleted()
    }

    /**
     * 排版图片
     */
    private suspend fun setTypeImage(
        book: Book,
        src: String,
        textHeight: Float,
        imageStyle: String?,
    ) {
        val size = ImageProvider.getImageSize(book, src, ReadBook.bookSource)
        if (size.width > 0 && size.height > 0) {
            prepareNextPageIfNeed(durY)
            var height = size.height
            var width = size.width
            val inlineStyle = ImageStyleParser.ImageStyle.fromSrc(src)
            if (inlineStyle is ImageStyleParser.ImageStyle.Size) {
                //显式尺寸:只给宽或高时,另一维按图片原始比例补全
                val explicitWidth = inlineStyle.widthPercent?.let { visibleWidth * it / 100f }
                    ?: inlineStyle.widthPx?.dpToPx()
                val explicitHeight = inlineStyle.heightPercent?.let { visibleHeight * it / 100f }
                    ?: inlineStyle.heightPx?.dpToPx()
                when {
                    explicitWidth != null && explicitWidth > 0f -> {
                        width = explicitWidth.toInt().coerceAtLeast(1)
                        height = if (explicitHeight != null && explicitHeight > 0f) {
                            explicitHeight.toInt().coerceAtLeast(1)
                        } else {
                            size.height * width / size.width
                        }
                    }

                    explicitHeight != null && explicitHeight > 0f -> {
                        height = explicitHeight.toInt().coerceAtLeast(1)
                        width = size.width * height / size.height
                    }
                }
                //与默认样式一致:超出屏幕等比缩小并翻页
                if (width > visibleWidth) {
                    height = height * visibleWidth / width
                    width = visibleWidth
                }
                if (height > visibleHeight) {
                    width = width * visibleHeight / height
                    height = visibleHeight
                }
                prepareNextPageIfNeed(durY + height)
            } else {
                when ((inlineStyle?.keyword ?: imageStyle)?.uppercase()) {
                    Book.imgStyleFull -> {
                        width = visibleWidth
                        height = size.height * visibleWidth / size.width
                        if (pageAnim != PageAnim.scrollPageAnim && height > visibleHeight - durY) {
                            if (height > visibleHeight) {
                                width = width * visibleHeight / height
                                height = visibleHeight
                            }
                            prepareNextPageIfNeed(durY + height)
                        }
                    }

                    Book.imgStyleSingle -> {
                        width = visibleWidth
                        height = size.height * visibleWidth / size.width
                        if (height > visibleHeight) {
                            width = width * visibleHeight / height
                            height = visibleHeight
                        }
                        if (durY > 0f) {
                            prepareNextPageIfNeed()
                        }

                        // 图片竖直方向居中：调整 Y 坐标
                        if (height < visibleHeight) {
                            val adjustHeight = (visibleHeight - height) / 2f
                            durY = adjustHeight // 将 Y 坐标设置为居中位置
                        }
                    }

                    else -> {
                        if (size.width > visibleWidth) {
                            height = size.height * visibleWidth / size.width
                            width = visibleWidth
                        }
                        if (height > visibleHeight) {
                            width = width * visibleHeight / height
                            height = visibleHeight
                        }
                        prepareNextPageIfNeed(durY + height)
                    }
                }
            }
            val textLine = TextLine(isImage = true, reviewTitleOffset = reviewTitleOffset)
            textLine.text = " "
            textLine.lineTop = durY + paddingTop
            durY += height
            textLine.lineBottom = durY + paddingTop
            val (start, end) = if (visibleWidth > width) {
                val adjustWidth = (visibleWidth - width) / 2f
                Pair(adjustWidth, adjustWidth + width)
            } else {
                Pair(0f, width.toFloat())
            }
            textLine.addColumn(
                ImageColumn(start = absStartX + start, end = absStartX + end, src = src)
            )
            calcTextLinePosition(textPages, textLine, stringBuilder.length)
            stringBuilder.append(" ") // 确保翻页时索引计算正确
            pendingTextPage.addLine(textLine)
        }
        durY += textHeight * paragraphSpacing / 10f
    }

    /**
     * 排版文字
     */
    @Suppress("DEPRECATION")
    private suspend fun setTypeText(
        book: Book,
        text: String,
        textPaint: TextPaint,
        textHeight: Float,
        fontMetrics: Paint.FontMetrics,
        imageStyle: String?,
        isTitle: Boolean = false,
        isFirstLine: Boolean = true,
        emptyContent: Boolean = false,
        isVolumeTitle: Boolean = false,
        srcList: LinkedList<String>? = null
    ) {
        val widthsArray = allocateFloatArray(text.length)
        textPaint.getTextWidthsCompat(text, widthsArray)
        val layout = if (useZhLayout) {
            val (words, widths) = measureTextSplit(text, widthsArray)
            val indentSize = if (isFirstLine) paragraphIndent.length else 0
            ZhLayout(text, textPaint, visibleWidth, words, widths, indentSize)
        } else {
            StaticLayout(text, textPaint, visibleWidth, Layout.Alignment.ALIGN_NORMAL, 0f, 0f, true)
        }
        durY = when {
            //标题y轴居中
            emptyContent && textPages.isEmpty() -> {
                val textPage = pendingTextPage
                if (textPage.lineSize == 0) {
                    val ty = (visibleHeight - layout.lineCount * textHeight) / 2
                    if (ty > titleTopSpacing) ty else titleTopSpacing.toFloat()
                } else {
                    var textLayoutHeight = layout.lineCount * textHeight
                    val fistLine = textPage.getLine(0)
                    if (fistLine.lineTop < textLayoutHeight + titleTopSpacing) {
                        textLayoutHeight = fistLine.lineTop - titleTopSpacing
                    }
                    textPage.lines.forEach {
                        it.lineTop -= textLayoutHeight
                        it.lineBase -= textLayoutHeight
                        it.lineBottom -= textLayoutHeight
                    }
                    durY - textLayoutHeight
                }
            }

            isTitle && textPages.isEmpty() && pendingTextPage.lines.isEmpty() -> {
                when (imageStyle?.uppercase()) {
                    Book.imgStyleSingle -> {
                        val ty = (visibleHeight - layout.lineCount * textHeight) / 2
                        if (ty > titleTopSpacing) ty else titleTopSpacing.toFloat()
                    }

                    else -> durY + titleTopSpacing
                }
            }

            else -> durY
        }
        for (lineIndex in 0 until layout.lineCount) {
            val textLine = TextLine(isTitle = isTitle, reviewTitleOffset = reviewTitleOffset)
            prepareNextPageIfNeed(durY + textHeight)
            val lineStart = layout.getLineStart(lineIndex)
            val lineEnd = layout.getLineEnd(lineIndex)
            val lineText = text.substring(lineStart, lineEnd)
            val (words, widths) = measureTextSplit(lineText, widthsArray, lineStart)
            val desiredWidth = widths.fastSum()
            textLine.text = lineText
            when {
                lineIndex == 0 && layout.lineCount > 1 && !isTitle && isFirstLine -> {
                    //多行的第一行 非标题
                    addCharsToLineFirst(
                        book, absStartX, textLine, words, textPaint,
                        desiredWidth, widths, srcList
                    )
                }

                lineIndex == layout.lineCount - 1 -> {
                    //最后一行、单行
                    //标题x轴居中
                    val startX = if (
                        isTitle &&
                        (isMiddleTitle || emptyContent || isVolumeTitle
                                || imageStyle?.uppercase() == Book.imgStyleSingle)
                    ) {
                        (visibleWidth - desiredWidth) / 2
                    } else {
                        0f
                    }
                    addCharsToLineNatural(
                        book, absStartX, textLine, words,
                        startX, !isTitle && lineIndex == 0, widths, srcList
                    )
                }

                else -> {
                    if (
                        isTitle &&
                        (isMiddleTitle || emptyContent || isVolumeTitle
                                || imageStyle?.uppercase() == Book.imgStyleSingle)
                    ) {
                        //标题居中
                        val startX = (visibleWidth - desiredWidth) / 2
                        addCharsToLineNatural(
                            book, absStartX, textLine, words,
                            startX, false, widths, srcList
                        )
                    } else {
                        //中间行
                        addCharsToLineMiddle(
                            book, absStartX, textLine, words, textPaint,
                            desiredWidth, 0f, widths, srcList
                        )
                    }
                }
            }
            if (doublePage) {
                textLine.isLeftLine = absStartX < viewWidth / 2
            }
            calcTextLinePosition(textPages, textLine, stringBuilder.length)
            stringBuilder.append(lineText)
            textLine.upTopBottom(durY, textHeight, fontMetrics)
            val textPage = pendingTextPage
            textPage.addLine(textLine)
            durY += textHeight * lineSpacingExtra
            if (textPage.height < durY) {
                textPage.height = durY
            }
        }
        durY += textHeight * paragraphSpacing / 10f
    }

    private fun calcTextLinePosition(
        textPages: MutableList<TextPage>,
        textLine: TextLine,
        sbLength: Int
    ) {
        val lastLine = pendingTextPage.lines.lastOrNull { it.paragraphNum > 0 }
            ?: textPages.lastOrNull()?.lines?.lastOrNull { it.paragraphNum > 0 }
        val paragraphNum = when {
            lastLine == null -> 1
            lastLine.isParagraphEnd -> lastLine.paragraphNum + 1
            else -> lastLine.paragraphNum
        }
        textLine.paragraphNum = paragraphNum
        updateReviewCount(textLine, paragraphNum)
        textLine.chapterPosition =
            (textPages.lastOrNull()?.lines?.lastOrNull()?.run {
                chapterPosition + charSize + if (isParagraphEnd) 1 else 0
            } ?: 0) + sbLength
        textLine.pagePosition = sbLength
    }

    /**
     * 有缩进,两端对齐
     */
    private suspend fun addCharsToLineFirst(
        book: Book,
        absStartX: Int,
        textLine: TextLine,
        words: List<String>,
        textPaint: TextPaint,
        /**自然排版长度**/
        desiredWidth: Float,
        textWidths: List<Float>,
        srcList: LinkedList<String>?
    ) {
        var x = 0f
        if (!textFullJustify) {
            addCharsToLineNatural(
                book, absStartX, textLine, words,
                x, true, textWidths, srcList
            )
            return
        }
        val bodyIndent = paragraphIndent
        repeat(bodyIndent.length) {
            val x1 = x + indentCharWidth
            textLine.addColumn(
                TextColumn(
                    charData = ChapterProvider.indentChar,
                    start = absStartX + x,
                    end = absStartX + x1
                )
            )
            x = x1
            textLine.indentWidth = x
        }
        textLine.indentSize = bodyIndent.length
        if (words.size > bodyIndent.length) {
            val text1 = words.subList(bodyIndent.length, words.size)
            val textWidths1 = textWidths.subList(bodyIndent.length, textWidths.size)
            addCharsToLineMiddle(
                book, absStartX, textLine, text1, textPaint,
                desiredWidth, x, textWidths1, srcList
            )
        }
    }

    /**
     * 无缩进,两端对齐
     */
    private suspend fun addCharsToLineMiddle(
        book: Book,
        absStartX: Int,
        textLine: TextLine,
        words: List<String>,
        textPaint: TextPaint,
        /**自然排版长度**/
        desiredWidth: Float,
        /**起始x坐标**/
        startX: Float,
        textWidths: List<Float>,
        srcList: LinkedList<String>?
    ) {
        if (!textFullJustify) {
            addCharsToLineNatural(
                book, absStartX, textLine, words,
                startX, false, textWidths, srcList
            )
            return
        }
        val residualWidth = visibleWidth - desiredWidth
        val spaceSize = words.count { it == " " }
        textLine.startX = absStartX + startX
        if (spaceSize > 1) {
            val d = residualWidth / spaceSize
            textLine.wordSpacing = d
            var x = startX
            for (index in words.indices) {
                val char = words[index]
                val cw = textWidths[index]
                val x1 = if (char == " ") {
                    if (index != words.lastIndex) (x + cw + d) else (x + cw)
                } else {
                    (x + cw)
                }
                addCharToLine(
                    book, absStartX, textLine, char,
                    x, x1, index + 1 == words.size, srcList
                )
                x = x1
            }
        } else {
            val gapCount: Int = words.lastIndex
            val d = if (gapCount > 0) residualWidth / gapCount else 0f
            textLine.extraLetterSpacingOffsetX = -d / 2
            textLine.extraLetterSpacing = d / textPaint.textSize
            var x = startX
            for (index in words.indices) {
                val char = words[index]
                val cw = textWidths[index]
                val x1 = if (index != words.lastIndex) (x + cw + d) else (x + cw)
                addCharToLine(
                    book, absStartX, textLine, char,
                    x, x1, index + 1 == words.size, srcList
                )
                x = x1
            }
        }
        exceed(absStartX, textLine, words)
    }

    /**
     * 自然排列
     */
    private suspend fun addCharsToLineNatural(
        book: Book,
        absStartX: Int,
        textLine: TextLine,
        words: List<String>,
        startX: Float,
        hasIndent: Boolean,
        textWidths: List<Float>,
        srcList: LinkedList<String>?
    ) {
        val indentLength = paragraphIndent.length
        var x = startX
        textLine.startX = absStartX + startX
        for (index in words.indices) {
            val char = words[index]
            val cw = textWidths[index]
            val x1 = x + cw
            addCharToLine(book, absStartX, textLine, char, x, x1, index + 1 == words.size, srcList)
            x = x1
            if (hasIndent && index == indentLength - 1) {
                textLine.indentWidth = x
            }
        }
        exceed(absStartX, textLine, words)
    }

    /**
     * 添加字符
     */
    private suspend fun addCharToLine(
        book: Book,
        absStartX: Int,
        textLine: TextLine,
        char: String,
        xStart: Float,
        xEnd: Float,
        isLineEnd: Boolean,
        srcList: LinkedList<String>?
    ) {
        val column = when {
            srcList != null && char == ChapterProvider.srcReplaceChar -> {
                val src = srcList.removeFirst()
                ImageProvider.cacheImage(book, src, ReadBook.bookSource)
                ImageColumn(
                    start = absStartX + xStart,
                    end = absStartX + xEnd,
                    src = src
                )
            }

            isLineEnd && char == ChapterProvider.reviewChar -> {
                val width = ChapterProvider.getReviewWidth(textLine.isTitle)
                ReviewColumn(
                    start = absStartX + xStart,
                    end = absStartX + xStart + width,
                    count = 0
                )
            }

            else -> {
                TextColumn(
                    start = absStartX + xStart,
                    end = absStartX + xEnd,
                    charData = char
                )
            }
        }
        textLine.addColumn(column)
    }

    private fun updateReviewCount(textLine: TextLine, paragraphNum: Int) {
        val count = ChapterProvider.getReviewCount(
            paragraphNum,
            textLine.isTitle,
            reviewTitleOffset,
            chapterIndex = bookChapter.index
        )
        // count == -1 是可写的空评论气泡，也要落到列上。
        if (count == 0) return
        textLine.columns.forEach { column ->
            if (column is ReviewColumn) {
                column.count = count
            }
        }
    }

    /**
     * 超出边界处理
     */
    private fun exceed(absStartX: Int, textLine: TextLine, words: List<String>) {
        var size = words.size
        if (size < 2) return
        val visibleEnd = absStartX + visibleWidth
        val columns = textLine.columns
        var offset = 0
        val endColumn = if (words.last() == " ") {
            size--
            offset++
            columns[columns.lastIndex - 1]
        } else {
            columns.last()
        }
        val endX = endColumn.end.roundToInt()
        if (endX > visibleEnd) {
            textLine.exceed = true
            val cc = (endX - visibleEnd) / size
            for (i in 0..<size) {
                textLine.getColumnReverseAt(i, offset).let {
                    val py = cc * (size - i)
                    it.start -= py
                    it.end -= py
                }
            }
        }
    }

    private suspend fun prepareNextPageIfNeed(requestHeight: Float = -1f) {
        if (requestHeight > visibleHeight || requestHeight == -1f) {
            val textPage = pendingTextPage
            // 双页的 durY 不正确，可能会小于实际高度
            if (textPage.height < durY) {
                textPage.height = durY
            }
            if (doublePage && absStartX < viewWidth / 2) {
                //当前页面左列结束
                textPage.leftLineSize = textPage.lineSize
                absStartX = viewWidth / 2 + paddingLeft
            } else {
                //当前页面结束,设置各种值
                if (textPage.leftLineSize == 0) {
                    textPage.leftLineSize = textPage.lineSize
                }
                textPage.text = stringBuilder.toString()
                currentCoroutineContext().ensureActive()
                onPageCompleted()
                //新建页面
                pendingTextPage = TextPage()
                stringBuilder.clear()
                absStartX = paddingLeft
            }
            durY = 0f
        }
    }

    private fun allocateFloatArray(size: Int): FloatArray {
        if (size > floatArray.size) {
            floatArray = FloatArray(size)
        }
        return floatArray
    }

    private fun measureTextSplit(
        text: String,
        widthsArray: FloatArray,
        start: Int = 0
    ): Pair<ArrayList<String>, ArrayList<Float>> {
        val length = text.length
        var clusterCount = 0
        for (i in start..<start + length) {
            if (widthsArray[i] > 0) clusterCount++
        }
        val widths = ArrayList<Float>(clusterCount)
        val stringList = ArrayList<String>(clusterCount)
        var i = 0
        while (i < length) {
            val clusterBaseIndex = i++
            widths.add(widthsArray[start + clusterBaseIndex])
            while (i < length && widthsArray[start + i] == 0f && !isZeroWidthChar(text[i])) {
                i++
            }
            stringList.add(text.substring(clusterBaseIndex, i))
        }
        return stringList to widths
    }

    private fun isZeroWidthChar(char: Char): Boolean {
        val code = char.code
        return code == 8203 || code == 8204 || code == 8205 || code == 8288
    }

}
