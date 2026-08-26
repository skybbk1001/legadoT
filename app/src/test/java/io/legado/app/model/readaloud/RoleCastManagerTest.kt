package io.legado.app.model.readaloud

import io.legado.app.data.entities.TtsVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoleCastManagerTest {

    private val maleYoung = VoiceRef(1, TtsVoice("m1", "云希", "male", "young"))
    private val maleOld = VoiceRef(1, TtsVoice("m2", "云健", "male", "old"))
    private val femaleYoung = VoiceRef(2, TtsVoice("f1", "晓晓", "female", "young"))
    private val pool = listOf(maleYoung, maleOld, femaleYoung)

    @Test
    fun `gender narrows the pool`() {
        assertEquals(
            femaleYoung,
            RoleCastManager.pickVoice(RoleProfile("苏眉", "female", "unknown"), pool, emptyMap())
        )
    }

    @Test
    fun `age narrows further within the matched gender`() {
        assertEquals(
            maleOld,
            RoleCastManager.pickVoice(RoleProfile("老王", "male", "old"), pool, emptyMap())
        )
    }

    @Test
    fun `an unmatchable gender keeps the whole pool rather than failing`() {
        val picked = RoleCastManager.pickVoice(
            RoleProfile("神秘人", "female", "child"),
            listOf(maleYoung, maleOld),
            emptyMap()
        )
        assertEquals(maleYoung, picked)
    }

    @Test
    fun `the least used voice wins so roles do not collide`() {
        val usage = mapOf(maleYoung.key to 2, maleOld.key to 1)
        assertEquals(
            maleOld,
            RoleCastManager.pickVoice(RoleProfile("甲", "male", "unknown"), pool, usage)
        )
    }

    @Test
    fun `ties break by key rather than by pool order`() {
        // 两者同为 male 且都未被占用, 差别只在 key; 列表顺序与 key 顺序相反
        val listedFirst = VoiceRef(2, TtsVoice("a1", "甲音", "male", "unknown"))
        val lowestKey = VoiceRef(1, TtsVoice("z9", "乙音", "male", "unknown"))
        assertEquals(
            lowestKey,
            RoleCastManager.pickVoice(
                RoleProfile("甲", "male", "unknown"),
                listOf(listedFirst, lowestKey),
                emptyMap()
            )
        )
    }

    @Test
    fun `an empty pool yields null so the caller can fall back to the narrator`() {
        assertNull(RoleCastManager.pickVoice(RoleProfile("甲", "male", "young"), emptyList(), emptyMap()))
    }

    @Test
    fun `unknown gender does not narrow anything`() {
        assertEquals(
            maleYoung,
            RoleCastManager.pickVoice(RoleProfile("甲", null, null), pool, emptyMap())
        )
    }

    @Test
    fun `the unknown gender token keeps the whole pool instead of selecting unknown voices`() {
        // 音色侧同样存在 unknown 这一取值, 画像写 unknown 时表示无偏好而非只要 unknown 音色
        val unknownVoice = VoiceRef(2, TtsVoice("u1", "无名", "unknown", "unknown"))
        assertEquals(
            maleYoung,
            RoleCastManager.pickVoice(
                RoleProfile("甲", "unknown", null),
                listOf(maleYoung, unknownVoice),
                emptyMap()
            )
        )
    }

    @Test
    fun `two identical profiles draw different voices`() {
        val twins = listOf(RoleProfile("甲", "male", "unknown"), RoleProfile("乙", "male", "unknown"))
        val drawn = RoleCastManager.assign(twins, pool, emptyMap()).map { it.second }
        assertEquals(listOf(maleYoung, maleOld), drawn)
    }

    @Test
    fun `a third identical profile wraps back to the least used voice`() {
        val triplets = List(3) { RoleProfile("角色$it", "male", "unknown") }
        val drawn = RoleCastManager.assign(triplets, listOf(maleYoung, maleOld), emptyMap())
            .map { it.second }
        assertEquals(listOf(maleYoung, maleOld, maleYoung), drawn)
    }

    @Test
    fun `seeded usage pushes the first assignment off the pre used voice`() {
        val drawn = RoleCastManager.assign(
            listOf(RoleProfile("甲", "male", "unknown")),
            pool,
            mapOf(maleYoung.key to 1)
        )
        assertEquals(listOf(maleOld), drawn.map { it.second })
    }

    @Test
    fun `an empty candidate pool assigns null to every profile`() {
        val profiles = listOf(RoleProfile("甲", "male", "young"), RoleProfile("乙", "female", "old"))
        val drawn = RoleCastManager.assign(profiles, emptyList(), emptyMap())
        assertEquals(profiles, drawn.map { it.first })
        assertEquals(listOf(null, null), drawn.map { it.second })
    }

    @Test
    fun `assign is reproducible for identical inputs`() {
        val profiles = List(4) { RoleProfile("角色$it", "male", "unknown") }
        val usage = mapOf(maleOld.key to 1)
        assertEquals(
            RoleCastManager.assign(profiles, pool, usage),
            RoleCastManager.assign(profiles, pool, usage)
        )
    }

    @Test
    fun `assign leaves the seed usage map untouched`() {
        val usage = mapOf(maleYoung.key to 1)
        RoleCastManager.assign(List(3) { RoleProfile("角色$it", "male", "unknown") }, pool, usage)
        assertEquals(mapOf(maleYoung.key to 1), usage)
    }

    @Test
    fun `an engine default voice has a stable candidate identity`() {
        val defaultVoice = VoiceRef(7, null, "默认引擎")
        assertEquals("7:", defaultVoice.key)
        assertEquals(
            defaultVoice,
            RoleCastManager.pickVoice(
                RoleProfile("甲", "female", "young"),
                listOf(defaultVoice),
                emptyMap()
            )
        )
    }

    @Test
    fun `aliases canonicalize both segments and profiles`() {
        val script = RoleScript(
            segments = listOf(
                Segment(0, 0, 2, "林公子"),
                Segment(0, 2, 4, "林风")
            ),
            roles = listOf(
                RoleProfile("林公子", "male", "young"),
                RoleProfile("林风", "male", "middle")
            )
        )
        val canonical = RoleCastManager.canonicalize(script, mapOf("林公子" to "林风"))
        assertEquals(listOf("林风", "林风"), canonical.segments.map { it.role })
        assertEquals(listOf(RoleProfile("林风", "male", "middle")), canonical.roles)
    }

    @Test
    fun `an alias cycle leaves the original role unchanged`() {
        val script = RoleScript(
            segments = listOf(Segment(0, 0, 2, "甲")),
            roles = listOf(RoleProfile("甲"))
        )
        assertEquals(
            script,
            RoleCastManager.canonicalize(script, mapOf("甲" to "乙", "乙" to "甲"))
        )
    }

    @Test
    fun `a default voice already in use pushes the next role elsewhere`() {
        // 引擎默认音色的 key 尾部为空, 与具名音色同域计数
        val engineDefault = VoiceRef(1, null, "引擎")
        val drawn = RoleCastManager.assign(
            listOf(RoleProfile("甲", null, null)),
            listOf(engineDefault, maleYoung),
            mapOf(engineDefault.key to 1)
        )
        assertEquals(listOf(maleYoung), drawn.map { it.second })
    }
}
