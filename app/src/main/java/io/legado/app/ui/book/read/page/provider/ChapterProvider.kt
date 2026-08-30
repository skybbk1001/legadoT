package io.legado.app.ui.book.read.page.provider

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Paint.FontMetrics
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.collection.LruCache
import androidx.core.os.postDelayed
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookContent
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.ReviewColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.utils.RealPathUtil
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.ImageStyleParser
import io.legado.app.utils.SvgUtils
import io.legado.app.utils.buildMainHandler
import io.legado.app.utils.dpToPx
import io.legado.app.utils.fastSum
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isPad
import io.legado.app.utils.postEvent
import io.legado.app.utils.spToPx
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.textHeight
import kotlinx.coroutines.CoroutineScope
import splitties.init.appCtx
import java.util.LinkedList
import java.util.Locale
import java.util.regex.Matcher

/**
 * 解析内容生成章节和页面
 */
@Suppress("DEPRECATION", "ConstPropertyName")
object ChapterProvider {
    //用于图片字的替换
    const val srcReplaceChar = "▩"

    //用于评论按钮的替换
    const val reviewChar = "▨"
    //段评图标SVG里的评论数量占位符
    const val reviewIconPlaceholder = "{{count}}"
    private const val reviewIconCacheMaxBytes = 1024 * 1024

    const val indentChar = "　"

    @JvmStatic
    var viewWidth = 0
        private set

    @JvmStatic
    var viewHeight = 0
        private set

    @JvmStatic
    var paddingLeft = 0
        private set

    @JvmStatic
    var paddingTop = 0
        private set

    @JvmStatic
    var paddingRight = 0
        private set

    @JvmStatic
    var paddingBottom = 0
        private set

    @JvmStatic
    var visibleWidth = 0
        private set

    @JvmStatic
    var visibleHeight = 0
        private set

    @JvmStatic
    var visibleRight = 0
        private set

    @JvmStatic
    var visibleBottom = 0
        private set

    @JvmStatic
    var lineSpacingExtra = 0f
        private set

    @JvmStatic
    var paragraphSpacing = 0
        private set

    @JvmStatic
    var titleTopSpacing = 0
        private set

    @JvmStatic
    var titleBottomSpacing = 0
        private set

    @JvmStatic
    var indentCharWidth = 0f
        private set

    @JvmStatic
    var titlePaintTextHeight = 0f
        private set

    @JvmStatic
    var contentPaintTextHeight = 0f
        private set

    @JvmStatic
    var titlePaintFontMetrics = FontMetrics()

    @JvmStatic
    var contentPaintFontMetrics = FontMetrics()

    @JvmStatic
    var typeface: Typeface? = Typeface.DEFAULT
        private set

    @JvmStatic
    var titlePaint: TextPaint = TextPaint()

    @JvmStatic
    var contentPaint: TextPaint = TextPaint()

    @JvmStatic
    var reviewPaint: TextPaint = TextPaint()


    private data class ReviewIconCacheKey(
        val templateHash: Int,
        val templateLength: Int,
        val countText: String,
        val widthPx: Int,
        val heightPx: Int,
    )

    private val reviewIconBitmapCache = object :
        LruCache<ReviewIconCacheKey, Bitmap>(reviewIconCacheMaxBytes) {

        override fun sizeOf(key: ReviewIconCacheKey, value: Bitmap): Int {
            return value.byteCount
        }

        override fun entryRemoved(
            evicted: Boolean,
            key: ReviewIconCacheKey,
            oldValue: Bitmap,
            newValue: Bitmap?,
        ) {
            if (oldValue !== newValue && !oldValue.isRecycled) {
                oldValue.recycle()
            }
        }
    }

    private var lastReviewIconTemplate: String? = null
    private var invalidReviewIconTemplate: String? = null
    private var lastReviewIconAspectTemplate: String? = null
    private var reviewIconAspectRatio: Float? = null

    @JvmStatic
    var doublePage = false
        private set

    @JvmStatic
    var visibleRect = RectF()

    private val handler by lazy {
        buildMainHandler()
    }

    private var upViewSizeRunnable: Runnable? = null

    @Volatile
    private var reviewCountProvider: ((Int, Int) -> Int)? = null
    @Volatile
    private var reviewKeyProvider: ((Int, Int) -> String?)? = null

    private const val reviewTitleOffset = 1

    init {
        upStyle()
    }

