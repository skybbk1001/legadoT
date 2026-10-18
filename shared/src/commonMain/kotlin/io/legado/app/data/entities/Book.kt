package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookType
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.nio.charset.Charset
import java.time.LocalDate

@TypeConverters(Book.Converters::class)
@Entity(
    tableName = "books",
    indices = [Index(value = ["name", "author"], unique = true)]
)
data class Book(
    // 详情页Url(本地书源存储完整文件路径)
    @PrimaryKey
    @ColumnInfo(defaultValue = "")
    override var bookUrl: String = "",
    // 目录页Url (toc=table of Contents)
    @ColumnInfo(defaultValue = "")
    var tocUrl: String = "",
    // 书源URL(默认BookType.local)
    @ColumnInfo(defaultValue = BookType.localTag)
    var origin: String = BookType.localTag,
    //书源名称 or 本地书籍文件名
    @ColumnInfo(defaultValue = "")
    var originName: String = "",
    // 书籍名称(书源获取)
    @ColumnInfo(defaultValue = "")
    override var name: String = "",
    // 作者名称(书源获取)
    @ColumnInfo(defaultValue = "")
    override var author: String = "",
    // 分类信息(书源获取)
    override var kind: String? = null,
    // 分类信息(用户修改)
    var customTag: String? = null,
    // 封面Url(书源获取)
    var coverUrl: String? = null,
    // 封面Url(用户修改)
    var customCoverUrl: String? = null,
    // 简介内容(书源获取)
    var intro: String? = null,
    // 简介内容(用户修改)
    var customIntro: String? = null,
    // 自定义字符集名称(仅适用于本地书籍)
    var charset: String? = null,
    // 类型,详见BookType
    @ColumnInfo(defaultValue = "0")
    var type: Int = BookType.text,
    // 自定义分组索引号
    @ColumnInfo(defaultValue = "0")
    var group: Long = 0,
    // 最新章节标题
    var latestChapterTitle: String? = null,
    // 最新章节标题更新时间
    @ColumnInfo(defaultValue = "0")
    var latestChapterTime: Long = System.currentTimeMillis(),
    // 最近一次更新书籍信息的时间
    @ColumnInfo(defaultValue = "0")
    var lastCheckTime: Long = System.currentTimeMillis(),
    // 最近一次发现新章节的数量
    @ColumnInfo(defaultValue = "0")
    var lastCheckCount: Int = 0,
    // 书籍目录总数
    @ColumnInfo(defaultValue = "0")
    var totalChapterNum: Int = 0,
    // 当前章节名称
    var durChapterTitle: String? = null,
    // 当前章节索引
    @ColumnInfo(defaultValue = "0")
    var durChapterIndex: Int = 0,
    // 当前阅读的进度(首行字符的索引位置)
    @ColumnInfo(defaultValue = "0")
    var durChapterPos: Int = 0,
    // 最近一次阅读书籍的时间(打开正文的时间)
    @ColumnInfo(defaultValue = "0")
    var durChapterTime: Long = System.currentTimeMillis(),
    //字数
    override var wordCount: String? = null,
    // 刷新书架时更新书籍信息
    @ColumnInfo(defaultValue = "1")
    var canUpdate: Boolean = true,
    // 手动排序
    @ColumnInfo(defaultValue = "0")
    var order: Int = 0,
    //书源排序
    @ColumnInfo(defaultValue = "0")
    var originOrder: Int = 0,
    // 自定义书籍变量信息(用于书源规则检索书籍信息)
    override var variable: String? = null,
    //阅读设置
    var readConfig: ReadConfig? = null,
    //同步时间
    @ColumnInfo(defaultValue = "0")
    var syncTime: Long = 0L
) : BaseBook {

    override fun equals(other: Any?): Boolean {
        if (other is Book) {
            return other.bookUrl == bookUrl
        }
        return false
    }

    override fun hashCode(): Int {
        return bookUrl.hashCode()
    }

    @delegate:Transient
    @delegate:Ignore
    override val variableMap: HashMap<String, String> by lazy {
        GSON.fromJsonObject<HashMap<String, String>>(variable).getOrNull() ?: hashMapOf()
    }

    @Ignore
    override var infoHtml: String? = null

    @Ignore
    override var tocHtml: String? = null

    @Ignore
    var downloadUrls: List<String>? = null

    @Ignore
    var folderName: String? = null

    @get:Ignore
    val lastChapterIndex get() = totalChapterNum - 1

    fun getRealAuthor() = author.replace(AppPattern.authorRegex, "")

    fun getDisplayCover() = if (customCoverUrl.isNullOrEmpty()) coverUrl else customCoverUrl

    /**
     * 加载封面时使用的来源(书源)origin。
     * 自定义封面(含封面规则结果)通常托管在书源以外的站点，若仍以书源身份加载，
     * 会把书源的 Cookie/Referer 注入到第三方域名(见 AnalyzeUrl.setCookie)，
     * 触发对方风控导致请求挂起/被拒，最终回退默认封面。
     * 此时返回 null，仅使用 URL 自带的 headers。
     */
    fun getCoverSourceOrigin() = if (customCoverUrl.isNullOrEmpty()) origin else null

    fun getDisplayIntro() = if (customIntro.isNullOrEmpty()) intro else customIntro

    //自定义简介有自动更新的需求时，可通过更新intro再调用upCustomIntro()完成
    @Suppress("unused")
    fun upCustomIntro() {
        customIntro = intro
    }

    fun fileCharset(): Charset {
        return charset(charset ?: "UTF-8")
    }

    val config: ReadConfig
        get() {
            if (readConfig == null) {
                readConfig = ReadConfig()
            }
            return readConfig!!
        }

    fun setReverseToc(reverseToc: Boolean) {
        config.reverseToc = reverseToc
    }

    fun getReverseToc(): Boolean {
        return config.reverseToc
    }

    fun setUseReplaceRule(useReplaceRule: Boolean) {
        config.useReplaceRule = useReplaceRule
    }

    fun setReSegment(reSegment: Boolean) {
        config.reSegment = reSegment
    }

    fun getReSegment(): Boolean {
        return config.reSegment
    }

    fun setPageAnim(pageAnim: Int?) {
        config.pageAnim = pageAnim
    }

    fun setImageStyle(imageStyle: String?) {
        config.imageStyle = imageStyle
    }

    fun getImageStyle(): String? {
        return config.imageStyle
    }

    fun setTtsEngine(ttsEngine: String?) {
        config.ttsEngine = ttsEngine
    }

    fun getTtsEngine(): String? {
        return config.ttsEngine
    }

    fun setAudioSkipEnabled(enabled: Boolean?) {
        config.audioSkipEnabled = enabled
    }

    fun setAudioIntroMs(value: Int?) {
        config.audioIntroMs = value?.coerceAtLeast(0)
    }

    fun setAudioOutroMs(value: Int?) {
        config.audioOutroMs = value?.coerceAtLeast(0)
    }

    fun setAudioPlaySpeed(speed: Float?) {
        config.audioPlaySpeed = speed
    }

    fun getAudioPlaySpeed(): Float {
        return config.audioPlaySpeed ?: 1f
    }

    fun setAudioPlayMode(mode: Int?) {
        config.audioPlayMode = mode
    }

    fun getAudioPlayMode(): Int? {
        return config.audioPlayMode
    }

    fun setSplitLongChapter(limitLongContent: Boolean) {
        config.splitLongChapter = limitLongContent
    }

    fun getSplitLongChapter(): Boolean {
        return config.splitLongChapter
    }

    // readSimulating 的 setter 和 getter
    fun setReadSimulating(readSimulating: Boolean) {
        config.readSimulating = readSimulating
    }

    fun getReadSimulating(): Boolean {
        return config.readSimulating
    }

    // startDate 的 setter 和 getter
    fun setStartDate(startDate: LocalDate?) {
        config.startDate = startDate
    }

    fun getStartDate(): LocalDate? {
        if (!config.readSimulating || config.startDate == null) {
            return LocalDate.now()
        }
        return config.startDate
    }

    // startChapter 的 setter 和 getter
    fun setStartChapter(startChapter: Int) {
        config.startChapter = startChapter
    }

    fun getStartChapter(): Int {
        if (config.readSimulating) return config.startChapter ?: 0
        return this.durChapterIndex
    }

    // dailyChapters 的 setter 和 getter
    fun setDailyChapters(dailyChapters: Int) {
        config.dailyChapters = dailyChapters
    }

    fun getDailyChapters(): Int {
        return config.dailyChapters
    }

    fun getDelTag(tag: Long): Boolean {
        return config.delTag and tag == tag
    }

    fun addDelTag(tag: Long) {
        config.delTag = config.delTag and tag
    }

    fun removeDelTag(tag: Long) {
        config.delTag = config.delTag and tag.inv()
    }

    fun createBookMark(): Bookmark {
        return Bookmark(
            bookName = name,
            bookAuthor = author,
        )
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val hTag = 2L
        const val rubyTag = 4L
        const val imgStyleDefault = "DEFAULT"
        const val imgStyleFull = "FULL"
        const val imgStyleText = "TEXT"
        const val imgStyleSingle = "SINGLE"
    }

    data class ReadConfig(
        var reverseToc: Boolean = false,
        var pageAnim: Int? = null,
        var reSegment: Boolean = false,
        var imageStyle: String? = null,
        var useReplaceRule: Boolean? = null,// 正文使用净化替换规则
        var delTag: Long = 0L,//去除标签
        var ttsEngine: String? = null,
        var audioSkipEnabled: Boolean? = null,
        var audioIntroMs: Int? = null,
        var audioOutroMs: Int? = null,
        var audioSkipMinDurationMs: Int? = null,
        var audioPlaySpeed: Float? = null,
        var audioPlayMode: Int? = null,
        var splitLongChapter: Boolean = true,
        var readSimulating: Boolean = false,
        var startDate: LocalDate? = null,
        var startChapter: Int? = null,     // 用户设置的起始章节
        var dailyChapters: Int = 3    // 用户设置的每日更新章节数
    )

    class Converters {

        @TypeConverter
        fun readConfigToString(config: ReadConfig?): String = GSON.toJson(config)

        @TypeConverter
        fun stringToReadConfig(json: String?) = GSON.fromJsonObject<ReadConfig>(json).getOrNull()
    }
}
