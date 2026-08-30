package io.legado.app.model

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.annotation.Keep
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Transformation
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.target.Target.SIZE_ORIGINAL
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.help.CacheManager
import io.legado.app.help.DefaultData
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import kotlinx.coroutines.currentCoroutineContext
import splitties.init.appCtx
import java.io.File

@Keep
@Suppress("ConstPropertyName")
object BookCover {

    private const val coverRuleConfigKey = "legadoCoverRuleConfig"
    const val configFileName = "coverRule.json"

    var drawBookName = true
        private set
    var drawBookAuthor = true
        private set

    /**
     * 图库为空或非书名上下文(如 BookController/PhotoDialog)时的兜底封面。
     * 保留该公开属性名以兼容既有调用方。
     */
    val defaultDrawable: Drawable
        get() = fallbackDrawable

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun loadFallbackDrawable(): Drawable =
        appCtx.resources.getDrawable(R.drawable.image_cover_default, null)

    private val fallbackDrawable: Drawable by lazy { loadFallbackDrawable() }

    /** 当前主题默认封面图库路径(空则回退兜底图) */
    private var coverPaths: List<String> = emptyList()
    private val coverDrawableCache = HashMap<String, Drawable>()

    init {
        upDefaultCover()
    }

    fun upDefaultCover() {
        val isNightTheme = AppConfig.isNightTheme
        drawBookName = if (isNightTheme) {
            appCtx.getPrefBoolean(PreferKey.coverShowNameN, true)
        } else {
            appCtx.getPrefBoolean(PreferKey.coverShowName, true)
        }
        drawBookAuthor = if (isNightTheme) {
            appCtx.getPrefBoolean(PreferKey.coverShowAuthorN, true)
        } else {
            appCtx.getPrefBoolean(PreferKey.coverShowAuthor, true)
        }
        coverPaths = getDefaultCoverPaths(isNightTheme)
        coverDrawableCache.clear()
    }

    /**
     * 读默认封面图库路径。兼容旧版单路径(未加 JSON 包裹的裸路径)数据。
     */
    fun getDefaultCoverPaths(isNight: Boolean = AppConfig.isNightTheme): List<String> {
        val key = if (isNight) PreferKey.defaultCoverDark else PreferKey.defaultCover
        val raw = appCtx.getPrefString(key) ?: return emptyList()
        if (!raw.startsWith("[")) {
            return listOf(raw)
        }
        return GSON.fromJsonArray<String>(raw).getOrNull()?.filter { it.isNotBlank() } ?: emptyList()
    }

    /**
     * 保存默认封面图库路径并刷新内存池。
     */
    fun saveDefaultCoverPaths(paths: List<String>, isNight: Boolean = AppConfig.isNightTheme) {
        val key = if (isNight) PreferKey.defaultCoverDark else PreferKey.defaultCover
        if (paths.isEmpty()) {
            appCtx.removePref(key)
        } else {
            appCtx.putPrefString(key, GSON.toJson(paths))
        }
        upDefaultCover()
    }

    /**
     * 按书名确定性取一张默认封面:同书名永远同图(改名会换图);图库为空回退兜底图。
     */
    fun defaultDrawableFor(name: String?): Drawable {
        if (coverPaths.isEmpty()) return defaultDrawable
        val path = if (name.isNullOrBlank()) {
            coverPaths.first()
        } else {
            coverPaths[(name.hashCode() and Int.MAX_VALUE) % coverPaths.size]
        }
        return decodeCached(path) ?: defaultDrawable
    }

