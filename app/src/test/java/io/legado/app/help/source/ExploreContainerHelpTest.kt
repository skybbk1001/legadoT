package io.legado.app.help.source

import io.legado.app.data.entities.ExploreContainer
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExploreContainerHelpTest {

    private val kinds = listOf(
        ExploreKind("玄幻", "https://a.com/xuanhuan/{{page}}"),
        ExploreKind("都市", "https://a.com/dushi/{{page}}"),
        ExploreKind("分组标题", null),
    )

    @Test
    fun resolve_prefers_current_kind_url_by_title() {
        val url = ExploreContainerHelp.resolveKindUrl(kinds, "玄幻", "https://a.com/old")
        assertEquals("https://a.com/xuanhuan/{{page}}", url)
    }

    @Test
    fun resolve_falls_back_to_snapshot_when_title_missing() {
        val url = ExploreContainerHelp.resolveKindUrl(kinds, "已删除分类", "https://a.com/old")
        assertEquals("https://a.com/old", url)
    }

    @Test
    fun resolve_falls_back_when_matched_kind_has_blank_url() {
        val url = ExploreContainerHelp.resolveKindUrl(kinds, "分组标题", "https://a.com/old")
        assertEquals("https://a.com/old", url)
    }

    @Test
    fun resolve_prefers_exact_url_match_among_duplicate_titles() {
        val dup = listOf(
            ExploreKind("更多", "https://a.com/fantasy/more"),
            ExploreKind("更多", "https://a.com/city/more"),
        )
        val url = ExploreContainerHelp.resolveKindUrl(dup, "更多", "https://a.com/city/more")
        assertEquals("https://a.com/city/more", url)
    }

    @Test
    fun valid_kinds_filters_error_and_blank_url_and_dedups() {
        val kinds = listOf(
            ExploreKind("玄幻", "https://a.com/xh"),
            ExploreKind("分组标题", null),
            ExploreKind("ERROR:js", "stacktrace"),
            ExploreKind("玄幻", "https://a.com/xh"), // 重复
            ExploreKind("", "https://a.com/blank-title"),
        )
        val out = ExploreContainerHelp.validKinds(kinds)
        assertEquals(listOf("玄幻", ""), out.map { it.title })
    }

    @Test
    fun resolve_container_kinds_limited_to_selected() {
        val container = ExploreContainer(
            kindTitle = "玄幻",
            kindUrl = "https://a.com/xh",
            kindTitles = "玄幻,都市",
            kindUrls = "https://a.com/xh,https://a.com/ds",
        )
        val sourceKinds = listOf(
            ExploreKind("玄幻", "https://a.com/xh"),
            ExploreKind("都市", "https://a.com/ds"),
            ExploreKind("科幻", "https://a.com/kh"),
        )
        val out = ExploreContainerHelp.resolveContainerKinds(container, sourceKinds)
        assertEquals(listOf("玄幻", "都市"), out.map { it.title })
        assertEquals(2, out.size)
    }

    @Test
    fun resolve_container_kinds_falls_back_to_snapshot_url() {
        val container = ExploreContainer(
            kindTitle = "玄幻",
            kindUrl = "https://a.com/xh",
            kindTitles = "已删除分类",
            kindUrls = "https://a.com/old",
        )
        val out = ExploreContainerHelp.resolveContainerKinds(container, emptyList())
        assertEquals(listOf("已删除分类"), out.map { it.title })
        assertEquals("https://a.com/old", out[0].url)
    }

    @Test
    fun resolve_container_kinds_empty_titles_returns_all() {
        val container = ExploreContainer(kindTitle = "玄幻", kindUrl = "https://a.com/xh")
        val sourceKinds = listOf(
            ExploreKind("玄幻", "https://a.com/xh"),
            ExploreKind("都市", "https://a.com/ds"),
        )
        val out = ExploreContainerHelp.resolveContainerKinds(container, sourceKinds)
        assertEquals(listOf("玄幻", "都市"), out.map { it.title })
    }

    @Test
    fun resolve_container_kinds_prefers_exact_snapshot_url_on_duplicate_title() {
        val container = ExploreContainer(
            kindTitle = "更多",
            kindUrl = "https://a.com/city/more",
            kindTitles = "更多",
            kindUrls = "https://a.com/city/more",
        )
        val dup = listOf(
            ExploreKind("更多", "https://a.com/fantasy/more"),
            ExploreKind("更多", "https://a.com/city/more"),
        )
        val out = ExploreContainerHelp.resolveContainerKinds(container, dup)
        assertEquals("https://a.com/city/more", out[0].url)
    }

    @Test
    fun books_json_round_trip() {
        val books = listOf(
            SearchBook(
                bookUrl = "https://a.com/b/1", origin = "https://a.com",
                name = "斗破苍穹", author = "天蚕土豆",
                coverUrl = "https://a.com/c/1.jpg", intro = "简介"
            ),
            SearchBook(
                bookUrl = "https://a.com/b/2", origin = "https://a.com",
                name = "完美世界", author = "辰东"
            ),
        )
        val json = ExploreContainerHelp.booksToJson(books)
        val parsed = ExploreContainerHelp.booksFromJson(json)
        assertEquals(2, parsed!!.size)
        assertEquals("斗破苍穹", parsed[0].name)
        assertEquals("https://a.com/b/2", parsed[1].bookUrl)
    }

    @Test
    fun books_from_invalid_json_returns_null() {
        assertNull(ExploreContainerHelp.booksFromJson("not json"))
        assertNull(ExploreContainerHelp.booksFromJson(null))
    }

    @Test
    fun books_from_empty_json_array_returns_empty_list_not_null() {
        val parsed = ExploreContainerHelp.booksFromJson("[]")
        assertEquals(0, parsed!!.size)
    }

    // ===== 缓存壳 =====

    @Test
    fun cached_round_trip() {
        val books = listOf(
            SearchBook(
                bookUrl = "https://a.com/b/1", origin = "https://a.com",
                name = "斗破苍穹", author = "天蚕土豆"
            )
        )
        val json = ExploreContainerHelp.cachedToJson(
            CachedExploreBooks(time = 123L, page = 5, books = books)
        )
        val parsed = ExploreContainerHelp.cachedFromJson(json)!!
        assertEquals(123L, parsed.time)
        assertEquals(5, parsed.page)
        assertEquals("斗破苍穹", parsed.books[0].name)
    }

    @Test
    fun cached_from_legacy_bare_array_defaults_time0_page1() {
        val books = listOf(
            SearchBook(bookUrl = "https://a.com/b/1", origin = "https://a.com", name = "完美世界")
        )
        val legacyJson = ExploreContainerHelp.booksToJson(books)
        val parsed = ExploreContainerHelp.cachedFromJson(legacyJson)!!
        assertEquals(0L, parsed.time)
        assertEquals(1, parsed.page)
        assertEquals("完美世界", parsed.books[0].name)
    }

    @Test
    fun cached_from_invalid_json_returns_null() {
        assertNull(ExploreContainerHelp.cachedFromJson(null))
        assertNull(ExploreContainerHelp.cachedFromJson(""))
        assertNull(ExploreContainerHelp.cachedFromJson("not json"))
        // GSON unsafe 分配不执行 Kotlin 默认值,缺 books 字段是 null,必须判掉
        assertNull(ExploreContainerHelp.cachedFromJson("{\"time\":1}"))
    }

    @Test
    fun cached_page_below_1_is_clamped() {
        val parsed = ExploreContainerHelp.cachedFromJson("{\"time\":1,\"page\":0,\"books\":[]}")!!
        assertEquals(1, parsed.page)
    }

    // ===== 过期判断 =====

    @Test
    fun expired_when_time_zero_or_older_than_24h() {
        val now = 1_000_000_000_000L
        assertTrue(ExploreContainerHelp.isExpired(0L, now))
        assertTrue(ExploreContainerHelp.isExpired(now - 25 * 3600_000L, now))
        assertFalse(ExploreContainerHelp.isExpired(now - 23 * 3600_000L, now))
    }

    // ===== 相对时间 =====

    @Test
    fun format_update_time() {
        val now = 1_000_000_000_000L
        assertNull(ExploreContainerHelp.formatUpdateTime(0L, now))
        assertEquals("刚刚", ExploreContainerHelp.formatUpdateTime(now - 30_000L, now))
        assertEquals("5分钟前", ExploreContainerHelp.formatUpdateTime(now - 5 * 60_000L, now))
        assertEquals("3小时前", ExploreContainerHelp.formatUpdateTime(now - 3 * 3600_000L, now))
        assertEquals("2天前", ExploreContainerHelp.formatUpdateTime(now - 2 * 86400_000L, now))
        // 时钟回拨(time 在未来)按刚刚
        assertEquals("刚刚", ExploreContainerHelp.formatUpdateTime(now + 60_000L, now))
    }

    // ===== 多分组 =====

    @Test
    fun group_add_dedup_and_normalize_delimiters() {
        val c = ExploreContainer(groupName = "玄幻")
        c.addGroup("精品;玄幻,新组")
        assertEquals("玄幻,精品,新组", c.groupName)
    }

    @Test
    fun group_add_to_empty() {
        val c = ExploreContainer()
        c.addGroup("单组")
        assertEquals("单组", c.groupName)
    }

    @Test
    fun group_remove_and_removing_last_leaves_empty() {
        val c = ExploreContainer(groupName = "玄幻,精品")
        c.removeGroup("玄幻")
        assertEquals("精品", c.groupName)
        c.removeGroup("精品")
        assertEquals("", c.groupName)
    }

    @Test
    fun group_has_exact_match_not_substring() {
        val c = ExploreContainer(groupName = "东方玄幻,精品")
        assertTrue(c.hasGroup("东方玄幻"))
        assertFalse(c.hasGroup("玄幻"))
        assertFalse(ExploreContainer().hasGroup("玄幻"))
    }

    @Test
    fun deal_groups_split_dedup_sort() {
        val out = ExploreContainerHelp.dealGroups(listOf("b组,a组", "a组;c组", ""))
        assertEquals(listOf("a组", "b组", "c组"), out)
    }

    private fun container(id: Long, group: String) =
        ExploreContainer(id = id, groupName = group)

    private val containers = listOf(
        container(1, "玄幻"),
        container(2, "东方玄幻"),
        container(3, "玄幻,都市"),
        container(4, ""),
        container(5, "no_group"),
    )

    @Test
    fun filter_empty_returns_everything() {
        val result = ExploreContainerHelp.filterByGroup(containers, "")
        assertEquals(5, result.size)
    }

    @Test
    fun filter_no_group_returns_only_ungrouped() {
        val result = ExploreContainerHelp.filterByGroup(
            containers, ExploreContainerHelp.GROUP_VALUE_NO_GROUP
        )
        assertEquals(listOf(4L), result.map { it.id })
    }

    @Test
    fun filter_group_matches_exactly_not_substring() {
        val result = ExploreContainerHelp.filterByGroup(
            containers, ExploreContainerHelp.GROUP_VALUE_PREFIX + "玄幻"
        )
        assertEquals(listOf(1L, 3L), result.map { it.id })
    }

    @Test
    fun filter_group_named_like_sentinel_is_unambiguous() {
        val result = ExploreContainerHelp.filterByGroup(
            containers, ExploreContainerHelp.GROUP_VALUE_PREFIX + "no_group"
        )
        assertEquals(listOf(5L), result.map { it.id })
    }
}
