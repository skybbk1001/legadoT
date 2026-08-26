package io.legado.app.model.readaloud

import io.legado.app.data.entities.RoleCast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechScriptTest {

    private val narrator = RoleCast(bookUrl = "b", roleName = RoleCast.NARRATOR, ttsEngineId = 1)
    private val lin = RoleCast(bookUrl = "b", roleName = "林风", ttsEngineId = 2, voice = "v2")

    /** 不变式: 有序、首尾相接、完整覆盖、每段都有 */
    private fun assertWellFormed(paragraphs: List<String>, segments: List<Segment>) {
        var index = 0
        paragraphs.forEachIndexed { p, text ->
            var cursor = 0
            var count = 0
            while (index < segments.size && segments[index].p == p) {
                val seg = segments[index]
                assertEquals("段 $p 片段不相接", cursor, seg.s)
                cursor = seg.e
                index++
                count++
            }
            // 空段落的覆盖判定退化为 0 == 0, 片段数单独查
            assertTrue("段 $p 没有片段", count > 0)
            assertEquals("段 $p 未被完整覆盖", text.length, cursor)
        }
        assertEquals("有多余片段", index, segments.size)
    }

    @Test
    fun `no annotation degrades to one narrator segment per paragraph`() {
        val paragraphs = listOf("第一段", "第二段内容")
        val segments = SpeechScript.sanitize(paragraphs, emptyList())
        assertWellFormed(paragraphs, segments)
        assertEquals(2, segments.size)
        assertEquals(RoleCast.NARRATOR, segments[0].role)
        assertEquals(Segment(1, 0, 5, RoleCast.NARRATOR), segments[1])
    }

    @Test
    fun `an empty paragraph keeps a zero length narrator segment`() {
        val paragraphs = listOf("", "abc")
        val segments = SpeechScript.sanitize(paragraphs, emptyList())
        assertWellFormed(paragraphs, segments)
        assertEquals(
            listOf(
                Segment(0, 0, 0, RoleCast.NARRATOR),
                Segment(1, 0, 3, RoleCast.NARRATOR)
            ),
            segments
        )
    }

    @Test
    fun `a well formed annotation is kept as is`() {
        val paragraphs = listOf("林风冷冷道：滚。")
        val raw = listOf(
            Segment(0, 0, 6, RoleCast.NARRATOR),
            Segment(0, 6, 8, "林风")
        )
        val segments = SpeechScript.sanitize(paragraphs, raw)
        assertWellFormed(paragraphs, segments)
        assertEquals(raw, segments)
    }

    @Test
    fun `out of order segments are sorted before validation`() {
        val paragraphs = listOf("林风冷冷道：滚。")
        val raw = listOf(
            Segment(0, 6, 8, "林风"),
            Segment(0, 0, 6, RoleCast.NARRATOR)
        )
        val segments = SpeechScript.sanitize(paragraphs, raw)
        assertWellFormed(paragraphs, segments)
        assertEquals(2, segments.size)
    }

    @Test
    fun `a gap makes the whole paragraph fall back to narrator`() {
        val paragraphs = listOf("林风冷冷道：滚。")
        val raw = listOf(
            Segment(0, 0, 5, RoleCast.NARRATOR),
            Segment(0, 6, 8, "林风")
        )
        val segments = SpeechScript.sanitize(paragraphs, raw)
        assertEquals(listOf(Segment(0, 0, 8, RoleCast.NARRATOR)), segments)
    }

    @Test
    fun `an overlap makes the whole paragraph fall back to narrator`() {
        val paragraphs = listOf("林风冷冷道：滚。")
        val raw = listOf(
            Segment(0, 0, 7, RoleCast.NARRATOR),
            Segment(0, 6, 8, "林风")
        )
        assertEquals(
            listOf(Segment(0, 0, 8, RoleCast.NARRATOR)),
            SpeechScript.sanitize(paragraphs, raw)
        )
    }

    @Test
    fun `an incomplete tail is padded with a narrator segment`() {
        val paragraphs = listOf("林风冷冷道：滚。")
        val raw = listOf(Segment(0, 0, 6, "林风"))
        val segments = SpeechScript.sanitize(paragraphs, raw)
        assertWellFormed(paragraphs, segments)
        assertEquals(
            listOf(
                Segment(0, 0, 6, "林风"),
                Segment(0, 6, 8, RoleCast.NARRATOR)
            ),
            segments
        )
    }

    @Test
    fun `a narrator tail absorbs the uncovered rest instead of splitting`() {
        val paragraphs = listOf("林风冷冷道：滚。")
        val raw = listOf(Segment(0, 0, 6, RoleCast.NARRATOR))
        assertEquals(
            listOf(Segment(0, 0, 8, RoleCast.NARRATOR)),
            SpeechScript.sanitize(paragraphs, raw)
        )
    }

    @Test
    fun `an end index past the paragraph is clamped to its length`() {
        val paragraphs = listOf("abc")
        val segments = SpeechScript.sanitize(paragraphs, listOf(Segment(0, 0, 9, "林风")))
        assertWellFormed(paragraphs, segments)
        assertEquals(listOf(Segment(0, 0, 3, "林风")), segments)
    }

    @Test
    fun `segments starting past the paragraph end are dropped`() {
        val paragraphs = listOf("abc")
        val segments = SpeechScript.sanitize(
            paragraphs,
            listOf(Segment(0, 0, 3, "林风"), Segment(0, 3, 6, "苏眉"))
        )
        assertWellFormed(paragraphs, segments)
        assertEquals(listOf(Segment(0, 0, 3, "林风")), segments)
    }

    @Test
    fun `sanitize is idempotent over its own output`() {
        val paragraphs = listOf("", "林风冷冷道：滚。", "他转身就走")
        val once = SpeechScript.sanitize(
            paragraphs,
            listOf(Segment(1, 0, 6, RoleCast.NARRATOR), Segment(1, 6, 99, "林风"))
        )
        assertEquals(once, SpeechScript.sanitize(paragraphs, once))
    }

    @Test
    fun `empty or unnamed segments fall back to narrator`() {
        val paragraphs = listOf("abc")
        assertEquals(
            listOf(Segment(0, 0, 3, RoleCast.NARRATOR)),
            SpeechScript.sanitize(paragraphs, listOf(Segment(0, 0, 0, "林风"), Segment(0, 0, 3, "林风")))
        )
        assertEquals(
            listOf(Segment(0, 0, 3, RoleCast.NARRATOR)),
            SpeechScript.sanitize(paragraphs, listOf(Segment(0, 0, 3, "  ")))
        )
    }

    @Test
    fun `segments referencing a missing paragraph are dropped`() {
        val paragraphs = listOf("abc")
        val segments = SpeechScript.sanitize(
            paragraphs,
            listOf(Segment(0, 0, 3, "林风"), Segment(7, 0, 3, "林风"))
        )
        assertWellFormed(paragraphs, segments)
        assertEquals(1, segments.size)
    }

    @Test
    fun `segmentsOf returns the sorted slices of one paragraph`() {
        val paragraphs = listOf("林风冷冷道：滚。", "他转身就走")
        val script = script(paragraphs, listOf(
            Segment(0, 0, 6, RoleCast.NARRATOR),
            Segment(0, 6, 8, "林风"),
            Segment(1, 0, 5, RoleCast.NARRATOR)
        ))
        assertEquals(2, script.segmentsOf(0).size)
        assertEquals(1, script.segmentsOf(1).size)
        assertEquals(emptyList<Segment>(), script.segmentsOf(9))
    }

    @Test
    fun `textOf slices the paragraph and honours a mid paragraph start`() {
        val paragraphs = listOf("林风冷冷道：滚。")
        val script = script(paragraphs, listOf(
            Segment(0, 0, 6, RoleCast.NARRATOR),
            Segment(0, 6, 8, "林风")
        ))
        assertEquals("林风冷冷道：", script.textOf(script.segmentsOf(0)[0]))
        assertEquals("滚。", script.textOf(script.segmentsOf(0)[1]))
        // 从第 3 字起播: 首片段被截断, 后续片段整段读
        assertEquals("冷道：", script.textOf(script.segmentsOf(0)[0], 3))
        assertEquals("滚。", script.textOf(script.segmentsOf(0)[1], 3))
    }

    @Test
    fun `segmentIndexAt finds the slice covering a character offset`() {
        val paragraphs = listOf("林风冷冷道：滚。")
        val script = script(paragraphs, listOf(
            Segment(0, 0, 6, RoleCast.NARRATOR),
            Segment(0, 6, 8, "林风")
        ))
        assertEquals(0, script.segmentIndexAt(0, 0))
        assertEquals(0, script.segmentIndexAt(0, 5))
        assertEquals(1, script.segmentIndexAt(0, 6))
        assertEquals(1, script.segmentIndexAt(0, 7))
        // 越界钳到末片段, 空段落返回 0
        assertEquals(1, script.segmentIndexAt(0, 99))
        assertEquals(0, script.segmentIndexAt(9, 0))
    }

    @Test
    fun `castOf resolves known roles and falls back for unknown ones`() {
        val paragraphs = listOf("林风冷冷道：滚。")
        val script = script(paragraphs, listOf(
            Segment(0, 0, 6, RoleCast.NARRATOR),
            Segment(0, 6, 8, "林风")
        ))
        assertSame(narrator, script.castOf(script.segmentsOf(0)[0]))
        assertSame(lin, script.castOf(script.segmentsOf(0)[1]))
        assertSame(narrator, script.castOf(Segment(0, 0, 1, "查无此人")))
    }

    @Test
    fun `narratorOnly builds a degraded script covering every paragraph`() {
        val paragraphs = listOf("第一段", "第二段内容")
        val script = SpeechScript.narratorOnly(paragraphs, narrator)
        assertEquals(1, script.segmentsOf(0).size)
        assertEquals("第二段内容", script.textOf(script.segmentsOf(1)[0]))
        assertSame(narrator, script.castOf(script.segmentsOf(1)[0]))
    }

    private fun script(paragraphs: List<String>, raw: List<Segment>) = SpeechScript(
        paragraphs = paragraphs,
        segments = SpeechScript.sanitize(paragraphs, raw),
        cast = mapOf(RoleCast.NARRATOR to narrator, "林风" to lin),
        fallback = narrator
    )
}