    /**
     * 是否为默认封面(兜底图或图库中某张)。供氛围背景等"非真实封面不派生"逻辑使用。
     */
    fun isDefaultCover(drawable: Drawable?): Boolean {
        if (drawable == null) return true
        if (drawable === fallbackDrawable) return true
        if (coverPaths.isEmpty()) return false
        val bitmap = (drawable as? BitmapDrawable)?.bitmap
        return coverDrawableCache.values.any {
            it === drawable || (bitmap != null && (it as? BitmapDrawable)?.bitmap === bitmap)
        }
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun decodeCached(path: String): Drawable? {
        coverDrawableCache[path]?.let { return it }
        return kotlin.runCatching {
            BitmapDrawable(appCtx.resources, BitmapUtils.decodeBitmap(path, 600, 900))
        }.getOrNull()?.also { coverDrawableCache[path] = it }
    }

    /**
     * 加载封面
     */
    fun load(
        context: Context,
        path: String?,
        loadOnlyWifi: Boolean = false,
        sourceOrigin: String? = null,
        name: String? = null,
        onLoadFinish: (() -> Unit)? = null,
    ): RequestBuilder<Drawable> {
        val defaultDrawable = defaultDrawableFor(name)
        if (AppConfig.useDefaultCover) {
            return ImageLoader.load(context, defaultDrawable)
                .centerCrop()
        }
        var options = RequestOptions().set(OkHttpModelLoader.loadOnlyWifiOption, loadOnlyWifi)
        if (sourceOrigin != null) {
            options = options.set(OkHttpModelLoader.sourceOriginOption, sourceOrigin)
        }
        var builder = ImageLoader.load(context, path)
            .apply(options)
        if (onLoadFinish != null) {
            builder = builder.addListener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable?>,
                    isFirstResource: Boolean,
                ): Boolean {
                    onLoadFinish.invoke()
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable?>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean,
                ): Boolean {
                    onLoadFinish.invoke()
                    return false
                }
            })
        }
        return builder.placeholder(defaultDrawable)
            .error(defaultDrawable)
            .centerCrop()
    }

    /**
     * 加载漫画图片
     */
    fun loadManga(
        context: Context,
        path: String?,
        loadOnlyWifi: Boolean = false,
        sourceOrigin: String? = null,
        transformation: Transformation<Bitmap>? = null,
    ): RequestBuilder<Drawable> {
        var options = RequestOptions().set(OkHttpModelLoader.loadOnlyWifiOption, loadOnlyWifi)
            .set(OkHttpModelLoader.mangaOption, true)
        if (sourceOrigin != null) {
            options = options.set(OkHttpModelLoader.sourceOriginOption, sourceOrigin)
        }
        return ImageLoader.load(context, path)
            .apply(options)
            .override(context.resources.displayMetrics.widthPixels, SIZE_ORIGINAL)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .skipMemoryCache(true).let {
                if (transformation != null) {
                    it.transform(transformation)
                } else {
                    it
                }
            }
    }

    fun preloadManga(
        context: Context,
        path: String?,
        loadOnlyWifi: Boolean = false,
        sourceOrigin: String? = null,
    ): RequestBuilder<File?> {
        var options = RequestOptions().set(OkHttpModelLoader.loadOnlyWifiOption, loadOnlyWifi)
            .set(OkHttpModelLoader.mangaOption, true)
        if (sourceOrigin != null) {
            options = options.set(OkHttpModelLoader.sourceOriginOption, sourceOrigin)
        }
        return Glide.with(context)
            .downloadOnly()
            .apply(options)
            .load(path)
    }

    fun getCoverRule(): CoverRule {
        return getConfig() ?: DefaultData.coverRule
    }

    fun getConfig(): CoverRule? {
        return GSON.fromJsonObject<CoverRule>(CacheManager.get(coverRuleConfigKey))
            .getOrNull()
    }

    suspend fun searchCover(book: Book): String? {
        val config = getCoverRule()
        if (!config.enable || config.searchUrl.isBlank() || config.coverRule.isBlank()) {
            return null
        }
        val analyzeUrl = AnalyzeUrl(
            config.searchUrl,
            book.name,
            source = config,
            coroutineContext = currentCoroutineContext(),
            hasLoginHeader = false
        )
        val res = analyzeUrl.getStrResponseAwait()
        val analyzeRule = AnalyzeRule(book)
        analyzeRule.setCoroutineContext(currentCoroutineContext())
        analyzeRule.setContent(res.body)
        analyzeRule.setRedirectUrl(res.url)
        return analyzeRule.getString(config.coverRule, isUrl = true)
    }

    fun saveCoverRule(config: CoverRule) {
        val json = GSON.toJson(config)
        saveCoverRule(json)
    }

    fun saveCoverRule(json: String) {
        CacheManager.put(coverRuleConfigKey, json)
    }

    fun delCoverRule() {
        CacheManager.delete(coverRuleConfigKey)
    }

    @Keep
    data class CoverRule(
        var enable: Boolean = true,
        var searchUrl: String,
        var coverRule: String,
        override var concurrentRate: String? = null,
        override var loginUrl: String? = null,
        override var loginUi: String? = null,
        override var header: String? = null,
        override var jsLib: String? = null,
        override var enabledCookieJar: Boolean? = false,
    ) : BaseSource {

        override fun getTag(): String {
            return searchUrl
        }

        override fun getKey(): String {
            return searchUrl
        }
    }

}