    /**
     * 获取拆分完的章节数据
     */
    suspend fun getTextChapter(
        book: Book,
        bookChapter: BookChapter,
        displayTitle: String,
        bookContent: BookContent,
        chapterSize: Int,
    ): TextChapter {
        val contents = bookContent.textList
        val chapterReviewTitleOffset = if (ReadBookConfig.titleMode != 2 || bookChapter.isVolume) {
            reviewTitleOffset
        } else {
            0
        }
        val textPages = arrayListOf<TextPage>()
        val stringBuilder = StringBuilder()
        var absStartX = paddingLeft
        var durY = 0f
        textPages.add(TextPage())
        if (ReadBookConfig.titleMode != 2 || bookChapter.isVolume) {
            //标题非隐藏
            displayTitle.splitNotBlank("\n").forEach { text ->
                setTypeText(
                    book, absStartX, durY,
                    text,
                    textPages,
                    stringBuilder,
                    titlePaint,
                    titlePaintTextHeight,
                    titlePaintFontMetrics,
                    reviewTitleOffset = chapterReviewTitleOffset,
                    isTitle = true,
                    emptyContent = contents.isEmpty(),
                    isVolumeTitle = bookChapter.isVolume
                ).let {
                    absStartX = it.first
                    durY = it.second
                }
            }
            textPages.last().lines.last().isParagraphEnd = true
            stringBuilder.append("\n")
            durY += titleBottomSpacing
        }
        contents.forEach { content ->
            //单图内联样式:行内(TEXT)图片替换为占位符,其余图片保留块级标签
            var text = content.replace(srcReplaceChar, "▣")
            val srcList = LinkedList<String>()
            val sb = StringBuffer()
            val styleMatcher = AppPattern.imgPattern.matcher(text)
            while (styleMatcher.find()) {
                val src = styleMatcher.group(1) ?: continue
                val inlineStyle = ImageStyleParser.ImageStyle.fromSrc(src)
                val isInline = inlineStyle == ImageStyleParser.ImageStyle.Text
                        || (book.getImageStyle().equals(Book.imgStyleText, true)
                        && inlineStyle == null)
                if (isInline) {
                    srcList.add(src)
                    styleMatcher.appendReplacement(sb, srcReplaceChar)
                } else {
                    styleMatcher.appendReplacement(
                        sb,
                        Matcher.quoteReplacement(styleMatcher.group())
                    )
                }
            }
            styleMatcher.appendTail(sb)
            text = sb.toString()

            val matcher = AppPattern.imgPattern.matcher(text)
            var start = 0
            while (matcher.find()) {
                val textSegment = text.substring(start, matcher.start())
                if (textSegment.isNotBlank()) {
                    setTypeText(
                        book,
                        absStartX,
                        durY,
                        textSegment,
                        textPages,
                        stringBuilder,
                        contentPaint,
                        contentPaintTextHeight,
                        contentPaintFontMetrics,
                        reviewTitleOffset = chapterReviewTitleOffset,
                        srcList = srcList
                    ).let {
                        absStartX = it.first
                        durY = it.second
                    }
                }
                setTypeImage(
                    book,
                    matcher.group(1)!!,
                    absStartX,
                    durY,
                    textPages,
                    contentPaintTextHeight,
                    stringBuilder,
                    book.getImageStyle(),
                    reviewTitleOffset = chapterReviewTitleOffset
                ).let {
                    absStartX = it.first
                    durY = it.second
                }
                start = matcher.end()
            }
            if (start < text.length) {
                val textSegment = text.substring(start, text.length)
                if (textSegment.isNotBlank()) {
                    setTypeText(
                        book, absStartX, durY,
                        textSegment,
                        textPages,
                        stringBuilder,
                        contentPaint,
                        contentPaintTextHeight,
                        contentPaintFontMetrics,
                        reviewTitleOffset = chapterReviewTitleOffset,
                        srcList = srcList
                    ).let {
                        absStartX = it.first
                        durY = it.second
                    }
                }
            }
            textPages.last().lines.last().isParagraphEnd = true
            appendReviewColumnIfNeeded(textPages.last().lines.last())
            stringBuilder.append("\n")
        }
        val textPage = textPages.last()
        val endPadding = 20.dpToPx()
        val durYPadding = durY + endPadding
        if (textPage.height < durYPadding) {
            textPage.height = durYPadding
        } else {
            textPage.height += endPadding
        }
        textPage.text = stringBuilder.toString()
        textPages.forEachIndexed { index, item ->
            item.index = index
            //item.pageSize = textPages.size
            item.chapterIndex = bookChapter.index
            item.chapterSize = chapterSize
            item.title = displayTitle
            item.doublePage = doublePage
            item.paddingTop = paddingTop
            item.upLinesPosition()
        }

        return TextChapter(
            bookChapter,
            bookChapter.index, displayTitle,
            //textPages,
            chapterSize,
            contents.isNotEmpty(),
            bookContent.sameTitleRemoved,
            bookChapter.isVip,
            bookChapter.isPay,
            bookContent.effectiveReplaceRules
        )
    }

    fun getTextChapterAsync(
        scope: CoroutineScope,
        book: Book,
        bookChapter: BookChapter,
        displayTitle: String,
        bookContent: BookContent,
        chapterSize: Int,
    ): TextChapter {

        val textChapter = TextChapter(
            bookChapter,
            bookChapter.index, displayTitle,
            chapterSize,
            bookContent.textList.isNotEmpty(),
            bookContent.sameTitleRemoved,
            bookChapter.isVip,
            bookChapter.isPay,
            bookContent.effectiveReplaceRules
        ).apply {
            createLayout(scope, book, bookContent)
        }

        return textChapter
    }

