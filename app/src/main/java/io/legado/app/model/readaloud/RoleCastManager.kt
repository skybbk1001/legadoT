package io.legado.app.model.readaloud

import io.legado.app.data.appDb
import io.legado.app.data.entities.RoleAlias
import io.legado.app.data.entities.RoleCast
import io.legado.app.data.entities.TtsVoice
import io.legado.app.model.ReadAloud
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext

/** 一个引擎下的一个音色。音色 id 只在引擎内唯一, 跨引擎的身份靠 [key] */
data class VoiceRef(
    val engineId: Long,
    val voice: TtsVoice?,
    val engineName: String = ""
) {

    val key: String get() = key(engineId, voice?.id)

    companion object {
        fun key(engineId: Long, voiceId: String?): String = "$engineId:${voiceId.orEmpty()}"
    }
}

/**
 * 角色到音色的分配。自动打底, 已落库的条目一律保留。
 */
object RoleCastManager {

    internal fun canonicalize(script: RoleScript, aliases: Map<String, String>): RoleScript {
        if (aliases.isEmpty()) return script
        val segments = script.segments.map { segment ->
            segment.copy(role = canonicalName(segment.role, aliases))
        }
        val profiles = LinkedHashMap<String, RoleProfile>()
        script.roles.forEach { profile ->
            val canonical = canonicalName(profile.name, aliases)
            val mapped = profile.copy(name = canonical)
            if (profiles[canonical] == null || profile.name == canonical) {
                profiles[canonical] = mapped
            }
        }
        return RoleScript(segments, RoleAnnotator.rolesIn(segments, profiles.values.toList()))
    }

    private fun canonicalName(name: String, aliases: Map<String, String>): String {
        var current = name
        val visited = HashSet<String>()
        while (visited.add(current)) {
            current = aliases[current] ?: return current
        }
        return name
    }

    suspend fun canonicalize(bookUrl: String, script: RoleScript): RoleScript = withContext(IO) {
        val aliases = appDb.roleAliasDao.getByBook(bookUrl)
            .associate { it.aliasName to it.canonicalName }
        canonicalize(script, aliases)
    }

    /**
     * 把 [aliasName] 并入 [canonicalName], 别名的 casting 随之丢弃。
     *
     * @return false 表示名字为空或指向自身, 调用方无需提示
     */
    suspend fun mergeRole(
        bookUrl: String,
        aliasName: String,
        canonicalName: String
    ): Boolean = withContext(IO) {
        val alias = aliasName.trim()
        val existingAliases = appDb.roleAliasDao.getByBook(bookUrl)
            .associate { it.aliasName to it.canonicalName }
        val canonical = canonicalName(canonicalName.trim(), existingAliases)
        if (alias.isBlank() || canonical.isBlank() || alias == canonical) {
            return@withContext false
        }
        appDb.roleAliasDao.redirect(bookUrl, alias, canonical)
        appDb.roleAliasDao.insert(RoleAlias(bookUrl, alias, canonical))
        appDb.roleCastDao.delete(bookUrl, alias)
        true
    }

    /**
     * @param usage 音色 [VoiceRef.key] 到本书已占用次数, 用于避免多个角色撞同一个声音
     * @return null 表示无可用音色, 调用方回退旁白
     */
    fun pickVoice(
        profile: RoleProfile,
        candidates: List<VoiceRef>,
        usage: Map<String, Int>
    ): VoiceRef? {
        if (candidates.isEmpty()) return null
        val byGender = narrow(candidates, profile.gender, TtsVoice.GENDER_UNKNOWN) {
            it.voice?.gender ?: TtsVoice.GENDER_UNKNOWN
        }
        val byAge = narrow(byGender, profile.age, TtsVoice.AGE_UNKNOWN) {
            it.voice?.age ?: TtsVoice.AGE_UNKNOWN
        }
        return byAge.minWithOrNull(
            compareBy({ usage[it.key] ?: 0 }, { it.key })
        )
    }

    /**
     * 按 [profiles] 顺序依次取音, 每次取中即在内部计数上累加,
     * 因此同一批里画像相同的角色会被推到不同音色上。[usage] 只读, 作为累加起点
     *
     * @return 每个画像与其取到的音色, 候选池为空时音色为 null
     */
    internal fun assign(
        profiles: List<RoleProfile>,
        candidates: List<VoiceRef>,
        usage: Map<String, Int>
    ): List<Pair<RoleProfile, VoiceRef?>> {
        val counted = HashMap(usage)
        return profiles.map { profile ->
            val picked = pickVoice(profile, candidates, counted)
            picked?.let { counted[it.key] = (counted[it.key] ?: 0) + 1 }
            profile to picked
        }
    }

    /** 条件为空或筛完为空时保持原集合, 保证永不因画像不匹配而配不到音 */
    private fun narrow(
        candidates: List<VoiceRef>,
        want: String?,
        unknown: String,
        of: (VoiceRef) -> String
    ): List<VoiceRef> {
        if (want.isNullOrBlank() || want == unknown) return candidates
        val hit = candidates.filter { of(it) == want }
        return hit.ifEmpty { candidates }
    }

