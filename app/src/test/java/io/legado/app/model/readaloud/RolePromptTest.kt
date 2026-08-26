package io.legado.app.model.readaloud

import io.legado.app.data.entities.RoleCast
import io.legado.app.data.entities.TtsVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RolePromptTest {

    @Test
    fun `chunks respect an approximate character budget without splitting paragraphs`() {
        assertEquals(
            listOf(0..1, 2..2),
            RolePrompt.chunks(listOf("1234", "5678", "90"), batchSize = 60, maxChars = 8)
        )
        assertEquals(
            listOf(0..0),
            RolePrompt.chunks(listOf("a very long paragraph"), maxChars = 3)
        )
    }

    @Test
    fun `chunks cover every paragraph exactly once`() {
        assertEquals(emptyList<IntRange>(), RolePrompt.chunks(0))
        assertEquals(listOf(0..59), RolePrompt.chunks(60))
        assertEquals(listOf(0..59, 60..60), RolePrompt.chunks(61))
        assertEquals(listOf(0..1, 2..3, 4..4), RolePrompt.chunks(5, batchSize = 2))
    }

    @Test
    fun `user prompt numbers paragraphs with absolute indices and their length`() {
        val prompt = RolePrompt.buildUser(
            listOf("第零段", "第一段", "第二段"), 1..2, emptyList()
        )
        assertTrue(prompt.contains("[1|len=3] 第一段"))
        assertTrue(prompt.contains("[2|len=3] 第二段"))
        assertTrue("不该带上范围外的段落", !prompt.contains("[0|"))
    }

    @Test
    fun `known roles are carried into later chunks but the narrator is not`() {
        val withRoles = RolePrompt.buildUser(listOf("甲"), 0..0, listOf("林风", "旁白", " "))
        assertTrue(withRoles.contains("已知角色：林风"))
        assertTrue("旁白不必告诉模型", !withRoles.contains("旁白"))

        val noRoles = RolePrompt.buildUser(listOf("甲"), 0..0, emptyList())
        assertTrue(!noRoles.contains("已知角色"))
    }

    @Test
    fun `a well formed response parses into segments and roles`() {
        val json = """
            {"roles":[{"name":"林风","gender":"male","age":"young"}],
             "segments":[{"p":0,"s":0,"e":6,"r":"旁白"},{"p":0,"s":6,"e":8,"r":"林风"}]}
        """.trimIndent()
        val script = RolePrompt.parse(json, 0..0)!!
        assertEquals(listOf(RoleProfile("林风", "male", "young")), script.roles)
        assertEquals(Segment(0, 0, 6, "旁白"), script.segments[0])
        assertEquals(Segment(0, 6, 8, "林风"), script.segments[1])
    }

    @Test
    fun `segments outside the requested chunk are dropped`() {
        val json = """{"segments":[{"p":3,"s":0,"e":2,"r":"甲"},{"p":9,"s":0,"e":2,"r":"乙"}]}"""
        val script = RolePrompt.parse(json, 3..5)!!
        assertEquals(1, script.segments.size)
        assertEquals(3, script.segments[0].p)
    }

    @Test
    fun `malformed json yields null and an empty object yields an empty script`() {
        assertNull(RolePrompt.parse("not json at all", 0..0))
        val empty = RolePrompt.parse("{}", 0..0)!!
        assertTrue(empty.segments.isEmpty())
        assertTrue(empty.roles.isEmpty())
    }

    @Test
    fun `explicit nulls in string fields do not crash the parser`() {
        val json = """
            {"roles":[{"name":null,"gender":null,"age":null},{"name":"林风"}],
             "segments":[{"p":0,"s":0,"e":2,"r":null},null]}
        """.trimIndent()
        val script = RolePrompt.parse(json, 0..0)!!
        assertEquals(listOf("林风"), script.roles.map { it.name })
        assertEquals(listOf(Segment(0, 0, 2, "")), script.segments)
    }

    @Test
    fun `merge concatenates segments in order and dedupes roles by name`() {
        val a = RoleScript(
            listOf(Segment(1, 0, 2, "甲")),
            listOf(RoleProfile("林风", "male", "young"))
        )
        val b = RoleScript(
            listOf(Segment(0, 0, 2, "乙")),
            listOf(RoleProfile("林风", "female", "old"), RoleProfile("苏眉", "female", "young"))
        )
        val merged = RolePrompt.merge(listOf(a, b))
        assertEquals(listOf(Segment(0, 0, 2, "乙"), Segment(1, 0, 2, "甲")), merged.segments)
        assertEquals(listOf("林风", "苏眉"), merged.roles.map { it.name })
        assertEquals("先出现的画像胜出", "male", merged.roles[0].gender)
    }

    @Test
    fun `markdown fenced replies parse like the bare json`() {
        val bare = """{"roles":[{"name":"林风","gender":"male","age":"young"}],""" +
            """"segments":[{"p":0,"s":0,"e":6,"r":"旁白"}]}"""
        val expected = RolePrompt.parse(bare, 0..0)!!
        assertEquals(expected, RolePrompt.parse("```json\n$bare\n```", 0..0))
        assertEquals(expected, RolePrompt.parse("```\n$bare\n```", 0..0))
        assertEquals(expected, RolePrompt.parse("  ```json\n$bare\n```  \n", 0..0))
    }

    @Test
    fun `role gender and age are normalized at the parse boundary`() {
        val json = """{"roles":[{"name":"林风","gender":"Male","age":" Young "}]}"""
        val role = RolePrompt.parse(json, 0..0)!!.roles.single()
        assertEquals(TtsVoice.GENDER_MALE, role.gender)
        assertEquals("young", role.age)
    }

    @Test
    fun `values outside the fixed sets normalize to unknown`() {
        val json = """{"roles":[{"name":"林风","gender":"robot","age":"ancient"},{"name":"苏眉"}]}"""
        val roles = RolePrompt.parse(json, 0..0)!!.roles
        assertEquals(TtsVoice.GENDER_UNKNOWN, roles[0].gender)
        assertEquals(TtsVoice.AGE_UNKNOWN, roles[0].age)
        assertEquals("缺字段与非法值同归 unknown", TtsVoice.GENDER_UNKNOWN, roles[1].gender)
        assertEquals(TtsVoice.AGE_UNKNOWN, roles[1].age)
    }

    @Test
    fun `the prompt enumerates exactly the values the code accepts`() {
        assertTrue(RolePrompt.DEFAULT_SYSTEM.contains(TtsVoice.GENDER_MALE))
        assertTrue(RolePrompt.DEFAULT_SYSTEM.contains(TtsVoice.GENDER_FEMALE))
        assertTrue(RolePrompt.DEFAULT_SYSTEM.contains(TtsVoice.GENDER_UNKNOWN))
        assertTrue(RolePrompt.DEFAULT_SYSTEM.contains(TtsVoice.AGE_UNKNOWN))
        // prompt 里罗列的每个取值都必须被归一化原样接受, 否则模型照着答也会落 unknown
        listedValues("gender 取 ").forEach {
            assertEquals("prompt 列的 gender 值 $it 不在代码取值域", it, TtsVoice.normalizeGender(it))
        }
        listedValues("age 取 ").forEach {
            assertEquals("prompt 列的 age 值 $it 不在代码取值域", it, TtsVoice.normalizeAge(it))
        }
    }

    /** 取 DEFAULT_SYSTEM 中 "<prefix>a|b|c" 形式的取值枚举 */
    private fun listedValues(prefix: String): List<String> {
        val from = RolePrompt.DEFAULT_SYSTEM.indexOf(prefix)
        assertTrue("DEFAULT_SYSTEM 缺少 $prefix", from >= 0)
        val tail = RolePrompt.DEFAULT_SYSTEM.substring(from + prefix.length)
        val values = tail.takeWhile { it.isLetter() || it == '|' }.split('|')
        assertTrue("$prefix 后没有枚举出取值", values.size > 1)
        return values
    }

    @Test
    fun `narrator synonyms are folded onto the narrator identity`() {
        val json = """
            {"roles":[{"name":"解说","gender":"unknown"},{"name":"林风","gender":"male"}],
             "segments":[{"p":0,"s":0,"e":2,"r":"Narrator"},{"p":0,"s":2,"e":4,"r":"林风"}]}
        """.trimIndent()
        val parsed = RolePrompt.parse(json, 0..0)!!
        assertEquals(listOf(RoleCast.NARRATOR, "林风"), parsed.segments.map { it.role })
        assertEquals(listOf(RoleCast.NARRATOR, "林风"), parsed.roles.map { it.name })
    }
}