    /**
     * 排版图片
     */
    private suspend fun setTypeImage(
        book: Book,
        src: String,
        x: Int,
        y: Float,
        textPages: ArrayList<TextPage>,
        textHeight: Float,
        stringBuilder: StringBuilder,
        imageStyle: String?,
        reviewTitleOffset: Int = ChapterProvider.reviewTitleOffset,
    ): Pair<Int, Float> {
        var absStartX = x
        var durY = y
        val size = ImageProvider.getImageSize(book, src, ReadBook.bookSource)
        if (size.width > 0 && size.height > 0) {
            if (durY > visibleHeight) {
                val textPage = textPages.last()
                if (textPage.height < durY) {
                    textPage.height = durY
                }
                textPage.text = stringBuilder.toString().ifEmpty { "本页无文字内容" }
                stringBuilder.clear()
                textPages.add(TextPage())
                durY = 0f
            }
            var height = size.height
            var width = size.width
            val inlineStyle = ImageStyleParser.ImageStyle.fromSrc(src)
            when ((inlineStyle?.keyword ?: imageStyle)?.uppercase(Locale.ROOT)) {
                Book.imgStyleFull -> {
                    width = visibleWidth
                    height = size.height * visibleWidth / size.width
                }

                Book.imgStyleSingle -> {
                    width = visibleWidth
                    height = size.height * visibleWidth / size.width
                    if (height > visibleHeight) {
                        width = width * visibleHeight / height
                        height = visibleHeight
                    }
                    if (durY > 0f) {
                        val textPage = textPages.last()
                        if (doublePage && absStartX < viewWidth / 2) {
                            //当前页面左列结束
                            textPage.leftLineSize = textPage.lineSize
                            absStartX = viewWidth / 2 + paddingLeft
                        } else {
                            //当前页面结束
                            if (textPage.leftLineSize == 0) {
                                textPage.leftLineSize = textPage.lineSize
                            }
                            textPage.text = stringBuilder.toString().ifEmpty { "本页无文字内容" }
                            stringBuilder.clear()
                            textPages.add(TextPage())
                        }
                        // 双页的 durY 不正确，可能会小于实际高度
                        if (textPage.height < durY) {
                            textPage.height = durY
                        }
                        durY = 0f
                    }

                    // 图片竖直方向居中：调整 Y 坐标
                    if (height < visibleHeight) {
                        val adjustHeight = (visibleHeight - height) / 2f
                        durY = adjustHeight // 将 Y 坐标设置为居中位置
                    }
                }

                else -> {
                    if (inlineStyle is ImageStyleParser.ImageStyle.Size) {
                        //显式尺寸:只给宽或高时,另一维按图片原始比例补全
                        val explicitWidth = inlineStyle.widthPercent?.let { visibleWidth * it / 100f }
                            ?: inlineStyle.widthPx?.dpToPx()
                        val explicitHeight =
                            inlineStyle.heightPercent?.let { visibleHeight * it / 100f }
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
                    }
                    if (width > visibleWidth) {
                        height = height * visibleWidth / width
                        width = visibleWidth
                    }
                    if (height > visibleHeight) {
                        width = width * visibleHeight / height
                        height = visibleHeight
                    }
                    if (durY + height > visibleHeight) {
                        val textPage = textPages.last()
                        if (doublePage && absStartX < viewWidth / 2) {
                            //当前页面左列结束
                            textPage.leftLineSize = textPage.lineSize
                            absStartX = viewWidth / 2 + paddingLeft
                        } else {
                            //当前页面结束
                            if (textPage.leftLineSize == 0) {
                                textPage.leftLineSize = textPage.lineSize
                            }
                            textPage.text = stringBuilder.toString().ifEmpty { "本页无文字内容" }
                            stringBuilder.clear()
                            textPages.add(TextPage())
                        }
                        // 双页的 durY 不正确，可能会小于实际高度
                        if (textPage.height < durY) {
                            textPage.height = durY
                        }
                        durY = 0f
                    }
                }
            }
            val textLine = TextLine(isImage = true, reviewTitleOffset = reviewTitleOffset)
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
                ImageColumn(start = x + start, end = x + end, src = src)
            )
            calcTextLinePosition(textPages, textLine, stringBuilder.length)
            stringBuilder.append(" ") // 确保翻页时索引计算正确
            textPages.last().addLine(textLine)
        }
        return absStartX to durY + textHeight * paragraphSpacing / 10f
    }

    /**
     * 排版文字
     */
    private suspend fun setTypeText(
        book: Book,
        x: Int,
        y: Float,
        text: String,
        textPages: ArrayList<TextPage>,
        stringBuilder: StringBuilder,
        textPaint: TextPaint,
        textHeight: Float,
        fontMetrics: FontMetrics,
        reviewTitleOffset: Int = ChapterProvider.reviewTitleOffset,
        isTitle: Boolean = false,
        emptyContent: Boolean = false,
        isVolumeTitle: Boolean = false,
        srcList: LinkedList<String>? = null
    ): Pair<Int, Float> {
        var absStartX = x
        val layout = if (ReadBookConfig.useZhLayout) {
            ZhLayout(
                text, textPaint, visibleWidth, emptyList(), emptyList(),
                ReadBookConfig.paragraphIndent.length
            )
        } else {
            StaticLayout(text, textPaint, visibleWidth, Layout.Alignment.ALIGN_NORMAL, 0f, 0f, true)
        }
        var durY = when {
            //标题y轴居中
            emptyContent && textPages.size == 1 -> {
                val textPage = textPages.last()
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
                    y - textLayoutHeight
                }
            }

            isTitle && textPages.size == 1 && textPages.last().lines.isEmpty() ->
                y + titleTopSpacing

            else -> y
        }
        for (lineIndex in 0 until layout.lineCount) {
            val textLine = TextLine(isTitle = isTitle, reviewTitleOffset = reviewTitleOffset)
            if (durY + textHeight > visibleHeight) {
                val textPage = textPages.last()
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
                    //新建页面
                    textPages.add(TextPage())
                    stringBuilder.clear()
                    absStartX = paddingLeft
                }
                if (textPage.height < durY) {
                    textPage.height = durY
                }
                durY = 0f
            }
            val lineStart = layout.getLineStart(lineIndex)
            val lineEnd = layout.getLineEnd(lineIndex)
            val lineText = text.substring(lineStart, lineEnd)
            val (words, widths) = measureTextSplit(lineText, textPaint)
            val desiredWidth = widths.fastSum()
            when {
                lineIndex == 0 && layout.lineCount > 1 && !isTitle -> {
                    //第一行 非标题
                    textLine.text = lineText
                    addCharsToLineFirst(
                        book, absStartX, textLine, words,
                        desiredWidth, widths, srcList
                    )
                }

                lineIndex == layout.lineCount - 1 -> {
                    //最后一行
                    textLine.text = lineText
                    //标题x轴居中
                    val startX = if (
                        isTitle &&
                        (ReadBookConfig.isMiddleTitle || emptyContent || isVolumeTitle)
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
                        (ReadBookConfig.isMiddleTitle || emptyContent || isVolumeTitle)
                    ) {
                        //标题居中
                        val startX = (visibleWidth - desiredWidth) / 2
                        addCharsToLineNatural(
                            book, absStartX, textLine, words,
                            startX, false, widths, srcList
                        )
                    } else {
                        //中间行
                        textLine.text = lineText
                        addCharsToLineMiddle(
                            book, absStartX, textLine, words,
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
            val textPage = textPages.last()
            textPage.addLine(textLine)
            durY += textHeight * lineSpacingExtra
            if (textPage.height < durY) {
                textPage.height = durY
            }
        }
        durY += textHeight * paragraphSpacing / 10f
        return Pair(absStartX, durY)
    }

    private fun calcTextLinePosition(
        textPages: ArrayList<TextPage>,
        textLine: TextLine,
        sbLength: Int
    ) {
        val lastLine = textPages.last().lines.lastOrNull { it.paragraphNum > 0 }
            ?: textPages.getOrNull(
                textPages.lastIndex - 1
            )?.lines?.lastOrNull { it.paragraphNum > 0 }
        val paragraphNum = when {
            lastLine == null -> 1
            lastLine.isParagraphEnd -> lastLine.paragraphNum + 1
            else -> lastLine.paragraphNum
        }
        textLine.paragraphNum = paragraphNum
        updateReviewCount(textLine, paragraphNum)
        textLine.chapterPosition =
            (textPages.getOrNull(textPages.lastIndex - 1)?.lines?.lastOrNull()?.run {
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
        /**自然排版长度**/
        desiredWidth: Float,
        textWidths: List<Float>,
        srcList: LinkedList<String>?
    ) {
        var x = 0f
        if (!ReadBookConfig.textFullJustify) {
            addCharsToLineNatural(
                book, absStartX, textLine, words,
                x, true, textWidths, srcList
            )
            return
        }
        val bodyIndent = ReadBookConfig.paragraphIndent
        for (i in bodyIndent.indices) {
            val x1 = x + indentCharWidth
            textLine.addColumn(
                TextColumn(
                    charData = indentChar,
                    start = absStartX + x,
                    end = absStartX + x1
                )
            )
            x = x1
            textLine.indentWidth = x
        }
        if (words.size > bodyIndent.length) {
            val text1 = words.subList(bodyIndent.length, words.size)
            val textWidths1 = textWidths.subList(bodyIndent.length, textWidths.size)
            addCharsToLineMiddle(
                book, absStartX, textLine, text1,
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
        /**自然排版长度**/
        desiredWidth: Float,
        /**起始x坐标**/
        startX: Float,
        textWidths: List<Float>,
        srcList: LinkedList<String>?
    ) {
        if (!ReadBookConfig.textFullJustify) {
            addCharsToLineNatural(
                book, absStartX, textLine, words,
                startX, false, textWidths, srcList
            )
            return
        }
        val residualWidth = visibleWidth - desiredWidth
        val spaceSize = words.count { it == " " }
        if (spaceSize > 1) {
            val d = residualWidth / spaceSize
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
            val d = residualWidth / gapCount
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
        val indentLength = ReadBookConfig.paragraphIndent.length
        var x = startX
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
            srcList != null && char == srcReplaceChar -> {
                val src = srcList.removeFirst()
                ImageProvider.cacheImage(book, src, ReadBook.bookSource)
                ImageColumn(
                    start = absStartX + xStart,
                    end = absStartX + xEnd,
                    src = src
                )
            }

            isLineEnd && char == reviewChar -> {
                val width = getReviewWidth(textLine.isTitle)
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

    /**
     * 超出边界处理
     */
    private fun exceed(absStartX: Int, textLine: TextLine, words: List<String>) {
        val visibleEnd = absStartX + visibleWidth
        val endX = textLine.columns.lastOrNull()?.end ?: return
        if (endX > visibleEnd) {
            val cc = (endX - visibleEnd) / words.size
            for (i in 0..words.lastIndex) {
                textLine.getColumnReverseAt(i).let {
                    val py = cc * (words.size - i)
                    it.start -= py
                    it.end -= py
                }
            }
        }
    }

    fun measureTextSplit(
        text: String,
        paint: TextPaint
    ): Pair<ArrayList<String>, ArrayList<Float>> {
        val length = text.length
        val widthsArray = FloatArray(length)
        paint.getTextWidths(text, widthsArray)
        val clusterCount = widthsArray.count { it > 0f }
        val widths = ArrayList<Float>(clusterCount)
        val stringList = ArrayList<String>(clusterCount)
        var i = 0
        while (i < length) {
            val clusterBaseIndex = i++
            widths.add(widthsArray[clusterBaseIndex])
            while (i < length && widthsArray[i] == 0f) {
                i++
            }
            stringList.add(text.substring(clusterBaseIndex, i))
        }
        return stringList to widths
    }

    /**
     * 更新样式
     */
    fun upStyle() {
        typeface = getTypeface(ReadBookConfig.textFont)
        getPaints(typeface).let {
            titlePaint = it.first
            contentPaint = it.second
        }
        reviewPaint.color = ReadBookConfig.reviewIconColor.takeIf { it != 0 }
            ?: if (AppConfig.isNightTheme) {
                ColorUtils.lightenColor(contentPaint.color)
            } else {
                ColorUtils.darkenColor(contentPaint.color)
            }
        reviewPaint.textSize = contentPaint.textSize * 0.45f
        reviewPaint.textAlign = Paint.Align.CENTER
        reviewPaint.isAntiAlias = true
        //间距
        lineSpacingExtra = ReadBookConfig.lineSpacingExtra / 10f
        paragraphSpacing = ReadBookConfig.paragraphSpacing
        titleTopSpacing = ReadBookConfig.titleTopSpacing.dpToPx()
        titleBottomSpacing = ReadBookConfig.titleBottomSpacing.dpToPx()
        val bodyIndent = ReadBookConfig.paragraphIndent
        indentCharWidth = if (bodyIndent.isNotEmpty()) {
            var indentWidth = StaticLayout.getDesiredWidth(bodyIndent, contentPaint)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                indentWidth += contentPaint.letterSpacing * contentPaint.textSize
            }
            indentWidth / bodyIndent.length
        } else {
            0f
        }
        titlePaintTextHeight = titlePaint.textHeight
        contentPaintTextHeight = contentPaint.textHeight
        titlePaintFontMetrics = titlePaint.fontMetrics
        contentPaintFontMetrics = contentPaint.fontMetrics
        upLayout()
    }

    fun setReviewProviders(
        countProvider: ((Int, Int) -> Int)?,
        keyProvider: ((Int, Int) -> String?)?
    ) {
        reviewCountProvider = countProvider
        reviewKeyProvider = keyProvider
        refreshReviewColumns()
    }

    fun clearReviewProviders() {
        setReviewProviders(null, null)
    }

    fun setReviewCountProvider(provider: ((Int) -> Int)?) {
        val wrappedProvider = provider?.let { p ->
            { _: Int, reviewId: Int -> p(reviewId) }
        }
        setReviewProviders(wrappedProvider, reviewKeyProvider)
    }

    fun setReviewKeyProvider(provider: ((Int) -> String?)?) {
        val wrappedProvider = provider?.let { p ->
            { _: Int, reviewId: Int -> p(reviewId) }
        }
        setReviewProviders(reviewCountProvider, wrappedProvider)
    }

    fun getReviewKeyById(reviewId: Int, chapterIndex: Int = ReadBook.durChapterIndex): String? {
        return reviewKeyProvider?.invoke(chapterIndex, reviewId)?.takeIf { it.isNotBlank() }
    }

    /**
     * provider 或样式变化后，及时同步当前缓存章节里的段评列，避免旧图标残留或错位。
     */
    fun refreshReviewColumnsForStyleChange() {
        refreshReviewColumns()
    }

    private fun refreshReviewColumns() {
        refreshReviewColumns(ReadBook.prevTextChapter)
        refreshReviewColumns(ReadBook.curTextChapter)
        refreshReviewColumns(ReadBook.nextTextChapter)
    }

    private fun refreshReviewColumns(textChapter: TextChapter?) {
        textChapter ?: return
        val chapterIndex = textChapter.chapter.index
        var pageIndex = 0
        while (pageIndex < textChapter.pageSize) {
            val page = textChapter.getPage(pageIndex) ?: break
            val lines = page.lines
            val lineSize = lines.size
            var lineIndex = 0
            while (lineIndex < lineSize) {
                val line = lines.getOrNull(lineIndex) ?: break
                val count = getReviewCount(
                    paragraphNum = line.paragraphNum,
                    isTitle = line.isTitle,
                    titleOffset = line.reviewTitleOffset,
                    chapterIndex = chapterIndex
                )
                var changed = false
                val shouldShow = count > 0 && line.isParagraphEnd
                if (!shouldShow) {
                    if (line.removeColumns { it is ReviewColumn }) {
                        changed = true
                    }
                } else {
                    var hasReviewColumn = false
                    line.columns.forEach { column ->
                        if (column is ReviewColumn) {
                            hasReviewColumn = true
                            if (column.count != count) {
                                column.count = count
                                changed = true
                            }
                            if (updateReviewColumnLayout(column, line)) {
                                changed = true
                            }
                        }
                    }
                    if (!hasReviewColumn) {
                        appendReviewColumnIfNeeded(line, chapterIndex = chapterIndex)
                        changed = true
                    }
                }
                if (changed) {
                    line.invalidate()
                }
                lineIndex++
            }
            pageIndex++
        }
    }

    fun getReviewCount(
        paragraphNum: Int,
        isTitle: Boolean = false,
        titleOffset: Int = reviewTitleOffset,
        chapterIndex: Int = ReadBook.durChapterIndex,
    ): Int {
        val provider = reviewCountProvider ?: return 0
        if (isTitle) {
            val titleCount = provider(chapterIndex, -1)
            if (titleCount > 0) return titleCount
        }
        val reviewId = paragraphNum - titleOffset
        if (reviewId <= 0) return 0
        return provider(chapterIndex, reviewId)
    }

    private fun updateReviewCount(
        textLine: TextLine,
        paragraphNum: Int,
        titleOffset: Int? = null,
        chapterIndex: Int = ReadBook.durChapterIndex,
    ) {
        val count = getReviewCount(
            paragraphNum,
            textLine.isTitle,
            titleOffset ?: textLine.reviewTitleOffset,
            chapterIndex
        )
        if (count <= 0) return
        textLine.columns.forEach { column ->
            if (column is ReviewColumn) {
                column.count = count
            }
        }
    }

    fun appendReviewColumnIfNeeded(
        textLine: TextLine,
        titleOffset: Int? = null,
        chapterIndex: Int = ReadBook.durChapterIndex,
    ) {
        if (textLine.columns.any { it is ReviewColumn }) return
        val count = getReviewCount(
            textLine.paragraphNum,
            textLine.isTitle,
            titleOffset ?: textLine.reviewTitleOffset,
            chapterIndex
        )
        if (count <= 0) return
        val reviewColumn = ReviewColumn(start = 0f, end = 0f, count = count)
        updateReviewColumnLayout(reviewColumn, textLine)
        textLine.addColumn(reviewColumn)
    }

    private fun getReviewScaleFactor(): Float {
        return ReadBookConfig.reviewIconScale.coerceIn(50, 200) / 100f
    }

    private fun getReviewColumnRange(textLine: TextLine): Pair<Float, Float> {
        val width = getReviewWidth(textLine.isTitle)
        // 图标可延伸进右侧页边距，最多到屏幕物理边缘（双页时为本页中线），留 1dp 不贴边
        val screenRight = if (doublePage && textLine.isLeftLine) {
            (viewWidth / 2).toFloat()
        } else {
            viewWidth.toFloat()
        }
        val maxEnd = screenRight - 1.dpToPx()
        val textEnd = textLine.columns.lastOrNull { it !is ReviewColumn }?.end ?: textLine.lineEnd
        // 紧跟文字末尾；排满整行时溢出到页边距，而不是被往左拽压在文字上
        val start = kotlin.math.min(textEnd, maxEnd - width)
        return start to start + width
    }

    private fun updateReviewColumnLayout(reviewColumn: ReviewColumn, textLine: TextLine): Boolean {
        val (start, end) = getReviewColumnRange(textLine)
        if (reviewColumn.start == start && reviewColumn.end == end) {
            return false
        }
        reviewColumn.start = start
        reviewColumn.end = end
        return true
    }

    fun getReviewWidth(isTitle: Boolean): Float {
        val base = if (isTitle) titlePaint.textSize else contentPaint.textSize
        val defaultWidth = base * 0.9f * getReviewScaleFactor()
        val aspectRatio = getReviewIconAspectRatio() ?: return defaultWidth
        return getReviewHeight(isTitle) * 0.9f * aspectRatio
    }

    fun getReviewHeight(isTitle: Boolean): Float {
        val base = if (isTitle) titlePaint.textSize else contentPaint.textSize
        return base * getReviewScaleFactor()
    }

    fun getReviewCountText(count: Int): String {
        return if (count > 999) "999" else count.toString()
    }

    fun clearReviewIconCache() {
        lastReviewIconTemplate = null
        invalidReviewIconTemplate = null
        lastReviewIconAspectTemplate = null
        reviewIconAspectRatio = null
        reviewIconBitmapCache.evictAll()
    }

    private fun getReviewIconAspectRatio(): Float? {
        val template = ReadBookConfig.reviewIconSvg.trim()
        if (template.isBlank()) {
            lastReviewIconAspectTemplate = null
            reviewIconAspectRatio = null
            return null
        }
        if (lastReviewIconAspectTemplate != template) {
            lastReviewIconAspectTemplate = template
            reviewIconAspectRatio = SvgUtils
                .getAspectRatioFromSvgText(template.replace(reviewIconPlaceholder, "88"))
                ?.takeIf { it.isFinite() && it > 0f }
        }
        return reviewIconAspectRatio
    }

    fun getReviewIconBitmap(count: Int, widthPx: Int, heightPx: Int): Bitmap? {
        if (count <= 0 || widthPx <= 0 || heightPx <= 0) return null
        val template = ReadBookConfig.reviewIconSvg.trim()
        val countText = getReviewCountText(count)
        if (template.isBlank()) {
            if (lastReviewIconTemplate != null) {
                clearReviewIconCache()
            }
            return null
        }
        if (lastReviewIconTemplate != template) {
            reviewIconBitmapCache.evictAll()
            invalidReviewIconTemplate = null
            lastReviewIconTemplate = template
        }
        if (invalidReviewIconTemplate == template) return null
        val cacheKey = ReviewIconCacheKey(
            templateHash = template.hashCode(),
            templateLength = template.length,
            countText = countText,
            widthPx = widthPx,
            heightPx = heightPx,
        )
        reviewIconBitmapCache[cacheKey]?.let { cached ->
            if (!cached.isRecycled) return cached
            reviewIconBitmapCache.remove(cacheKey)
        }
        val svgText = template.replace(reviewIconPlaceholder, countText)
        val bitmap = SvgUtils.createBitmapFromSvgText(svgText, widthPx, heightPx)
        if (bitmap == null) {
            invalidReviewIconTemplate = template
            return null
        }
        invalidReviewIconTemplate = null
        reviewIconBitmapCache.put(cacheKey, bitmap)
        return bitmap
    }

    private fun getTypeface(fontPath: String): Typeface? {
        return kotlin.runCatching {
            when {
                fontPath.isContentScheme() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    appCtx.contentResolver
                        .openFileDescriptor(Uri.parse(fontPath), "r")!!
                        .use {
                            Typeface.Builder(it.fileDescriptor).build()
                        }
                }

                fontPath.isContentScheme() -> {
                    Typeface.createFromFile(RealPathUtil.getPath(appCtx, Uri.parse(fontPath)))
                }

                fontPath.isNotEmpty() -> Typeface.createFromFile(fontPath)
                else -> when (AppConfig.systemTypefaces) {
                    1 -> Typeface.SERIF
                    2 -> Typeface.MONOSPACE
                    else -> Typeface.SANS_SERIF
                }
            }
        }.getOrElse {
            ReadBookConfig.textFont = ""
            ReadBookConfig.save()
            Typeface.SANS_SERIF
        } ?: Typeface.DEFAULT
    }

    private val highlightTypefaceCache = HashMap<String, Typeface?>()

    /**
     * 高亮自定义字体: 按路径解析 Typeface 并缓存。
     * 逐列绘制时高频调用, 必须缓存(命中与未命中都缓存)。失败返回 null, 不影响阅读字体。
     */
    fun getHighlightTypeface(fontPath: String): Typeface? {
        if (fontPath.isEmpty()) return null
        if (highlightTypefaceCache.containsKey(fontPath)) return highlightTypefaceCache[fontPath]
        val typeface = kotlin.runCatching {
            when {
                fontPath.isContentScheme() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    appCtx.contentResolver
                        .openFileDescriptor(Uri.parse(fontPath), "r")!!
                        .use { Typeface.Builder(it.fileDescriptor).build() }
                }

                fontPath.isContentScheme() ->
                    Typeface.createFromFile(RealPathUtil.getPath(appCtx, Uri.parse(fontPath)))

                else -> Typeface.createFromFile(fontPath)
            }
        }.getOrNull()
        highlightTypefaceCache[fontPath] = typeface
        return typeface
    }

    private fun getPaints(typeface: Typeface?): Pair<TextPaint, TextPaint> {
        // 字体统一处理
        val bold = Typeface.create(typeface, Typeface.BOLD)
        val normal = Typeface.create(typeface, Typeface.NORMAL)
        val (titleFont, textFont) = when (ReadBookConfig.textBold) {
            1 -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    Pair(Typeface.create(typeface, 900, false), bold)
                else
                    Pair(bold, bold)
            }

            2 -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    Pair(normal, Typeface.create(typeface, 300, false))
                else
                    Pair(normal, normal)
            }

            else -> Pair(bold, normal)
        }

        //标题
        val tPaint = TextPaint()
        tPaint.color = ReadBookConfig.textColor
        tPaint.letterSpacing = ReadBookConfig.letterSpacing
        tPaint.typeface = titleFont
        tPaint.textSize = with(ReadBookConfig) { textSize + titleSize }.toFloat().spToPx()
        tPaint.isAntiAlias = true
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q && AppConfig.optimizeRender) {
            tPaint.isLinearText = true
        }
        //正文
        val cPaint = TextPaint()
        cPaint.color = ReadBookConfig.textColor
        cPaint.letterSpacing = ReadBookConfig.letterSpacing
        cPaint.typeface = textFont
        cPaint.textSize = ReadBookConfig.textSize.toFloat().spToPx()
        cPaint.isAntiAlias = true
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q && AppConfig.optimizeRender) {
            cPaint.isLinearText = true
        }
        return Pair(tPaint, cPaint)
    }

    /**
     * 更新View尺寸
     */
    fun upViewSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) {
            return
        }
        if (width != viewWidth || height != viewHeight) {
            if (width == viewWidth) {
                upViewSizeRunnable = handler.postDelayed(300) {
                    upViewSizeRunnable = null
                    notifyViewSizeChange(width, height)
                }
            } else {
                notifyViewSizeChange(width, height)
            }
        } else if (upViewSizeRunnable != null) {
            handler.removeCallbacks(upViewSizeRunnable!!)
            upViewSizeRunnable = null
        }
    }

    private fun notifyViewSizeChange(width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        upLayout()
        postEvent(EventBus.UP_CONFIG, arrayListOf(5))
    }

    /**
     * 更新绘制尺寸
     */
    fun upLayout() {
        when (AppConfig.doublePageHorizontal) {
            "0" -> doublePage = false
            "1" -> doublePage = true
            "2" -> {
                doublePage = (viewWidth > viewHeight)
                        && ReadBook.pageAnim() != 3
            }

            "3" -> {
                doublePage = (viewWidth > viewHeight || appCtx.isPad)
                        && ReadBook.pageAnim() != 3
            }
        }

        if (viewWidth <= 0 || viewHeight <= 0) {
            return
        }

        paddingLeft = ReadBookConfig.paddingLeft.dpToPx()
        paddingTop = ReadBookConfig.paddingTop.dpToPx()
        paddingRight = ReadBookConfig.paddingRight.dpToPx()
        paddingBottom = ReadBookConfig.paddingBottom.dpToPx()
        visibleWidth = if (doublePage) {
            viewWidth / 2 - paddingLeft - paddingRight
        } else {
            viewWidth - paddingLeft - paddingRight
        }
        //留1dp画最后一行下划线
        visibleHeight = viewHeight - paddingTop - paddingBottom
        visibleRight = viewWidth - paddingRight
        visibleBottom = paddingTop + visibleHeight

        if (paddingLeft >= visibleRight || paddingTop >= visibleBottom) {
            AppLog.put("边距设置过大，请重新设置", toast = true)
            setFallbackLayout()
        }

        visibleRect.set( //留余，让溢出时也显示
            paddingLeft.toFloat() - 10,
            paddingTop.toFloat() - 10,
            viewWidth.toFloat(), //放宽到屏幕右缘，供段评图标溢出到右页边距时显示与点击
            visibleBottom.toFloat() + 10f.dpToPx() //下划线最远10dp
        )

    }

    private fun setFallbackLayout() {
        paddingLeft = 20.dpToPx()
        paddingTop = 5.dpToPx()
        paddingRight = 20.dpToPx()
        paddingBottom = 5.dpToPx()
        visibleWidth = if (doublePage) {
            viewWidth / 2 - paddingLeft - paddingRight
        } else {
            viewWidth - paddingLeft - paddingRight
        }
        //留1dp画最后一行下划线
        visibleHeight = viewHeight - paddingTop - paddingBottom
        visibleRight = viewWidth - paddingRight
        visibleBottom = paddingTop + visibleHeight
    }

}