    /** All engines contribute candidates; an engine without a list contributes its default voice. */
    suspend fun availableVoices(): List<VoiceRef> = withContext(IO) {
        appDb.httpTTSDao.all.flatMap { tts ->
            val voices = TtsVoice.parseList(tts.voices)
            if (voices.isEmpty()) {
                listOf(VoiceRef(tts.id, null, tts.name))
            } else {
                voices.map { VoiceRef(tts.id, it, tts.name) }
            }
        }
    }

    suspend fun castOf(bookUrl: String): Map<String, RoleCast> = withContext(IO) {
        val engines = appDb.httpTTSDao.all.associateBy { it.id }
        val currentEngineId = ReadAloud.ttsEngine?.toLongOrNull() ?: 0L
        appDb.roleCastDao.getByBook(bookUrl)
            .associate { it.roleName to playableCast(it, engines, currentEngineId) }
    }

    /**
     * 旁白缺省绑当前朗读引擎。ReadAloud.ttsEngine 为空或非数字(系统 TTS)时落到 0,
     * 与 [RoleCast.ttsEngineId] 默认值一致, 取流侧解析不到该 id 会退回当前引擎
     */
    suspend fun narratorCast(bookUrl: String): RoleCast = withContext(IO) {
        val currentEngineId = ReadAloud.ttsEngine?.toLongOrNull() ?: 0L
        val existing = appDb.roleCastDao.get(bookUrl, RoleCast.NARRATOR)
        if (existing != null) {
            if (!existing.isManual && existing.ttsEngineId != currentEngineId) {
                existing.ttsEngineId = currentEngineId
                existing.voice = null
                appDb.roleCastDao.insert(existing)
            }
            val engines = appDb.httpTTSDao.all.associateBy { it.id }
            return@withContext playableCast(existing, engines, currentEngineId)
        }
        RoleCast(
            bookUrl = bookUrl,
            roleName = RoleCast.NARRATOR,
            ttsEngineId = currentEngineId
        ).also { appDb.roleCastDao.insert(it) }
    }

    /** Invalid persisted bindings remain untouched for UI repair, while playback gets a safe fallback. */
    private fun playableCast(
        cast: RoleCast,
        engines: Map<Long, io.legado.app.data.entities.HttpTTS>,
        currentEngineId: Long
    ): RoleCast {
        val engine = engines[cast.ttsEngineId]
            ?: return cast.copy(ttsEngineId = currentEngineId, voice = null)
        val voice = cast.voice ?: return cast
        val voiceExists = TtsVoice.parseList(engine.voices).any { it.id == voice }
        return if (voiceExists) cast else cast.copy(voice = null)
    }

    /** 为尚无 casting 的角色自动配音色; 已有记录一律保留, 只把出场章号推到最新 */
    suspend fun ensureCast(bookUrl: String, roles: List<RoleProfile>, chapterIndex: Int) {
        if (roles.isEmpty()) return
        val existing = withContext(IO) {
            appDb.roleCastDao.getByBook(bookUrl).associateBy { it.roleName }
        }
        val candidates = availableVoices()
        val narratorEngineId = narratorCast(bookUrl).ttsEngineId
        val usage = HashMap<String, Int>()
        existing.values.forEach { cast ->
            // 引擎默认音色(voice 为 null)也占一个声音, 漏计会让后续角色全撞到它上面
            val key = VoiceRef.key(cast.ttsEngineId, cast.voice)
            usage[key] = (usage[key] ?: 0) + 1
        }
        val toWrite = ArrayList<RoleCast>()
        val pending = ArrayList<RoleProfile>()
        roles.forEach { profile ->
            val current = existing[profile.name]
            if (current != null) {
                var changed = false
                if (current.lastSeenChapter < chapterIndex) {
                    current.lastSeenChapter = chapterIndex
                    changed = true
                }
                if (current.gender != profile.gender || current.ageGroup != profile.age) {
                    current.gender = profile.gender
                    current.ageGroup = profile.age
                    changed = true
                }
                if (changed) toWrite.add(current)
                return@forEach
            }
            // 同名角色在一批里只配一次, 与已落库角色被跳过的口径一致
            if (pending.none { it.name == profile.name }) pending.add(profile)
        }
        assign(pending, candidates, usage).forEach { (profile, picked) ->
            toWrite.add(
                RoleCast(
                    bookUrl = bookUrl,
                    roleName = profile.name,
                    ttsEngineId = picked?.engineId ?: narratorEngineId,
                    voice = picked?.voice?.id,
                    gender = profile.gender,
                    ageGroup = profile.age,
                    isManual = false,
                    lastSeenChapter = chapterIndex
                )
            )
        }
        if (toWrite.isNotEmpty()) {
            withContext(IO) { appDb.roleCastDao.insert(*toWrite.toTypedArray()) }
        }
    }
}
