package io.legado.app.help

import io.legado.app.help.BottomBarSkinFormat.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomBarSkinFormatTest {

    @Test
    fun `parseEntryName selected`() {
        assertEquals(Entry("bookshelf", true), BottomBarSkinFormat.parseEntryName("bookshelf_selected.png"))
    }

    @Test
    fun `parseEntryName normal`() {
        assertEquals(Entry("home", false), BottomBarSkinFormat.parseEntryName("home_normal.png"))
    }

    @Test
    fun `parseEntryName strips directory prefix`() {
        assertEquals(Entry("notes", true), BottomBarSkinFormat.parseEntryName("sub/notes_selected.png"))
    }

    @Test
    fun `parseEntryName rejects unknown slot`() {
        assertNull(BottomBarSkinFormat.parseEntryName("foo_selected.png"))
    }

    @Test
    fun `parseEntryName accepts jpg`() {
        assertEquals(Entry("home", true), BottomBarSkinFormat.parseEntryName("home_selected.jpg"))
    }

    @Test
    fun `parseEntryName accepts webp normal`() {
        assertEquals(Entry("notes", false), BottomBarSkinFormat.parseEntryName("notes_normal.webp"))
    }

    @Test
    fun `parseEntryName accepts svg`() {
        assertEquals(Entry("bookshelf", true), BottomBarSkinFormat.parseEntryName("bookshelf_selected.svg"))
        assertEquals(Entry("home", false), BottomBarSkinFormat.parseEntryName("HOME_NORMAL.SVG"))
    }

    @Test
    fun `parseEntryName rejects non image extension`() {
        assertNull(BottomBarSkinFormat.parseEntryName("home_selected.txt"))
    }

    @Test
    fun `parseEntryName rejects missing state`() {
        assertNull(BottomBarSkinFormat.parseEntryName("home.png"))
    }

    @Test
    fun `parseEntryName is case insensitive and canonicalizes to lowercase`() {
        assertEquals(Entry("home", true), BottomBarSkinFormat.parseEntryName("HOME_SELECTED.PNG"))
    }

    @Test
    fun `parseEntryName rejects bare png extension`() {
        assertNull(BottomBarSkinFormat.parseEntryName(".png"))
    }

    @Test
    fun `parseEntryName rejects trailing slash`() {
        assertNull(BottomBarSkinFormat.parseEntryName("dir/"))
    }

    @Test
    fun `parseEntryName rejects statistics slot`() {
        assertNull(BottomBarSkinFormat.parseEntryName("statistics_selected.png"))
    }

    @Test
    fun `uniqueName returns desired when free`() {
        assertEquals("Ins蓝", BottomBarSkinFormat.uniqueName("Ins蓝", emptyList()))
    }

    @Test
    fun `uniqueName appends index on collision`() {
        assertEquals("猫 (2)", BottomBarSkinFormat.uniqueName("猫", listOf("猫")))
        assertEquals("猫 (3)", BottomBarSkinFormat.uniqueName("猫", listOf("猫", "猫 (2)")))
    }

    @Test
    fun `sanitize replaces illegal chars`() {
        assertEquals("a_b_c", BottomBarSkinFormat.sanitize("a/b:c"))
    }

    @Test
    fun `sanitize keeps cjk`() {
        assertEquals("颜文字猫", BottomBarSkinFormat.sanitize("颜文字猫"))
    }

    @Test
    fun `sanitize falls back when blank`() {
        assertEquals("skin", BottomBarSkinFormat.sanitize("  "))
    }

    @Test
    fun `isImageName true for image extensions`() {
        assertTrue(BottomBarSkinFormat.isImageName("a.png"))
        assertTrue(BottomBarSkinFormat.isImageName("A.JPG"))
        assertTrue(BottomBarSkinFormat.isImageName("dir/b.webp"))
        assertTrue(BottomBarSkinFormat.isImageName("dir/c.svg"))
    }

    @Test
    fun `isImageName false for non image`() {
        assertFalse(BottomBarSkinFormat.isImageName("a.txt"))
        assertFalse(BottomBarSkinFormat.isImageName("noext"))
    }
}
