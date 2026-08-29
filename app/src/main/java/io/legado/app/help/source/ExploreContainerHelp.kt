package io.legado.app.help.source

import com.google.gson.JsonObject
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.ExploreContainer
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.cnCompare
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 发现容器书籍缓存壳:带写入时间与页码;v1 裸数组按 time=0/page=1 兼容 */
data class CachedExploreBooks(
    val version: Int = 1,
    val time: Long = 0,
    val page: Int = 1,
    val books: List<SearchBook> = emptyList(),
)

/**
 * 发现容器:分类 URL 解析与书籍数据磁盘缓存
 */
object ExploreContainerHelp {

    // lazy 保证 JVM 单测只调 resolve/json 函数时不触发 Android 依赖
    private val aCache by lazy { ACache.get("exploreContainerBooks") }

    /**
     * 优先按分类名在书源当前分类列表中匹配最新 URL(兼容 JS 动态生成的分类),
     * 同名分类以 URL 快照精确匹配优先;
     * 匹配不到或匹配项 URL 为空时回退到添加容器时的快照
     */
    fun resolveKindUrl(kinds: List<ExploreKind>, kindTitle: String, fallbackUrl: String): String {
        return kinds.firstOrNull { it.title == kindTitle && it.url == fallbackUrl }?.url
            ?: kinds.firstOrNull { it.title == kindTitle && !it.url.isNullOrBlank() }?.url
            ?: fallbackUrl
    }

    fun booksToJson(books: List<SearchBook>): String = GSON.toJson(books)

    fun booksFromJson(json: String?): List<SearchBook>? =
        GSON.fromJsonArray<SearchBook>(json).getOrNull()

    suspend fun getCached(containerId: Long): CachedExploreBooks? =
        withContext(Dispatchers.IO) {
            cachedFromJson(aCache.getAsString(containerId.toString()))
        }

    /**
     * 写缓存壳;写后校验容器仍存在,已删则立即清除。
     * 删除路径均为"先删行、后删缓存",写后校验覆盖删除与写入的全部交错序
     */
    suspend fun putCached(containerId: Long, books: List<SearchBook>, page: Int, time: Long) {
        withContext(Dispatchers.IO) {
            aCache.put(
                containerId.toString(),
                cachedToJson(CachedExploreBooks(time = time, page = page, books = books))
            )
            if (appDb.exploreContainerDao.getById(containerId) == null) {
                aCache.remove(containerId.toString())
            }
        }
    }

    suspend fun removeCache(containerId: Long) {
        withContext(Dispatchers.IO) {
            aCache.remove(containerId.toString())
        }
    }

    /** 缓存有效期,超过则进入页面时静默重拉(真机验收可临时调小) */
    const val CACHE_EXPIRE_MS = 24 * 60 * 60 * 1000L

    fun cachedToJson(cached: CachedExploreBooks): String = GSON.toJson(cached)

    /** 解析缓存壳;v1 裸数组包壳兼容;坏 JSON/缺 books 字段返回 null */
    fun cachedFromJson(json: String?): CachedExploreBooks? {
        if (json.isNullOrBlank()) return null
        if (json.trimStart().startsWith("[")) {
            return booksFromJson(json)?.let { CachedExploreBooks(time = 0, page = 1, books = it) }
        }
        return runCatching {
            val jsonObj = GSON.fromJson(json, JsonObject::class.java) ?: return null
            // books 字段必须存在(缺 books 字段返回 null)
            if (!jsonObj.has("books")) return null
            val shell: CachedExploreBooks? =
                GSON.fromJson(json, CachedExploreBooks::class.java)
            // GSON unsafe 分配不执行 Kotlin 默认值,缺字段是 JVM 零值
            @Suppress("SENSELESS_COMPARISON")
            if (shell == null || shell.books == null) return null
            shell.copy(page = shell.page.coerceAtLeast(1))
        }.getOrNull()
    }

    fun isExpired(time: Long, now: Long): Boolean = now - time > CACHE_EXPIRE_MS

    /** 过滤出可用于展示/切换的有效分类:URL 非空且标题非 ERROR 前缀;按标题+URL 去重 */
    fun validKinds(kinds: List<ExploreKind>): List<ExploreKind> =
        kinds.filter { !it.url.isNullOrBlank() && !it.title.startsWith("ERROR:") }
            .distinctBy { it.title to it.url }

    /**
     * 解析容器应展示的分类标签:
     * 添加时勾选了分类(kindTitles 非空)则只展示勾选的那几个(按勾选顺序),
     * 分类名匹配不到当前书源分类时用快照 URL 兜底;旧数据(kindTitles 空)展示书源全部分类。
     */
    fun resolveContainerKinds(
        container: ExploreContainer,
        sourceKinds: List<ExploreKind>,
    ): List<ExploreKind> {
        val selectedTitles = container.kindTitles.splitNotBlank(AppPattern.splitGroupRegex)
        if (selectedTitles.isEmpty()) return validKinds(sourceKinds)
        val valid = validKinds(sourceKinds)
        val selectedUrls = container.kindUrls.splitNotBlank(AppPattern.splitGroupRegex)
        return selectedTitles.mapIndexed { index, title ->
            val snapshotUrl = selectedUrls.getOrNull(index)
            valid.firstOrNull {
                it.title == title && snapshotUrl != null && it.url == snapshotUrl
            } ?: valid.firstOrNull { it.title == title }
            ?: ExploreKind(title, snapshotUrl ?: "")
        }.filter { !it.url.isNullOrBlank() }
    }

    /** 原始分组串列表 → 切分/去重/中文排序;DAO flowGroups 与发现页 chips 共用 */
    fun dealGroups(list: List<String>): List<String> {
        val groups = linkedSetOf<String>()
        list.forEach {
            it.splitNotBlank(AppPattern.splitGroupRegex).forEach { group ->
                groups.add(group)
            }
        }
        return groups.sortedWith { o1, o2 -> o1.cnCompare(o2) }
    }

    /**
     * 分组弹层行 value / 管理页筛选值命名空间:
     * 特殊行用固定值,分组行加前缀,分组名与固定值不撞
     */
    const val GROUP_VALUE_ALL = "all"
    const val GROUP_VALUE_NO_GROUP = "no_group"
    const val GROUP_VALUE_MANAGE = "manage"
    const val GROUP_VALUE_PREFIX = "group:"

    /** 按筛选值过滤:空=全量;no_group=未分组;其余剥 group: 前缀后 hasGroup 精确匹配 */
    fun filterByGroup(
        containers: List<ExploreContainer>,
        filter: String,
    ): List<ExploreContainer> = when {
        filter.isEmpty() -> containers
        filter == GROUP_VALUE_NO_GROUP -> containers.filter { it.groupName.isEmpty() }
        else -> {
            val group = filter.removePrefix(GROUP_VALUE_PREFIX)
            containers.filter { it.hasGroup(group) }
        }
    }

    /** 相对时间;time<=0 返回 null(界面隐藏标签) */
    fun formatUpdateTime(time: Long, now: Long): String? {
        if (time <= 0) return null
        val diff = now - time
        return when {
            diff < 60_000L -> "刚刚"
            diff < 3600_000L -> "${diff / 60_000L}分钟前"
            diff < 86400_000L -> "${diff / 3600_000L}小时前"
            else -> "${diff / 86400_000L}天前"
        }
    }
}
