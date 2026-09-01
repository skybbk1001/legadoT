package io.legado.app.ui.book.toc

import io.legado.app.data.entities.BookChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TocListStateTest {

    @Test
    fun `book without volumes stays flat`() {
        val state = TocListState()
        state.setFullChapters(listOf(chapter(0), chapter(1), chapter(2)), durChapterIndex = 1, resetCollapse = true)

        val items = state.showNormal(durChapterIndex = 1)

        assertEquals(listOf("chapter:0", "chapter:1", "chapter:2"), items.map { it.key })
        assertEquals(listOf(0, 0, 0), items.map { it.depth })
    }

    @Test
    fun `default collapse keeps current volume expanded`() {
        val state = TocListState()
        state.setFullChapters(
            listOf(volume(0, "第一卷"), chapter(1), chapter(2), volume(3, "第二卷"), chapter(4), chapter(5)),
            durChapterIndex = 4,
            resetCollapse = true
        )

        val items = state.showNormal(durChapterIndex = 4)

        assertTrue(state.isVolumeCollapsed(0))
        assertFalse(state.isVolumeCollapsed(3))
        assertEquals(listOf("volume:0", "volume:3", "chapter:4", "chapter:5"), items.map { it.key })
        assertEquals(2, state.findVisiblePositionByChapterIndex(4))
    }

    @Test
    fun `toggle volume changes visible chapter rows`() {
        val state = TocListState()
        state.setFullChapters(
            listOf(volume(0, "第一卷"), chapter(1), chapter(2), volume(3, "第二卷"), chapter(4)),
            durChapterIndex = 1,
            resetCollapse = true
        )
        assertTrue(state.isVolumeCollapsed(3))

        assertTrue(state.toggleVolume(3))
        val expanded = state.showNormal(durChapterIndex = 1)

        assertFalse(state.isVolumeCollapsed(3))
        assertEquals(listOf("volume:0", "chapter:1", "chapter:2", "volume:3", "chapter:4"), expanded.map { it.key })
    }

    @Test
    fun `hidden chapter falls back to parent volume position`() {
        val state = TocListState()
        state.setFullChapters(
            listOf(volume(0, "第一卷"), chapter(1), volume(2, "第二卷"), chapter(3)),
            durChapterIndex = 1,
            resetCollapse = true
        )
        state.showNormal(durChapterIndex = 1)

        assertEquals(-1, state.findVisiblePositionByChapterIndex(3))
        assertEquals(2, state.findFallbackVisiblePositionForChapterIndex(3))
    }

    @Test
    fun `expand volume containing chapter makes position visible`() {
        val state = TocListState()
        state.setFullChapters(
            listOf(volume(0, "第一卷"), chapter(1), volume(2, "第二卷"), chapter(3)),
            durChapterIndex = 1,
            resetCollapse = true
        )
        state.showNormal(durChapterIndex = 1)

        assertTrue(state.expandVolumeContainingChapter(3))
        state.showNormal(durChapterIndex = 1)

        assertEquals(3, state.findVisiblePositionByChapterIndex(3))
    }

    @Test
    fun `reversed toc groups chapters preceding their volume`() {
        val state = TocListState()
        // 原序: V1(0), c1(1), c2(2), V2(3), c3(4), c4(5)
        // reverseToc 重写 index 后 DB 顺序: c4(0), c3(1), V2(2), c2(3), c1(4), V1(5)
        val reversed = listOf(
            chapter(0, "第4章"), chapter(1, "第3章"), volume(2, "第二卷"),
            chapter(3, "第2章"), chapter(4, "第1章"), volume(5, "第一卷")
        )
        state.setFullChapters(reversed, durChapterIndex = 0, resetCollapse = true, reversed = true)

        // dur=第4章(属于第二卷):默认只展开第二卷,卷头置于组首,组间/组内保持"最新在前"
        val items = state.showNormal(durChapterIndex = 0)

        assertEquals(listOf("volume:2", "chapter:0", "chapter:1", "volume:5"), items.map { it.key })
        assertEquals(2, (items[0] as TocListItem.Volume).chapterCount)
        assertEquals(2, (items[3] as TocListItem.Volume).chapterCount)
        assertEquals(2, state.parentVolumeIndexOf(0))
        assertEquals(5, state.parentVolumeIndexOf(3))
    }

    @Test
    fun `reversed toc expand and fallback use corrected parent map`() {
        val state = TocListState()
        val reversed = listOf(
            chapter(0, "第4章"), chapter(1, "第3章"), volume(2, "第二卷"),
            chapter(3, "第2章"), chapter(4, "第1章"), volume(5, "第一卷")
        )
        state.setFullChapters(reversed, durChapterIndex = 0, resetCollapse = true, reversed = true)
        state.showNormal(durChapterIndex = 0)
        assertTrue(state.isVolumeCollapsed(5))

        assertTrue(state.expandVolumeContainingChapter(3))
        state.showNormal(durChapterIndex = 0)

        assertEquals(4, state.findVisiblePositionByChapterIndex(3))
    }

    @Test
    fun `search volume rows are not toggleable but normal ones are`() {
        val state = TocListState()
        state.setFullChapters(
            listOf(volume(0, "第一卷"), chapter(1), volume(2, "第二卷"), chapter(3)),
            durChapterIndex = 1,
            resetCollapse = true
        )
        state.showNormal(durChapterIndex = 1)

        val normalVolume = state.visibleItems.first() as TocListItem.Volume
        assertTrue(normalVolume.toggleable)

        val searchItems = state.showSearch(listOf(chapter(3)), durChapterIndex = 1)
        val searchVolume = searchItems.first() as TocListItem.Volume
        assertFalse(searchVolume.toggleable)
    }

    @Test
    fun `search inserts parent volume context without changing collapse state`() {
        val state = TocListState()
        val all = listOf(volume(0, "第一卷"), chapter(1), chapter(2), volume(3, "第二卷"), chapter(4), chapter(5))
        state.setFullChapters(all, durChapterIndex = 1, resetCollapse = true)
        assertTrue(state.isVolumeCollapsed(3))

        val searchItems = state.showSearch(listOf(chapter(4), chapter(5)), durChapterIndex = 1)

        assertEquals(listOf("volume:3", "chapter:4", "chapter:5"), searchItems.map { it.key })
        val volume = searchItems.first() as TocListItem.Volume
        assertEquals(2, volume.matchedCount)
        assertEquals(2, volume.chapterCount)
        assertTrue(state.isVolumeCollapsed(3))
    }

    @Test
    fun `search volume title match can show volume row without child matches`() {
        val state = TocListState()
        val secondVolume = volume(3, "第二卷")
        state.setFullChapters(
            listOf(volume(0, "第一卷"), chapter(1), secondVolume, chapter(4)),
            durChapterIndex = 1,
            resetCollapse = true
        )

        val searchItems = state.showSearch(listOf(secondVolume), durChapterIndex = 1)

        assertEquals(listOf("volume:3"), searchItems.map { it.key })
        val volumeItem = searchItems.first() as TocListItem.Volume
        assertTrue(volumeItem.matchedSelf)
        assertEquals(0, volumeItem.matchedCount)
    }

    @Test
    fun `chapters under volume use depth one while loose chapters stay depth zero`() {
        val state = TocListState()
        state.setFullChapters(
            listOf(chapter(0), volume(1, "第一卷"), chapter(2), chapter(3)),
            durChapterIndex = 2,
            resetCollapse = true
        )

        val items = state.showNormal(durChapterIndex = 2)

        assertEquals(listOf("chapter:0", "volume:1", "chapter:2", "chapter:3"), items.map { it.key })
        assertEquals(listOf(0, 0, 1, 1), items.map { it.depth })
    }

    @Test
    fun `collapse state is preserved when full chapters refresh without reset`() {
        val state = TocListState()
        val chapters = listOf(volume(0, "第一卷"), chapter(1), volume(2, "第二卷"), chapter(3))
        state.setFullChapters(chapters, durChapterIndex = 1, resetCollapse = true)
        assertTrue(state.toggleVolume(2))
        assertFalse(state.isVolumeCollapsed(2))

        state.setFullChapters(chapters, durChapterIndex = 1, resetCollapse = false)
        state.showNormal(durChapterIndex = 1)

        assertFalse(state.isVolumeCollapsed(2))
        assertEquals(3, state.findVisiblePositionByChapterIndex(3))
    }

    @Test
    fun `reset collapse recomputes current volume`() {
        val state = TocListState()
        val chapters = listOf(volume(0, "第一卷"), chapter(1), volume(2, "第二卷"), chapter(3))
        state.setFullChapters(chapters, durChapterIndex = 1, resetCollapse = true)
        assertTrue(state.isVolumeCollapsed(2))

        state.setFullChapters(chapters, durChapterIndex = 3, resetCollapse = true)
        state.showNormal(durChapterIndex = 3)

        assertTrue(state.isVolumeCollapsed(0))
        assertFalse(state.isVolumeCollapsed(2))
    }

    @Test
    fun `fallback position can find visible volume row`() {
        val state = TocListState()
        state.setFullChapters(
            listOf(volume(0, "第一卷"), chapter(1), volume(2, "第二卷"), chapter(3)),
            durChapterIndex = 2,
            resetCollapse = true
        )
        state.showNormal(durChapterIndex = 2)

        assertEquals(1, state.findFallbackVisiblePositionForChapterIndex(2))
    }

    private fun volume(index: Int, title: String): BookChapter {
        return BookChapter(
            url = "volume-$index",
            title = title,
            isVolume = true,
            bookUrl = "book",
            index = index
        )
    }

    private fun chapter(index: Int, title: String = "第${index}章"): BookChapter {
        return BookChapter(
            url = "chapter-$index",
            title = title,
            isVolume = false,
            bookUrl = "book",
            index = index
        )
    }
}
